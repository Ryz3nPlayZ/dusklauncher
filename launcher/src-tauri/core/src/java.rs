//! Java runtime provisioning from Mojang's launcher meta.
//!
//! Mojang ships prebuilt JREs per platform; `javaVersion.component` in each
//! version JSON names the runtime (e.g. `java-runtime-delta`/Java 21 for 1.20.5+).
//!
//! Discovery: the versioned `all.json` index maps platform → component →
//! per-release file manifests. The middle path segment is a Mojang product
//! version that they rotate occasionally (last rotation caught by the boot
//! test as a 404); when it 404s, grab the fresh one from a living launcher
//! (e.g. HMCL's `MojangJavaDownloadTask`) and update `RUNTIME_INDEX_URL`.

use crate::{Error, Result};
use serde::Deserialize;
use std::path::PathBuf;

/// Versioned Mojang java-runtime index. Rotated by Mojang; see module docs.
const RUNTIME_INDEX_URL: &str = "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

#[derive(Debug, Deserialize)]
struct RuntimeEntry {
    manifest: ManifestRef,
    version: RuntimeVersion,
}

#[derive(Debug, Deserialize)]
struct ManifestRef {
    url: String,
    #[allow(dead_code)]
    sha1: String,
    #[allow(dead_code)]
    size: u64,
}

#[derive(Debug, Deserialize)]
struct RuntimeVersion {
    #[allow(dead_code)]
    name: String,
    /// ISO-8601; lexicographic max == newest for a fixed format.
    released: String,
}

fn platform_key() -> String {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("windows", "x86_64") => "windows-x64",
        ("windows", "aarch64") => "windows-arm64",
        ("macos", "aarch64") => "mac-os-arm64",
        ("macos", "x86_64") => "mac-os",
        ("linux", "x86_64") => "linux",
        ("linux", "aarch64") => "linux-arm64",
        _ => "linux",
    }
    .to_string()
}

/// Resolve the file-manifest URL for a runtime component on this platform.
/// Picks the newest release when the index lists several.
pub async fn runtime_manifest_url(
    client: &reqwest::Client,
    component: &str,
) -> Result<String> {
    let index: std::collections::HashMap<
        String,
        std::collections::HashMap<String, Vec<RuntimeEntry>>,
    > = client.get(RUNTIME_INDEX_URL).send().await?.error_for_status()?.json().await?;
    let key = platform_key();
    let entry = index
        .get(&key)
        .and_then(|plat| plat.get(component))
        .and_then(|entries| entries.iter().max_by(|a, b| a.version.released.cmp(&b.version.released)))
        .ok_or_else(|| Error::Other(format!("no java runtime {component} for platform {key}")))?;
    Ok(entry.manifest.url.clone())
}

/// Directory a runtime component is provisioned into.
pub fn runtime_dir(runtimes_root: &std::path::Path, component: &str) -> PathBuf {
    runtimes_root.join(component)
}

/// True if the runtime appears already provisioned (contains a java binary).
pub fn is_provisioned(dir: &std::path::Path) -> bool {
    java_candidates(dir).iter().any(|p| p.exists())
}

fn java_candidates(dir: &std::path::Path) -> Vec<PathBuf> {
    let bin = if cfg!(target_os = "windows") { "java.exe" } else { "java" };
    vec![
        dir.join("jre.bundle").join("Contents").join("Home").join("bin").join(bin),
        dir.join("bin").join(bin),
        dir.join("jds.bundle").join("bin").join(bin),
        dir.join("jdk").join("bin").join(bin),
    ]
}

/// Provision a runtime if missing. The per-release file manifest maps
/// relative paths to directory/file/link entries; files download `raw`
/// (sha1-verified), links are recreated as symlinks, and the manifest's
/// `executable` flag drives unix permissions.
pub async fn provision(
    client: &reqwest::Client,
    component: &str,
    runtimes_root: &std::path::Path,
    on_progress: impl FnMut(crate::download::ProgressEvent) + Send,
) -> Result<PathBuf> {
    let dir = runtime_dir(runtimes_root, component);
    if is_provisioned(&dir) {
        return Ok(dir);
    }
    let manifest_url = runtime_manifest_url(client, component).await?;
    #[derive(Deserialize)]
    struct FileManifest {
        files: std::collections::HashMap<String, RuntimeFile>,
    }
    #[derive(Deserialize)]
    struct RuntimeFile {
        #[serde(rename = "type")]
        kind: String,
        #[serde(default)]
        downloads: std::collections::HashMap<String, DownloadInfo>,
        #[serde(default)]
        executable: bool,
        #[serde(default)]
        target: Option<String>,
    }
    #[derive(Deserialize)]
    struct DownloadInfo {
        url: String,
        sha1: String,
        size: u64,
    }

    let manifest: FileManifest = client
        .get(&manifest_url)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let mut downloads = Vec::new();
    let mut links = Vec::new();
    for (rel, entry) in &manifest.files {
        let dest = dir.join(rel);
        match entry.kind.as_str() {
            "directory" => {
                tokio::fs::create_dir_all(&dest).await?;
            }
            "file" => {
                let Some(info) = entry.downloads.get("raw") else {
                    return Err(Error::Other(format!("runtime file {rel} has no raw download")));
                };
                if let Some(parent) = dest.parent() {
                    tokio::fs::create_dir_all(parent).await?;
                }
                downloads.push(crate::download::Download {
                    url: info.url.clone(),
                    dest,
                    sha1: Some(info.sha1.clone()),
                    size: Some(info.size),
                });
            }
            "link" => {
                let Some(target) = &entry.target else {
                    return Err(Error::Other(format!("runtime link {rel} has no target")));
                };
                if let Some(parent) = dest.parent() {
                    tokio::fs::create_dir_all(parent).await?;
                }
                links.push((dest, target.clone()));
            }
            other => {
                return Err(Error::Other(format!("unknown runtime entry {rel} of type {other}")));
            }
        }
    }
    if downloads.is_empty() {
        return Err(Error::Other(format!("empty runtime manifest for {component}")));
    }
    crate::download::download_all(client, downloads, 12, on_progress).await?;
    for (dest, target) in links {
        let _ = tokio::fs::remove_file(&dest).await;
        tokio::fs::symlink(&target, &dest).await?;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        for (rel, entry) in &manifest.files {
            if entry.kind != "file" {
                continue;
            }
            let mode = if entry.executable { 0o755 } else { 0o644 };
            let _ = tokio::fs::set_permissions(dir.join(rel), std::fs::Permissions::from_mode(mode)).await;
        }
    }
    Ok(dir)
}

pub fn java_executable(dir: &std::path::Path) -> PathBuf {
    java_candidates(dir)
        .into_iter()
        .find(|p| p.exists())
        .unwrap_or_else(|| dir.join("bin").join(if cfg!(target_os = "windows") { "java.exe" } else { "java" }))
}
