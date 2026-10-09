//! Client of the Dusk cosmetics service (`server/`): the wallet, what the
//! account owns, redeem codes, and the loadout other Dusk players see.
//!
//! Sign-in reuses the Minecraft session: the launcher "joins" a random
//! server id at Mojang's session server with the MC access token, and the
//! service verifies that join (`hasJoined`) before issuing a bearer token.
//! No password, no extra account — owning the Minecraft account is the
//! identity. The token is cached in `<data>/dusk-session.json` and
//! re-issued transparently on a 401 or when the signed-in account changes.
//!
//! Offline play (cracked) has no Mojang session; it signs in with the
//! offline name and a random key kept in `<data>/dusk-device.key`, which
//! holds that name on Dusk for this device.

use crate::appstate::AppState;
use crate::cosmetics::{self, Inventory, Loadout};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use tauri::State;

/// Production host; override with `DUSK_API` for a local server.
pub const DEFAULT_API: &str = "https://dusk.129-213-43-152.sslip.io";

pub fn api_base() -> String {
    let s = std::env::var("DUSK_API").unwrap_or_else(|_| DEFAULT_API.to_string());
    s.trim_end_matches('/').to_string()
}

const MOJANG_JOIN: &str = "https://sessionserver.mojang.com/session/minecraft/join";

// ── token cache ────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
struct StoredToken {
    token: String,
    uuid: String,
    username: String,
}

fn token_path(data_dir: &Path) -> PathBuf {
    data_dir.join("dusk-session.json")
}

fn load_token(data_dir: &Path) -> Option<StoredToken> {
    std::fs::read(token_path(data_dir))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
}

fn save_token(data_dir: &Path, t: &StoredToken) {
    if let Ok(bytes) = serde_json::to_vec_pretty(t) {
        let _ = fasterlauncher_core::write_atomic(&token_path(data_dir), &bytes);
    }
}

/// Forget the cached token (sign-out).
pub fn clear_token(data_dir: &Path) {
    let _ = std::fs::remove_file(token_path(data_dir));
}

fn undashed(uuid: &str) -> String {
    uuid.replace('-', "")
}

// ── auth ───────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct AuthResponse {
    token: String,
    uuid: String,
    username: String,
}

fn device_key_path(data_dir: &Path) -> PathBuf {
    data_dir.join("dusk-device.key")
}

/// This device's offline key, made on first use.
fn device_key(data_dir: &Path) -> Result<String, String> {
    if let Ok(k) = std::fs::read_to_string(device_key_path(data_dir)) {
        let k = k.trim();
        if k.len() >= 32 && k.chars().all(|c| c.is_ascii_hexdigit()) {
            return Ok(k.to_string());
        }
    }
    let k = hex::encode(rand::random::<[u8; 32]>());
    // the key is what holds the offline name: a torn write would lose it
    fasterlauncher_core::write_atomic(&device_key_path(data_dir), k.as_bytes())
        .map_err(|e| format!("couldn't save the Dusk device key: {e}"))?;
    Ok(k)
}

/// Sign in the offline-play identity: its name, held by this device's key.
async fn sign_in_offline(state: &AppState, username: &str) -> Result<StoredToken, String> {
    let key = device_key(&state.data_dir)?;
    let resp = state
        .client
        .post(format!("{}/v1/auth/offline", api_base()))
        .json(&json!({ "username": username, "key": key }))
        .send()
        .await
        .map_err(|e| format!("Dusk service unreachable: {e}"))?;
    let auth: AuthResponse = parse(resp).await?;
    let stored = StoredToken { token: auth.token, uuid: auth.uuid, username: auth.username };
    save_token(&state.data_dir, &stored);
    Ok(stored)
}

/// Prove ownership of the Minecraft account to the service and cache the
/// token it hands back. Offline play signs in with its name instead.
async fn sign_in(state: &AppState) -> Result<StoredToken, String> {
    if let Some(offline) = crate::commands::offline_session(state) {
        return sign_in_offline(state, &offline.username).await;
    }
    let session = crate::commands::ensure_play_session(state).await?;
    if session.access_token.is_empty() {
        return Err("Sign in with Microsoft to use the Dusk store.".into());
    }
    let server_id = hex::encode(rand::random::<[u8; 20]>());
    let join = state
        .client
        .post(MOJANG_JOIN)
        .json(&json!({
            "accessToken": session.access_token,
            "selectedProfile": undashed(&session.uuid),
            "serverId": server_id,
        }))
        .send()
        .await
        .map_err(|e| format!("Mojang session server unreachable: {e}"))?;
    if !join.status().is_success() {
        return Err(format!(
            "Mojang rejected the session (HTTP {}) — sign in again.",
            join.status().as_u16()
        ));
    }
    let resp = state
        .client
        .post(format!("{}/v1/auth/minecraft", api_base()))
        .json(&json!({ "username": session.username, "serverId": server_id }))
        .send()
        .await
        .map_err(|e| format!("Dusk service unreachable: {e}"))?;
    let auth: AuthResponse = parse(resp).await?;
    let stored = StoredToken { token: auth.token, uuid: auth.uuid, username: auth.username };
    save_token(&state.data_dir, &stored);
    Ok(stored)
}

/// A token for the account that is signed in right now.
async fn token(state: &AppState) -> Result<StoredToken, String> {
    let current = crate::auth_store::load_session(&state.data_dir)
        .or_else(|| crate::commands::offline_session(state))
        .map(|s| undashed(&s.uuid));
    if let Some(t) = load_token(&state.data_dir) {
        if current.as_deref() == Some(undashed(&t.uuid).as_str()) {
            return Ok(t);
        }
    }
    sign_in(state).await
}

/// The service's `{"error": "..."}` body as the error string, else the status.
pub(crate) async fn parse<T: DeserializeOwned>(resp: reqwest::Response) -> Result<T, String> {
    let status = resp.status();
    let body = resp.bytes().await.map_err(|e| e.to_string())?;
    if status.is_success() {
        return serde_json::from_slice(&body).map_err(|e| format!("bad response from Dusk service: {e}"));
    }
    let msg = serde_json::from_slice::<Value>(&body)
        .ok()
        .and_then(|v| v.get("error").and_then(Value::as_str).map(str::to_string))
        .unwrap_or_else(|| format!("Dusk service returned HTTP {}", status.as_u16()));
    Err(msg)
}

/// An authenticated call; a stale token is re-issued once and the call retried.
pub(crate) async fn call<T: DeserializeOwned>(
    state: &AppState,
    method: reqwest::Method,
    path: &str,
    body: Option<Value>,
) -> Result<T, String> {
    parse(send(state, method, path, body.map(Body::Json)).await?).await
}

pub(crate) enum Body {
    Json(Value),
    /// raw bytes with their content type (image uploads)
    Raw(Vec<u8>, &'static str),
}

/// The authenticated request behind [`call`], for callers that need the
/// raw response (image bytes) or a non-JSON body.
pub(crate) async fn send(
    state: &AppState,
    method: reqwest::Method,
    path: &str,
    body: Option<Body>,
) -> Result<reqwest::Response, String> {
    let mut tok = token(state).await?;
    for attempt in 0..2 {
        let mut req = state
            .client
            .request(method.clone(), format!("{}{path}", api_base()))
            .bearer_auth(&tok.token);
        match &body {
            Some(Body::Json(b)) => req = req.json(b),
            Some(Body::Raw(bytes, mime)) => {
                req = req.header(reqwest::header::CONTENT_TYPE, *mime).body(bytes.clone())
            }
            None => {}
        }
        let resp = req.send().await.map_err(|e| format!("Dusk service unreachable: {e}"))?;
        if resp.status() == reqwest::StatusCode::UNAUTHORIZED && attempt == 0 {
            clear_token(&state.data_dir);
            tok = sign_in(state).await?;
            continue;
        }
        return Ok(resp);
    }
    unreachable!()
}

// ── DTOs ───────────────────────────────────────────────────────────────────

/// One priced catalog item as the service lists it (`GET /v1/catalog`).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoreItem {
    pub id: u32,
    pub kind: String,
    pub name: String,
    pub animated: bool,
    pub price: i64,
}

#[derive(Deserialize)]
struct CatalogResponse {
    items: Vec<StoreItem>,
}

/// `GET /v1/me`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Me {
    pub uuid: String,
    pub username: String,
    pub coins: i64,
    pub owned: Vec<u32>,
    #[serde(default)]
    pub loadout: Loadout,
    /// a cracked account
    #[serde(default)]
    pub offline: bool,
}

/// Everything the store page needs in one round trip. `coins`/`owned` come
/// from the account when it could be reached; otherwise `error` says why
/// and `owned` falls back to the local cache.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Store {
    pub items: Vec<StoreItem>,
    pub coins: i64,
    pub owned: Vec<u32>,
    pub signed_in: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Redeemed {
    pub granted: i64,
    pub coins: i64,
    /// a launcher feature the code switched on locally ("offline")
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub unlocked: Option<String>,
}

/// The code that unlocks offline play. Deliberately undocumented in the UI.
const OFFLINE_CODE: &str = "cracked";

#[derive(Deserialize)]
struct Bought {
    coins: i64,
    owned: Vec<u32>,
}

// ── account sync ───────────────────────────────────────────────────────────

/// Pull the account from the service and refresh the local inventory cache.
pub async fn fetch_me(state: &AppState) -> Result<Me, String> {
    let me: Me = call(state, reqwest::Method::GET, "/v1/me", None).await?;
    cosmetics::save_inventory(&state.data_dir, &Inventory { owned: me.owned.clone() })?;
    Ok(me)
}

/// Publish the loadout so other Dusk players see it. The service is the
/// authority on ownership: an unowned id comes back as an error.
pub async fn publish_loadout(state: &AppState, loadout: &Loadout) -> Result<Loadout, String> {
    call(state, reqwest::Method::PUT, "/v1/me/loadout", Some(Value::Object(loadout.clone()))).await
}

/// `GET/POST /v1/me/referral` — this account's code and who brought it.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Referral {
    pub code: String,
    pub referred_by: Option<String>,
    pub referral_paid: bool,
    pub can_claim: bool,
    pub invited: i64,
    pub paid: i64,
    pub referrer_reward: i64,
    pub referee_reward: i64,
    pub coins: i64,
}

#[derive(Deserialize)]
struct Launched {
    granted: i64,
}

/// Tell the service a game launched; the first launch settles a pending
/// referral. Returns the coins that paid this account (usually 0).
pub async fn report_launch(state: &AppState) -> Result<i64, String> {
    let l: Launched = call(state, reqwest::Method::POST, "/v1/me/launched", None).await?;
    Ok(l.granted)
}

// ── commands ───────────────────────────────────────────────────────────────

#[tauri::command]
pub async fn get_referral(state: State<'_, AppState>) -> Result<Referral, String> {
    call(&state, reqwest::Method::GET, "/v1/me/referral", None).await
}

/// Name the player who invited this account (new accounts only, once).
#[tauri::command]
pub async fn claim_referral(state: State<'_, AppState>, code: String) -> Result<Referral, String> {
    let code = code.trim();
    if code.is_empty() {
        return Err("Enter a referral code first.".into());
    }
    call(&state, reqwest::Method::POST, "/v1/me/referral", Some(json!({ "code": code }))).await
}

#[tauri::command]
pub async fn get_store(state: State<'_, AppState>) -> Result<Store, String> {
    let resp = state
        .client
        .get(format!("{}/v1/catalog", api_base()))
        .send()
        .await
        .map_err(|e| format!("Dusk service unreachable: {e}"))?;
    let catalog: CatalogResponse = parse(resp).await?;
    match fetch_me(&state).await {
        Ok(me) => Ok(Store { items: catalog.items, coins: me.coins, owned: me.owned, signed_in: true, error: None }),
        Err(e) => Ok(Store {
            items: catalog.items,
            coins: 0,
            owned: cosmetics::load_inventory(&state.data_dir).owned,
            signed_in: false,
            error: Some(e),
        }),
    }
}

#[tauri::command]
pub async fn get_wallet(state: State<'_, AppState>) -> Result<Me, String> {
    fetch_me(&state).await
}

#[tauri::command]
pub async fn redeem_code(state: State<'_, AppState>, code: String) -> Result<Redeemed, String> {
    let code = code.trim();
    if code.is_empty() {
        return Err("Enter a code first.".into());
    }
    // offline play: only while no Microsoft account is signed in, and never
    // sent to the service. Signed in, the code goes to the server like any
    // other (and is simply unknown there).
    if code.eq_ignore_ascii_case(OFFLINE_CODE) && crate::auth_store::load_session(&state.data_dir).is_none() {
        let settings = {
            let mut s = state.settings.lock().unwrap();
            if s.offline_name.trim().is_empty() {
                s.offline_name = "Player".into();
            }
            s.clone()
        };
        state.save_settings(&settings);
        return Ok(Redeemed { granted: 0, coins: 0, unlocked: Some("offline".into()) });
    }
    call(&state, reqwest::Method::POST, "/v1/me/redeem", Some(json!({ "code": code }))).await
}

/// Spend coins on a catalog item. The service checks price and balance;
/// the local inventory cache is refreshed from its answer.
#[tauri::command]
pub async fn buy_cosmetic(state: State<'_, AppState>, id: u32) -> Result<Store, String> {
    let bought: Bought = call(&state, reqwest::Method::POST, "/v1/me/buy", Some(json!({ "id": id }))).await?;
    cosmetics::save_inventory(&state.data_dir, &Inventory { owned: bought.owned.clone() })?;
    let resp = state
        .client
        .get(format!("{}/v1/catalog", api_base()))
        .send()
        .await
        .map_err(|e| format!("Dusk service unreachable: {e}"))?;
    let catalog: CatalogResponse = parse(resp).await?;
    Ok(Store { items: catalog.items, coins: bought.coins, owned: bought.owned, signed_in: true, error: None })
}
