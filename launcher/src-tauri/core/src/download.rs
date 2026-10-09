//! Concurrent, sha1-verified downloads.

use crate::{Error, Result};
use sha1::{Digest, Sha1};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::io::AsyncWriteExt;

#[derive(Debug, Clone)]
pub struct Download {
    pub url: String,
    /// Destination path (relative to a root or absolute).
    pub dest: PathBuf,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ProgressEvent {
    Started { total: usize },
    FileDone { url: String },
    Failed { url: String, error: String },
    Finished,
}

/// Download artifacts concurrently, verifying sha1 where provided.
/// Files are only downloaded if missing or checksum-mismatched.
pub async fn download_all(
    client: &reqwest::Client,
    downloads: Vec<Download>,
    concurrency: usize,
    on_progress: impl FnMut(ProgressEvent),
) -> Result<usize> {
    download_all_cached(client, downloads, concurrency, None, on_progress).await
}

/// Files already checked against their sha1, keyed by path, with the size
/// and modification time they had then. A launch re-checks thousands of
/// assets and libraries; hashing every one of them each time costs seconds
/// of disk and CPU for files that haven't changed since they were verified.
/// A file whose size or mtime moved is hashed again.
pub struct VerifiedCache {
    path: PathBuf,
    entries: std::sync::Mutex<std::collections::HashMap<String, (u64, u128)>>,
    dirty: std::sync::atomic::AtomicBool,
}

impl VerifiedCache {
    /// Load the cache kept at `path` (empty when missing or unreadable).
    pub fn load(path: PathBuf) -> Self {
        let entries = std::fs::read(&path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        Self { path, entries: std::sync::Mutex::new(entries), dirty: false.into() }
    }

    /// Write the cache back if anything was added.
    pub fn save(&self) {
        if !self.dirty.swap(false, std::sync::atomic::Ordering::Relaxed) {
            return;
        }
        let json = serde_json::to_vec(&*self.entries.lock().unwrap());
        if let (Ok(json), Some(parent)) = (json, self.path.parent()) {
            let _ = std::fs::create_dir_all(parent);
            let tmp = self.path.with_extension("tmp");
            if std::fs::write(&tmp, json).is_ok() {
                let _ = std::fs::rename(&tmp, &self.path);
            }
        }
    }

    fn stamp(meta: &std::fs::Metadata) -> Option<(u64, u128)> {
        let mtime = meta.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok()?.as_nanos();
        Some((meta.len(), mtime))
    }

    fn key(dl: &Download) -> String {
        format!("{}|{}", dl.dest.display(), dl.sha1.as_deref().unwrap_or(""))
    }

    fn holds(&self, dl: &Download, meta: &std::fs::Metadata) -> bool {
        Self::stamp(meta).is_some_and(|s| self.entries.lock().unwrap().get(&Self::key(dl)) == Some(&s))
    }

    fn record(&self, dl: &Download) {
        let Some(stamp) = std::fs::metadata(&dl.dest).ok().as_ref().and_then(Self::stamp) else { return };
        self.entries.lock().unwrap().insert(Self::key(dl), stamp);
        self.dirty.store(true, std::sync::atomic::Ordering::Relaxed);
    }
}

/// [`download_all`], skipping the hash of files `cache` already verified
/// (and recording the ones it checks or fetches). The caller saves it.
pub async fn download_all_cached(
    client: &reqwest::Client,
    downloads: Vec<Download>,
    concurrency: usize,
    cache: Option<Arc<VerifiedCache>>,
    mut on_progress: impl FnMut(ProgressEvent),
) -> Result<usize> {
    // one file per destination: two workers fetching the same path (an asset
    // index names some identical files twice) would share its .part file
    let mut seen = std::collections::HashSet::new();
    let downloads: std::collections::VecDeque<_> = downloads.into_iter().filter(|d| seen.insert(d.dest.clone())).collect();
    on_progress(ProgressEvent::Started { total: downloads.len() });
    let client = Arc::new(client.clone());
    let queue = Arc::new(tokio::sync::Mutex::new(downloads));
    let mut failed: Vec<(String, String)> = Vec::new();

    // each finished file is reported as it lands, not when its worker
    // runs out of queue, so progress moves during the install
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<std::result::Result<String, (String, String)>>();
    let mut workers = tokio::task::JoinSet::new();
    for _ in 0..concurrency.max(1) {
        let client = client.clone();
        let queue = queue.clone();
        let cache = cache.clone();
        let tx = tx.clone();
        workers.spawn(async move {
            loop {
                let next = queue.lock().await.pop_front();
                let Some(dl) = next else { break };
                let res = download_checked(&client, &dl, cache.as_deref()).await;
                let _ = tx.send(res.map(|()| dl.url.clone()).map_err(|e| (dl.url.clone(), e.to_string())));
            }
        });
    }
    drop(tx);

    let mut done = 0usize;
    while let Some(result) = rx.recv().await {
        match result {
            Ok(url) => {
                done += 1;
                on_progress(ProgressEvent::FileDone { url });
            }
            Err((url, error)) => {
                on_progress(ProgressEvent::Failed { url: url.clone(), error: error.clone() });
                failed.push((url, error));
            }
        }
    }
    while let Some(joined) = workers.join_next().await {
        joined.map_err(|e| Error::Other(e.to_string()))?;
    }
    on_progress(ProgressEvent::Finished);

    if failed.is_empty() {
        Ok(done)
    } else {
        let (url, error) = &failed[0];
        Err(Error::Other(format!("{} download(s) failed, e.g. {}: {}", failed.len(), url, error)))
    }
}

pub async fn download_one(client: &reqwest::Client, dl: &Download) -> Result<()> {
    download_checked(client, dl, None).await
}

async fn download_checked(client: &reqwest::Client, dl: &Download, cache: Option<&VerifiedCache>) -> Result<()> {
    if verify_existing(dl, cache).await? {
        return Ok(());
    }
    fetch_with_retries(client, dl).await?;
    if let Some(cache) = cache {
        cache.record(dl);
    }
    Ok(())
}

async fn fetch_with_retries(client: &reqwest::Client, dl: &Download) -> Result<()> {
    // Retry what a flaky network does to a fetch: a truncated body (checksum
    // mismatch), a reset or timed-out connection, a 5xx or 429. Mojang's CDN
    // drops connections mid-body often enough to fail a 4000-object asset
    // install without this.
    let mut attempt = 1;
    loop {
        match fetch_and_write(client, dl).await {
            Err(e) if attempt < ATTEMPTS && is_transient(&e) => {
                tokio::time::sleep(std::time::Duration::from_millis(500 * 3u64.pow(attempt - 1))).await;
                attempt += 1;
            }
            other => return other,
        }
    }
}

const ATTEMPTS: u32 = 3;

fn is_transient(e: &Error) -> bool {
    match e {
        Error::Checksum { .. } => true,
        Error::Http(e) => e
            .status()
            .is_none_or(|s| s.is_server_error() || s == reqwest::StatusCode::TOO_MANY_REQUESTS),
        _ => false,
    }
}

async fn fetch_and_write(client: &reqwest::Client, dl: &Download) -> Result<()> {
    let mut response = client.get(&dl.url).send().await?.error_for_status()?;
    if let Some(parent) = dl.dest.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    // Streamed into a side file and hashed as it arrives, then renamed into
    // place only once it's whole and matches: a big jar is never held in
    // memory, and a cut-off download never sits where an install trusts it.
    let part = part_path(&dl.dest);
    let written = async {
        let mut file = tokio::io::BufWriter::new(tokio::fs::File::create(&part).await?);
        let mut hasher = Sha1::new();
        while let Some(chunk) = response.chunk().await? {
            hasher.update(&chunk);
            file.write_all(&chunk).await?;
        }
        file.flush().await?;
        drop(file);
        if let Some(expected) = &dl.sha1 {
            let actual = hex::encode(hasher.finalize());
            if !actual.eq_ignore_ascii_case(expected) {
                return Err(Error::Checksum {
                    path: dl.dest.display().to_string(),
                    expected: expected.clone(),
                    actual,
                });
            }
        }
        tokio::fs::rename(&part, &dl.dest).await?;
        Ok(())
    }
    .await;
    if written.is_err() {
        let _ = tokio::fs::remove_file(&part).await;
    }
    written
}

/// Where a download is written before it's known to be whole.
fn part_path(dest: &Path) -> PathBuf {
    let mut name = dest.as_os_str().to_owned();
    name.push(".part");
    PathBuf::from(name)
}

/// Hex sha1 of some bytes.
pub fn sha1_hex(bytes: &[u8]) -> String {
    hex::encode(Sha1::digest(bytes))
}

/// Hex sha1 of a file on disk — the key Modrinth's `version_files` lookup
/// answers by.
pub async fn sha1_file(path: &Path) -> Result<String> {
    let bytes = tokio::fs::read(path).await?;
    let mut hasher = Sha1::new();
    hasher.update(&bytes);
    Ok(hex::encode(hasher.finalize()))
}

async fn verify_existing(dl: &Download, cache: Option<&VerifiedCache>) -> Result<bool> {
    let Ok(meta) = tokio::fs::metadata(&dl.dest).await else { return Ok(false) };
    if let Some(size) = dl.size {
        if meta.len() != size {
            return Ok(false);
        }
    }
    let Some(expected) = dl.sha1.clone() else { return Ok(true) };
    if cache.is_some_and(|c| c.holds(dl, &meta)) {
        return Ok(true);
    }
    // hashing a big jar is CPU work: keep it off the async workers
    let path = dl.dest.clone();
    let ok = tokio::task::spawn_blocking(move || -> std::io::Result<bool> {
        let bytes = std::fs::read(&path)?;
        let mut hasher = Sha1::new();
        hasher.update(&bytes);
        Ok(hex::encode(hasher.finalize()).eq_ignore_ascii_case(&expected))
    })
    .await
    .map_err(|e| Error::Other(e.to_string()))??;
    if ok {
        if let Some(cache) = cache {
            cache.record(dl);
        }
    }
    Ok(ok)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn trusts_a_verified_file_until_it_changes() {
        let dir = std::env::temp_dir().join(format!("dusk-verified-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let dest = dir.join("a.bin");
        std::fs::write(&dest, b"hello").unwrap();
        let dl = Download {
            url: String::new(),
            dest: dest.clone(),
            sha1: Some("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d".into()),
            size: Some(5),
        };
        let cache = VerifiedCache::load(dir.join("verified.json"));
        assert!(verify_existing(&dl, Some(&cache)).await.unwrap());
        cache.save();
        let cache = VerifiedCache::load(dir.join("verified.json"));
        assert!(cache.holds(&dl, &std::fs::metadata(&dest).unwrap()));
        // same size, different bytes, new mtime: hashed again and rejected
        std::thread::sleep(std::time::Duration::from_millis(20));
        std::fs::write(&dest, b"jello").unwrap();
        assert!(!verify_existing(&dl, Some(&cache)).await.unwrap());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn a_download_lands_whole_or_not_at_all() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            while let Ok((mut sock, _)) = listener.accept().await {
                tokio::spawn(async move {
                    let mut buf = [0u8; 1024];
                    let _ = tokio::io::AsyncReadExt::read(&mut sock, &mut buf).await;
                    let _ = sock
                        .write_all(b"HTTP/1.1 200 OK\r\ncontent-length: 5\r\nconnection: close\r\n\r\nhello")
                        .await;
                });
            }
        });
        let dir = std::env::temp_dir().join(format!("dusk-fetch-{}", std::process::id()));
        let dest = dir.join("mods").join("a.jar");
        let dl = |sha1: &str| Download {
            url: format!("http://{addr}/a.jar"),
            dest: dest.clone(),
            sha1: Some(sha1.into()),
            size: None,
        };
        let client = reqwest::Client::new();
        // a body that doesn't match leaves nothing behind
        let bad = fetch_and_write(&client, &dl("0000000000000000000000000000000000000000")).await;
        assert!(matches!(bad, Err(Error::Checksum { .. })), "{bad:?}");
        assert!(!dest.exists() && !part_path(&dest).exists());
        fetch_and_write(&client, &dl("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d")).await.unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), b"hello");
        assert!(!part_path(&dest).exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn retries_corrupt_bodies_but_not_local_failures() {
        let checksum = Error::Checksum { path: "a".into(), expected: "x".into(), actual: "y".into() };
        assert!(is_transient(&checksum));
        assert!(!is_transient(&Error::Io(std::io::Error::other("disk full"))));
        assert!(!is_transient(&Error::Other("nope".into())));
    }
}
