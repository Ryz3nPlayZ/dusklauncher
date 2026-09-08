//! Local skin library: PNGs stored under `<data>/skins/`, indexed in
//! skins.json. Import validates 64x64 / 64x32 (legacy) skins. Applying a skin
//! to the signed-in Mojang account goes through `api.minecraftservices.com`
//! (`upload_skin`); the account's active skin is cached under `<data>/cache/`
//! for the Home avatar.

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

/// Upload a wardrobe skin to the signed-in Mojang account.
/// `variant` is `"classic"` or `"slim"`; `name` picks the wardrobe entry
/// (need not be the launcher-selected one).
#[tauri::command]
pub async fn upload_skin(
    state: State<'_, AppState>,
    name: String,
    variant: String,
) -> Result<(), String> {
    let variant = variant.to_lowercase();
    if variant != "classic" && variant != "slim" {
        return Err("variant must be \"classic\" or \"slim\"".into());
    }
    let png = std::fs::read(skins_dir(&state).join(format!("{name}.png")))
        .map_err(|e| format!("skin \"{name}\" not readable: {e}"))?;
    let mut session = crate::auth_store::load_session(&state.data_dir)
        .ok_or_else(|| "sign in with Microsoft before uploading a skin".to_string())?;
    fasterlauncher_core::auth::upload_skin(&state.client, &session, png, &variant)
        .await
        .map_err(|e| e.to_string())?;
    // Reflect the change immediately: refetch the profile and re-cache the
    // account skin so the Home avatar updates without a re-login.
    if let Ok(profile) =
        fasterlauncher_core::auth::fetch_profile(&state.client, &session.access_token).await
    {
        if let Some(active) = profile.active_skin() {
            session.skin_url = active.url.clone();
            session.skin_variant = active.variant.clone();
            crate::auth_store::save_session(&state.data_dir, &session);
            refresh_account_skin_cache(&state, &session.skin_url).await;
        }
    }
    Ok(())
}

/// Reset the account's skin to the default (unapply any custom skin).
#[tauri::command]
pub async fn reset_skin(state: State<'_, AppState>) -> Result<(), String> {
    let mut session = crate::auth_store::load_session(&state.data_dir)
        .ok_or_else(|| "sign in with Microsoft first".to_string())?;
    fasterlauncher_core::auth::reset_skin(&state.client, &session)
        .await
        .map_err(|e| e.to_string())?;
    session.skin_url = String::new();
    session.skin_variant = String::new();
    crate::auth_store::save_session(&state.data_dir, &session);
    Ok(())
}

/// The account's active skin as a PNG data URL (for the Home avatar), or
/// null when the account has no custom skin. Downloaded in Rust and cached
/// under `<data>/cache/`, so the webview never hits textures.minecraft.net
/// (and never needs CORS headers to exist).
#[tauri::command]
pub async fn get_account_skin(state: State<'_, AppState>) -> Result<Option<String>, String> {
    let Some(session) = crate::auth_store::load_session(&state.data_dir) else {
        return Ok(None);
    };
    let mut skin_url = session.skin_url.clone();
    if skin_url.is_empty() {
        // Sessions saved before skin tracking exists have no URL — backfill
        // via a profile fetch (the token is fresh enough in practice; if not,
        // the avatar simply stays empty until the next sign-in).
        if let Ok(profile) =
            fasterlauncher_core::auth::fetch_profile(&state.client, &session.access_token).await
        {
            let mut session = session;
            if let Some(active) = profile.active_skin() {
                skin_url = active.url.clone();
                session.skin_url = skin_url.clone();
                session.skin_variant = active.variant.clone();
                crate::auth_store::save_session(&state.data_dir, &session);
            } else {
                return Ok(None);
            }
        } else {
            return Ok(None);
        }
    }
    Ok(Some(account_skin_data_url(&state, &skin_url).await?))
}

async fn refresh_account_skin_cache(state: &AppState, skin_url: &str) {
    let _ = account_skin_data_url(state, skin_url).await;
}

/// Data URL for the account skin, downloading (and caching) when the cached
/// copy doesn't match the URL. The URL is stored next to the PNG so a skin
/// change is detected by content, not by mtime.
async fn account_skin_data_url(state: &AppState, skin_url: &str) -> Result<String, String> {
    use base64::Engine;
    let cache_dir = state.data_dir.join("cache");
    let png_path = cache_dir.join("account-skin.png");
    let url_path = cache_dir.join("account-skin.url");
    let cached_url = std::fs::read_to_string(&url_path).unwrap_or_default();
    if cached_url != skin_url || !png_path.exists() {
        let png = fasterlauncher_core::auth::fetch_skin_png(&state.client, skin_url)
            .await
            .map_err(|e| e.to_string())?;
        std::fs::create_dir_all(&cache_dir).map_err(|e| e.to_string())?;
        std::fs::write(&png_path, &png).map_err(|e| e.to_string())?;
        std::fs::write(&url_path, skin_url).map_err(|e| e.to_string())?;
    }
    let bytes = std::fs::read(&png_path).map_err(|e| e.to_string())?;
    let b64 = base64::engine::general_purpose::STANDARD.encode(bytes);
    Ok(format!("data:image/png;base64,{b64}"))
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
