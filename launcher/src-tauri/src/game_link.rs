//! A private line the running game uses to switch accounts without a restart.
//!
//! The launcher listens on a random loopback port and writes the port and a
//! random secret to `game-link.json` (readable by this user only); the game is
//! pointed at that file with `-Ddusk.link`. Every request must carry the
//! secret, so other programs on the machine can't ask for a login.

use crate::appstate::{Account, AppState};
use crate::{auth_flow, auth_store};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

static LINK: Mutex<Option<(u16, String)>> = Mutex::new(None);

/// Start listening (once per launcher run) and write the file the game reads.
pub fn start(app: &AppHandle, data_dir: &Path) -> Option<PathBuf> {
    let mut link = LINK.lock().unwrap();
    if link.is_none() {
        let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).ok()?;
        listener.set_nonblocking(true).ok()?;
        let port = listener.local_addr().ok()?.port();
        let secret = hex::encode(rand::random::<[u8; 32]>());
        let (app, key) = (app.clone(), secret.clone());
        tauri::async_runtime::spawn(async move {
            let Ok(listener) = tokio::net::TcpListener::from_std(listener) else { return };
            loop {
                let Ok((stream, _)) = listener.accept().await else { continue };
                let (app, key) = (app.clone(), key.clone());
                tauri::async_runtime::spawn(async move {
                    let _ = tokio::time::timeout(Duration::from_secs(30), serve(app, stream, &key)).await;
                });
            }
        });
        *link = Some((port, secret));
    }
    let (port, secret) = link.as_ref()?;
    let path = data_dir.join("game-link.json");
    let body = json!({ "port": port, "secret": secret }).to_string();
    // made fresh each time so the owner-only mode applies even to an old file
    let _ = std::fs::remove_file(&path);
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut opts, 0o600);
    use std::io::Write;
    opts.open(&path).and_then(|mut f| f.write_all(body.as_bytes())).ok()?;
    Some(path)
}

async fn serve(app: AppHandle, mut stream: tokio::net::TcpStream, key: &str) -> std::io::Result<()> {
    let Some((method, path, auth, body)) = read_request(&mut stream).await else {
        return reply(&mut stream, 400, &json!({ "error": "bad request" })).await;
    };
    if !same(auth.as_bytes(), format!("Bearer {key}").as_bytes()) {
        return reply(&mut stream, 401, &json!({ "error": "unauthorized" })).await;
    }
    let (status, out) = match (method.as_str(), path.as_str()) {
        ("GET", "/accounts") => (200, accounts(&app)),
        ("POST", "/switch") => {
            let uuid = serde_json::from_slice::<Value>(&body)
                .ok()
                .and_then(|v| v["uuid"].as_str().map(str::to_string))
                .unwrap_or_default();
            match switch(&app, &uuid).await {
                Ok(v) => (200, v),
                Err(e) => (409, json!({ "error": e })),
            }
        }
        _ => (404, json!({ "error": "not found" })),
    };
    reply(&mut stream, status, &out).await
}

fn accounts(app: &AppHandle) -> Value {
    let state = app.state::<AppState>();
    let active = auth_store::load_session(&state.data_dir).map(|s| s.uuid.replace('-', ""));
    let list: Vec<Value> = auth_store::list_stashed(&state.data_dir)
        .into_iter()
        .map(|s| {
            json!({
                "uuid": s.uuid,
                "username": s.username,
                "active": active.as_deref() == Some(s.uuid.replace('-', "").as_str()),
            })
        })
        .collect();
    json!({ "accounts": list })
}

/// Make a saved account the active one and hand its login to the game.
async fn switch(app: &AppHandle, uuid: &str) -> Result<Value, String> {
    let state = app.state::<AppState>();
    let mut session = auth_store::load_stashed(&state.data_dir, uuid)
        .ok_or("That account isn't saved in the launcher any more — sign in there again.")?;
    if session.needs_refresh() {
        let config = auth_flow::resolve_auth_config(&state);
        session = fasterlauncher_core::auth::refresh_session(&state.client, &config, &session)
            .await
            .map_err(|e| format!("Couldn't sign in as {} ({e}). Sign in again in the launcher.", session.username))?;
    }
    auth_store::save_session(&state.data_dir, &session);
    crate::dusk::clear_token(&state.data_dir);
    *state.account.lock().unwrap() = Some(Account {
        username: session.username.clone(),
        uuid: session.uuid.clone(),
        authenticated: true,
    });
    let _ = app.emit("account-changed", ());
    Ok(json!({
        "uuid": session.uuid,
        "username": session.username,
        "accessToken": session.access_token,
        "xuid": session.xuid,
    }))
}

/// Request line, Authorization header and body; small requests only.
async fn read_request(stream: &mut tokio::net::TcpStream) -> Option<(String, String, String, Vec<u8>)> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 4096];
    let head_end = loop {
        let n = stream.read(&mut chunk).await.ok()?;
        if n == 0 {
            return None;
        }
        buf.extend_from_slice(&chunk[..n]);
        if let Some(i) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            break i + 4;
        }
        if buf.len() > 16 * 1024 {
            return None;
        }
    };
    let head = String::from_utf8_lossy(&buf[..head_end]).into_owned();
    let mut lines = head.lines();
    let mut first = lines.next()?.split_whitespace();
    let (method, path) = (first.next()?.to_string(), first.next()?.to_string());
    let (mut auth, mut len) = (String::new(), 0usize);
    for line in lines {
        let Some((k, v)) = line.split_once(':') else { continue };
        match k.trim().to_ascii_lowercase().as_str() {
            "authorization" => auth = v.trim().to_string(),
            "content-length" => len = v.trim().parse().ok()?,
            _ => {}
        }
    }
    if len > 16 * 1024 {
        return None;
    }
    let mut body = buf[head_end..].to_vec();
    while body.len() < len {
        let n = stream.read(&mut chunk).await.ok()?;
        if n == 0 {
            return None;
        }
        body.extend_from_slice(&chunk[..n]);
    }
    body.truncate(len);
    Some((method, path, auth, body))
}

async fn reply(stream: &mut tokio::net::TcpStream, status: u16, body: &Value) -> std::io::Result<()> {
    let body = body.to_string();
    let head = format!(
        "HTTP/1.1 {status} {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        if status == 200 { "OK" } else { "Error" },
        body.len()
    );
    stream.write_all(head.as_bytes()).await?;
    stream.write_all(body.as_bytes()).await?;
    stream.flush().await
}

/// Compare without bailing at the first differing byte.
fn same(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}
