//! Local skin library: PNGs stored under `<data>/skins/`, indexed in
//! skins.json. Import validates 64x64 / 64x32 (legacy) skins. Applying a skin
//! to the signed-in Mojang account goes through `api.minecraftservices.com`
//! (`upload_skin`); the account's active skin is cached under `<data>/cache/`
//! for the Home avatar. Offline play has no Mojang account, so its skin
//! lives on the Dusk service instead (`/v1/me/skin`), where Dusk clients
//! look it up by name.

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
    /// the arm model picked for this skin; none means read it off the PNG
    /// (the launcher's lib/skin.ts and the mod's SkinLibrary share the rule)
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct SkinIndex {
    #[serde(default)]
    skins: Vec<SkinDto>,
}

fn skins_dir(state: &AppState) -> PathBuf {
    state.data_dir.join("skins")
}

/// `<name>.png` in the wardrobe, for a name that can only mean a file there.
fn skin_file(state: &AppState, name: &str) -> Result<PathBuf, String> {
    if name.is_empty() || name.starts_with('.') || name.contains(['/', '\\']) {
        return Err("skin not found".into());
    }
    Ok(skins_dir(state).join(format!("{name}.png")))
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

#[tauri::command(async)]
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
        model: None,
    };
    index.skins.retain(|s| s.name != name);
    index.skins.push(dto.clone());
    save_index(&state, &index);
    Ok(Some(dto))
}

/// Rename a skin (file + index entry). Names are display names too, so this
/// is how hash-named imports become readable.
#[tauri::command(async)]
pub fn rename_skin(state: State<'_, AppState>, old_name: String, new_name: String) -> Result<(), String> {
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

#[tauri::command(async)]
pub fn delete_skin(state: State<AppState>, name: String) -> Result<(), String> {
    let mut index = load_index(&state);
    // Only a name the index holds: it becomes a file path below.
    if !index.skins.iter().any(|s| s.name == name) {
        return Err("skin not found".into());
    }
    let was_selected = index.skins.iter().any(|s| s.name == name && s.selected);
    index.skins.retain(|s| s.name != name);
    if was_selected {
        if let Some(first) = index.skins.first_mut() {
            first.selected = true;
        }
    }
    save_index(&state, &index);
    // To the trash, like a deleted world; gone outright where there's none.
    let path = skin_file(&state, &name)?;
    if trash::delete(&path).is_err() {
        let _ = std::fs::remove_file(&path);
    }
    Ok(())
}

#[tauri::command(async)]
pub fn set_selected_skin(state: State<'_, AppState>, name: String) -> Result<(), String> {
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

/// Say which arms a wardrobe skin has (`"classic"` or `"slim"`), or go back
/// to reading them off the PNG (`null`). The PNG carries no flag, so this is
/// what decides the model sent with the skin when the PNG reads wrong.
#[tauri::command(async)]
pub fn set_skin_model(state: State<'_, AppState>, name: String, model: Option<String>) -> Result<(), String> {
    let model = model.map(|m| m.to_lowercase());
    if model.as_deref().is_some_and(|m| m != "classic" && m != "slim") {
        return Err("model must be \"classic\" or \"slim\"".into());
    }
    let mut index = load_index(&state);
    let skin = index.skins.iter_mut().find(|s| s.name == name).ok_or("skin not found")?;
    skin.model = model;
    save_index(&state, &index);
    Ok(())
}

/// Return the selected skin PNG as a data URL (skins are a few KB).
#[tauri::command(async)]
pub fn read_skin(state: State<'_, AppState>, name: String) -> Result<String, String> {
    let bytes = std::fs::read(skin_file(&state, &name)?).map_err(|e| e.to_string())?;
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
    let png = std::fs::read(skin_file(&state, &name)?)
        .map_err(|e| format!("skin \"{name}\" not readable: {e}"))?;
    if crate::commands::offline_session(&state).is_some() {
        let path = format!("/v1/me/skin?model={variant}");
        let resp = crate::dusk::send(&state, reqwest::Method::PUT, &path, Some(crate::dusk::Body::Raw(png, "image/png"))).await?;
        let saved = crate::dusk::parse::<serde_json::Value>(resp).await?;
        remember_offline_model(&state, saved.get("model").and_then(|m| m.as_str()).unwrap_or(&variant));
        return Ok(());
    }
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
    if crate::commands::offline_session(&state).is_some() {
        let resp = crate::dusk::send(&state, reqwest::Method::DELETE, "/v1/me/skin", None).await?;
        if !resp.status().is_success() {
            return Err(format!("Dusk service returned HTTP {}", resp.status().as_u16()));
        }
        remember_offline_model(&state, "");
        return Ok(());
    }
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
    if let Some(offline) = crate::commands::offline_session(&state) {
        return Ok(offline_skin(&state, &offline.username).await);
    }
    let Some(mut session) = crate::auth_store::load_session(&state.data_dir) else {
        return Ok(None);
    };
    // Ask Mojang what the account wears now: a skin changed in game or on
    // minecraft.net (and its arm model) shows here without a re-login.
    // Offline or with a stale token, the one last seen stands.
    if !session.needs_refresh() {
        if let Ok(profile) =
            fasterlauncher_core::auth::fetch_profile(&state.client, &session.access_token).await
        {
            let (url, variant) = profile
                .active_skin()
                .map(|s| (s.url.clone(), s.variant.clone()))
                .unwrap_or_default();
            if url != session.skin_url || variant != session.skin_variant {
                session.skin_url = url;
                session.skin_variant = variant;
                crate::auth_store::save_session(&state.data_dir, &session);
            }
        }
    }
    if session.skin_url.is_empty() {
        return Ok(None);
    }
    Ok(Some(account_skin_data_url(&state, &session.skin_url).await?))
}

/// The offline identity's skin on the Dusk service, as a data URL; None
/// when it has none or the service can't be reached.
async fn offline_skin(state: &AppState, username: &str) -> Option<String> {
    use base64::Engine;
    let resp = state
        .client
        .get(format!("{}/v1/skins/{username}", crate::dusk::api_base()))
        .send()
        .await
        .ok()?;
    if resp.status() == reqwest::StatusCode::NOT_FOUND {
        remember_offline_model(state, "");
    }
    if !resp.status().is_success() {
        return None;
    }
    let model = resp.headers().get("x-skin-model").and_then(|v| v.to_str().ok()).unwrap_or("classic").to_string();
    remember_offline_model(state, &model);
    let png = resp.bytes().await.ok()?;
    Some(format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(png)))
}

fn offline_model_path(state: &AppState) -> PathBuf {
    state.data_dir.join("cache").join("offline-skin.model")
}

/// What the Dusk service last said about the offline skin's arms, so the
/// account knows it the way a Microsoft one knows Mojang's; "" for none.
fn remember_offline_model(state: &AppState, model: &str) {
    let path = offline_model_path(state);
    if model.is_empty() {
        let _ = std::fs::remove_file(path);
    } else if std::fs::read_to_string(&path).ok().as_deref() != Some(model) {
        let _ = std::fs::create_dir_all(state.data_dir.join("cache"));
        let _ = std::fs::write(path, model);
    }
}

/// The offline skin's arm model as last seen ("classic"/"slim"), or "".
pub(crate) fn offline_model(state: &AppState) -> String {
    match std::fs::read_to_string(offline_model_path(state)).unwrap_or_default().trim() {
        m @ ("classic" | "slim") => m.to_string(),
        _ => String::new(),
    }
}

/// One Mojang cape the account owns (Migrator, Pan, …), with its texture.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountCapeDto {
    pub id: String,
    pub name: String,
    pub active: bool,
    /// the 64x32 PNG as a data URL; empty if it could not be fetched
    pub texture: String,
}

/// The Mojang capes on the signed-in account. Empty for an offline or
/// signed-out session.
#[tauri::command]
pub async fn list_account_capes(state: State<'_, AppState>) -> Result<Vec<AccountCapeDto>, String> {
    use base64::Engine;
    let session = crate::commands::ensure_play_session(&state).await?;
    if session.access_token.is_empty() {
        return Ok(Vec::new());
    }
    let profile = fasterlauncher_core::auth::fetch_profile(&state.client, &session.access_token)
        .await
        .map_err(|e| e.to_string())?;
    let mut out = Vec::with_capacity(profile.capes.len());
    for c in profile.capes {
        let texture = match fasterlauncher_core::auth::fetch_skin_png(&state.client, &c.url).await {
            Ok(png) => format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(png)),
            Err(_) => String::new(),
        };
        out.push(AccountCapeDto {
            name: c.alias.clone().unwrap_or_else(|| "Cape".into()),
            active: c.state.eq_ignore_ascii_case("ACTIVE"),
            id: c.id,
            texture,
        });
    }
    Ok(out)
}

/// Show a Mojang cape on the account (`id`), or hide it (`null`).
#[tauri::command]
pub async fn set_account_cape(state: State<'_, AppState>, id: Option<String>) -> Result<(), String> {
    let session = crate::commands::ensure_play_session(&state).await?;
    if session.access_token.is_empty() {
        return Err("sign in with Microsoft to change your cape".into());
    }
    fasterlauncher_core::auth::set_active_cape(&state.client, &session, id.as_deref())
        .await
        .map_err(|e| e.to_string())
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_skins_arm_pick_round_trips_and_older_indexes_still_load() {
        let old: SkinIndex = serde_json::from_str(r#"{"skins":[{"name":"steve","addedAt":1,"selected":true}]}"#).unwrap();
        assert_eq!(old.skins[0].model, None);
        // no pick, no key: the mod's SkinLibrary reads the PNG then
        assert!(!serde_json::to_string(&old).unwrap().contains("model"));
        let mut index = old;
        index.skins[0].model = Some("slim".into());
        let back: SkinIndex = serde_json::from_str(&serde_json::to_string(&index).unwrap()).unwrap();
        assert_eq!(back.skins[0].model.as_deref(), Some("slim"));
    }
}
