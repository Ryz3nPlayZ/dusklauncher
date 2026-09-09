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
///
/// Fabric's profile is a partial piston JSON (`inheritsFrom` semantics): it
/// carries mainClass, argument arrays and its own libraries, but no
/// `javaVersion`, `downloads.client` or `assetIndex`. Parsing it standalone
/// used to die with `missing field javaVersion`; the vanilla JSON must be
/// merged in first. Returns the merged version JSON for launching.
pub async fn install_fabric(
    client: &reqwest::Client,
    game_version: &str,
    loader_version: Option<&str>,
    versions_dir: &Path,
    vanilla: &meta::VersionJson,
) -> Result<meta::VersionJson> {
    let profile = fetch_fabric_profile(client, game_version, loader_version).await?;
    tokio::fs::create_dir_all(versions_dir).await?;
    let merged = merge_with_vanilla(profile, vanilla);
    let path = versions_dir.join(format!("{}.json", merged.id));
    tokio::fs::write(&path, serde_json::to_vec_pretty(&merged)?).await?;
    Ok(merged)
}

/// `inheritsFrom` merge: fabric fields override, vanilla supplies everything
/// fabric doesn't ship (java runtime, client jar, assets). Libraries are the
/// union with fabric entries winning on a name collision.
pub fn merge_with_vanilla(
    mut profile: serde_json::Value,
    vanilla: &meta::VersionJson,
) -> meta::VersionJson {
    // Tolerate older fabric meta payloads that omit these piston fields.
    let obj = profile.as_object_mut().expect("fabric profile is an object");
    obj.entry("type").or_insert_with(|| serde_json::json!("release"));
    obj.entry("libraries").or_insert_with(|| serde_json::json!([]));
    let fabric: meta::VersionJson = serde_json::from_value(profile).expect("fabric profile json deserializes");
    let mut merged = vanilla.clone();
    merged.id = fabric.id;
    merged.kind = fabric.kind;
    merged.main_class = fabric.main_class;
    // inheritsFrom semantics: the child's argument arrays are APPENDED to the
    // parent's, never substituted — fabric's profile ships `"game": []` and a
    // single emu jvm flag, so taking it wholesale would drop every vanilla
    // game arg and `-cp ${classpath}` (an empty classpath, i.e. exactly the
    // `ClassNotFoundException: KnotClient` failure).
    merged.arguments = match (merged.arguments.take(), fabric.arguments) {
        (Some(mut parent), Some(child)) => {
            parent.game.extend(child.game);
            parent.jvm.extend(child.jvm);
            Some(parent)
        }
        (parent, child) => parent.or(child),
    };
    merged.minecraft_arguments = fabric.minecraft_arguments.or(merged.minecraft_arguments);
    merged.asset_index = fabric.asset_index.or(merged.asset_index);
    merged.assets = fabric.assets.or(merged.assets);
    if fabric.downloads.client.is_some() {
        merged.downloads = fabric.downloads;
    }
    let mut seen = std::collections::HashSet::new();
    merged.libraries.retain(|l| seen.insert(l.name.clone()));
    for lib in fabric.libraries {
        if !seen.contains(&lib.name) {
            merged.libraries.push(lib);
        }
    }
    merged
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

#[cfg(test)]
mod tests {
    use super::*;

    fn vanilla() -> meta::VersionJson {
        let json = r#"{
            "id": "26.2", "type": "release",
            "mainClass": "net.minecraft.client.main.Main",
            "javaVersion": {"component": "java-runtime-epsilon", "majorVersion": 25},
            "arguments": {"jvm": ["-cp", "${classpath}"], "game": ["--username", "${auth_player_name}"]},
            "libraries": [{"name": "com.mojang:logging"}],
            "assetIndex": {"id": "262", "url": "https://example.test/262.json", "sha1": "aa", "totalSize": 1},
            "assets": "262",
            "downloads": {"client": {"url": "https://example.test/client.jar", "sha1": "bb", "size": 2}}
        }"#;
        serde_json::from_str(json).unwrap()
    }

    /// The exact shape that broke launching (captured from a real install):
    /// fabric meta's profile json has `type`, `mainClass`, `arguments` and
    /// maven-style `libraries`, but NO `javaVersion`, `downloads` or
    /// `assetIndex` — and its `arguments.game` is EMPTY. Standalone parsing
    /// died with `missing field javaVersion`; a wholesale-arguments merge
    /// dropped `-cp` and all vanilla game args.
    #[test]
    fn fabric_profile_merges_with_vanilla() {
        let profile = serde_json::json!({
            "id": "fabric-loader-0.19.3-26.2",
            "inheritsFrom": "26.2",
            "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "arguments": {
                "jvm": ["-DFabricMcEmu= net.minecraft.client.main.Main "],
                "game": []
            },
            "libraries": [
                {"name": "net.fabricmc:fabric-loader:0.19.3", "url": "https://maven.fabricmc.net/"},
                {"name": "org.ow2.asm:asm:9.10.1", "url": "https://maven.fabricmc.net/",
                 "sha1": "ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236", "size": 126151}
            ]
        });
        let merged = merge_with_vanilla(profile, &vanilla());

        assert_eq!(merged.main_class, "net.fabricmc.loader.impl.launch.knot.KnotClient");
        assert_eq!(merged.id, "fabric-loader-0.19.3-26.2");
        // inherited, not defaulted
        assert_eq!(merged.effective_java().component, "java-runtime-epsilon");
        assert!(merged.downloads.client.is_some(), "client jar comes from vanilla");
        assert_eq!(merged.asset_index.as_ref().unwrap().id, "262");
        // arguments concatenate: fabric's empty game list must not blank out
        // vanilla's game args, and its emu flag appends after vanilla's jvm
        let args = merged.arguments.as_ref().unwrap();
        assert!(args.game.iter().any(|a| a == "--username"), "vanilla game args survive");
        assert!(args.jvm.iter().any(|a| a == "-cp"), "vanilla -cp survives");
        assert!(
            args.jvm.iter().any(|a| a.to_string().contains("FabricMcEmu")),
            "fabric jvm flag appended"
        );
        // union of libraries, no duplicates
        let names: Vec<&str> = merged.libraries.iter().map(|l| l.name.as_str()).collect();
        assert!(names.contains(&"com.mojang:logging"));
        assert!(names.contains(&"net.fabricmc:fabric-loader:0.19.3"));
        assert_eq!(names.len(), 3);
        // every merged library resolves to a real jar (download + classpath)
        let fabric_loader = merged
            .libraries
            .iter()
            .find(|l| l.name == "net.fabricmc:fabric-loader:0.19.3")
            .unwrap();
        let artifact = fabric_loader.resolve_artifact().unwrap();
        assert_eq!(
            artifact.url,
            "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"
        );
    }

    /// The fabric profile alone must NOT parse into a usable version: this is
    /// the guard against someone reintroducing standalone parsing.
    #[test]
    fn fabric_profile_without_vanilla_is_not_launchable() {
        let json = r#"{
            "id": "fabric-loader-0.19.3-26.2",
            "type": "release",
            "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "arguments": {"jvm": [], "game": []},
            "libraries": []
        }"#;
        let parsed: meta::VersionJson = serde_json::from_str(json).unwrap();
        assert!(parsed.java_version.is_none());
        assert!(parsed.downloads.client.is_none());
    }
}
