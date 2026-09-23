//! Cosmetics: the catalog shipped inside the bundled DuskClient jar and the
//! player's chosen loadout (docs/COSMETICS.md).
//!
//! The jar is the single source of truth for what exists — the launcher
//! reads `assets/duskclient/cosmetics/registry.json` (and the cape PNGs)
//! straight out of the zip, so there is never a second copy of the catalog
//! to keep in sync. The loadout is launcher-wide (`<data>/cosmetics.json`)
//! and is written into every Fabric instance's `config/duskclient.json` at
//! launch, which is where the mod picks it up; it is also pushed to the Dusk
//! service so other Dusk players see it (`dusk::publish_loadout`).
//!
//! What the player *owns* lives on the Dusk service (coins, purchases,
//! redeem codes — `dusk.rs`). `<data>/inventory.json` is only a cache of the
//! service's answer so the wardrobe still lists what you own offline.

use crate::appstate::AppState;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::collections::BTreeSet;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_dialog::DialogExt;

/// The jars this build ships, one per game line: intermediary mappings are
/// per-version (a 1.21.x build fails to even load on 26.x) and the
/// rendering/HUD APIs the mod hooks moved between lines (post chains,
/// render states, HUD layers, screen ownership), so the mod is compiled per
/// target (`client-mod/build.gradle -Pmc=…`) and the launcher picks the one
/// matching the profile. Together they cover every release from 1.21
/// through 26.2. Keep in step with `client-mod/gradle.properties`.
pub const CLIENT_MOD_JARS: [&str; 9] = [
    "duskclient-1.21.1.jar",  // 1.21, 1.21.1
    "duskclient-1.21.3.jar",  // 1.21.2, 1.21.3
    "duskclient-1.21.4.jar",  // 1.21.4
    "duskclient-1.21.5.jar",  // 1.21.5
    "duskclient-1.21.8.jar",  // 1.21.6 – 1.21.8
    "duskclient-1.21.10.jar", // 1.21.9, 1.21.10
    "duskclient-1.21.11.jar", // 1.21.11
    "duskclient-26.1.jar",    // 26.1.x
    "duskclient-26.2.jar",    // 26.2.x
];
/// Human-readable list of the game versions those jars cover.
pub const CLIENT_MOD_GAME_VERSIONS: &str = "1.21 through 26.2";

/// The bundled jar to inject into a `game_version`, or `None` when this
/// build has nothing compiled for it.
pub fn client_mod_jar_for(game_version: &str) -> Option<&'static str> {
    let mut parts = game_version.split(['.', '-']);
    let major = parts.next();
    let minor = parts.next();
    let patch = parts.next().and_then(|p| p.parse::<u32>().ok()).unwrap_or(0);
    match (major, minor) {
        (Some("1"), Some("21")) => Some(match patch {
            0 | 1 => CLIENT_MOD_JARS[0],
            2 | 3 => CLIENT_MOD_JARS[1],
            4 => CLIENT_MOD_JARS[2],
            5 => CLIENT_MOD_JARS[3],
            6..=8 => CLIENT_MOD_JARS[4],
            9 | 10 => CLIENT_MOD_JARS[5],
            11 => CLIENT_MOD_JARS[6],
            _ => return None,
        }),
        (Some("26"), Some("1")) => Some(CLIENT_MOD_JARS[7]),
        (Some("26"), Some("2")) => Some(CLIENT_MOD_JARS[8]),
        _ => None,
    }
}

/// Whether the bundled client mod may be injected into a `game_version`.
pub fn client_mod_supports(game_version: &str) -> bool {
    client_mod_jar_for(game_version).is_some()
}

/// Whether a `mods/` folder already carries one of our jars (a copy from
/// "install bundled client mod" wins over the forced one).
pub fn client_mod_in_mods(mods_dir: &Path) -> bool {
    CLIENT_MOD_JARS.iter().any(|j| mods_dir.join(j).exists())
}
const REGISTRY_PATH: &str = "assets/duskclient/cosmetics/registry.json";
const COSMETICS_PREFIX: &str = "assets/duskclient/cosmetics/";

/// Where a bundled DuskClient jar lives, in priority order:
/// 1. `DUSK_CLIENT_MOD_JAR` (developer override, any version),
/// 2. the Tauri resource (`resources/<jar>`, populated by the client-mod
///    Gradle build via `installToLauncher`),
/// 3. the client-mod source tree next to this crate (debug builds only, so
///    `tauri dev` works without a packaging step),
/// 4. a jar dropped into the launcher data dir by hand.
pub fn bundled_client_mod_jar(app: &AppHandle, data_dir: &Path, jar: &str) -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Some(p) = std::env::var_os("DUSK_CLIENT_MOD_JAR") {
        candidates.push(PathBuf::from(p));
    }
    if let Ok(res) = app.path().resource_dir() {
        candidates.push(res.join("resources").join(jar));
        candidates.push(res.join(jar));
    }
    if cfg!(debug_assertions) {
        let src_tauri = Path::new(env!("CARGO_MANIFEST_DIR"));
        candidates.push(src_tauri.join("resources").join(jar));
    }
    candidates.push(data_dir.join(jar));
    candidates.push(data_dir.join("bundled").join(jar));
    candidates.into_iter().find(|p| p.is_file())
}

/// Any bundled jar — the catalog inside is identical across versions, so the
/// store and wardrobe read whichever one is present.
pub fn any_bundled_client_mod_jar(app: &AppHandle, data_dir: &Path) -> Option<PathBuf> {
    CLIENT_MOD_JARS.iter().find_map(|j| bundled_client_mod_jar(app, data_dir, j))
}

// ── catalog ────────────────────────────────────────────────────────────────

/// One cape as the registry describes it. `frames` is derived by the UI from
/// the PNG (MinecraftCapes rule: height ≠ width/2 ⇒ vertical frame strip).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CapeEntry {
    pub id: u32,
    pub name: String,
    #[serde(default)]
    pub glint: bool,
    #[serde(default)]
    pub upside_down: bool,
    /// true when `capes/<id>/ears.png` ships alongside the cape
    #[serde(default)]
    pub ears: bool,
    /// animation speed in ms per frame (100 = the MinecraftCapes default)
    #[serde(default = "default_frame_ms")]
    pub frame_ms: u32,
}

fn default_frame_ms() -> u32 {
    100
}

fn one() -> u32 {
    1
}

/// One Cosmetica-style model accessory (`accessories/<id>/model.json` +
/// `texture.png`). `offset` is Cosmetica's pixel offset, `attachment` one
/// of head|body|left_arm|right_arm|left_leg|right_leg (docs/COSMETICS.md §5).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccessoryEntry {
    pub id: u32,
    pub name: String,
    #[serde(default = "default_attachment")]
    pub attachment: String,
    #[serde(default)]
    pub offset: [f32; 3],
    #[serde(default)]
    pub mirrored: bool,
    #[serde(default = "one")]
    pub frames: u32,
    #[serde(default = "one")]
    pub ticks_per_frame: u32,
    #[serde(default)]
    pub flags: u32,
    /// where it was imported from (informational, e.g. `cosmetica:ufa1T`)
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
}

fn default_attachment() -> String {
    "body".into()
}

/// `registry.json` as shipped in the jar.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Catalog {
    #[serde(default)]
    pub version: u32,
    #[serde(default)]
    pub capes: Vec<CapeEntry>,
    #[serde(default)]
    pub accessories: Vec<AccessoryEntry>,
}

fn read_jar_entry(jar: &Path, name: &str) -> Result<Vec<u8>, String> {
    let file = std::fs::File::open(jar).map_err(|e| format!("open {}: {e}", jar.display()))?;
    let mut zip = zip::ZipArchive::new(file).map_err(|e| e.to_string())?;
    let mut entry = zip
        .by_name(name)
        .map_err(|_| format!("{name} is not in the client mod jar"))?;
    let mut buf = Vec::with_capacity(entry.size() as usize);
    entry.read_to_end(&mut buf).map_err(|e| e.to_string())?;
    Ok(buf)
}

/// The catalog inside the bundled jar. An empty catalog (not an error) when
/// no jar is packaged, so the wardrobe can say so instead of breaking.
#[tauri::command]
pub fn list_cosmetics(app: AppHandle, state: State<AppState>) -> Result<Catalog, String> {
    let Some(jar) = any_bundled_client_mod_jar(&app, &state.data_dir) else {
        return Ok(Catalog::default());
    };
    let bytes = read_jar_entry(&jar, REGISTRY_PATH)?;
    serde_json::from_slice(&bytes).map_err(|e| format!("registry.json: {e}"))
}

/// A cosmetic texture out of the jar as a PNG data URL. `kind` is `cape` or
/// `ears` (both under `capes/<id>/`) or `accessory` (`accessories/<id>/texture.png`).
#[tauri::command]
pub fn read_cosmetic_texture(
    app: AppHandle,
    state: State<AppState>,
    kind: String,
    id: u32,
) -> Result<String, String> {
    let path = match kind.as_str() {
        "cape" => format!("capes/{id}/cape.png"),
        "ears" => format!("capes/{id}/ears.png"),
        "accessory" => format!("accessories/{id}/texture.png"),
        _ => return Err("unknown texture kind (want cape|ears|accessory)".into()),
    };
    let jar = any_bundled_client_mod_jar(&app, &state.data_dir)
        .ok_or("Bundled client mod is not packaged in this build yet.")?;
    let bytes = read_jar_entry(&jar, &format!("{COSMETICS_PREFIX}{path}"))?;
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD.encode(bytes);
    Ok(format!("data:image/png;base64,{b64}"))
}

/// An accessory's Blockbench model JSON out of the jar, for the wardrobe
/// preview (the mod bakes the very same file in-game).
#[tauri::command]
pub fn read_cosmetic_model(app: AppHandle, state: State<AppState>, id: u32) -> Result<Value, String> {
    let jar = any_bundled_client_mod_jar(&app, &state.data_dir)
        .ok_or("Bundled client mod is not packaged in this build yet.")?;
    let bytes = read_jar_entry(&jar, &format!("{COSMETICS_PREFIX}accessories/{id}/model.json"))?;
    serde_json::from_slice(&bytes).map_err(|e| format!("model.json: {e}"))
}

// ── loadout ────────────────────────────────────────────────────────────────

/// The loadout is the JSON object from docs/COSMETICS.md §1.3 — slot → id,
/// plus an optional `settings` object — kept as raw JSON so the launcher
/// never has to know about slots the mod adds later.
pub type Loadout = Map<String, Value>;

fn loadout_path(data_dir: &Path) -> PathBuf {
    data_dir.join("cosmetics.json")
}

pub fn load_loadout(data_dir: &Path) -> Loadout {
    std::fs::read(loadout_path(data_dir))
        .ok()
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default()
}

/// Sanity-check what the UI sends: slot values are small non-negative ints
/// (or, for multi-slots like `accessories`, arrays of them), `settings` (if
/// present) is an object. Anything else is a bug upstream.
fn validate_loadout(loadout: &Loadout) -> Result<(), String> {
    let is_id = |v: &Value| matches!(v.as_u64(), Some(n) if n <= u32::MAX as u64);
    for (slot, v) in loadout {
        if slot == "settings" {
            if !v.is_object() {
                return Err("loadout.settings must be an object".into());
            }
            continue;
        }
        let ok = match v {
            Value::Array(items) => items.iter().all(is_id),
            other => is_id(other),
        };
        if !ok {
            return Err(format!("loadout slot {slot:?} must be a non-negative id (or a list of them)"));
        }
    }
    Ok(())
}

#[tauri::command]
pub fn get_loadout(state: State<AppState>) -> Loadout {
    load_loadout(&state.data_dir)
}

/// Replace the loadout wholesale (the UI sends the full object; a slot that
/// is absent is unequipped). The service is asked first — it is the
/// authority on what the account owns, and it is what other Dusk players
/// read — and only its answer is written locally.
#[tauri::command]
pub async fn set_loadout(state: State<'_, AppState>, loadout: Loadout) -> Result<Loadout, String> {
    validate_loadout(&loadout)?;
    let loadout = crate::dusk::publish_loadout(&state, &loadout).await?;
    save_loadout(&state.data_dir, &loadout)?;
    Ok(loadout)
}

pub fn save_loadout(data_dir: &Path, loadout: &Loadout) -> Result<(), String> {
    let path = loadout_path(data_dir);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    std::fs::write(&path, serde_json::to_vec_pretty(loadout).unwrap()).map_err(|e| e.to_string())
}

/// Merge the current loadout into an instance's `config/duskclient.json`
/// (the file the mod and the launcher share), leaving every other key the
/// mod stores there untouched. Called right before a Fabric launch.
pub fn write_loadout_to_instance(data_dir: &Path, instance_root: &Path) -> Result<(), String> {
    let cfg_dir = instance_root.join("config");
    let path = cfg_dir.join("duskclient.json");
    let mut doc: Map<String, Value> = std::fs::read(&path)
        .ok()
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();
    let mut cosmetics = doc
        .get("cosmetics")
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();
    cosmetics.insert("loadout".into(), Value::Object(load_loadout(data_dir)));
    doc.insert("cosmetics".into(), Value::Object(cosmetics));
    std::fs::create_dir_all(&cfg_dir).map_err(|e| e.to_string())?;
    std::fs::write(&path, serde_json::to_vec_pretty(&doc).unwrap()).map_err(|e| e.to_string())
}

// ── inventory (the store) ───────────────────────────────────────────────

/// What the account owns, by registry id — a cache of the service's answer
/// (`dusk::fetch_me` refreshes it). Equipped items are always owned.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Inventory {
    #[serde(default)]
    pub owned: Vec<u32>,
}

fn inventory_path(data_dir: &Path) -> PathBuf {
    data_dir.join("inventory.json")
}

/// The ids a loadout wears, whatever the slot (`cape: 7`, `accessories: [16]`).
fn loadout_ids(loadout: &Loadout) -> impl Iterator<Item = u32> + '_ {
    loadout.iter().filter(|(k, _)| k.as_str() != "settings").flat_map(|(_, v)| match v {
        Value::Array(items) => items.iter().filter_map(Value::as_u64).collect::<Vec<_>>(),
        other => other.as_u64().into_iter().collect(),
    })
    .filter_map(|n| u32::try_from(n).ok())
}

pub fn load_inventory(data_dir: &Path) -> Inventory {
    let stored: Inventory = std::fs::read(inventory_path(data_dir))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default();
    let mut owned: BTreeSet<u32> = stored.owned.into_iter().collect();
    owned.extend(loadout_ids(&load_loadout(data_dir)));
    Inventory { owned: owned.into_iter().collect() }
}

pub fn save_inventory(data_dir: &Path, inv: &Inventory) -> Result<(), String> {
    let path = inventory_path(data_dir);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    std::fs::write(&path, serde_json::to_vec_pretty(inv).unwrap()).map_err(|e| e.to_string())
}

/// The cached inventory, refreshed from the service when it can be reached
/// (offline, the cache is what you get).
#[tauri::command]
pub async fn get_inventory(state: State<'_, AppState>) -> Result<Inventory, String> {
    match crate::dusk::fetch_me(&state).await {
        Ok(me) => Ok(Inventory { owned: me.owned }),
        Err(_) => Ok(load_inventory(&state.data_dir)),
    }
}

/// Save a cape's PNG (native save dialog) so the player can upload the very
/// same file at minecraftcapes.net — that is the only way a player running
/// the MinecraftCapes or Cosmetica mod sees a Dusk cape (docs/COSMETICS.md
/// §3.1). Resolves to the written path, or `None` when cancelled.
#[tauri::command]
pub async fn export_cosmetic_texture(
    app: AppHandle,
    state: State<'_, AppState>,
    kind: String,
    id: u32,
) -> Result<Option<String>, String> {
    let (path, stem) = match kind.as_str() {
        "cape" => (format!("capes/{id}/cape.png"), "cape"),
        "ears" => (format!("capes/{id}/ears.png"), "ears"),
        "accessory" => (format!("accessories/{id}/texture.png"), "texture"),
        _ => return Err("unknown texture kind (want cape|ears|accessory)".into()),
    };
    let jar = any_bundled_client_mod_jar(&app, &state.data_dir)
        .ok_or("Bundled client mod is not packaged in this build yet.")?;
    let bytes = read_jar_entry(&jar, &format!("{COSMETICS_PREFIX}{path}"))?;
    let name = list_cosmetics(app.clone(), state.clone())?
        .capes
        .iter()
        .find(|c| c.id == id)
        .map(|c| c.name.clone())
        .unwrap_or_else(|| format!("{stem}-{id}"));
    let safe: String = name
        .chars()
        .map(|c| if c.is_alphanumeric() || c == ' ' || c == '-' || c == '_' { c } else { '_' })
        .collect();
    let picked = app
        .dialog()
        .file()
        .add_filter("PNG image", &["png"])
        .set_file_name(format!("{}.png", safe.trim()))
        .blocking_save_file();
    let Some(file) = picked else { return Ok(None) };
    let out = file.into_path().map_err(|e| e.to_string())?;
    std::fs::write(&out, bytes).map_err(|e| e.to_string())?;
    Ok(Some(out.display().to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jar_follows_the_game_line() {
        assert_eq!(client_mod_jar_for("1.21"), Some("duskclient-1.21.1.jar"));
        assert_eq!(client_mod_jar_for("1.21.1"), Some("duskclient-1.21.1.jar"));
        assert_eq!(client_mod_jar_for("1.21.2"), Some("duskclient-1.21.3.jar"));
        assert_eq!(client_mod_jar_for("1.21.3"), Some("duskclient-1.21.3.jar"));
        assert_eq!(client_mod_jar_for("1.21.4"), Some("duskclient-1.21.4.jar"));
        assert_eq!(client_mod_jar_for("1.21.5"), Some("duskclient-1.21.5.jar"));
        assert_eq!(client_mod_jar_for("1.21.6"), Some("duskclient-1.21.8.jar"));
        assert_eq!(client_mod_jar_for("1.21.7"), Some("duskclient-1.21.8.jar"));
        assert_eq!(client_mod_jar_for("1.21.8"), Some("duskclient-1.21.8.jar"));
        assert_eq!(client_mod_jar_for("1.21.9"), Some("duskclient-1.21.10.jar"));
        assert_eq!(client_mod_jar_for("1.21.10"), Some("duskclient-1.21.10.jar"));
        assert_eq!(client_mod_jar_for("1.21.11"), Some("duskclient-1.21.11.jar"));
        assert_eq!(client_mod_jar_for("1.21.12"), None);
        assert_eq!(client_mod_jar_for("26.2"), Some("duskclient-26.2.jar"));
        assert_eq!(client_mod_jar_for("26.2.1"), Some("duskclient-26.2.jar"));
        assert_eq!(client_mod_jar_for("26.1"), Some("duskclient-26.1.jar"));
        assert_eq!(client_mod_jar_for("26.1.2"), Some("duskclient-26.1.jar"));
        assert_eq!(client_mod_jar_for("26.3"), None);
        assert_eq!(client_mod_jar_for("1.20.1"), None);
        assert!(!client_mod_supports("1.8.9"));
    }

    #[test]
    fn loadout_validation() {
        let mut ok = Loadout::new();
        ok.insert("cape".into(), Value::from(7));
        ok.insert("settings".into(), Value::Object(Map::new()));
        ok.insert("accessories".into(), Value::Array(vec![Value::from(16), Value::from(2)]));
        assert!(validate_loadout(&ok).is_ok());

        let mut bad = Loadout::new();
        bad.insert("accessories".into(), Value::Array(vec![Value::from("x")]));
        assert!(validate_loadout(&bad).is_err());

        let mut bad = Loadout::new();
        bad.insert("cape".into(), Value::from("seven"));
        assert!(validate_loadout(&bad).is_err());
        let mut bad = Loadout::new();
        bad.insert("cape".into(), Value::from(-1));
        assert!(validate_loadout(&bad).is_err());
    }

    #[test]
    fn inventory_always_contains_the_worn_ids() {
        let tmp = std::env::temp_dir().join(format!("dusk-inventory-{}", std::process::id()));
        std::fs::create_dir_all(&tmp).unwrap();
        std::fs::write(tmp.join("cosmetics.json"), r#"{"cape":5,"accessories":[16,12],"settings":{}}"#).unwrap();
        std::fs::write(tmp.join("inventory.json"), r#"{"owned":[12,3]}"#).unwrap();
        assert_eq!(load_inventory(&tmp).owned, vec![3, 5, 12, 16]);
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn instance_config_merge_keeps_other_keys() {
        let tmp = std::env::temp_dir().join(format!("dusk-cosmetics-{}", std::process::id()));
        let data = tmp.join("data");
        let inst = tmp.join("inst");
        std::fs::create_dir_all(inst.join("config")).unwrap();
        std::fs::create_dir_all(&data).unwrap();
        std::fs::write(
            inst.join("config/duskclient.json"),
            r#"{"backgroundPath":"/x.png","cosmetics":{"showOthers":false}}"#,
        )
        .unwrap();
        std::fs::write(data.join("cosmetics.json"), r#"{"cape":3}"#).unwrap();

        write_loadout_to_instance(&data, &inst).unwrap();
        let doc: Value =
            serde_json::from_slice(&std::fs::read(inst.join("config/duskclient.json")).unwrap()).unwrap();
        assert_eq!(doc["backgroundPath"], "/x.png");
        assert_eq!(doc["cosmetics"]["showOthers"], false);
        assert_eq!(doc["cosmetics"]["loadout"]["cape"], 3);
        let _ = std::fs::remove_dir_all(&tmp);
    }
}
