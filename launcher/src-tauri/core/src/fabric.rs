//! Fabric loader profile installation.
//!
//! Fabric's meta serves a drop-in piston-format version profile:
//! https://meta.fabricmc.net/v2/versions/loader/<game_version>/<loader_version>/profile/json
//! Its mainClass is net.fabricmc.loader.impl.launch.knot.KnotClient.

use crate::{meta, Result};
use serde_json::Value;
use std::path::Path;

pub const FABRIC_META_PROFILE_URL: &str =
    "https://meta.fabricmc.net/v2/versions/loader";

/// Fetch the Fabric profile JSON for a game version. `loader_version` of
/// `None` or `Some("latest")` resolves to the latest stable loader.
pub async fn fetch_fabric_profile(
    client: &reqwest::Client,
    game_version: &str,
    loader_version: Option<&str>,
) -> Result<Value> {
    let loader = match loader_version {
        Some(v) if !v.is_empty() && v != "latest" => v.to_string(),
        _ => "latest".to_string(),
    };
    let url = format!("{FABRIC_META_PROFILE_URL}/{game_version}/{loader}/profile/json");
    let text = client.get(&url).send().await?.error_for_status()?.text().await?;
    Ok(serde_json::from_str(&text)?)
}

/// Install a fabric profile into a profiles' versions dir.
/// Returns the merged version JSON (fabric profile) for subsequent launching.
pub async fn install_fabric(
    client: &reqwest::Client,
    game_version: &str,
    loader_version: Option<&str>,
    versions_dir: &Path,
) -> Result<meta::VersionJson> {
    let profile = fetch_fabric_profile(client, game_version, loader_version).await?;
    tokio::fs::create_dir_all(versions_dir).await?;
    let id = profile
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or(game_version)
        .to_string();
    let path = versions_dir.join(format!("{id}.json"));
    tokio::fs::write(&path, serde_json::to_vec_pretty(&profile)?).await?;
    let parsed: meta::VersionJson = serde_json::from_value(profile)?;
    Ok(parsed)
}

/// Resolve the stable "latest stable" loader version from Fabric meta
/// (used for display / pinning).
pub async fn latest_loader_version(client: &reqwest::Client) -> Result<String> {
    let url = "https://meta.fabricmc.net/v2/versions/loader";
    let versions: Vec<Value> = client.get(url).send().await?.error_for_status()?.json().await?;
    let stable = versions
        .iter()
        .find(|v| v.get("loader").and_then(|l| l.get("stable")).and_then(|s| s.as_bool()).unwrap_or(false))
        .and_then(|v| v.get("loader").and_then(|l| l.get("version")).and_then(|s| s.as_str()))
        .map(str::to_owned);
    Ok(stable.unwrap_or_else(|| "0.17.0".into()))
}
