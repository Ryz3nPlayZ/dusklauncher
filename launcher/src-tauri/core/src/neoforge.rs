//! NeoForge loader profile installation.
//!
//! Unlike Fabric, NeoForge has no drop-in profile endpoint: its installer
//! jar runs processors that patch the vanilla client into a
//! `neoforge-<v>-client.jar` library, then writes an `inheritsFrom` version
//! JSON. The launcher runs that installer headless
//! (`--install-client <data_dir>`) so its outputs land in the shared
//! `libraries/`, reads the JSON it wrote, and merges it with vanilla the same
//! way Fabric's profile is merged.
//!
//! Versions: `net.neoforged:neoforge` numbers builds after the game version
//! minus its leading `1.` (MC 1.21.1 → `21.1.x`, 1.21 → `21.0.x`); from the
//! 26.x year-based versions on, the full game version leads (MC 26.1 →
//! `26.1.0.x`). The first NeoForge build is for 1.20.2.

use crate::{fabric, meta, Error, Result};
use std::path::{Path, PathBuf};

pub const NEOFORGE_VERSIONS_URL: &str =
    "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";
pub const NEOFORGE_MAVEN: &str = "https://maven.neoforged.net/releases/net/neoforged/neoforge";

/// The prefix every NeoForge build for `game_version` starts with, dot
/// included so `21.1.` never matches `21.10.x`.
pub fn version_prefix(game_version: &str) -> Option<String> {
    let parts: Vec<&str> = game_version.split('.').collect();
    if parts.iter().any(|p| p.is_empty() || !p.bytes().all(|b| b.is_ascii_digit())) {
        return None;
    }
    match parts.as_slice() {
        ["1", minor] => Some(format!("{minor}.0.")),
        ["1", minor, patch] => Some(format!("{minor}.{patch}.")),
        [year, drop] if *year != "1" => Some(format!("{year}.{drop}.0.")),
        [year, drop, hotfix] if *year != "1" => Some(format!("{year}.{drop}.{hotfix}.")),
        _ => None,
    }
}

/// Numeric sort key for a build like `21.1.252` or `26.1.0.3-beta`.
fn build_key(v: &str) -> Vec<u64> {
    v.split('-')
        .next()
        .unwrap_or(v)
        .split('.')
        .map(|n| n.parse().unwrap_or(0))
        .collect()
}

/// The newest stable build for `game_version`, else the newest beta (new
/// game versions only have betas for their first weeks). Alpha builds such
/// as `26.1.0.0-alpha.9+snapshot-6` target the game's snapshots, not the
/// release, and are never picked.
pub fn pick_version(versions: &[String], game_version: &str) -> Option<String> {
    let prefix = version_prefix(game_version)?;
    let matching = versions.iter().filter(|v| v.starts_with(&prefix));
    let newest = |suffix: Option<&str>| {
        matching
            .clone()
            .filter(|v| v.split_once('-').map(|(_, s)| s) == suffix)
            .max_by_key(|v| build_key(v))
            .cloned()
    };
    newest(None).or_else(|| newest(Some("beta")))
}

/// Resolve the build to install. A pinned `loader_version` is used as-is;
/// `None` / `"latest"` picks from NeoForge's maven listing.
pub async fn resolve_version(
    client: &reqwest::Client,
    game_version: &str,
    loader_version: Option<&str>,
) -> Result<String> {
    if let Some(v) = loader_version.filter(|v| !v.is_empty() && *v != "latest") {
        return Ok(v.to_string());
    }
    #[derive(serde::Deserialize)]
    struct Listing {
        versions: Vec<String>,
    }
    let listing: Listing = client
        .get(NEOFORGE_VERSIONS_URL)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    pick_version(&listing.versions, game_version)
        .ok_or_else(|| Error::Other(format!("NeoForge has no build for Minecraft {game_version}")))
}

/// Where the installer leaves the version JSON for `version`.
pub fn installed_json_path(install_root: &Path, version: &str) -> PathBuf {
    install_root
        .join("versions")
        .join(format!("neoforge-{version}"))
        .join(format!("neoforge-{version}.json"))
}

/// Written once the installer exits cleanly. The version JSON can't serve:
/// the installer extracts it first, so a run that dies mid-download leaves
/// it behind next to an unpatched game, and FML then reports the install
/// as corrupted.
fn installed_marker_path(install_root: &Path, version: &str) -> PathBuf {
    install_root.join("installers").join(format!("neoforge-{version}.installed"))
}

/// Install NeoForge for a game version and return the version JSON merged
/// with vanilla. `install_root` is the launcher data dir: the installer
/// writes `libraries/` (shared with every profile) and `versions/` under it.
/// `java_bin` runs the installer; the game's own runtime is the right one,
/// since the processors are built for it.
pub async fn install_neoforge(
    client: &reqwest::Client,
    java_bin: &Path,
    game_version: &str,
    loader_version: Option<&str>,
    install_root: &Path,
    versions_dir: &Path,
    vanilla: &meta::VersionJson,
) -> Result<meta::VersionJson> {
    let version = resolve_version(client, game_version, loader_version).await?;
    let json_path = installed_json_path(install_root, &version);
    let marker = installed_marker_path(install_root, &version);
    if !marker.exists() || !json_path.exists() {
        // The installer patches the vanilla jar it finds at
        // versions/<mc>/<mc>.jar, fetching it only when that is missing or
        // fails its checksum; our downloader (checksummed, retried) is the
        // sturdier of the two for its largest download.
        if let Some(art) = &vanilla.downloads.client {
            crate::download::download_one(
                client,
                &crate::download::Download {
                    url: art.url.clone(),
                    dest: install_root.join("versions").join(&vanilla.id).join(format!("{}.jar", vanilla.id)),
                    sha1: Some(art.sha1.clone()),
                    size: Some(art.size),
                },
            )
            .await?;
        }
        run_installer(client, java_bin, &version, install_root).await?;
        tokio::fs::write(&marker, b"").await?;
    }
    let profile: serde_json::Value = serde_json::from_slice(&tokio::fs::read(&json_path).await.map_err(|e| {
        Error::Other(format!("NeoForge installer left no profile at {}: {e}", json_path.display()))
    })?)?;
    let merged = fabric::merge_with_vanilla(profile, vanilla);
    tokio::fs::create_dir_all(versions_dir).await?;
    let path = versions_dir.join(format!("{}.json", merged.id));
    tokio::fs::write(&path, serde_json::to_vec_pretty(&merged)?).await?;
    Ok(merged)
}

async fn run_installer(
    client: &reqwest::Client,
    java_bin: &Path,
    version: &str,
    install_root: &Path,
) -> Result<()> {
    let cache = install_root.join("installers");
    tokio::fs::create_dir_all(&cache).await?;
    let jar = cache.join(format!("neoforge-{version}-installer.jar"));
    if !jar.exists() {
        let url = format!("{NEOFORGE_MAVEN}/{version}/neoforge-{version}-installer.jar");
        let bytes = client.get(&url).send().await?.error_for_status()?.bytes().await?;
        let part = jar.with_extension("jar.part");
        tokio::fs::write(&part, &bytes).await?;
        tokio::fs::rename(&part, &jar).await?;
    }
    // The installer refuses a target without a launcher profile store; it
    // only adds an entry to it, which nothing here reads.
    let profiles = install_root.join("launcher_profiles.json");
    if !profiles.exists() {
        tokio::fs::write(&profiles, br#"{"profiles":{}}"#).await?;
    }
    let mut installer = tokio::process::Command::new(java_bin);
    #[cfg(windows)]
    installer.creation_flags(crate::launch::CREATE_NO_WINDOW);
    let out = installer
        .arg("-jar")
        .arg(&jar)
        .arg("--install-client")
        .arg(install_root)
        .current_dir(&cache)
        .output()
        .await?;
    if !out.status.success() {
        let log = String::from_utf8_lossy(&out.stdout);
        let tail: Vec<&str> = log.lines().rev().take(8).collect();
        let tail: Vec<&str> = tail.into_iter().rev().collect();
        return Err(Error::Other(format!(
            "NeoForge {version} installer failed ({}); full log in {}:\n{}",
            out.status,
            jar.with_extension("jar.log").display(),
            tail.join("\n")
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefixes_follow_both_numbering_schemes() {
        assert_eq!(version_prefix("1.21.1").as_deref(), Some("21.1."));
        assert_eq!(version_prefix("1.21").as_deref(), Some("21.0."));
        assert_eq!(version_prefix("1.20.2").as_deref(), Some("20.2."));
        assert_eq!(version_prefix("26.1").as_deref(), Some("26.1.0."));
        assert_eq!(version_prefix("26.1.2").as_deref(), Some("26.1.2."));
        assert_eq!(version_prefix("25w14craftmine"), None);
        assert_eq!(version_prefix("1.21.1-pre1"), None);
    }

    fn listing() -> Vec<String> {
        [
            "20.2.3-beta", "21.1.9", "21.1.10", "21.1.252", "21.1.300-beta",
            "21.10.64", "26.1.0.0-alpha.9+snapshot-6", "26.1.0.0-alpha.11+pre-2",
            "26.1.0.2-beta", "26.1.0.10-beta", "26.1.0.9-beta",
        ]
        .map(String::from)
        .to_vec()
    }

    #[test]
    fn picks_newest_stable_numerically() {
        // 252 > 10 > 9 numerically, and the newer beta loses to a stable
        assert_eq!(pick_version(&listing(), "1.21.1").as_deref(), Some("21.1.252"));
        // 21.1. must not match 21.10.x
        assert_eq!(pick_version(&listing(), "1.21.10").as_deref(), Some("21.10.64"));
    }

    #[test]
    fn falls_back_to_newest_beta_never_alpha() {
        assert_eq!(pick_version(&listing(), "26.1").as_deref(), Some("26.1.0.10-beta"));
        assert_eq!(pick_version(&listing(), "1.20.2").as_deref(), Some("20.2.3-beta"));
    }

    #[test]
    fn no_build_for_pre_neoforge_versions() {
        assert_eq!(pick_version(&listing(), "1.20.1"), None);
        assert_eq!(pick_version(&listing(), "1.8.9"), None);
    }

    /// Trimmed from the JSON NeoForge 21.1.252's installer writes: its
    /// module path is built from `${library_directory}` and
    /// `${classpath_separator}`, and its game args append to vanilla's.
    #[test]
    fn installer_profile_merges_with_vanilla() {
        let vanilla: meta::VersionJson = serde_json::from_value(serde_json::json!({
            "id": "1.21.1",
            "type": "release",
            "mainClass": "net.minecraft.client.main.Main",
            "arguments": {"jvm": ["-cp", "${classpath}"], "game": ["--username", "${auth_player_name}"]},
            "libraries": [{"name": "com.mojang:logging:1.2.7"}],
            "javaVersion": {"component": "java-runtime-delta", "majorVersion": 21},
            "downloads": {"client": {"url": "https://example.test/client.jar", "sha1": "bb", "size": 2}}
        }))
        .unwrap();
        let installed = serde_json::json!({
            "id": "neoforge-21.1.252",
            "inheritsFrom": "1.21.1",
            "type": "release",
            "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
            "arguments": {
                "game": ["--fml.neoForgeVersion", "21.1.252", "--launchTarget", "forgeclient"],
                "jvm": ["-DignoreList=client-extra,${version_name}.jar", "-p",
                        "${library_directory}/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar"]
            },
            "libraries": [{"name": "net.neoforged:neoforge:21.1.252:universal", "downloads": {"artifact": {
                "path": "net/neoforged/neoforge/21.1.252/neoforge-21.1.252-universal.jar",
                "url": "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.252/neoforge-21.1.252-universal.jar",
                "sha1": "aa", "size": 1}}}]
        });
        let merged = fabric::merge_with_vanilla(installed, &vanilla);
        assert_eq!(merged.id, "neoforge-21.1.252");
        assert_eq!(merged.main_class, "cpw.mods.bootstraplauncher.BootstrapLauncher");
        let args = merged.arguments.as_ref().unwrap();
        assert_eq!(args.game.len(), 2 + 4, "vanilla game args kept, neoforge's appended");
        assert_eq!(args.jvm.len(), 2 + 3);
        assert_eq!(merged.effective_java().major_version, 21);
        assert!(merged.downloads.client.is_some(), "vanilla client jar carried over");
        let art = merged.libraries[1].resolve_artifact().unwrap();
        assert_eq!(art.path, "net/neoforged/neoforge/21.1.252/neoforge-21.1.252-universal.jar");
    }
}
