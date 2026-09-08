//! Download and extract LWJGL natives for the current platform.
//!
//! Version JSONs carry per-OS natives as library `classifiers` (e.g.
//! `natives-macos-arm64`). The jars are downloaded into the libraries tree
//! and extracted (minus META-INF) into the profile's natives directory —
//! the client is pointed there via `-Djava.library.path`.

use crate::download::{self, Download};
use crate::meta;
use crate::Result;
use std::path::{Path, PathBuf};

/// Download all applicable natives jars and extract them into `natives_dir`.
pub async fn install_natives(
    client: &reqwest::Client,
    version: &meta::VersionJson,
    libraries_root: &Path,
    natives_dir: &Path,
) -> Result<usize> {
    let Some(classifier) = meta::natives_classifier() else {
        return Ok(0);
    };

    let mut downloads: Vec<Download> = Vec::new();
    let mut jars: Vec<PathBuf> = Vec::new();
    for lib in &version.libraries {
        if !meta::library_allowed(lib) {
            continue;
        }
        let Some(dl) = &lib.downloads else { continue };
        let Some(artifact) = dl.classifiers.get(classifier) else { continue };
        let dest = libraries_root.join(&artifact.path);
        jars.push(dest.clone());
        downloads.push(Download {
            url: artifact.url.clone(),
            dest,
            sha1: Some(artifact.sha1.clone()),
            size: Some(artifact.size),
        });
    }

    if downloads.is_empty() {
        return Ok(0);
    }
    download::download_all(client, downloads, 8, |_| {}).await?;
    tokio::fs::create_dir_all(natives_dir).await?;
    for jar in &jars {
        extract_zip(jar, natives_dir).await?;
    }
    Ok(jars.len())
}

/// Extract a zip/jar into a directory, skipping META-INF and signatures.
/// Runs on the blocking pool: ZipArchive is not Send and unzip is CPU/IO work.
pub async fn extract_zip(archive: &Path, dest_dir: &Path) -> Result<()> {
    let archive = archive.to_path_buf();
    let dest_dir = dest_dir.to_path_buf();
    tokio::task::spawn_blocking(move || {
        let file = std::fs::File::open(&archive)?;
        let mut zip =
            zip::ZipArchive::new(file).map_err(|e| crate::Error::Other(e.to_string()))?;
        for index in 0..zip.len() {
            let mut entry = zip
                .by_index(index)
                .map_err(|e| crate::Error::Other(e.to_string()))?;
            if entry.is_dir() {
                continue;
            }
            let Some(name) = entry.enclosed_name() else { continue };
            let name = name.display().to_string();
            if name.starts_with("META-INF/") {
                continue;
            }
            let out_path = dest_dir.join(&name);
            if let Some(parent) = out_path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut buf = Vec::with_capacity(entry.size() as usize);
            std::io::Read::read_to_end(&mut entry, &mut buf)?;
            std::fs::write(&out_path, &buf)?;
        }
        Ok(())
    })
    .await
    .map_err(|e| crate::Error::Other(e.to_string()))?
}
