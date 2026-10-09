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
    /// the multiplayer server they're on — joinable
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server: Option<String>,
    #[serde(default)]
    pub last_seen: i64,
    #[serde(default)]
    pub unread: i64,
    /// a cracked (offline-mode) account; the friends list tags it
    #[serde(default)]
    pub offline: bool,
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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub playing: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server: Option<String>,
    pub cape: Option<u32>,
    pub accessories: Vec<u32>,
    /// everything they own, so a gift can skip it
    #[serde(default)]
    pub owned: Vec<u32>,
    /// achievements they've claimed
    #[serde(default)]
    pub badges: Vec<String>,
    /// a cracked (offline-mode) account
    #[serde(default)]
    pub offline: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatMessage {
    pub id: i64,
    pub from_uuid: String,
    pub to_uuid: String,
    pub body: String,
    pub sent_at: i64,
    /// text | invite (meta: server, version) | image (meta: image) |
    /// gift (meta: item, name, kind)
    #[serde(default = "text_kind")]
    pub kind: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub meta: Option<serde_json::Value>,
}

fn text_kind() -> String {
    "text".into()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Privacy {
    pub appear_offline: bool,
    pub share_activity: bool,
    /// everyone | friends_of_friends | nobody
    pub friend_requests: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BlockedPlayer {
    pub uuid: String,
    pub username: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Gifted {
    pub coins: i64,
    pub message: ChatMessage,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Outfit {
    pub id: i64,
    pub name: String,
    pub loadout: crate::cosmetics::Loadout,
    pub created_at: i64,
}

// ── commands ─────────────────────────────────────────────────────────────

/// A player uuid fit to be a path segment of a service URL: hex and dashes
/// only, so no value can reach a different endpoint with this account's token.
fn player(uuid: &str) -> Result<&str, String> {
    let hex = uuid.chars().filter(|c| *c != '-').count();
    if hex == 32 && uuid.chars().all(|c| c == '-' || c.is_ascii_hexdigit()) {
        Ok(uuid)
    } else {
        Err("That isn't a player id.".into())
    }
}

/// The launcher's heartbeat: keeps this account online for its friends,
/// says which game version it's in (`None` when no game is running), and
/// returns the pending-request / unread / friends-online counts.
#[tauri::command]
pub async fn social_heartbeat(state: State<'_, AppState>, playing: Option<String>) -> Result<SocialSummary, String> {
    // the server comes from the game log, not the UI (see `game_activity`)
    let server = state.activity.lock().unwrap().as_ref().and_then(|a| a.server.clone());
    call(
        &state,
        reqwest::Method::POST,
        "/v1/me/presence",
        Some(json!({ "playing": playing, "server": server })),
    )
    .await
}

#[tauri::command]
pub async fn list_friends(state: State<'_, AppState>) -> Result<Vec<Friend>, String> {
    call(&state, reqwest::Method::GET, "/v1/friends", None).await
}

#[tauri::command]
pub async fn remove_friend(state: State<'_, AppState>, uuid: String) -> Result<Vec<Friend>, String> {
    call(&state, reqwest::Method::DELETE, &format!("/v1/friends/{uuid}", uuid = player(&uuid)?), None).await
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
    call(&state, reqwest::Method::GET, &format!("/v1/profile/{uuid}", uuid = player(&uuid)?), None).await
}

#[tauri::command]
pub async fn get_messages(state: State<'_, AppState>, uuid: String, after_id: i64) -> Result<Vec<ChatMessage>, String> {
    call(&state, reqwest::Method::GET, &format!("/v1/messages/{uuid}?afterId={after_id}", uuid = player(&uuid)?), None).await
}

#[tauri::command]
pub async fn send_message(state: State<'_, AppState>, uuid: String, body: String) -> Result<ChatMessage, String> {
    let body = body.trim();
    if body.is_empty() {
        return Err("Type a message first.".into());
    }
    call(&state, reqwest::Method::POST, &format!("/v1/messages/{uuid}", uuid = player(&uuid)?), Some(json!({ "body": body }))).await
}

/// Invite a friend to the server this account is on (or any address).
#[tauri::command]
pub async fn send_invite(
    state: State<'_, AppState>,
    uuid: String,
    server: String,
    version: Option<String>,
) -> Result<ChatMessage, String> {
    let server = server.trim();
    if server.is_empty() {
        return Err("Join a server first — there's nothing to invite them to.".into());
    }
    call(
        &state,
        reqwest::Method::POST,
        &format!("/v1/messages/{uuid}", uuid = player(&uuid)?),
        Some(json!({ "kind": "invite", "meta": { "server": server, "version": version } })),
    )
    .await
}

const IMAGE_MAX_BYTES: u64 = 8 * 1024 * 1024;

#[derive(Deserialize)]
struct Uploaded {
    id: String,
}

/// Send one of this machine's screenshots to a friend: the file is uploaded
/// to the service (only this account and the people it's sent to can fetch
/// it back), then posted as an image message.
#[tauri::command]
pub async fn send_screenshot(state: State<'_, AppState>, uuid: String, path: String) -> Result<ChatMessage, String> {
    player(&uuid)?;
    let path = crate::screenshots::resolve(&state, &path)?;
    let len = std::fs::metadata(&path).map_err(|e| e.to_string())?.len();
    if len > IMAGE_MAX_BYTES {
        return Err("That screenshot is over 8 MB — too big to send.".into());
    }
    let bytes = std::fs::read(&path).map_err(|e| e.to_string())?;
    let mime = if bytes.starts_with(b"\x89PNG") { "image/png" } else { "image/jpeg" };
    let resp = crate::dusk::send(&state, reqwest::Method::POST, "/v1/images", Some(crate::dusk::Body::Raw(bytes, mime))).await?;
    let up: Uploaded = crate::dusk::parse(resp).await?;
    call(
        &state,
        reqwest::Method::POST,
        &format!("/v1/messages/{uuid}", uuid = player(&uuid)?),
        Some(json!({ "kind": "image", "meta": { "image": up.id } })),
    )
    .await
}

/// A chat image as a data URL, cached under `<data>/cache/chat-images/` —
/// images never change once uploaded.
#[tauri::command]
pub async fn get_chat_image(state: State<'_, AppState>, id: String) -> Result<String, String> {
    if id.len() != 32 || !id.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("bad image id".into());
    }
    let cache = state.data_dir.join("cache").join("chat-images").join(&id);
    let bytes = match std::fs::read(&cache) {
        Ok(b) => b,
        Err(_) => {
            let resp = crate::dusk::send(&state, reqwest::Method::GET, &format!("/v1/images/{id}"), None).await?;
            if !resp.status().is_success() {
                return Err(match resp.status().as_u16() {
                    404 => "This image has expired.".into(),
                    s => format!("Dusk service returned HTTP {s}"),
                });
            }
            let b = resp.bytes().await.map_err(|e| e.to_string())?.to_vec();
            // whole or not at all: a torn file would be served as the image forever
            let _ = fasterlauncher_core::write_atomic(&cache, &b);
            b
        }
    };
    let mime = if bytes.starts_with(b"\x89PNG") { "image/png" } else { "image/jpeg" };
    Ok(format!("data:{mime};base64,{}", base64::engine::general_purpose::STANDARD.encode(&bytes)))
}

// ── privacy & blocking ───────────────────────────────────────────────────

#[tauri::command]
pub async fn get_privacy(state: State<'_, AppState>) -> Result<Privacy, String> {
    call(&state, reqwest::Method::GET, "/v1/me/privacy", None).await
}

#[tauri::command]
pub async fn set_privacy(state: State<'_, AppState>, privacy: Privacy) -> Result<Privacy, String> {
    let body = serde_json::to_value(&privacy).map_err(|e| e.to_string())?;
    call(&state, reqwest::Method::PUT, "/v1/me/privacy", Some(body)).await
}

#[tauri::command]
pub async fn list_blocked(state: State<'_, AppState>) -> Result<Vec<BlockedPlayer>, String> {
    call(&state, reqwest::Method::GET, "/v1/blocks", None).await
}

/// Block by uuid (from the friends list) — ends any friendship and pending
/// requests; they can't message or request this account again.
#[tauri::command]
pub async fn block_player(state: State<'_, AppState>, uuid: String) -> Result<Vec<BlockedPlayer>, String> {
    call(&state, reqwest::Method::POST, "/v1/blocks", Some(json!({ "uuid": uuid }))).await
}

#[tauri::command]
pub async fn unblock_player(state: State<'_, AppState>, uuid: String) -> Result<Vec<BlockedPlayer>, String> {
    call(&state, reqwest::Method::DELETE, &format!("/v1/blocks/{uuid}", uuid = player(&uuid)?), None).await
}

// ── gifts & outfits ──────────────────────────────────────────────────────

/// Buy cosmetic `id` for friend `uuid`; answers with the new balance and the
/// gift message that now sits in the conversation.
#[tauri::command]
pub async fn gift_cosmetic(state: State<'_, AppState>, uuid: String, id: u32) -> Result<Gifted, String> {
    call(&state, reqwest::Method::POST, "/v1/me/gift", Some(json!({ "to": uuid, "id": id }))).await
}

/// The quest boards, streak and achievements (shape: `Quests` in api.ts).
#[tauri::command]
pub async fn get_quests(state: State<'_, AppState>) -> Result<serde_json::Value, String> {
    call(&state, reqwest::Method::GET, "/v1/me/quests", None).await
}

/// Claim a quest by id, "streak", or "all": `{ paid, quests }`.
#[tauri::command]
pub async fn claim_quest(state: State<'_, AppState>, id: String) -> Result<serde_json::Value, String> {
    call(&state, reqwest::Method::POST, "/v1/me/quests/claim", Some(json!({ "id": id }))).await
}

#[tauri::command]
pub async fn list_outfits(state: State<'_, AppState>) -> Result<Vec<Outfit>, String> {
    call(&state, reqwest::Method::GET, "/v1/me/outfits", None).await
}

/// Save the given loadout under `name` (replacing an outfit of that name).
#[tauri::command]
pub async fn save_outfit(
    state: State<'_, AppState>,
    name: String,
    loadout: crate::cosmetics::Loadout,
) -> Result<Vec<Outfit>, String> {
    call(
        &state,
        reqwest::Method::POST,
        "/v1/me/outfits",
        Some(json!({ "name": name.trim(), "loadout": loadout })),
    )
    .await
}

#[tauri::command]
pub async fn delete_outfit(state: State<'_, AppState>, id: i64) -> Result<Vec<Outfit>, String> {
    call(&state, reqwest::Method::DELETE, &format!("/v1/me/outfits/{id}"), None).await
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
    // 204/404: no such player. Anything else (a 429 above all — the session
    // server limits each IP) is a failure, so a cached skin still shows.
    if matches!(resp.status().as_u16(), 204 | 404) {
        return Ok(None);
    }
    if !resp.status().is_success() {
        return Err(format!("Mojang session server returned HTTP {}", resp.status().as_u16()));
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

    // a version-3 uuid is an offline player: Mojang has no skin for it, the
    // Dusk service may (one it uploaded there)
    if undashed.as_bytes()[12] == b'3' {
        let resp = state
            .client
            .get(format!("{}/v1/skins/{undashed}", crate::dusk::api_base()))
            .send()
            .await
            .map_err(|e| e.to_string());
        let resp = match resp {
            Ok(r) => r,
            Err(e) => return cached.map(|b| Some(png_data_url(&b))).ok_or(e),
        };
        if resp.status() == reqwest::StatusCode::NOT_FOUND {
            let _ = std::fs::remove_file(&png_path);
            return Ok(None);
        }
        let png = match resp.error_for_status() {
            Ok(r) => r.bytes().await.map_err(|e| e.to_string())?,
            Err(e) => return cached.map(|b| Some(png_data_url(&b))).ok_or_else(|| e.to_string()),
        };
        save_skin(&png_path, &url_path, &png, "dusk")?;
        return Ok(Some(png_data_url(&png)));
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
    save_skin(&png_path, &url_path, &png, &skin_url)?;
    Ok(Some(png_data_url(&png)))
}

/// The PNG, then the marker naming where it came from: a marker beside a
/// torn PNG would keep serving it as fresh.
fn save_skin(png_path: &std::path::Path, url_path: &std::path::Path, png: &[u8], source: &str) -> Result<(), String> {
    fasterlauncher_core::write_atomic(png_path, png).map_err(|e| e.to_string())?;
    fasterlauncher_core::write_atomic(url_path, source.as_bytes()).map_err(|e| e.to_string())
}
