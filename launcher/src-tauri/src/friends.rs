//! Friends, profiles and 1:1 chat, backed by the Dusk service (`server/`).
//!
//! A friend's skin is fetched from Mojang's public, unauthenticated session
//! server (`sessionserver.mojang.com/session/minecraft/profile/{uuid}`) —
//! unlike the signed-in account's own skin, no Microsoft token is needed or
//! usable for someone else's uuid.

use crate::appstate::AppState;
use crate::dusk::call;
use base64::Engine as _;
use serde::{Deserialize, Serialize};
use serde_json::json;
use tauri::State;

// ── DTOs (mirror server/src/main.rs's friend/message JSON shapes) ──────────

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Friend {
    pub uuid: String,
    pub username: String,
    pub online: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub playing: Option<String>,
    #[serde(default)]
    pub last_seen: i64,
    #[serde(default)]
    pub unread: i64,
}

/// `POST /v1/me/presence`'s answer: what the status pill badges.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SocialSummary {
    pub requests: i64,
    pub unread: i64,
    pub online: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FriendRequest {
    pub id: i64,
    pub uuid: String,
    pub username: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FriendRequests {
    pub incoming: Vec<FriendRequest>,
    pub outgoing: Vec<FriendRequest>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FriendProfile {
    pub uuid: String,
    pub username: String,
    pub online: bool,
    pub last_seen: i64,
    pub cape: Option<u32>,
    pub accessories: Vec<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatMessage {
    pub id: i64,
    pub from_uuid: String,
    pub to_uuid: String,
    pub body: String,
    pub sent_at: i64,
}

// ── commands ─────────────────────────────────────────────────────────────

/// The launcher's heartbeat: keeps this account online for its friends,
/// says which game version it's in (`None` when no game is running), and
/// returns the pending-request / unread / friends-online counts.
#[tauri::command]
pub async fn social_heartbeat(state: State<'_, AppState>, playing: Option<String>) -> Result<SocialSummary, String> {
    call(&state, reqwest::Method::POST, "/v1/me/presence", Some(json!({ "playing": playing }))).await
}

#[tauri::command]
pub async fn list_friends(state: State<'_, AppState>) -> Result<Vec<Friend>, String> {
    call(&state, reqwest::Method::GET, "/v1/friends", None).await
}

#[tauri::command]
pub async fn remove_friend(state: State<'_, AppState>, uuid: String) -> Result<Vec<Friend>, String> {
    call(&state, reqwest::Method::DELETE, &format!("/v1/friends/{uuid}"), None).await
}

#[tauri::command]
pub async fn list_friend_requests(state: State<'_, AppState>) -> Result<FriendRequests, String> {
    call(&state, reqwest::Method::GET, "/v1/friends/requests", None).await
}

/// Send a request by username — the everyday case: typing a friend's name.
#[tauri::command]
pub async fn send_friend_request(state: State<'_, AppState>, username: String) -> Result<FriendRequests, String> {
    let username = username.trim();
    if username.is_empty() {
        return Err("Enter a username first.".into());
    }
    call(&state, reqwest::Method::POST, "/v1/friends/requests", Some(json!({ "username": username }))).await
}

#[tauri::command]
pub async fn accept_friend_request(state: State<'_, AppState>, id: i64) -> Result<FriendRequests, String> {
    call(&state, reqwest::Method::POST, &format!("/v1/friends/requests/{id}/accept"), None).await
}

#[tauri::command]
pub async fn decline_friend_request(state: State<'_, AppState>, id: i64) -> Result<FriendRequests, String> {
    call(&state, reqwest::Method::POST, &format!("/v1/friends/requests/{id}/decline"), None).await
}

#[tauri::command]
pub async fn get_friend_profile(state: State<'_, AppState>, uuid: String) -> Result<FriendProfile, String> {
    call(&state, reqwest::Method::GET, &format!("/v1/profile/{uuid}"), None).await
}

#[tauri::command]
pub async fn get_messages(state: State<'_, AppState>, uuid: String, after_id: i64) -> Result<Vec<ChatMessage>, String> {
    call(&state, reqwest::Method::GET, &format!("/v1/messages/{uuid}?afterId={after_id}"), None).await
}

#[tauri::command]
pub async fn send_message(state: State<'_, AppState>, uuid: String, body: String) -> Result<ChatMessage, String> {
    let body = body.trim();
    if body.is_empty() {
        return Err("Type a message first.".into());
    }
    call(&state, reqwest::Method::POST, &format!("/v1/messages/{uuid}"), Some(json!({ "body": body }))).await
}

// ── public skin (Mojang, no auth) ────────────────────────────────────────

#[derive(Deserialize)]
struct SessionProfile {
    properties: Vec<SessionProperty>,
}

#[derive(Deserialize)]
struct SessionProperty {
    name: String,
    value: String,
}

#[derive(Deserialize)]
struct TexturesPayload {
    textures: Textures,
}

#[derive(Deserialize)]
struct Textures {
    #[serde(rename = "SKIN")]
    skin: Option<Texture>,
}

#[derive(Deserialize)]
struct Texture {
    url: String,
}

/// The skin URL Mojang's session server has on file for `uuid`, if any.
async fn public_skin_url(client: &reqwest::Client, uuid: &str) -> Result<Option<String>, String> {
    let undashed = uuid.replace('-', "");
    let resp = client
        .get(format!("https://sessionserver.mojang.com/session/minecraft/profile/{undashed}"))
        .send()
        .await
        .map_err(|e| format!("Mojang session server unreachable: {e}"))?;
    if !resp.status().is_success() {
        return Ok(None);
    }
    let profile: SessionProfile = resp.json().await.map_err(|e| format!("bad Mojang reply: {e}"))?;
    let Some(prop) = profile.properties.into_iter().find(|p| p.name == "textures") else {
        return Ok(None);
    };
    let decoded = base64::engine::general_purpose::STANDARD
        .decode(prop.value)
        .map_err(|e| format!("bad texture payload: {e}"))?;
    let payload: TexturesPayload = serde_json::from_slice(&decoded).map_err(|e| format!("bad texture json: {e}"))?;
    Ok(payload.textures.skin.map(|s| s.url))
}

/// How long a cached skin is trusted before Mojang is asked again. The
/// session server rate-limits per IP, and a friends list asks for several
/// skins every time it opens.
const SKIN_TTL: std::time::Duration = std::time::Duration::from_secs(30 * 60);

fn png_data_url(bytes: &[u8]) -> String {
    format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes))
}

/// A friend's skin as a data URL, cached under `<data>/cache/friends/<uuid>.png`
/// — cheap to call often since a friends list may show several. A fresh
/// cache is served without touching the network; a stale one is still
/// served when Mojang can't be reached.
#[tauri::command]
pub async fn get_public_skin(state: State<'_, AppState>, uuid: String) -> Result<Option<String>, String> {
    // the uuid names cache files — never let it be a path
    let undashed = uuid.replace('-', "").to_ascii_lowercase();
    if undashed.len() != 32 || !undashed.chars().all(|c| c.is_ascii_hexdigit()) {
        return Ok(None);
    }
    let cache_dir = state.data_dir.join("cache").join("friends");
    let png_path = cache_dir.join(format!("{undashed}.png"));
    let url_path = cache_dir.join(format!("{undashed}.url"));

    let cached = std::fs::read(&png_path).ok();
    let fresh = std::fs::metadata(&url_path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.elapsed().ok())
        .is_some_and(|age| age < SKIN_TTL);
    if let (Some(bytes), true) = (&cached, fresh) {
        return Ok(Some(png_data_url(bytes)));
    }

    let skin_url = match public_skin_url(&state.client, &undashed).await {
        Ok(Some(url)) => url,
        Ok(None) => return Ok(None),
        Err(e) => return cached.map(|b| Some(png_data_url(&b))).ok_or(e),
    };
    let cached_url = std::fs::read_to_string(&url_path).unwrap_or_default();
    if cached_url == skin_url {
        if let Some(bytes) = cached {
            // unchanged — touch the marker so the TTL restarts
            let _ = std::fs::write(&url_path, &skin_url);
            return Ok(Some(png_data_url(&bytes)));
        }
    }
    let png = match fasterlauncher_core::auth::fetch_skin_png(&state.client, &skin_url).await {
        Ok(png) => png,
        Err(e) => return cached.map(|b| Some(png_data_url(&b))).ok_or_else(|| e.to_string()),
    };
    std::fs::create_dir_all(&cache_dir).map_err(|e| e.to_string())?;
    std::fs::write(&png_path, &png).map_err(|e| e.to_string())?;
    std::fs::write(&url_path, &skin_url).map_err(|e| e.to_string())?;
    Ok(Some(png_data_url(&png)))
}
