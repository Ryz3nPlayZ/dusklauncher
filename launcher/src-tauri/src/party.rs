//! Parties, backed by the Dusk service's `/v1/party` (server/src/party.rs).
//! The game holds the live party socket; the launcher only reads the party
//! and answers invites, so its JSON passes through to the UI unchanged.

use crate::appstate::AppState;
use crate::dusk::{send, Body};
use serde_json::{json, Value};
use tauri::State;

/// Party errors come as `{error: code, message}`; the message is the
/// sentence to show.
async fn party(state: &AppState, method: reqwest::Method, path: &str, body: Option<Value>) -> Result<Option<Value>, String> {
    let resp = send(state, method, &format!("/v1/party{path}"), body.map(Body::Json)).await?;
    let status = resp.status();
    let bytes = resp.bytes().await.map_err(|e| e.to_string())?;
    if status.is_success() {
        if bytes.is_empty() {
            return Ok(None);
        }
        return serde_json::from_slice(&bytes).map(Some).map_err(|e| format!("bad response from Dusk service: {e}"));
    }
    let v = serde_json::from_slice::<Value>(&bytes).unwrap_or(Value::Null);
    let text = |k: &str| v.get(k).and_then(Value::as_str).map(str::to_string);
    Err(text("message").or_else(|| text("error")).unwrap_or_else(|| format!("Dusk service returned HTTP {}", status.as_u16())))
}

/// Hex and dashes only, so no value can reach a different endpoint.
fn id(raw: &str) -> Result<&str, String> {
    if !raw.is_empty() && raw.len() <= 64 && raw.chars().all(|c| c == '-' || c.is_ascii_hexdigit()) {
        Ok(raw)
    } else {
        Err("That isn't a valid id.".into())
    }
}

/// The party this account is in, or `null`.
#[tauri::command]
pub async fn get_party(state: State<'_, AppState>) -> Result<Option<Value>, String> {
    party(&state, reqwest::Method::GET, "", None).await
}

#[tauri::command]
pub async fn list_party_invites(state: State<'_, AppState>) -> Result<Value, String> {
    let incoming = party(&state, reqwest::Method::GET, "/invites/incoming", None).await?;
    let outgoing = party(&state, reqwest::Method::GET, "/invites/outgoing", None).await?;
    Ok(json!({ "incoming": incoming.unwrap_or(json!([])), "outgoing": outgoing.unwrap_or(json!([])) }))
}

/// Inviting with no party starts one.
#[tauri::command]
pub async fn invite_to_party(state: State<'_, AppState>, uuid: String) -> Result<(), String> {
    party(&state, reqwest::Method::POST, "/invites", Some(json!({ "player": id(&uuid)? }))).await.map(|_| ())
}

#[tauri::command]
pub async fn accept_party_invite(state: State<'_, AppState>, invite: String, leave_current: bool) -> Result<(), String> {
    let path = format!("/invites/{}/accept", id(&invite)?);
    party(&state, reqwest::Method::POST, &path, Some(json!({ "leave_current": leave_current }))).await.map(|_| ())
}

#[tauri::command]
pub async fn decline_party_invite(state: State<'_, AppState>, invite: String) -> Result<(), String> {
    party(&state, reqwest::Method::POST, &format!("/invites/{}/decline", id(&invite)?), None).await.map(|_| ())
}

#[tauri::command]
pub async fn cancel_party_invite(state: State<'_, AppState>, invite: String) -> Result<(), String> {
    party(&state, reqwest::Method::DELETE, &format!("/invites/{}", id(&invite)?), None).await.map(|_| ())
}

#[tauri::command]
pub async fn leave_party(state: State<'_, AppState>) -> Result<(), String> {
    party(&state, reqwest::Method::POST, "/leave", None).await.map(|_| ())
}

#[tauri::command]
pub async fn disband_party(state: State<'_, AppState>) -> Result<(), String> {
    party(&state, reqwest::Method::DELETE, "", None).await.map(|_| ())
}

#[tauri::command]
pub async fn kick_party_member(state: State<'_, AppState>, uuid: String) -> Result<(), String> {
    party(&state, reqwest::Method::DELETE, &format!("/members/{}", id(&uuid)?), None).await.map(|_| ())
}

#[tauri::command]
pub async fn make_party_leader(state: State<'_, AppState>, uuid: String) -> Result<(), String> {
    party(&state, reqwest::Method::POST, "/leader", Some(json!({ "player": id(&uuid)? }))).await.map(|_| ())
}
