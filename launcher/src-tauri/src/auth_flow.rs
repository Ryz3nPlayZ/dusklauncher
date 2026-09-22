//! Desktop login orchestration. Official mode: an in-app webview runs the
//! legacy browser-control flow (title ID → oauth20_desktop.srf, code read
//! from the redirect fragment — no code for the user to type, no app
//! approval). There is no silent fallback: if the webview path can't run,
//! the user is asked (webview again vs device-code) instead. Azure mode:
//! loopback auth-code capture → token exchange → Minecraft session. The
//! Azure app must be registered as a native ("Mobile and desktop
//! applications") client with redirect URI `http://127.0.0.1:19735` (see
//! docs/ARCHITECTURE.md). A fixed port is used deliberately: it matches the
//! registered URI byte-for-byte, so login works regardless of how strictly
//! Entra matches loopback URIs.

use crate::appstate::AppState;
use fasterlauncher_core::auth::{self, AuthConfig, AuthMode, Session};
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};
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
        // Azure app: system browser + loopback redirect (the app registration
        // pins the exact loopback URI).
        AuthMode::AzureApp => run_loopback_login(app, state, &mut config, prompt).await,
        // Official title ID: in-app webview on the legacy desktop.srf
        // redirect (no code to type, no approval needed). No silent
        // fallback — if the webview can't run, the user chooses between
        // retrying it and the device-code flow (`begin_code_login`).
        AuthMode::OfficialTitle => match run_webview_login(app, state, &config).await {
            WebviewOutcome::Done(session) => Ok(session),
            WebviewOutcome::Fallback(reason) => {
                let _ = app.emit(
                    "auth-state",
                    serde_json::json!({ "state": "webviewFailed", "reason": reason }),
                );
                Err(reason)
            }
            WebviewOutcome::Abort(err) => Err(err),
        },
    }
}

/// The user-chosen alternative to the webview flow: RFC 8628 device-code
/// sign-in (Official mode) or the loopback browser flow (Azure mode).
pub async fn run_code_login(app: &AppHandle, state: &AppState) -> Result<Session, String> {
    let mut config = resolve_auth_config(state);
    config.validate().map_err(|e| e.to_string())?;
    match config.mode {
        AuthMode::OfficialTitle => run_device_code_login(app, state, &config).await,
        AuthMode::AzureApp => run_loopback_login(app, state, &mut config, "select_account").await,
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

/// Outcome of the official-mode webview login attempt. `Fallback` marks
/// "the webview itself couldn't run" (window, timeout, exchange) — the UI
/// offers the user a choice: retry the window or use the device code.
/// `Abort` is terminal (user cancelled, Microsoft refused, the Xbox chain
/// failed) and surfaces as a plain error.
enum WebviewOutcome {
    Done(Session),
    Fallback(String),
    Abort(String),
}

/// Official-mode interactive login without a device code: an in-app webview
/// loads the title-ID authorize page; when live.com redirects to
/// oauth20_desktop.srf the navigation interceptor reads the code from the
/// fragment, the window closes, and the normal token → SISU chain takes
/// over. This is the same flow the official launcher's embedded browser
/// uses — no Azure app, no approval, nothing for the user to type.
async fn run_webview_login(app: &AppHandle, state: &AppState, config: &AuthConfig) -> WebviewOutcome {
    let flow_state = auth::new_flow_state();
    let url = auth::title_desktop_authorize_url(&flow_state);

    // One sign-in window at a time — a stale window from a cancelled attempt
    // would collide on the label.
    if let Some(old) = app.get_webview_window("msa-signin") {
        let _ = old.close();
    }

    let _ = app.emit("auth-state", serde_json::json!({ "state": "webview" }));

    enum Msg {
        Code(String),
        Error(String),
        Cancelled,
    }
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Msg>();
    let parsed = match tauri::Url::parse(&url) {
        Ok(u) => u,
        Err(e) => return WebviewOutcome::Fallback(format!("authorize URL unparseable: {e}")),
    };

    let expected = flow_state.clone();
    let tx_nav = tx.clone();
    let builder = tauri::WebviewWindowBuilder::new(
        app,
        "msa-signin",
        tauri::WebviewUrl::External(parsed),
    )
    .title("Sign in with Microsoft")
    .inner_size(480.0, 680.0)
    .min_inner_size(420.0, 560.0)
    .resizable(true)
    .on_navigation(move |url| {
        let landed = url.host_str() == Some("login.live.com")
            && url.path().contains("oauth20_desktop.srf");
        if !landed {
            return true;
        }
        match auth::parse_desktop_callback(&url.to_string(), &expected) {
            Ok(code) => {
                let _ = tx_nav.send(Msg::Code(code));
            }
            Err(e) => {
                let _ = tx_nav.send(Msg::Error(e.to_string()));
            }
        }
        // Never render the bare desktop.srf landing page — the login is done.
        false
    });
    let window = match builder.build() {
        Ok(w) => w,
        Err(e) => {
            return WebviewOutcome::Fallback(format!("could not open the sign-in window: {e}"))
        }
    };

    // Closing the window is an explicit cancel — don't shove a code page at
    // a user who just dismissed sign-in.
    let tx_close = tx.clone();
    window.on_window_event(move |event| {
        if matches!(
            event,
            tauri::WindowEvent::CloseRequested { .. } | tauri::WindowEvent::Destroyed
        ) {
            let _ = tx_close.send(Msg::Cancelled);
        }
    });

    let msg = match tokio::time::timeout(LOGIN_TIMEOUT, rx.recv()).await {
        Ok(Some(m)) => m,
        // All senders dropped without an outcome — treat as a cancel.
        Ok(None) => Msg::Cancelled,
        Err(_) => {
            let _ = window.close();
            return WebviewOutcome::Fallback("the sign-in window timed out".into());
        }
    };
    let _ = window.close();
    match msg {
        Msg::Cancelled => {
            WebviewOutcome::Abort("Sign-in was cancelled — try again when ready.".into())
        }
        Msg::Error(e) => WebviewOutcome::Abort(e),
        Msg::Code(code) => {
            let mut config = config.clone();
            config.redirect_uri = auth::TITLE_DESKTOP_REDIRECT_URI.to_string();
            match auth::exchange_code(&state.client, &config, &code).await {
                Ok((ms_token, refresh)) => {
                    match finish_login(app, state, &config, ms_token, refresh).await {
                        Ok(session) => WebviewOutcome::Done(session),
                        Err(e) => WebviewOutcome::Abort(e),
                    }
                }
                // A code that fails to exchange is usually transient (expired
                // or single-use) — the device flow gets a fresh one.
                Err(e) => WebviewOutcome::Fallback(format!("token exchange failed: {e}")),
            }
        }
    }
}

/// Official-mode fallback login: RFC 8628 device-code flow against
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
    // Prefilled-code page (`?otc=`): the user just confirms instead of
    // retyping the code. live.com hands back a bare `microsoft.com/link` and
    // no `verification_uri_complete`, so build the prefilled link ourselves —
    // both `login.live.com/oauth20_remoteconnect.srf` and `microsoft.com/link`
    // honour `otc`. userCode is still emitted as the manual fallback.
    let confirm_uri = start.verification_uri_complete.clone().unwrap_or_else(|| {
        let sep = if start.verification_uri.contains('?') { '&' } else { '?' };
        format!("{}{sep}otc={}", start.verification_uri, start.user_code)
    });
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
