//! Mojang version manifest and per-version JSON handling.
//!
//! Manifest: https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
//! Version JSON: https://piston-meta.mojang.com/v1/packages/<sha1>/<id>.json

use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const VERSION_MANIFEST_URL: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct VersionManifest {
    pub latest: Latest,
    pub versions: Vec<VersionEntry>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Latest {
    pub release: String,
    pub snapshot: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct VersionEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub url: String,
    pub time: String,
    #[serde(rename = "releaseTime")]
    pub release_time: String,
    pub sha1: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct VersionJson {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(rename = "mainClass")]
    pub main_class: String,
    #[serde(rename = "javaVersion")]
    pub java_version: JavaVersion,
    #[serde(default)]
    pub arguments: Option<Arguments>,
    pub libraries: Vec<Library>,
    #[serde(rename = "assetIndex")]
    pub asset_index: Option<AssetIndex>,
    pub assets: Option<String>,
    pub downloads: Downloads,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct JavaVersion {
    pub component: String,
    #[serde(rename = "majorVersion")]
    pub major_version: u32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<serde_json::Value>,
    #[serde(default)]
    pub jvm: Vec<serde_json::Value>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AssetIndex {
    pub id: String,
    pub url: String,
    pub sha1: String,
    #[serde(default)]
    pub totalSize: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Downloads {
    pub client: Option<DownloadArtifact>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct DownloadArtifact {
    pub url: String,
    pub sha1: String,
    pub size: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Library {
    pub name: String,
    #[serde(default)]
    pub downloads: Option<LibraryDownloads>,
    #[serde(default)]
    pub rules: Option<Vec<Rule>>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct LibraryDownloads {
    pub artifact: Option<LibraryArtifact>,
    #[serde(default)]
    pub classifiers: std::collections::HashMap<String, LibraryArtifact>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct LibraryArtifact {
    pub path: String,
    pub url: String,
    pub sha1: String,
    pub size: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Rule {
    pub action: String,
    #[serde(default)]
    pub os: Option<RuleOs>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct RuleOs {
    pub name: Option<String>,
    pub arch: Option<String>,
}

impl VersionManifest {
    pub async fn fetch(client: &reqwest::Client) -> Result<Self> {
        Ok(client.get(VERSION_MANIFEST_URL).send().await?.error_for_status()?.json().await?)
    }

    pub fn find(&self, id: &str) -> Option<&VersionEntry> {
        self.versions.iter().find(|v| v.id == id)
    }
}

/// Fetch (and cache) the version JSON for a version id.
pub async fn fetch_version_json(
    client: &reqwest::Client,
    manifest: &VersionManifest,
    version_id: &str,
    cache_dir: &Path,
) -> Result<VersionJson> {
    let entry = manifest
        .find(version_id)
        .ok_or_else(|| Error::VersionNotFound(version_id.to_string()))?;

    let cache_path: PathBuf = cache_dir.join(format!("{}.json", version_id));
    if cache_path.exists() {
        if let Ok(text) = tokio::fs::read_to_string(&cache_path).await {
            if let Ok(v) = serde_json::from_str::<VersionJson>(&text) {
                return Ok(v);
            }
        }
    }

    let text = client.get(&entry.url).send().await?.error_for_status()?.text().await?;
    let version: VersionJson = serde_json::from_str(&text)?;
    tokio::fs::create_dir_all(cache_dir).await?;
    tokio::fs::write(&cache_path, &text).await?;
    Ok(version)
}

/// Current OS name as used in version JSON rules.
pub fn os_name() -> &'static str {
    match std::env::consts::OS {
        "windows" => "windows",
        "macos" => "osx",
        "linux" => "linux",
        other => other,
    }
}

/// Does this library apply to the current OS, per its rules?
pub fn library_allowed(lib: &Library) -> bool {
    let Some(rules) = &lib.rules else { return true };
    let os = os_name();
    let mut allowed = false;
    for rule in rules {
        let applies = match &rule.os {
            Some(o) => {
                let name_ok = o.name.as_deref().map_or(true, |n| n == os);
                let arch_ok = o
                    .arch
                    .as_deref()
                    .map_or(true, |a| a == std::env::consts::ARCH);
                name_ok && arch_ok
            }
            None => true,
        };
        if applies {
            allowed = rule.action == "allow";
        }
    }
    allowed
}

/// The natives classifier for the current platform, if any.
pub fn natives_classifier() -> Option<&'static str> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("windows", "x86_64") => Some("natives-windows"),
        ("windows", "aarch64") => Some("natives-windows-arm64"),
        ("macos", "aarch64") => Some("natives-macos-arm64"),
        ("macos", "x86_64") => Some("natives-macos"),
        ("linux", _) => Some("natives-linux"),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn natives_by_platform() {
        // sanity: classifier naming convention per version JSON classifiers
        assert!(natives_classifier().unwrap_or("none").starts_with("natives-"));
    }
}
