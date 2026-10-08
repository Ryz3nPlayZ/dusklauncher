//! An instance's own picture, like the icons Prism, the Modrinth App and
//! CurseForge show: one the player picks, or a Modrinth pack's icon when the
//! pack is installed. It lives in the instance folder as
//! `dusk-icon-<millis>.<ext>` — a new name each change, so the window never
//! shows a cached old one — and a duplicate copies it along with the rest.

use crate::appstate::AppState;
use crate::commands::{dto, ProfileDto};
use fasterlauncher_core::profile::Profile;
use std::path::{Path, PathBuf};
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;

const PREFIX: &str = "dusk-icon-";
const MAX_BYTES: u64 = 8 << 20;

/// The picture's file, when the instance has one and it's still there.
pub fn icon_path(p: &Profile, data_dir: &Path) -> Option<PathBuf> {
    let name = p.icon.as_deref().filter(|n| ours(n))?;
    Some(p.dirs(data_dir).root.join(name)).filter(|f| f.is_file())
}

/// Only a name this module wrote is ever read or removed.
fn ours(name: &str) -> bool {
    name.starts_with(PREFIX) && !name.contains(['/', '\\']) && !name.contains("..")
}

/// The image type, read off the bytes rather than trusted from a name.
fn image_ext(bytes: &[u8]) -> Option<&'static str> {
    match bytes {
        [0x89, b'P', b'N', b'G', ..] => Some("png"),
        [0xFF, 0xD8, 0xFF, ..] => Some("jpg"),
        [b'G', b'I', b'F', b'8', ..] => Some("gif"),
        [b'R', b'I', b'F', b'F', _, _, _, _, b'W', b'E', b'B', b'P', ..] => Some("webp"),
        _ => None,
    }
}

/// Write `bytes` as the instance's picture and drop the one it replaces.
/// Returns the new file name.
fn store(root: &Path, old: Option<&str>, bytes: &[u8]) -> Result<String, String> {
    let ext = image_ext(bytes).ok_or("That isn't a PNG, JPEG, GIF or WebP image.")?;
    if bytes.len() as u64 > MAX_BYTES {
        return Err("That picture is over 8 MB.".into());
    }
    std::fs::create_dir_all(root).map_err(|e| e.to_string())?;
    let millis = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or_default();
    let name = format!("{PREFIX}{millis}.{ext}");
    std::fs::write(root.join(&name), bytes).map_err(|e| e.to_string())?;
    if let Some(old) = old.filter(|o| ours(o) && *o != name) {
        let _ = std::fs::remove_file(root.join(old));
    }
    Ok(name)
}

/// Record `bytes` as `profile_id`'s picture.
pub fn apply(state: &AppState, profile_id: &str, bytes: &[u8]) -> Result<ProfileDto, String> {
    let (root, old) = {
        let store = state.profiles.lock().unwrap();
        let p = store.profiles.iter().find(|p| p.id == profile_id).ok_or("profile not found")?;
        (p.dirs(&state.data_dir).root, p.icon.clone())
    };
    let name = store(&root, old.as_deref(), bytes)?;
    state
        .patch_profile(profile_id, |p| p.icon = Some(name))
        .map(|p| dto(&p, &state.data_dir))
        .ok_or_else(|| "profile not found".into())
}

/// PICTURE → CHOOSE… in an instance's settings. `None` when the picker is
/// cancelled.
#[tauri::command]
pub async fn set_profile_icon(app: AppHandle, state: State<'_, AppState>, profile_id: String) -> Result<Option<ProfileDto>, String> {
    let picked = app
        .dialog()
        .file()
        .add_filter("Image", &["png", "jpg", "jpeg", "gif", "webp"])
        .blocking_pick_file();
    let Some(file) = picked else { return Ok(None) };
    let path = file.into_path().map_err(|e| e.to_string())?;
    if tokio::fs::metadata(&path).await.map(|m| m.len()).unwrap_or(0) > MAX_BYTES {
        return Err("That picture is over 8 MB.".into());
    }
    let bytes = tokio::fs::read(&path).await.map_err(|e| format!("couldn't read {}: {e}", path.display()))?;
    apply(&state, &profile_id, &bytes).map(Some)
}

/// PICTURE → RESET: back to the stock banner.
#[tauri::command(async)]
pub fn clear_profile_icon(state: State<'_, AppState>, profile_id: String) -> Result<ProfileDto, String> {
    let p = state
        .patch_profile(&profile_id, |p| {
            if let Some(old) = p.icon.take().filter(|o| ours(o)) {
                let _ = std::fs::remove_file(p.dirs(&state.data_dir).root.join(old));
            }
        })
        .ok_or("profile not found")?;
    Ok(dto(&p, &state.data_dir))
}

/// A Modrinth pack's icon on the instance it was installed as. Best effort:
/// an instance without one keeps the stock banner.
pub async fn fetch_pack_icon(state: &AppState, profile_id: &str, url: &str) {
    let got = async {
        let resp = state.client.get(url).send().await.ok()?.error_for_status().ok()?;
        if resp.content_length().is_some_and(|n| n > MAX_BYTES) {
            return None;
        }
        resp.bytes().await.ok()
    }
    .await;
    if let Some(bytes) = got {
        if let Err(e) = apply(state, profile_id, &bytes) {
            tracing::debug!("pack icon not kept: {e}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_new_picture_replaces_the_old_one() {
        let root = std::env::temp_dir().join(format!("dusk-icons-{}", std::process::id()));
        let png = [0x89, b'P', b'N', b'G', 0, 0, 0, 0];
        let first = store(&root, None, &png).unwrap();
        assert!(first.starts_with(PREFIX) && first.ends_with(".png"));
        std::thread::sleep(std::time::Duration::from_millis(2));
        let webp = *b"RIFF\0\0\0\0WEBPVP8 ";
        let second = store(&root, Some(&first), &webp).unwrap();
        assert!(second.ends_with(".webp"));
        assert!(!root.join(&first).exists() && root.join(&second).is_file());
        assert!(store(&root, None, b"<svg/>").is_err());
        // a name it didn't write is never removed
        std::fs::write(root.join("options.txt"), "x").unwrap();
        store(&root, Some("options.txt"), &png).unwrap();
        assert!(root.join("options.txt").is_file());
        assert!(!ours("../dusk-icon-1.png") && !ours("options.txt"));
        let _ = std::fs::remove_dir_all(&root);
    }
}
