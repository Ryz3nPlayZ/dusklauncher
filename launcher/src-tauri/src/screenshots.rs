//! Screenshots across every instance (`<instance>/screenshots/`), newest
//! first, with favorites (`<data>/screenshot-favorites.json`, keyed
//! `<profile id>/<file name>`) and delete-to-trash.
//!
//! Paths coming back from the UI are only trusted after [`resolve`] has
//! confirmed they name an image directly inside some instance's
//! screenshots folder.

use crate::appstate::AppState;
use serde::Serialize;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;
use tauri::State;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Screenshot {
    pub path: String,
    pub name: String,
    pub profile_id: String,
    pub profile_name: String,
    /// unix millis (file modified time)
    pub taken_at: u64,
    pub size: u64,
    pub favorite: bool,
}

fn is_image(path: &Path) -> bool {
    matches!(
        path.extension().and_then(|e| e.to_str()).map(str::to_ascii_lowercase).as_deref(),
        Some("png" | "jpg" | "jpeg")
    )
}

fn favorites_path(data_dir: &Path) -> PathBuf {
    data_dir.join("screenshot-favorites.json")
}

fn load_favorites(data_dir: &Path) -> BTreeSet<String> {
    std::fs::read(favorites_path(data_dir))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn save_favorites(data_dir: &Path, favs: &BTreeSet<String>) -> Result<(), String> {
    let bytes = serde_json::to_vec_pretty(favs).map_err(|e| e.to_string())?;
    std::fs::write(favorites_path(data_dir), bytes).map_err(|e| e.to_string())
}

/// `path` checked to be an image file directly inside an instance's
/// screenshots folder; answers the canonical path.
pub fn resolve(state: &AppState, path: &str) -> Result<PathBuf, String> {
    let (path, _) = locate(state, path)?;
    Ok(path)
}

/// [`resolve`], plus the favorites key for the file.
fn locate(state: &AppState, path: &str) -> Result<(PathBuf, String), String> {
    let not_found = || "That screenshot isn't there any more.".to_string();
    let path = std::fs::canonicalize(path).map_err(|_| not_found())?;
    let profiles_root = std::fs::canonicalize(state.data_dir.join("profiles")).map_err(|_| not_found())?;
    let rel = path.strip_prefix(&profiles_root).map_err(|_| not_found())?;
    let parts: Vec<_> = rel.components().collect();
    if parts.len() != 3 || parts[1].as_os_str() != "screenshots" || !is_image(&path) || !path.is_file() {
        return Err(not_found());
    }
    let key = format!(
        "{}/{}",
        parts[0].as_os_str().to_string_lossy(),
        parts[2].as_os_str().to_string_lossy()
    );
    Ok((path, key))
}

#[tauri::command(async)]
pub fn list_screenshots(state: State<AppState>) -> Vec<Screenshot> {
    let profiles: Vec<_> = state.profiles.lock().unwrap().profiles.clone();
    let favs = load_favorites(&state.data_dir);
    let mut out = Vec::new();
    for p in profiles {
        let dir = p.dirs(&state.data_dir).root.join("screenshots");
        let Ok(entries) = std::fs::read_dir(&dir) else { continue };
        for e in entries.flatten() {
            let path = e.path();
            if !is_image(&path) {
                continue;
            }
            let Ok(meta) = e.metadata() else { continue };
            if !meta.is_file() {
                continue;
            }
            let name = e.file_name().to_string_lossy().to_string();
            let taken_at = meta
                .modified()
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
            out.push(Screenshot {
                favorite: favs.contains(&format!("{}/{name}", p.id)),
                path: path.to_string_lossy().to_string(),
                name,
                profile_id: p.id.clone(),
                profile_name: p.name.clone(),
                taken_at,
                size: meta.len(),
            });
        }
    }
    out.sort_by_key(|s| std::cmp::Reverse(s.taken_at));
    out
}

#[tauri::command]
pub fn set_screenshot_favorite(state: State<AppState>, path: String, favorite: bool) -> Result<(), String> {
    let (_, key) = locate(&state, &path)?;
    let mut favs = load_favorites(&state.data_dir);
    if favorite {
        favs.insert(key);
    } else {
        favs.remove(&key);
    }
    save_favorites(&state.data_dir, &favs)
}

/// Move a screenshot to the OS trash (recoverable), never a hard delete.
#[tauri::command(async)]
pub fn delete_screenshot(state: State<AppState>, path: String) -> Result<(), String> {
    let (path, key) = locate(&state, &path)?;
    trash::delete(&path).map_err(|e| format!("Couldn't move it to the trash: {e}"))?;
    let mut favs = load_favorites(&state.data_dir);
    if favs.remove(&key) {
        save_favorites(&state.data_dir, &favs)?;
    }
    Ok(())
}

/// Show the file selected in Finder / Explorer.
#[tauri::command]
pub fn reveal_screenshot(state: State<AppState>, path: String) -> Result<(), String> {
    let path = resolve(&state, &path)?;
    tauri_plugin_opener::reveal_item_in_dir(path).map_err(|e| e.to_string())
}
