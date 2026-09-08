//! Local skin library: PNGs stored under `<data>/skins/`, indexed in
//! skins.json. Import validates 64x64 / 64x32 (legacy) skins. Applying a skin
//! to a Mojang account requires Microsoft auth (not yet wired).

use crate::appstate::AppState;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tauri::State;
use tauri_plugin_dialog::DialogExt;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkinDto {
    pub name: String,
    pub added_at: u64,
    pub selected: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct SkinIndex {
    #[serde(default)]
    skins: Vec<SkinDto>,
}

fn skins_dir(state: &AppState) -> PathBuf {
    state.data_dir.join("skins")
}

fn load_index(state: &AppState) -> SkinIndex {
    std::fs::read(skins_dir(state).join("skins.json"))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn save_index(state: &AppState, index: &SkinIndex) {
    let dir = skins_dir(state);
    let _ = std::fs::create_dir_all(&dir);
    let _ = std::fs::write(dir.join("skins.json"), serde_json::to_vec_pretty(index).unwrap());
}

/// PNG IHDR dimensions without an image crate.
fn png_dimensions(bytes: &[u8]) -> Option<(u32, u32)> {
    // 8-byte signature + 4 len + 4 "IHDR" + 4 w + 4 h
    if bytes.len() < 24 || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let w = u32::from_be_bytes(bytes[16..20].try_into().ok()?);
    let h = u32::from_be_bytes(bytes[20..24].try_into().ok()?);
    Some((w, h))
}

#[tauri::command]
pub fn list_skins(state: State<AppState>) -> Vec<SkinDto> {
    load_index(&state).skins
}

#[tauri::command]
pub async fn import_skin(app: tauri::AppHandle, state: State<'_, AppState>) -> Result<Option<SkinDto>, String> {
    let picked = app
        .dialog()
        .file()
        .add_filter("Minecraft skin (PNG)", &["png"])
        .blocking_pick_file();
    let Some(file) = picked else { return Ok(None) };
    let path = file.into_path().map_err(|e| e.to_string())?;

    let bytes = std::fs::read(&path).map_err(|e| e.to_string())?;
    match png_dimensions(&bytes) {
        Some((64, 64)) | Some((64, 32)) => {}
        other => {
            return Err(format!(
                "Skin must be a 64x64 (or legacy 64x32) PNG, got {:?}",
                other.map(|(w, h)| format!("{w}x{h}")).unwrap_or_else(|| "not a PNG".into())
            ))
        }
    }

    let name = path
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "skin".into());
    let dir = skins_dir(&state);
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    std::fs::write(dir.join(format!("{name}.png")), &bytes).map_err(|e| e.to_string())?;

    let mut index = load_index(&state);
    let dto = SkinDto {
        name: name.clone(),
        added_at: now_millis(),
        selected: false,
    };
    index.skins.retain(|s| s.name != name);
    index.skins.push(dto.clone());
    save_index(&state, &index);
    Ok(Some(dto))
}

/// Rename a skin (file + index entry). Names are display names too, so this
/// is how hash-named imports become readable.
#[tauri::command]
pub fn rename_skin(state: State<AppState>, old_name: String, new_name: String) -> Result<(), String> {
    let new_name = new_name.trim().to_string();
    if new_name.is_empty() || new_name.len() > 48 {
        return Err("name must be 1-48 characters".into());
    }
    if new_name.contains(['/', '\\', '.']) {
        return Err("name cannot contain / \\ or .".into());
    }
    let mut index = load_index(&state);
    if !index.skins.iter().any(|s| s.name == old_name) {
        return Err("skin not found".into());
    }
    if index.skins.iter().any(|s| s.name == new_name) {
        return Err("a skin with that name already exists".into());
    }
    let dir = skins_dir(&state);
    std::fs::rename(dir.join(format!("{old_name}.png")), dir.join(format!("{new_name}.png")))
        .map_err(|e| e.to_string())?;
    for s in index.skins.iter_mut() {
        if s.name == old_name {
            s.name = new_name.clone();
        }
    }
    save_index(&state, &index);
    Ok(())
}

#[tauri::command]
pub fn delete_skin(state: State<AppState>, name: String) -> Result<(), String> {    let mut index = load_index(&state);
    let was_selected = index.skins.iter().any(|s| s.name == name && s.selected);
    index.skins.retain(|s| s.name != name);
    if was_selected {
        if let Some(first) = index.skins.first_mut() {
            first.selected = true;
        }
    }
    save_index(&state, &index);
    let _ = std::fs::remove_file(skins_dir(&state).join(format!("{name}.png")));
    Ok(())
}

#[tauri::command]
pub fn set_selected_skin(state: State<AppState>, name: String) -> Result<(), String> {
    let mut index = load_index(&state);
    if !index.skins.iter().any(|s| s.name == name) {
        return Err("skin not found".into());
    }
    for s in index.skins.iter_mut() {
        s.selected = s.name == name;
    }
    save_index(&state, &index);
    Ok(())
}

/// Return the selected skin PNG as a data URL (skins are a few KB).
#[tauri::command]
pub fn read_skin(state: State<AppState>, name: String) -> Result<String, String> {
    let bytes = std::fs::read(skins_dir(&state).join(format!("{name}.png"))).map_err(|e| e.to_string())?;
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD.encode(bytes);
    Ok(format!("data:image/png;base64,{b64}"))
}

/// Upload the selected local skin to the signed-in Mojang account.
/// `variant` is `"classic"` or `"slim"`.
#[tauri::command]
pub async fn upload_selected_skin(
    state: State<'_, AppState>,
    variant: String,
) -> Result<(), String> {
    let variant = variant.to_lowercase();
    if variant != "classic" && variant != "slim" {
        return Err("variant must be \"classic\" or \"slim\"".into());
    }
    let index = load_index(&state);
    let selected = index
        .skins
        .iter()
        .find(|s| s.selected)
        .ok_or_else(|| "no skin selected — pick one in the wardrobe first".to_string())?;
    let png = std::fs::read(skins_dir(&state).join(format!("{}.png", selected.name)))
        .map_err(|e| e.to_string())?;
    let session = crate::auth_store::load_session(&state.data_dir)
        .ok_or_else(|| "sign in with Microsoft before uploading a skin".to_string())?;
    fasterlauncher_core::auth::upload_skin(&state.client, &session, png, &variant)
        .await
        .map_err(|e| e.to_string())
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
