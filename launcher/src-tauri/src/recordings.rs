//! Clips and replays across every instance (`<instance>/clips/` and
//! `<instance>/replays/`, written by the Dusk client), newest first, with
//! the details the recording carries in its `.mcpr` (duration, server,
//! game version, thumbnail) and delete-to-trash.
//!
//! Paths coming back from the UI are only trusted after [`resolve`] has
//! confirmed they name an `.mcpr` directly inside one of those folders.

use crate::appstate::AppState;
use serde::Serialize;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;
use tauri::State;

const KINDS: [&str; 2] = ["clips", "replays"];
/// A thumbnail bigger than this isn't one the client wrote.
const THUMB_MAX: u64 = 4 * 1024 * 1024;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Recording {
    pub path: String,
    pub name: String,
    /// "clip" or "replay"
    pub kind: String,
    pub profile_id: String,
    pub profile_name: String,
    /// unix millis (file modified time)
    pub recorded_at: u64,
    pub size: u64,
    pub duration_ms: u64,
    /// the server address, or "" for singleplayer
    pub server: String,
    pub mc_version: String,
}

fn is_mcpr(path: &Path) -> bool {
    path.extension().and_then(|e| e.to_str()).is_some_and(|e| e.eq_ignore_ascii_case("mcpr"))
}

/// `path` checked to be an `.mcpr` directly inside an instance's clips or
/// replays folder; answers the canonical path.
fn resolve(state: &AppState, path: &str) -> Result<PathBuf, String> {
    let not_found = || "That recording isn't there any more.".to_string();
    let path = std::fs::canonicalize(path).map_err(|_| not_found())?;
    let profiles_root = std::fs::canonicalize(state.data_dir.join("profiles")).map_err(|_| not_found())?;
    let rel = path.strip_prefix(&profiles_root).map_err(|_| not_found())?;
    let parts: Vec<_> = rel.components().collect();
    let in_media = parts.len() == 3 && KINDS.iter().any(|k| parts[1].as_os_str() == *k);
    if !in_media || !is_mcpr(&path) || !path.is_file() {
        return Err(not_found());
    }
    Ok(path)
}

/// `metaData.json` from inside the recording.
fn read_meta(path: &Path) -> Option<serde_json::Value> {
    let file = std::fs::File::open(path).ok()?;
    let mut zip = zip::ZipArchive::new(file).ok()?;
    let mut entry = zip.by_name("metaData.json").ok()?;
    let mut text = String::new();
    entry.read_to_string(&mut text).ok()?;
    serde_json::from_str(&text).ok()
}

#[tauri::command]
pub async fn list_recordings(state: State<'_, AppState>) -> Result<Vec<Recording>, String> {
    let profiles: Vec<_> = state.profiles.lock().unwrap().profiles.clone();
    let data_dir = state.data_dir.clone();
    // opening every zip is disk work; keep it off the main thread
    tauri::async_runtime::spawn_blocking(move || {
        let mut out = Vec::new();
        for p in profiles {
            let root = p.dirs(&data_dir).root;
            for kind in KINDS {
                let Ok(entries) = std::fs::read_dir(root.join(kind)) else { continue };
                for e in entries.flatten() {
                    let path = e.path();
                    if !is_mcpr(&path) {
                        continue;
                    }
                    let Ok(meta) = e.metadata() else { continue };
                    if !meta.is_file() {
                        continue;
                    }
                    let recorded_at = meta
                        .modified()
                        .ok()
                        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                        .map(|d| d.as_millis() as u64)
                        .unwrap_or(0);
                    let info = read_meta(&path).unwrap_or_default();
                    let text = |k: &str| info.get(k).and_then(|v| v.as_str()).unwrap_or("").to_string();
                    let singleplayer = info.get("singleplayer").and_then(|v| v.as_bool()).unwrap_or(false);
                    out.push(Recording {
                        path: path.to_string_lossy().to_string(),
                        name: e.file_name().to_string_lossy().to_string(),
                        kind: kind.trim_end_matches('s').to_string(),
                        profile_id: p.id.clone(),
                        profile_name: p.name.clone(),
                        recorded_at,
                        size: meta.len(),
                        duration_ms: info.get("duration").and_then(|v| v.as_u64()).unwrap_or(0),
                        server: if singleplayer { String::new() } else { text("serverName") },
                        mc_version: text("mcversion"),
                    });
                }
            }
        }
        out.sort_by_key(|r| std::cmp::Reverse(r.recorded_at));
        out
    })
    .await
    .map_err(|e| e.to_string())
}

/// The recording's `thumb.png` as a data URL, or None when it has none.
#[tauri::command]
pub async fn recording_thumb(state: State<'_, AppState>, path: String) -> Result<Option<String>, String> {
    let path = resolve(&state, &path)?;
    tauri::async_runtime::spawn_blocking(move || {
        use base64::Engine;
        let file = std::fs::File::open(&path).ok()?;
        let mut zip = zip::ZipArchive::new(file).ok()?;
        let entry = zip.by_name("thumb.png").ok()?;
        if entry.size() > THUMB_MAX {
            return None;
        }
        let mut bytes = Vec::with_capacity(entry.size() as usize);
        entry.take(THUMB_MAX).read_to_end(&mut bytes).ok()?;
        Some(format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes)))
    })
    .await
    .map_err(|e| e.to_string())
}

/// Move a recording to the OS trash (recoverable), never a hard delete.
#[tauri::command]
pub fn delete_recording(state: State<AppState>, path: String) -> Result<(), String> {
    let path = resolve(&state, &path)?;
    trash::delete(&path).map_err(|e| format!("Couldn't move it to the trash: {e}"))
}

/// Show the file selected in Finder / Explorer.
#[tauri::command]
pub fn reveal_recording(state: State<AppState>, path: String) -> Result<(), String> {
    let path = resolve(&state, &path)?;
    tauri_plugin_opener::reveal_item_in_dir(path).map_err(|e| e.to_string())
}
