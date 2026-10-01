//! Dusk client settings sync: the HUD layout, module options and menu prefs
//! follow the account to every instance and every machine.
//!
//! The launcher holds the master copy in `<data>/client-settings.json`.
//! Before a Fabric launch it overlays the master onto the instance's
//! `config/duskclient-hud.json` and the synced prefs of
//! `config/duskclient.json`, remembering what it wrote. When the game exits,
//! only the keys the player actually changed (diffed against that snapshot)
//! fold back into the master, so a module an older Minecraft version lacks
//! keeps its settings for the versions that have it. The master is then
//! pushed to the Dusk service (`/v1/me/settings`); a newer remote copy is
//! pulled before the next launch.

use crate::appstate::AppState;
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// `config/duskclient.json` keys that sync. The background is a path on
/// this machine and the cosmetics block is written by the launcher itself.
const PREF_KEYS: &[&str] = &["showAccountTile", "uiStyle", "titleScene", "favorites", "recordingMode", "clipSeconds"];

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Master {
    /// module id -> option -> value, as `duskclient-hud.json` holds it
    #[serde(default)]
    pub hud: Map<String, Value>,
    #[serde(default)]
    pub prefs: Map<String, Value>,
    /// the service's `updatedAt` for the copy this one was last synced with
    #[serde(default)]
    pub remote_at: i64,
    /// changed locally since the last successful push
    #[serde(default)]
    pub dirty: bool,
}

impl Master {
    fn is_empty(&self) -> bool {
        self.hud.is_empty() && self.prefs.is_empty()
    }
}

/// What the instance held right after the overlay.
#[derive(Debug, Clone, Default)]
pub struct Snapshot {
    hud: Map<String, Value>,
    prefs: Map<String, Value>,
}

fn master_path(data_dir: &Path) -> PathBuf {
    data_dir.join("client-settings.json")
}

pub fn load(data_dir: &Path) -> Master {
    std::fs::read(master_path(data_dir))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn save(data_dir: &Path, m: &Master) -> Result<(), String> {
    let bytes = serde_json::to_vec_pretty(m).map_err(|e| e.to_string())?;
    std::fs::write(master_path(data_dir), bytes).map_err(|e| e.to_string())
}

fn hud_file(instance_root: &Path) -> PathBuf {
    instance_root.join("config").join("duskclient-hud.json")
}

fn prefs_file(instance_root: &Path) -> PathBuf {
    instance_root.join("config").join("duskclient.json")
}

fn read_object(path: &Path) -> Map<String, Value> {
    std::fs::read(path)
        .ok()
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default()
}

fn write_object(path: &Path, doc: &Map<String, Value>) -> Result<(), String> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    std::fs::write(path, serde_json::to_vec_pretty(doc).unwrap()).map_err(|e| e.to_string())
}

/// Gson writes every number it read back as a double: `30` and `30.0` are
/// the same setting.
fn same(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::Number(x), Value::Number(y)) => x.as_f64() == y.as_f64(),
        (Value::Array(x), Value::Array(y)) => x.len() == y.len() && x.iter().zip(y).all(|(a, b)| same(a, b)),
        (Value::Object(x), Value::Object(y)) => {
            x.len() == y.len() && x.iter().all(|(k, v)| y.get(k).is_some_and(|w| same(v, w)))
        }
        _ => a == b,
    }
}

fn synced_prefs(doc: &Map<String, Value>) -> Map<String, Value> {
    PREF_KEYS
        .iter()
        .filter_map(|k| doc.get(*k).map(|v| (k.to_string(), v.clone())))
        .collect()
}

/// Overlay the master onto the instance, key by key: the instance keeps
/// whatever the master doesn't know about. With nothing synced yet the
/// instance is left alone and an empty snapshot is returned, so the first
/// exit adopts the instance's settings wholesale.
pub fn apply_to_instance(data_dir: &Path, instance_root: &Path) -> Result<Snapshot, String> {
    let master = load(data_dir);
    if master.is_empty() {
        return Ok(Snapshot::default());
    }

    let mut hud = read_object(&hud_file(instance_root));
    for (module, opts) in &master.hud {
        let Some(opts) = opts.as_object() else { continue };
        let mut merged = hud.get(module).and_then(Value::as_object).cloned().unwrap_or_default();
        for (k, v) in opts {
            merged.insert(k.clone(), v.clone());
        }
        hud.insert(module.clone(), Value::Object(merged));
    }
    write_object(&hud_file(instance_root), &hud)?;

    let mut doc = read_object(&prefs_file(instance_root));
    for (k, v) in &master.prefs {
        if PREF_KEYS.contains(&k.as_str()) {
            doc.insert(k.clone(), v.clone());
        }
    }
    write_object(&prefs_file(instance_root), &doc)?;

    Ok(Snapshot { hud, prefs: synced_prefs(&doc) })
}

/// Fold what the player changed in the instance since `snap` into the
/// master. Returns whether anything changed.
pub fn collect_from_instance(data_dir: &Path, instance_root: &Path, snap: &Snapshot) -> Result<bool, String> {
    let mut master = load(data_dir);
    let mut changed = false;

    for (module, opts) in read_object(&hud_file(instance_root)) {
        let Some(opts) = opts.as_object() else { continue };
        let before = snap.hud.get(&module).and_then(Value::as_object);
        for (k, v) in opts {
            if before.and_then(|b| b.get(k)).is_some_and(|old| same(old, v)) {
                continue;
            }
            let entry = master.hud.entry(module.clone()).or_insert_with(|| Value::Object(Map::new()));
            if !entry.is_object() {
                *entry = Value::Object(Map::new());
            }
            let slot = entry.as_object_mut().unwrap();
            if slot.get(k).is_some_and(|cur| same(cur, v)) {
                continue;
            }
            slot.insert(k.clone(), v.clone());
            changed = true;
        }
    }

    for (k, v) in synced_prefs(&read_object(&prefs_file(instance_root))) {
        if snap.prefs.get(&k).is_some_and(|old| same(old, &v)) {
            continue;
        }
        if master.prefs.get(&k).is_some_and(|cur| same(cur, &v)) {
            continue;
        }
        master.prefs.insert(k, v);
        changed = true;
    }

    if changed {
        master.dirty = true;
        save(data_dir, &master)?;
    }
    Ok(changed)
}

// ── the service ────────────────────────────────────────────────────────────

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Remote {
    settings: Option<Map<String, Value>>,
    #[serde(default)]
    updated_at: i64,
}

fn from_remote(settings: &Map<String, Value>) -> (Map<String, Value>, Map<String, Value>) {
    let part = |k: &str| settings.get(k).and_then(Value::as_object).cloned().unwrap_or_default();
    (part("hud"), part("prefs"))
}

/// Take a newer remote copy unless there are unpushed local changes (then
/// the local copy wins and the push that follows overwrites the remote).
fn adopt_remote(master: &mut Master, remote: &Remote) -> bool {
    let Some(settings) = &remote.settings else { return false };
    if remote.updated_at <= master.remote_at || master.dirty {
        return false;
    }
    let (hud, prefs) = from_remote(settings);
    master.hud = hud;
    master.prefs = prefs;
    master.remote_at = remote.updated_at;
    true
}

async fn push(state: &AppState, master: &mut Master) -> Result<(), String> {
    let body = json!({ "settings": { "hud": master.hud, "prefs": master.prefs } });
    let r: Remote = crate::dusk::call(state, reqwest::Method::PUT, "/v1/me/settings", Some(body)).await?;
    master.remote_at = r.updated_at;
    master.dirty = false;
    Ok(())
}

/// Pull a newer remote copy, then push unpushed local changes. Bounded so
/// an unreachable service never holds up a launch.
pub async fn sync(state: &AppState) {
    let work = async {
        let mut master = load(&state.data_dir);
        let remote: Remote = crate::dusk::call(state, reqwest::Method::GET, "/v1/me/settings", None).await?;
        let mut touched = adopt_remote(&mut master, &remote);
        if master.dirty && !master.is_empty() {
            push(state, &mut master).await?;
            touched = true;
        }
        if touched {
            save(&state.data_dir, &master)?;
        }
        Ok::<_, String>(())
    };
    match tokio::time::timeout(Duration::from_secs(4), work).await {
        Ok(Ok(())) => {}
        Ok(Err(e)) => tracing::debug!("client settings not synced: {e}"),
        Err(_) => tracing::debug!("client settings sync timed out"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("dusk-cs-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn put(path: PathBuf, v: Value) {
        write_object(&path, v.as_object().unwrap()).unwrap();
    }

    #[test]
    fn first_exit_adopts_the_instance() {
        let (data, inst) = (tmp("adopt-d"), tmp("adopt-i"));
        let snap = apply_to_instance(&data, &inst).unwrap();
        put(hud_file(&inst), json!({ "fps": { "enabled": true, "x": 4 } }));
        put(prefs_file(&inst), json!({ "uiStyle": "dawn", "backgroundPath": "/Users/me/bg.png", "cosmetics": {} }));
        assert!(collect_from_instance(&data, &inst, &snap).unwrap());
        let m = load(&data);
        assert_eq!(m.hud["fps"]["x"], 4);
        assert_eq!(m.prefs["uiStyle"], "dawn");
        assert!(!m.prefs.contains_key("backgroundPath"), "machine-local");
        assert!(!m.prefs.contains_key("cosmetics"), "launcher-written");
        assert!(m.dirty);
    }

    #[test]
    fn overlay_keeps_unknown_keys_and_exit_folds_only_changes() {
        let (data, inst) = (tmp("fold-d"), tmp("fold-i"));
        save(
            &data,
            &Master {
                hud: json!({ "fps": { "x": 10, "y": 10 }, "newModule": { "enabled": true } }).as_object().unwrap().clone(),
                prefs: json!({ "clipSeconds": 60 }).as_object().unwrap().clone(),
                ..Default::default()
            },
        )
        .unwrap();
        put(hud_file(&inst), json!({ "fps": { "x": 1, "scale": 2.0 }, "cps": { "enabled": false } }));
        put(prefs_file(&inst), json!({ "clipSeconds": 30, "backgroundPath": "x" }));

        let snap = apply_to_instance(&data, &inst).unwrap();
        let hud = read_object(&hud_file(&inst));
        assert_eq!(hud["fps"], json!({ "x": 10, "y": 10, "scale": 2.0 }));
        assert_eq!(hud["cps"], json!({ "enabled": false }));
        let prefs = read_object(&prefs_file(&inst));
        assert_eq!(prefs["clipSeconds"], 60);
        assert_eq!(prefs["backgroundPath"], "x");

        // the mod rewrites everything it knows (numbers as doubles) and drops
        // the module this version doesn't have; the player moved fps
        put(
            hud_file(&inst),
            json!({ "fps": { "x": 25.0, "y": 10.0, "scale": 2.0 }, "cps": { "enabled": false } }),
        );
        put(prefs_file(&inst), json!({ "clipSeconds": 60.0, "backgroundPath": "x" }));
        assert!(collect_from_instance(&data, &inst, &snap).unwrap());
        let m = load(&data);
        assert_eq!(m.hud["fps"]["x"], 25.0);
        assert_eq!(m.hud["fps"]["y"], 10, "unchanged key keeps the master's value");
        assert_eq!(m.hud["newModule"]["enabled"], true, "kept for versions that have it");
        assert_eq!(m.prefs["clipSeconds"], 60);
    }

    #[test]
    fn untouched_session_changes_nothing() {
        let (data, inst) = (tmp("same-d"), tmp("same-i"));
        let master = Master { hud: json!({ "fps": { "x": 3 } }).as_object().unwrap().clone(), ..Default::default() };
        save(&data, &master).unwrap();
        let snap = apply_to_instance(&data, &inst).unwrap();
        put(hud_file(&inst), json!({ "fps": { "x": 3.0 } }));
        assert!(!collect_from_instance(&data, &inst, &snap).unwrap());
        assert_eq!(load(&data), master);
    }

    #[test]
    fn remote_wins_only_when_newer_and_clean() {
        let remote = Remote {
            settings: Some(json!({ "hud": { "fps": { "x": 9 } }, "prefs": { "uiStyle": "dawn" } }).as_object().unwrap().clone()),
            updated_at: 20,
        };
        let mut m = Master { remote_at: 10, ..Default::default() };
        assert!(adopt_remote(&mut m, &remote));
        assert_eq!(m.hud["fps"]["x"], 9);
        assert_eq!(m.remote_at, 20);
        assert!(!adopt_remote(&mut m, &remote), "already have it");

        let mut dirty = Master { remote_at: 10, dirty: true, ..Default::default() };
        assert!(!adopt_remote(&mut dirty, &remote));
        assert!(dirty.hud.is_empty());

        let mut m = Master::default();
        assert!(!adopt_remote(&mut m, &Remote { settings: None, updated_at: 0 }));
    }
}
