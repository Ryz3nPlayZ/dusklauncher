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
    /// Absent in loader profiles (Fabric) and pre-1.7 version JSONs;
    /// [`VersionJson::effective_java`] fills the gap.
    #[serde(rename = "javaVersion", default)]
    pub java_version: Option<JavaVersion>,
    #[serde(default)]
    pub arguments: Option<Arguments>,
    /// Pre-1.13 versions spell their game args as one whitespace-joined
    /// template string instead of the rule-gated `arguments` arrays.
    #[serde(rename = "minecraftArguments", default)]
    pub minecraft_arguments: Option<String>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    #[serde(rename = "assetIndex", default)]
    pub asset_index: Option<AssetIndex>,
    #[serde(default)]
    pub assets: Option<String>,
    #[serde(default)]
    pub downloads: Downloads,
}

impl VersionJson {
    /// Java to launch with when the JSON doesn't say. Fabric profiles inherit
    /// the vanilla entry at merge time; for genuinely old JSONs Mojang's own
    /// launcher maps everything pre-1.17 to the Java 8 legacy runtime.
    pub fn effective_java(&self) -> JavaVersion {
        self.java_version.clone().unwrap_or(JavaVersion {
            component: "jre-legacy".into(),
            major_version: 8,
        })
    }
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

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
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
    /// Fabric-style libraries (meta.fabricmc.net) have no `downloads` block:
    /// they carry a top-level `url` (maven repo base) plus optional checksums.
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
    #[serde(default)]
    pub rules: Option<Vec<Rule>>,
}

/// A library jar resolved to a concrete download/classpath entry, regardless
/// of which dialect the version JSON speaks (Mojang piston or Fabric maven).
#[derive(Debug, Clone, PartialEq)]
pub struct ResolvedArtifact {
    pub path: String,
    pub url: String,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

impl Library {
    /// Maven coordinates → repository path:
    /// `group:artifact:version[:classifier]` →
    /// `group/as/dots/artifact/version/artifact-version[-classifier].jar`
    pub fn maven_path(&self) -> Option<String> {
        let mut parts = self.name.split(':');
        let group = parts.next()?;
        let artifact = parts.next()?;
        let version = parts.next()?;
        let file = match parts.next() {
            Some(classifier) => format!("{artifact}-{version}-{classifier}.jar"),
            None => format!("{artifact}-{version}.jar"),
        };
        Some(format!(
            "{}/{}/{}/{}",
            group.replace('.', "/"),
            artifact,
            version,
            file
        ))
    }

    /// Mojang libraries resolve through `downloads.artifact`; Fabric libraries
    /// through the top-level `url` base + a path derived from the coordinates.
    /// Returns `None` only for libraries that specify neither.
    pub fn resolve_artifact(&self) -> Option<ResolvedArtifact> {
        if let Some(a) = self.downloads.as_ref().and_then(|d| d.artifact.as_ref()) {
            return Some(ResolvedArtifact {
                path: a.path.clone(),
                url: a.url.clone(),
                sha1: Some(a.sha1.clone()),
                size: Some(a.size),
            });
        }
        let base = self.url.as_ref()?;
        let path = self.maven_path()?;
        let url = if base.ends_with('/') {
            format!("{base}{path}")
        } else {
            format!("{base}/{path}")
        };
        Some(ResolvedArtifact {
            path,
            url,
            sha1: self.sha1.clone(),
            size: self.size,
        })
    }
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

    /// 1.8.9-style JSON: no `javaVersion` block, no `arguments` — one
    /// `minecraftArguments` template string instead. This must parse.
    #[test]
    fn legacy_version_json_without_javaversion_parses() {
        let json = r#"{
            "id": "1.8.9", "type": "release",
            "mainClass": "net.minecraft.client.main.Main",
            "minecraftArguments": "--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} --assetsDir ${assets_root} --assetIndex ${assets_index_name} --uuid ${auth_uuid} --accessToken ${auth_access_token} --userType ${user_type}",
            "libraries": [],
            "assetIndex": {"id": "1.8", "url": "https://example.test/18.json", "sha1": "aa", "totalSize": 1},
            "assets": "1.8",
            "downloads": {"client": {"url": "https://example.test/c.jar", "sha1": "bb", "size": 1}}
        }"#;
        let v: VersionJson = serde_json::from_str(json).unwrap();
        assert!(v.java_version.is_none());
        assert!(v.arguments.is_none());
        assert!(v.minecraft_arguments.is_some());
        // pre-1.17 without a block maps to the Java 8 legacy runtime
        let java = v.effective_java();
        assert_eq!(java.component, "jre-legacy");
        assert_eq!(java.major_version, 8);
    }

    /// Fabric-loader profiles carry `arguments` but no `downloads` at all —
    /// the downloads key itself must be optional too.
    #[test]
    fn version_json_tolerates_missing_downloads_key() {
        let json = r#"{
            "id": "x", "type": "release", "mainClass": "Main",
            "arguments": {"jvm": [], "game": []},
            "libraries": []
        }"#;
        let v: VersionJson = serde_json::from_str(json).unwrap();
        assert!(v.downloads.client.is_none());
    }

    /// Fabric meta libraries: maven coordinates + repo base url, no
    /// `downloads` block. The loader jar itself ships no checksum.
    #[test]
    fn fabric_library_resolves_via_maven_url() {
        let lib: Library = serde_json::from_str(
            r#"{"name": "net.fabricmc:fabric-loader:0.19.3", "url": "https://maven.fabricmc.net/"}"#,
        )
        .unwrap();
        let art = lib.resolve_artifact().unwrap();
        assert_eq!(art.path, "net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar");
        assert_eq!(
            art.url,
            "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"
        );
        assert!(art.sha1.is_none());
        assert!(art.size.is_none());
    }

    /// Fabric dependency jars (asm, mixin) DO ship checksums top-level —
    /// honored when present so sha1 verification keeps working.
    #[test]
    fn fabric_library_checksums_are_honored() {
        let lib: Library = serde_json::from_str(
            r#"{"name": "org.ow2.asm:asm:9.10.1", "url": "https://maven.fabricmc.net/",
                "sha1": "ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236", "size": 126151}"#,
        )
        .unwrap();
        let art = lib.resolve_artifact().unwrap();
        assert_eq!(art.path, "org/ow2/asm/asm/9.10.1/asm-9.10.1.jar");
        assert_eq!(art.sha1.as_deref(), Some("ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236"));
        assert_eq!(art.size, Some(126151));
    }

    /// A repo base without a trailing slash still resolves (path join).
    #[test]
    fn maven_url_join_handles_missing_trailing_slash() {
        let lib: Library = serde_json::from_str(
            r#"{"name": "net.fabricmc:fabric-loader:0.19.3", "url": "https://maven.fabricmc.net"}"#,
        )
        .unwrap();
        let art = lib.resolve_artifact().unwrap();
        assert!(art.url.starts_with("https://maven.fabricmc.net/net/fabricmc/"));
    }

    /// Mojang libraries keep resolving through `downloads.artifact` unchanged.
    #[test]
    fn mojang_library_still_resolves_via_downloads() {
        let lib: Library = serde_json::from_str(
            r#"{"name": "com.mojang:logging:1.2.7",
                "downloads": {"artifact": {"path": "com/mojang/logging/1.2.7/logging-1.2.7.jar",
                "url": "https://libraries.minecraft.net/com/mojang/logging/1.2.7/logging-1.2.7.jar",
                "sha1": "aa", "size": 10}}}"#,
        )
        .unwrap();
        let art = lib.resolve_artifact().unwrap();
        assert_eq!(art.path, "com/mojang/logging/1.2.7/logging-1.2.7.jar");
        assert_eq!(art.sha1.as_deref(), Some("aa"));
    }

    /// Neither dialect → unresolvable (never downloadable, never on classpath).
    #[test]
    fn bare_library_without_url_is_unresolvable() {
        let lib: Library = serde_json::from_str(r#"{"name": "a:b:1"}"#).unwrap();
        assert!(lib.resolve_artifact().is_none());
    }
}
