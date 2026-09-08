//! Concurrent, sha1-verified downloads.

use crate::{Error, Result};
use sha1::{Digest, Sha1};
use std::path::PathBuf;
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
    mut on_progress: impl FnMut(ProgressEvent),
) -> Result<usize> {
    on_progress(ProgressEvent::Started { total: downloads.len() });
    let client = Arc::new(client.clone());
    let queue = Arc::new(tokio::sync::Mutex::new(
        downloads.into_iter().collect::<std::collections::VecDeque<_>>(),
    ));
    let mut failed: Vec<(String, String)> = Vec::new();

    let mut workers = tokio::task::JoinSet::new();
    for _ in 0..concurrency.max(1) {
        let client = client.clone();
        let queue = queue.clone();
        workers.spawn(async move {
            let mut results = Vec::new();
            loop {
                let next = queue.lock().await.pop_front();
                let Some(dl) = next else { break };
                let res = download_one(&client, &dl).await;
                match res {
                    Ok(()) => results.push(Ok(dl.url.clone())),
                    Err(e) => results.push(Err((dl.url.clone(), e.to_string()))),
                }
            }
            results
        });
    }

    let mut done = 0usize;
    while let Some(joined) = workers.join_next().await {
        for result in joined.map_err(|e| Error::Other(e.to_string()))? {
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
    if verify_existing(dl).await? {
        return Ok(());
    }
    // One retry on checksum mismatch: a truncated first fetch must not poison
    // the install with a permanent error.
    match fetch_and_write(client, dl).await {
        Err(Error::Checksum { .. }) => fetch_and_write(client, dl).await,
        other => other,
    }
}

async fn fetch_and_write(client: &reqwest::Client, dl: &Download) -> Result<()> {
    let response = client.get(&dl.url).send().await?.error_for_status()?;
    let bytes = response.bytes().await?;

    if let Some(expected) = &dl.sha1 {
        let mut hasher = Sha1::new();
        hasher.update(&bytes);
        let actual = hex::encode(hasher.finalize());
        if &actual != expected {
            return Err(Error::Checksum {
                path: dl.dest.display().to_string(),
                expected: expected.clone(),
                actual,
            });
        }
    }

    if let Some(parent) = dl.dest.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let mut file = tokio::fs::File::create(&dl.dest).await?;
    file.write_all(&bytes).await?;
    file.flush().await?;
    Ok(())
}

async fn verify_existing(dl: &Download) -> Result<bool> {
    let Ok(meta) = tokio::fs::metadata(&dl.dest).await else { return Ok(false) };
    if let Some(size) = dl.size {
        if meta.len() != size {
            return Ok(false);
        }
    }
    let Some(expected) = &dl.sha1 else { return Ok(true) };
    let bytes = tokio::fs::read(&dl.dest).await?;
    let mut hasher = Sha1::new();
    hasher.update(&bytes);
    Ok(hex::encode(hasher.finalize()) == *expected)
}
