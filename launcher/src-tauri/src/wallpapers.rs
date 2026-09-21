//! Custom launcher wallpapers: images and live-wallpaper videos stored under
//! `<data>/wallpapers/`. The frontend streams them through the asset protocol
//! (`convertFileSrc`), which is why tauri.conf.json scopes it to this folder —
//! a 100 MB MP4 cannot ride through IPC as a data URL the way skins do.

use crate::appstate::AppState;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tauri::State;
use tauri_plugin_dialog::DialogExt;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WallpaperDto {
    /// file name inside the wallpapers dir (unique; also the settings value)
    pub name: String,
    /// absolute path, for `convertFileSrc`
    pub path: String,
    /// "video" | "image"
    pub kind: String,
}

const VIDEO_EXTS: &[&str] = &["mp4", "webm", "mov", "m4v"];
const IMAGE_EXTS: &[&str] = &["png", "jpg", "jpeg", "webp", "gif", "avif"];

fn wallpapers_dir(state: &AppState) -> PathBuf {
    state.data_dir.join("wallpapers")
}

fn kind_of(name: &str) -> Option<&'static str> {
    let ext = name.rsplit('.').next().unwrap_or("").to_lowercase();
    if VIDEO_EXTS.contains(&ext.as_str()) {
        Some("video")
    } else if IMAGE_EXTS.contains(&ext.as_str()) {
        Some("image")
    } else {
        None
    }
}

/// Where the import dialog opens first. ~/Downloads/minecraftwalls is where
/// the bundled demo walls live on dev machines; anywhere else falls back to
/// the OS default.
fn default_pick_dir() -> Option<PathBuf> {
    let dir = dirs::home_dir()?.join("Downloads").join("minecraftwalls");
    dir.is_dir().then_some(dir)
}

#[tauri::command]
pub fn list_wallpapers(state: State<AppState>) -> Vec<WallpaperDto> {
    let dir = wallpapers_dir(&state);
    let mut out: Vec<WallpaperDto> = std::fs::read_dir(&dir)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().to_string();
            let kind = kind_of(&name)?.to_string();
            Some(WallpaperDto {
                path: e.path().to_string_lossy().to_string(),
                name,
                kind,
            })
        })
        .collect();
    out.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    out
}

/// Native picker → copies the file into `<data>/wallpapers/`. Returns the new
/// entry, or `None` when the dialog was cancelled.
#[tauri::command]
pub async fn import_wallpaper(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
) -> Result<Option<WallpaperDto>, String> {
    let mut picker = app
        .dialog()
        .file()
        .add_filter("Live wallpaper (MP4 · WebM · MOV)", &["mp4", "webm", "mov", "m4v"])
        .add_filter("Image", &["png", "jpg", "jpeg", "webp", "gif", "avif"]);
    if let Some(dir) = default_pick_dir() {
        picker = picker.set_directory(dir);
    }
    let Some(file) = picker.blocking_pick_file() else { return Ok(None) };
    let path = file.into_path().map_err(|e| e.to_string())?;

    let filename = path
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .ok_or("picked file has no name")?;
    if kind_of(&filename).is_none() {
        return Err(format!(
            "\"{filename}\" is not a supported wallpaper (mp4, webm, mov, png, jpg, webp, gif, avif)"
        ));
    }

    let dir = wallpapers_dir(&state);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    // keep the stem, dodge collisions with a -2, -3… suffix
    let stem = path.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let ext = path.extension().map(|s| s.to_string_lossy().to_lowercase()).unwrap_or_default();
    let mut dest = dir.join(&filename);
    let mut n = 1;
    while dest.exists() {
        n += 1;
        dest = dir.join(format!("{stem}-{n}.{ext}"));
    }
    std::fs::copy(&path, &dest).map_err(|e| e.to_string())?;

    Ok(Some(WallpaperDto {
        name: dest.file_name().unwrap().to_string_lossy().to_string(),
        path: dest.to_string_lossy().to_string(),
        kind: kind_of(&filename).unwrap_or("image").to_string(),
    }))
}

#[tauri::command]
pub fn remove_wallpaper(state: State<AppState>, name: String) -> Result<(), String> {
    if name.contains(['/', '\\']) || name.split('.').count() < 2 || name.starts_with('.') {
        return Err("not a wallpaper file name".into());
    }
    let path = wallpapers_dir(&state).join(&name);
    // belt-and-suspenders: never delete outside the wallpapers dir
    let dir = wallpapers_dir(&state);
    if !path.starts_with(&dir) || kind_of(&name).is_none() {
        return Err("not a wallpaper file name".into());
    }
    std::fs::remove_file(&path).map_err(|e| e.to_string())
}
