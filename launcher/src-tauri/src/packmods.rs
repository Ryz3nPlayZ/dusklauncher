//! Resource packs made for OptiFine keep their custom skies, connected
//! textures, random and glowing mob textures, mob models, item textures, GUI
//! textures and animations under `assets/minecraft/optifine/` (or the older
//! `mcpatcher/`). The game ignores all of it, so on Fabric each needs a mod
//! that reads the format. This finds which of those an instance's packs use
//! and installs the mods that draw them.

use crate::appstate::AppState;
use crate::mods;
use fasterlauncher_core::modrinth as mr;
use fasterlauncher_core::profile::Loader;
use serde::Serialize;
use std::collections::{HashMap, HashSet};
use std::path::Path;
use tauri::{AppHandle, State};

/// A Fabric mod: (Modrinth project, mod id, the name players know).
type FabricMod = (&'static str, &'static str, &'static str);

struct Feature {
    id: &'static str,
    /// what players call it: "custom skies"
    label: &'static str,
    /// folders under `optifine/` that mean a pack uses it
    dirs: &'static [&'static str],
    /// files right in `optifine/` that do too
    files: &'static [&'static str],
    /// the mods that draw it — a mod before a library it pins a build of
    mods: &'static [FabricMod],
}

const ETF: FabricMod = ("entitytexturefeatures", "entity_texture_features", "Entity Texture Features");

/// Each OptiFine pack feature and the Fabric mods that read it. All are
/// open source (MIT / LGPL) and installed from Modrinth, never bundled.
const FEATURES: &[Feature] = &[
    Feature {
        id: "sky",
        label: "custom skies",
        dirs: &["sky"],
        files: &[],
        // Nuit draws skies; Nuit Interop reads OptiFine's format into it
        mods: &[("nuit-interop", "nuit_interop", "Nuit Interop"), ("nuit", "nuit", "Nuit")],
    },
    Feature {
        id: "ctm",
        label: "connected textures",
        dirs: &["ctm"],
        files: &[],
        mods: &[("continuity", "continuity", "Continuity")],
    },
    Feature {
        id: "mobs",
        label: "random and glowing mob textures",
        dirs: &["random", "mob"],
        files: &["emissive.properties"],
        mods: &[ETF],
    },
    Feature {
        id: "cem",
        label: "custom mob models",
        dirs: &["cem"],
        files: &[],
        mods: &[("entity-model-features", "entity_model_features", "Entity Model Features"), ETF],
    },
    Feature {
        id: "cit",
        label: "custom item textures",
        dirs: &["cit"],
        files: &["cit.properties"],
        mods: &[("cit-resewn", "citresewn", "CIT Resewn")],
    },
    Feature {
        id: "gui",
        label: "custom GUI textures",
        dirs: &["gui"],
        files: &[],
        mods: &[("optigui", "optigui", "OptiGUI")],
    },
    Feature {
        id: "anim",
        label: "custom animations",
        dirs: &["anim"],
        files: &[],
        mods: &[("animatica", "animatica", "Animatica")],
    },
];

const ROOTS: &[&str] = &["assets/minecraft/optifine/", "assets/minecraft/mcpatcher/"];

/// The features a pack's file paths point at.
fn features_in<'a>(paths: impl Iterator<Item = &'a str>) -> HashSet<&'static str> {
    let mut found = HashSet::new();
    for path in paths {
        let Some(rest) = ROOTS.iter().find_map(|root| path.strip_prefix(root)) else { continue };
        let hit = match rest.split_once('/') {
            Some((dir, _)) => FEATURES.iter().find(|f| f.dirs.contains(&dir)),
            None => FEATURES.iter().find(|f| f.files.contains(&rest)),
        };
        if let Some(f) = hit {
            found.insert(f.id);
        }
    }
    found
}

/// A folder pack's features: whether each of their folders or files exists.
fn features_in_folder(pack: &Path) -> HashSet<&'static str> {
    let mut found = HashSet::new();
    for root in ROOTS {
        let base = pack.join(root);
        for f in FEATURES {
            let there = f.dirs.iter().any(|d| base.join(d).is_dir()) || f.files.iter().any(|n| base.join(n).is_file());
            if there {
                found.insert(f.id);
            }
        }
    }
    found
}

/// (pack name, its features) for every enabled pack in `dir`, zipped or a folder.
fn scan_packs(dir: &Path) -> Vec<(String, HashSet<&'static str>)> {
    let mut out = Vec::new();
    let Ok(entries) = std::fs::read_dir(dir) else { return out };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().to_string();
        let found = if path.is_dir() {
            features_in_folder(&path)
        } else if let Some(base) = name.strip_suffix(".zip") {
            // only the central directory is read, so a big pack costs little
            let Some(zip) = std::fs::File::open(&path).ok().and_then(|f| zip::ZipArchive::new(f).ok()) else {
                continue;
            };
            let found = features_in(zip.file_names());
            out.push((base.to_string(), found));
            continue;
        } else {
            continue;
        };
        out.push((name, found));
    }
    out.retain(|(_, f)| !f.is_empty());
    out.sort_by(|a, b| a.0.cmp(&b.0));
    out
}

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PackNeed {
    pub feature: String,
    pub label: String,
    /// the packs that have it
    pub packs: Vec<String>,
    /// the mods it still needs, by name
    pub mods: Vec<String>,
}

/// The pack features in `packs_dir` that `mods_dir` has no mod for yet.
fn needs(packs_dir: &Path, mods_dir: &Path) -> Vec<PackNeed> {
    let packs = scan_packs(packs_dir);
    if packs.is_empty() {
        return Vec::new();
    }
    let held = mods::fabric_mod_ids(mods_dir);
    FEATURES
        .iter()
        .filter_map(|f| {
            let missing: Vec<String> =
                f.mods.iter().filter(|m| !held.contains(m.1)).map(|m| m.2.to_string()).collect();
            let having: Vec<String> =
                packs.iter().filter(|(_, found)| found.contains(f.id)).map(|(n, _)| n.clone()).collect();
            (!missing.is_empty() && !having.is_empty()).then(|| PackNeed {
                feature: f.id.into(),
                label: f.label.into(),
                packs: having,
                mods: missing,
            })
        })
        .collect()
}

/// What an instance's resource packs use that it has no mod for. Empty for
/// anything but Fabric: the mods are installed as Fabric builds.
#[tauri::command(async)]
pub fn pack_mods(state: State<AppState>, profile_id: String) -> Result<Vec<PackNeed>, String> {
    let (profile, packs) = mods::profile_and_content(&state, &profile_id, "resourcepack")?;
    if profile.loader != Loader::Fabric {
        return Ok(Vec::new());
    }
    Ok(needs(&packs, &profile.dirs(&state.data_dir).mods))
}

/// A dependency a fabric.mod.json pins to one exact version (`"nuit":
/// "1.0.0-beta.5+mc1.21.11"`), as (mod id, that version without its build
/// part). Ranges aren't pins: the newest build meets those.
fn exact_pins(meta: &serde_json::Value) -> Vec<(String, String)> {
    let Some(depends) = meta.get("depends").and_then(|d| d.as_object()) else { return Vec::new() };
    depends
        .iter()
        .filter_map(|(id, want)| {
            let want = want.as_str()?.trim();
            let exact = want.starts_with(|c: char| c.is_ascii_digit())
                && !want.contains([' ', '*'])
                && !want.ends_with(".x");
            exact.then(|| (id.clone(), want.split('+').next().unwrap_or(want).to_string()))
        })
        .collect()
}

/// Whether a Modrinth version number (`mc1.21.11-1.0.0-beta.5+fabric`) names
/// this mod version (`1.0.0-beta.5`), not one that only starts or ends the
/// same (`1.0.0-beta.50`, `11.0.0-beta.5`).
fn names_version(number: &str, version: &str) -> bool {
    let edge = |c: Option<char>| c.is_none_or(|c| !c.is_ascii_alphanumeric() && c != '.');
    number.match_indices(version).any(|(i, _)| {
        edge(number[..i].chars().next_back()) && edge(number[i + version.len()..].chars().next())
    })
}

/// The build to install: the one a mod already added pins, else the newest
/// release (or newest build when there's no release).
fn pick(versions: Vec<mr::Version>, pin: Option<&String>) -> Option<mr::Version> {
    if let Some(pin) = pin {
        if let Some(i) = versions.iter().position(|v| names_version(&v.version_number, pin)) {
            let mut versions = versions;
            return Some(versions.swap_remove(i));
        }
    }
    mr::newest_preferring_release(versions)
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct PackModsInstalled {
    /// names of the mods added
    pub added: Vec<String>,
    /// names of the ones with no build for this game version
    pub unavailable: Vec<String>,
}

/// Install the mods a pack feature needs (every feature, without one), plus
/// their libraries. One at a time, so a library a mod pins to an exact
/// build — Nuit Interop pins Nuit — gets that build instead of the newest.
#[tauri::command]
pub async fn install_pack_mods(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    feature: Option<String>,
) -> Result<PackModsInstalled, String> {
    let (profile, packs) = mods::profile_and_content(&state, &profile_id, "resourcepack")?;
    if profile.loader != Loader::Fabric {
        return Err("These mods are Fabric mods — switch the loader to Fabric in SETTINGS.".into());
    }
    let mods_dir = profile.dirs(&state.data_dir).mods;
    let scan = {
        let (packs, mods_dir) = (packs.clone(), mods_dir.clone());
        tokio::task::spawn_blocking(move || (needs(&packs, &mods_dir), mods::fabric_mod_ids(&mods_dir)))
    };
    let (needs, mut held) = scan.await.map_err(|e| e.to_string())?;
    let mut wanted: Vec<FabricMod> = Vec::new();
    for f in FEATURES.iter().filter(|f| needs.iter().any(|n| n.feature == f.id)) {
        if feature.as_deref().is_some_and(|want| want != f.id) {
            continue;
        }
        for m in f.mods {
            if !wanted.iter().any(|w| w.1 == m.1) {
                wanted.push(*m);
            }
        }
    }
    let mut pins: HashMap<String, String> = HashMap::new();
    let mut deps = Vec::new();
    let (mut added, mut unavailable) = (Vec::new(), Vec::new());
    for (project, id, name) in wanted {
        if held.contains(id) {
            continue;
        }
        let versions = mr::project_versions_for_loader(&state.client, project, &profile.game_version, Some("fabric"))
            .await
            .map_err(|e| format!("Couldn't reach Modrinth for {name}: {e}"))?;
        let Some(ver) = pick(versions, pins.get(id)) else {
            unavailable.push(name.to_string());
            continue;
        };
        deps.extend(ver.dependencies.clone());
        let file = mods::install_version_file(app.clone(), state.clone(), profile.id.clone(), "mod", mods_dir.clone(), ver)
            .await
            .map_err(|e| format!("Couldn't install {name}: {e}"))?;
        if let Some(meta) = mods::fabric_meta(&mods_dir.join(&file.filename)) {
            pins.extend(exact_pins(&meta));
        }
        held.insert(id.to_string());
        added.push(name.to_string());
    }
    mods::install_required_deps(&app, &state, &profile, &mods_dir, deps).await;
    Ok(PackModsInstalled { added, unavailable })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn finds_optifine_features_by_their_folders() {
        let found = features_in(
            [
                "pack.mcmeta",
                "assets/minecraft/optifine/sky/world0/sky1.properties",
                "assets/minecraft/mcpatcher/ctm/glass/0.png",
                "assets/minecraft/optifine/emissive.properties",
                "assets/minecraft/optifine/cem/creeper.jem",
                "assets/minecraft/textures/block/stone.png",
                // a file named like a folder, and a namespace OptiFine doesn't read
                "assets/minecraft/optifine/cit",
                "assets/othermod/optifine/gui/chest.png",
            ]
            .into_iter(),
        );
        let mut found: Vec<_> = found.into_iter().collect();
        found.sort();
        assert_eq!(found, ["cem", "ctm", "mobs", "sky"]);
    }

    #[test]
    fn a_pack_with_skies_asks_for_the_sky_mods_the_instance_lacks() {
        let tmp = std::env::temp_dir().join(format!("dusk-packmods-{}", std::process::id()));
        let (packs, mods_dir) = (tmp.join("resourcepacks"), tmp.join("mods"));
        std::fs::create_dir_all(&packs).unwrap();
        std::fs::create_dir_all(&mods_dir).unwrap();
        let zip = |path: &Path, entries: &[(&str, &[u8])]| {
            let mut z = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
            for (name, body) in entries {
                z.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
                z.write_all(body).unwrap();
            }
            z.finish().unwrap();
        };
        zip(&packs.join("Skies.zip"), &[("assets/minecraft/optifine/sky/world0/sky1.properties", b"x")]);
        zip(&packs.join("Plain.zip"), &[("assets/minecraft/textures/block/stone.png", b"x")]);
        // turned off: its features don't count
        zip(&packs.join("Glass.zip.disabled"), &[("assets/minecraft/optifine/ctm/glass/0.png", b"x")]);
        // a folder pack
        std::fs::create_dir_all(packs.join("Mobs/assets/minecraft/optifine/random/entity")).unwrap();
        // Nuit is in, Nuit Interop isn't
        zip(&mods_dir.join("nuit.jar"), &[("fabric.mod.json", br#"{"id":"nuit","version":"1.0.0-beta.5"}"#)]);

        let got = needs(&packs, &mods_dir);
        assert_eq!(
            got,
            [
                PackNeed {
                    feature: "sky".into(),
                    label: "custom skies".into(),
                    packs: vec!["Skies".into()],
                    mods: vec!["Nuit Interop".into()],
                },
                PackNeed {
                    feature: "mobs".into(),
                    label: "random and glowing mob textures".into(),
                    packs: vec!["Mobs".into()],
                    mods: vec!["Entity Texture Features".into()],
                },
            ]
        );
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn a_pinned_library_gets_the_build_its_mod_names() {
        let meta = serde_json::json!({"depends": {
            "nuit": "1.0.0-beta.5+mc1.21.11",
            "fabric-api": "*",
            "minecraft": "~1.21.11",
            "fabricloader": ">=0.18.4",
            "sodium": "0.6.x",
        }});
        assert_eq!(exact_pins(&meta), [("nuit".to_string(), "1.0.0-beta.5".to_string())]);

        assert!(names_version("mc1.21.11-1.0.0-beta.5+fabric", "1.0.0-beta.5"));
        assert!(names_version("1.0.0-beta.5", "1.0.0-beta.5"));
        assert!(!names_version("mc1.21.11-1.0.0-beta.50+fabric", "1.0.0-beta.5"));
        assert!(!names_version("mc1.21.11-11.0.0-beta.5+fabric", "1.0.0-beta.5"));
        assert!(!names_version("mc1.21.11-1.0.0-beta.5.1+fabric", "1.0.0-beta.5"));

        let ver = |number: &str, kind: &str| -> mr::Version {
            serde_json::from_value(serde_json::json!({
                "id": number, "name": number, "version_number": number, "version_type": kind,
            }))
            .unwrap()
        };
        let list = || vec![ver("mc1.21.11-1.0.0-beta.6+fabric", "beta"), ver("mc1.21.11-1.0.0-beta.5+fabric", "beta")];
        // unpinned: the newest; pinned: the named one; a pin nothing matches falls back
        assert_eq!(pick(list(), None).unwrap().id, "mc1.21.11-1.0.0-beta.6+fabric");
        assert_eq!(pick(list(), Some(&"1.0.0-beta.5".into())).unwrap().id, "mc1.21.11-1.0.0-beta.5+fabric");
        assert_eq!(pick(list(), Some(&"2.0.0".into())).unwrap().id, "mc1.21.11-1.0.0-beta.6+fabric");
    }
}
