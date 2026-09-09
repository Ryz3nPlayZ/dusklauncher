//! Desktop login orchestration: loopback auth-code capture → token exchange
//! → Minecraft session. The Azure app must be registered as a native
//! ("Mobile and desktop applications") client with redirect URI
//! `http://127.0.0.1:19735` (see docs/ARCHITECTURE.md). A fixed port is used
//! deliberately: it matches the registered URI byte-for-byte, so login works
//! regardless of how strictly Entra matches loopback URIs.

use crate::appstate::AppState;
use fasterlauncher_core::auth::{self, AuthConfig, AuthMode, Session};
use std::time::Duration;
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// Fixed loopback port for the OAuth callback. Registered in Azure as
/// `http://127.0.0.1:19735`; changing it requires updating the registration.
pub const CALLBACK_PORT: u16 = 19735;
const LOGIN_TIMEOUT: Duration = Duration::from_secs(300);

const SUCCESS_PAGE: &str = "<html><body style=\"background:#141414;color:#e8e4da;font-family:monospace;padding:40px\"><h2>Signed in — return to DuskLauncher.</h2><p>You can close this tab.</p></body></html>";
const ERROR_PAGE: &str = "<html><body style=\"background:#141414;color:#e8e4da;font-family:monospace;padding:40px\"><h2>Sign-in failed — return to DuskLauncher.</h2><p>You can close this tab.</p></body></html>";

/// Client ID shipped with the app (DuskLauncher's own Azure registration).
/// This is a public identifier, not a secret — it ships in the binary the
/// same way other launchers ship theirs. Power users can override it
/// per-install via Settings → Auth Client ID.
pub const SHIPPED_CLIENT_ID: &str = "0e36efd3-4bb6-4bee-ac76-d33ac47fd3df";

/// Resolve the Azure client_id: explicit Settings override first, then the
/// `DUSK_CLIENT_ID` env var (runtime dev override or build-time injection
/// for release pipelines), then the ID shipped with the app.
pub fn resolve_client_id(state: &AppState) -> String {
    let from_settings = state.settings.lock().unwrap().auth_client_id.trim().to_string();
    if !from_settings.is_empty() {
        return from_settings;
    }
    if let Ok(id) = std::env::var("DUSK_CLIENT_ID") {
        if !id.trim().is_empty() {
            return id.trim().to_string();
        }
    }
    if let Some(id) = option_env!("DUSK_CLIENT_ID") {
        if !id.trim().is_empty() {
            return id.trim().to_string();
        }
    }
    SHIPPED_CLIENT_ID.to_string()
}

/// Resolve the full auth config from settings: which Microsoft identity
/// (`authMode`) and the matching client_id. Default is the official
/// Minecraft title ID, which needs no Azure app and no Microsoft approval;
/// "azure" selects our own registration via [`resolve_client_id`].
/// `redirect_uri` is filled in by the interactive flow only (refreshes
/// don't send it).
pub fn resolve_auth_config(state: &AppState) -> AuthConfig {
    let mode = AuthMode::from_setting(&state.settings.lock().unwrap().auth_mode);
    match mode {
        AuthMode::OfficialTitle => AuthConfig {
            client_id: auth::OFFICIAL_TITLE_CLIENT_ID.to_string(),
            redirect_uri: String::new(),
            mode,
        },
        AuthMode::AzureApp => AuthConfig {
            client_id: resolve_client_id(state),
            redirect_uri: String::new(),
            mode,
        },
    }
}

/// Full interactive login: open the browser, wait for the loopback callback,
/// exchange the code, verify game ownership, persist the session.
pub async fn run_login(app: &AppHandle, state: &AppState) -> Result<Session, String> {
    run_login_with_prompt(app, state, "select_account").await
}

/// Same as [`run_login`] but forces Microsoft's permission screen
/// (`prompt=consent`). This is the repair path for the Xbox HTTP 400: a plain
/// retry reuses the previously granted (missing) permissions, while a consent
/// prompt lets the user actually approve `XboxLive.signin` + `offline_access`.
pub async fn run_reconsent_login(app: &AppHandle, state: &AppState) -> Result<Session, String> {
    run_login_with_prompt(app, state, "consent").await
}

async fn run_login_with_prompt(
    app: &AppHandle,
    state: &AppState,
    prompt: &str,
) -> Result<Session, String> {
    let mut config = resolve_auth_config(state);
    config.validate().map_err(|e| e.to_string())?;
    match config.mode {
        // Azure app: browser + loopback redirect (the app registration pins
        // the exact loopback URI).
        AuthMode::AzureApp => run_loopback_login(app, state, &mut config, prompt).await,
        // Official title ID: device-code flow. live.com only registers
        // `oauth20_desktop.srf` as this title's redirect — loopback URIs are
        // rejected — so the user confirms at the verification page instead.
        AuthMode::OfficialTitle => run_device_code_login(app, state, &config).await,
    }
}

/// Shared tail of both interactive flows: XBL → XSTS → MCS, entitlements
/// check, persist, notify.
async fn finish_login(
    app: &AppHandle,
    state: &AppState,
    config: &AuthConfig,
    ms_token: String,
    refresh: String,
) -> Result<Session, String> {
    let _ = app.emit("auth-state", serde_json::json!({ "state": "finishing" }));
    let client = state.client.clone();
    let session = auth::full_login(&client, refresh, &ms_token, config.mode)
        .await
        .map_err(|e| e.to_string())?;
    if !auth::has_entitlements(&client, &session.access_token)
        .await
        .map_err(|e| e.to_string())?
    {
        return Err("This Microsoft account does not own Minecraft: Java Edition — buy it or switch accounts.".into());
    }

    crate::auth_store::save_session(&state.data_dir, &session);
    let _ = app.emit("auth-state", serde_json::json!({ "state": "signedIn" }));
    Ok(session)
}

async fn run_loopback_login(
    app: &AppHandle,
    state: &AppState,
    config: &mut AuthConfig,
    prompt: &str,
) -> Result<Session, String> {
    // Bind the fixed callback port so the redirect URI matches the Azure
    // registration byte-for-byte.
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", CALLBACK_PORT))
        .await
        .map_err(|e| format!("could not bind the login callback on 127.0.0.1:{CALLBACK_PORT} (is another copy of the launcher already signing in?): {e}"))?;
    config.redirect_uri = format!("http://127.0.0.1:{CALLBACK_PORT}");
    let flow_state = auth::new_flow_state();
    let url = auth::authorize_url_with_prompt(config, &flow_state, prompt)
        .map_err(|e| e.to_string())?;

    let _ = app.emit("auth-state", serde_json::json!({ "state": "waitingForBrowser" }));
    use tauri_plugin_opener::OpenerExt;
    app.opener()
        .open_url(url.clone(), None::<&str>)
        .map_err(|e| format!("could not open a browser for Microsoft login: {e}.\nOpen this URL manually:\n{url}"))?;

    let code = wait_for_code(listener, &flow_state).await?;
    let (ms_token, refresh) = auth::exchange_code(&state.client, config, &code)
        .await
        .map_err(|e| e.to_string())?;
    finish_login(app, state, config, ms_token, refresh).await
}

/// Official-mode interactive login: RFC 8628 device-code flow against
/// login.live.com. The browser opens the verification page; the UI shows the
/// code to type (emitted via `auth-state`); we poll until Microsoft hands
/// out tokens. No redirect URI, no callback port.
async fn run_device_code_login(
    app: &AppHandle,
    state: &AppState,
    config: &AuthConfig,
) -> Result<Session, String> {
    let start = auth::start_device_code(&state.client, config)
        .await
        .map_err(|e| e.to_string())?;
    // Prefilled-code page when Microsoft serves one (`?otc=`): the user just
    // confirms instead of retyping the code. userCode is still emitted as the
    // manual fallback (and for browsers that drop the query param).
    let confirm_uri = start
        .verification_uri_complete
        .clone()
        .unwrap_or_else(|| start.verification_uri.clone());
    let _ = app.emit(
        "auth-state",
        serde_json::json!({
            "state": "deviceCode",
            "userCode": start.user_code,
            "verificationUri": confirm_uri,
        }),
    );
    use tauri_plugin_opener::OpenerExt;
    let _ = app.opener().open_url(confirm_uri, None::<&str>);
    let (ms_token, refresh) = auth::poll_device_code(&state.client, config, &start)
        .await
        .map_err(|e| e.to_string())?;
    finish_login(app, state, config, ms_token, refresh).await
}

/// Accept loopback connections until one carries the auth code (or timeout).
async fn wait_for_code(
    listener: tokio::net::TcpListener,
    expected_state: &str,
) -> Result<String, String> {
    let deadline = tokio::time::Instant::now() + LOGIN_TIMEOUT;
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return Err("Login timed out waiting for the browser — try again.".into());
        }
        let (mut stream, _) = tokio::time::timeout(remaining, listener.accept())
            .await
            .map_err(|_| "Login timed out waiting for the browser — try again.".to_string())?
            .map_err(|e| format!("auth listener failed: {e}"))?;

        let mut buf = vec![0u8; 8192];
        let n = tokio::time::timeout(Duration::from_secs(30), stream.read(&mut buf))
            .await
            .map_err(|_| "Login timed out reading the browser response.".to_string())?
            .map_err(|e| format!("auth listener read failed: {e}"))?;
        let req = String::from_utf8_lossy(&buf[..n]);
        let target = req
            .lines()
            .next()
            .and_then(|line| line.split_whitespace().nth(1))
            .unwrap_or("/");
        // Stray requests without a query (favicon, probes) — ignore and wait.
        if !target.contains('?') {
            let _ = respond(&mut stream, false).await;
            continue;
        }
        match auth::parse_auth_callback(target, expected_state) {
            Ok(code) => {
                let _ = respond(&mut stream, true).await;
                return Ok(code);
            }
            Err(e) => {
                let _ = respond(&mut stream, false).await;
                return Err(e.to_string());
            }
        }
    }
}

async fn respond(stream: &mut tokio::net::TcpStream, ok: bool) -> std::io::Result<()> {
    let page = if ok { SUCCESS_PAGE } else { ERROR_PAGE };
    let head = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        page.len()
    );
    stream.write_all(head.as_bytes()).await?;
    stream.write_all(page.as_bytes()).await?;
    stream.flush().await
}
