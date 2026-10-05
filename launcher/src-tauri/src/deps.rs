//! Missing Fabric dependencies: a mod whose `fabric.mod.json` `depends` on a
//! mod id nothing in `mods/` provides makes Fabric refuse to start, with an
//! error most players never get to read. The CONTENT tab checks before then
//! and offers the missing mod from Modrinth.
//!
//! What counts as provided: each enabled jar's `id` and `provides`, and the
//! same for the jars it bundles (`jars`) — Fabric API is one jar holding
//! ~80 module jars, and mods depend on those module ids directly.

use crate::appstate::AppState;
use serde::Serialize;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::io::{Read, Seek};
use tauri::State;

/// fabric.mod.json files are small; bundled jars are read into memory to
/// look inside them, so those get a bigger cap
const META_CAP: u64 = 256 * 1024;
const NESTED_CAP: u64 = 64 << 20;

/// Ids the game and Fabric Loader themselves provide.
const BUILT_IN: &[&str] = &["minecraft", "java", "fabricloader", "fabric-loader", "mixinextras"];

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct MissingDep {
    /// the mod id the jars depend on
    pub id: String,
    /// what players call it
    pub label: String,
    /// the Modrinth project to install it from
    pub project: String,
    /// display names of the mods that need it
    pub needed_by: Vec<String>,
    /// a disabled jar in mods/ that would provide it (by its enabled name,
    /// `x.jar`), to turn back on instead
    pub disabled_file: Option<String>,
}

/// Mod id → (name players know, Modrinth slug), for the common libraries
/// whose id and slug differ. Anything else is tried under its own id.
fn known(id: &str) -> (String, String) {
    let (label, slug) = match id {
        "fabric" | "fabric-api" => ("Fabric API", "fabric-api"),
        "fabric-language-kotlin" => ("Fabric Language Kotlin", "fabric-language-kotlin"),
        _ if id.starts_with("fabric-") => ("Fabric API", "fabric-api"),
        "cloth-config" | "cloth-config2" => ("Cloth Config API", "cloth-config"),
        "modmenu" => ("Mod Menu", "modmenu"),
        "yet_another_config_lib_v3" | "yet-another-config-lib" => ("YetAnotherConfigLib", "yacl"),
        "architectury" => ("Architectury API", "architectury-api"),
        "geckolib" => ("GeckoLib", "geckolib"),
        "owo" => ("oωo", "owo-lib"),
        "sodium" => ("Sodium", "sodium"),
        "iris" => ("Iris Shaders", "iris"),
        "forgeconfigapiport" => ("Forge Config API Port", "forge-config-api-port"),
        "puzzleslib" => ("Puzzles Lib", "puzzles-lib"),
        "resourcefullib" => ("Resourceful Lib", "resourceful-lib"),
        "midnightlib" => ("MidnightLib", "midnightlib"),
        "trinkets" => ("Trinkets", "trinkets"),
        "playeranimator" => ("playerAnimator", "playeranimator"),
        "creativecore" => ("CreativeCore", "creativecore"),
        _ => return (id.to_string(), id.to_string()),
    };
    (label.to_string(), slug.to_string())
}

fn read_entry<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, name: &str, cap: u64) -> Option<Vec<u8>> {
    let mut f = zip.by_name(name).ok()?;
    if f.size() > cap {
        return None;
    }
    let mut buf = Vec::with_capacity(f.size() as usize);
    f.read_to_end(&mut buf).ok()?;
    Some(buf)
}

fn meta_of<R: Read + Seek>(zip: &mut zip::ZipArchive<R>) -> Option<serde_json::Value> {
    serde_json::from_slice(&read_entry(zip, "fabric.mod.json", META_CAP)?).ok()
}

/// The ids a jar provides: its own, its `provides`, and its bundled jars' (two levels down).
fn provided<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, meta: &serde_json::Value, depth: u32, out: &mut HashSet<String>) {
    if let Some(id) = meta.get("id").and_then(|v| v.as_str()) {
        out.insert(id.to_string());
    }
    for id in meta.get("provides").and_then(|v| v.as_array()).into_iter().flatten() {
        if let Some(id) = id.as_str() {
            out.insert(id.to_string());
        }
    }
    if depth >= 2 {
        return;
    }
    for nested in meta.get("jars").and_then(|v| v.as_array()).into_iter().flatten() {
        let Some(file) = nested.get("file").and_then(|v| v.as_str()) else { continue };
        let Some(bytes) = read_entry(zip, file, NESTED_CAP) else { continue };
        let Ok(mut inner) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) else { continue };
        if let Some(inner_meta) = meta_of(&mut inner) {
            provided(&mut inner, &inner_meta, depth + 1, out);
        }
    }
}

/// One jar in mods/: what it provides and, when enabled, what it needs.
struct Jar {
    filename: String,
    enabled: bool,
    name: String,
    provides: HashSet<String>,
    depends: Vec<String>,
}

fn read_jar(path: &std::path::Path, filename: String, enabled: bool) -> Option<Jar> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()?;
    let meta = meta_of(&mut zip)?;
    let mut provides = HashSet::new();
    provided(&mut zip, &meta, 0, &mut provides);
    let name = meta
        .get("name")
        .and_then(|v| v.as_str())
        .or_else(|| meta.get("id").and_then(|v| v.as_str()))
        .unwrap_or(&filename)
        .to_string();
    let depends = meta
        .get("depends")
        .and_then(|v| v.as_object())
        .map(|m| m.keys().cloned().collect())
        .unwrap_or_default();
    Some(Jar { filename, enabled, name, provides, depends })
}

fn find_missing(jars: &[Jar]) -> Vec<MissingDep> {
    let on: HashSet<&str> = jars
        .iter()
        .filter(|j| j.enabled)
        .flat_map(|j| j.provides.iter().map(String::as_str))
        .chain(BUILT_IN.iter().copied())
        .collect();
    let off: HashMap<&str, &str> = jars
        .iter()
        .filter(|j| !j.enabled)
        .flat_map(|j| j.provides.iter().map(move |id| (id.as_str(), j.filename.as_str())))
        .collect();
    // grouped by what players would install: every missing fabric-* module is one Fabric API
    let mut by_project: BTreeMap<String, MissingDep> = BTreeMap::new();
    for jar in jars.iter().filter(|j| j.enabled) {
        for id in jar.depends.iter().filter(|id| !on.contains(id.as_str())) {
            let (label, project) = known(id);
            let entry = by_project.entry(project.clone()).or_insert_with(|| MissingDep {
                id: id.clone(),
                label,
                project,
                needed_by: Vec::new(),
                disabled_file: off.get(id.as_str()).map(|f| f.to_string()),
            });
            if entry.disabled_file.is_none() {
                entry.disabled_file = off.get(id.as_str()).map(|f| f.to_string());
            }
            if !entry.needed_by.contains(&jar.name) {
                entry.needed_by.push(jar.name.clone());
            }
        }
    }
    by_project.into_values().collect()
}

/// What the instance's mods need that its mods/ doesn't have. Empty for
/// instances that don't run Fabric.
#[tauri::command]
pub async fn missing_dependencies(state: State<'_, AppState>, profile_id: String) -> Result<Vec<MissingDep>, String> {
    let (fabric, dir) = {
        let store = state.profiles.lock().unwrap();
        let profile = store.profiles.iter().find(|p| p.id == profile_id).ok_or("profile not found")?;
        (profile.loader.as_str() == "fabric", profile.dirs(&state.data_dir).mods)
    };
    if !fabric {
        return Ok(Vec::new());
    }
    tokio::task::spawn_blocking(move || {
        let jars: Vec<Jar> = std::fs::read_dir(&dir)
            .map(|rd| {
                rd.flatten()
                    .filter_map(|e| {
                        let filename = e.file_name().to_string_lossy().into_owned();
                        let enabled = filename.ends_with(".jar");
                        // disabled jars go by the name they have when on, like the CONTENT list
                        let logical = match filename.strip_suffix(".disabled") {
                            Some(base) if base.ends_with(".jar") => base.to_string(),
                            _ if enabled => filename,
                            _ => return None,
                        };
                        read_jar(&e.path(), logical, enabled)
                    })
                    .collect()
            })
            .unwrap_or_default();
        find_missing(&jars)
    })
    .await
    .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn jar_bytes(meta: &str, nested: &[(&str, Vec<u8>)]) -> Vec<u8> {
        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let opts = zip::write::SimpleFileOptions::default();
        zip.start_file("fabric.mod.json", opts).unwrap();
        zip.write_all(meta.as_bytes()).unwrap();
        for (name, bytes) in nested {
            zip.start_file(*name, opts).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap().into_inner()
    }

    fn jar(bytes: Vec<u8>, filename: &str, enabled: bool) -> Jar {
        let tmp = std::env::temp_dir().join(format!("dusk-deps-{}-{filename}", std::process::id()));
        std::fs::write(&tmp, bytes).unwrap();
        let out = read_jar(&tmp, filename.into(), enabled).unwrap();
        let _ = std::fs::remove_file(&tmp);
        out
    }

    #[test]
    fn bundled_modules_count_and_missing_ones_group() {
        let module = jar_bytes(r#"{"id":"fabric-rendering-v1"}"#, &[]);
        let api = jar_bytes(
            r#"{"id":"fabric-api","provides":["fabric"],"jars":[{"file":"META-INF/jars/r.jar"}]}"#,
            &[("META-INF/jars/r.jar", module)],
        );
        let needs = jar_bytes(
            r#"{"id":"a","name":"Mod A","depends":{"fabricloader":">=0.16","minecraft":"*","fabric-rendering-v1":"*","cloth-config2":"*"}}"#,
            &[],
        );
        let also = jar_bytes(r#"{"id":"b","name":"Mod B","depends":{"cloth-config":"*","fabric-key-binding-api-v1":"*"}}"#, &[]);
        let cloth = jar_bytes(r#"{"id":"cloth-config","provides":["cloth-config2"]}"#, &[]);

        // with Fabric API on: only Cloth Config is missing, needed by both
        let jars = [jar(api.clone(), "api.jar", true), jar(needs.clone(), "a.jar", true), jar(also.clone(), "b.jar", true)];
        let missing = find_missing(&jars);
        assert_eq!(missing.len(), 2, "{missing:?}");
        let cloth_dep = missing.iter().find(|m| m.project == "cloth-config").unwrap();
        assert_eq!(cloth_dep.needed_by, ["Mod A", "Mod B"]);
        assert_eq!(cloth_dep.label, "Cloth Config API");
        // fabric-key-binding-api-v1 isn't in this tiny test Fabric API
        assert!(missing.iter().any(|m| m.project == "fabric-api"));

        // Fabric API turned off: one entry for it, pointing at the disabled jar
        let jars = [
            jar(api, "api.jar", false),
            jar(needs, "a.jar", true),
            jar(cloth, "cloth.jar", true),
            jar(also, "b.jar", true),
        ];
        let missing = find_missing(&jars);
        assert_eq!(missing.len(), 1, "{missing:?}");
        assert_eq!(missing[0].label, "Fabric API");
        assert_eq!(missing[0].needed_by, ["Mod A", "Mod B"]);
        assert_eq!(missing[0].disabled_file.as_deref(), Some("api.jar"));
    }
}
