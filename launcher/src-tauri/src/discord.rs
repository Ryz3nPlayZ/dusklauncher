//! Discord Rich Presence over Discord's local IPC socket — "Playing
//! Minecraft 1.21.11 · On mc.example.net" on the Discord profile while a game
//! runs.
//!
//! The protocol is small enough to speak directly: frames are
//! `op: u32 LE, len: u32 LE, json`, a handshake (op 0) with the application
//! id, then `SET_ACTIVITY` commands (op 1). It needs a Discord application
//! id (discord.com/developers → New Application; the "dusk" art asset is
//! uploaded under Rich Presence → Art Assets). Without one, presence is
//! simply off.
//!
//! One worker thread owns the connection. `refresh` hands it the activity
//! the launcher wants shown; it (re)connects when Discord appears and
//! forgets the socket when Discord goes away.

use crate::appstate::AppState;
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::sync::mpsc::{channel, RecvTimeoutError, Sender};
use std::sync::OnceLock;
use std::time::Duration;
use tauri::{AppHandle, Manager};

/// Built-in application id (set at build time); `DUSK_DISCORD_APP_ID` overrides it.
const BUILT_IN_APP_ID: Option<&str> = option_env!("DUSK_DISCORD_APP_ID");

fn app_id() -> Option<String> {
    std::env::var("DUSK_DISCORD_APP_ID")
        .ok()
        .or(BUILT_IN_APP_ID.map(str::to_string))
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty() && s.chars().all(|c| c.is_ascii_digit()))
}

/// Whether presence can work at all in this build.
pub fn available() -> bool {
    app_id().is_some()
}

static WORKER: OnceLock<Option<Sender<Option<Value>>>> = OnceLock::new();

/// Push the current game activity (or its absence) to Discord, respecting
/// the "Discord Rich Presence" setting. Cheap; never blocks on Discord.
pub fn refresh(app: &AppHandle) {
    let Some(tx) = WORKER.get_or_init(spawn_worker) else { return };
    let state = app.state::<AppState>();
    let enabled = state.settings.lock().unwrap().discord_rpc;
    let activity = if enabled {
        state.activity.lock().unwrap().as_ref().map(|a| {
            let mut activity = json!({
                "details": format!("Minecraft {}", a.game_version),
                "timestamps": { "start": a.started_at },
                "assets": { "large_image": "dusk", "large_text": "DuskLauncher" },
            });
            activity["state"] = json!(match &a.server {
                Some(server) => format!("On {server}"),
                None => "Singleplayer".to_string(),
            });
            activity
        })
    } else {
        None
    };
    let _ = tx.send(activity);
}

fn spawn_worker() -> Option<Sender<Option<Value>>> {
    let id = app_id()?;
    let (tx, rx) = channel::<Option<Value>>();
    std::thread::Builder::new()
        .name("discord-rpc".into())
        .spawn(move || {
            let mut conn: Option<Conn> = None;
            let mut wanted: Option<Value> = None;
            // the last activity Discord actually accepted — `Some(None)` is a clear
            let mut shown: Option<Option<Value>> = None;
            loop {
                match rx.recv_timeout(Duration::from_secs(20)) {
                    Ok(next) => wanted = next,
                    Err(RecvTimeoutError::Timeout) => {}
                    Err(RecvTimeoutError::Disconnected) => return,
                }
                if shown.as_ref() == Some(&wanted) {
                    continue;
                }
                // nothing to show and no connection: stay disconnected
                if wanted.is_none() && conn.is_none() {
                    shown = Some(None);
                    continue;
                }
                if conn.is_none() {
                    conn = Conn::open(&id);
                }
                let Some(c) = conn.as_mut() else { continue };
                if c.set_activity(wanted.clone()).is_ok() {
                    shown = Some(wanted.clone());
                } else {
                    conn = None;
                    shown = None;
                }
            }
        })
        .ok()?;
    Some(tx)
}

#[cfg(unix)]
type Stream = std::os::unix::net::UnixStream;
#[cfg(windows)]
type Stream = std::fs::File;

struct Conn {
    stream: Stream,
    nonce: u64,
}

impl Conn {
    fn open(app_id: &str) -> Option<Conn> {
        for i in 0..10 {
            if let Some(stream) = connect(i) {
                let mut conn = Conn { stream, nonce: 0 };
                if conn.frame(0, &json!({ "v": 1, "client_id": app_id })).is_ok() && conn.read().is_ok() {
                    return Some(conn);
                }
            }
        }
        None
    }

    fn set_activity(&mut self, activity: Option<Value>) -> std::io::Result<()> {
        self.nonce += 1;
        let payload = json!({
            "cmd": "SET_ACTIVITY",
            "args": { "pid": std::process::id(), "activity": activity },
            "nonce": self.nonce.to_string(),
        });
        self.frame(1, &payload)?;
        let (op, _) = self.read()?;
        // op 2 = Discord closed the connection
        if op == 2 {
            return Err(std::io::Error::other("discord closed the connection"));
        }
        Ok(())
    }

    fn frame(&mut self, op: u32, payload: &Value) -> std::io::Result<()> {
        let body = serde_json::to_vec(payload)?;
        let mut buf = Vec::with_capacity(8 + body.len());
        buf.extend_from_slice(&op.to_le_bytes());
        buf.extend_from_slice(&(body.len() as u32).to_le_bytes());
        buf.extend_from_slice(&body);
        self.stream.write_all(&buf)?;
        self.stream.flush()
    }

    fn read(&mut self) -> std::io::Result<(u32, Vec<u8>)> {
        let mut head = [0u8; 8];
        self.stream.read_exact(&mut head)?;
        let op = u32::from_le_bytes(head[0..4].try_into().unwrap());
        let len = u32::from_le_bytes(head[4..8].try_into().unwrap()) as usize;
        if len > 1 << 20 {
            return Err(std::io::Error::other("oversized discord frame"));
        }
        let mut body = vec![0u8; len];
        self.stream.read_exact(&mut body)?;
        Ok((op, body))
    }
}

#[cfg(unix)]
fn connect(i: u32) -> Option<Stream> {
    let bases: Vec<std::path::PathBuf> = ["XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"]
        .iter()
        .filter_map(|k| std::env::var_os(k).map(std::path::PathBuf::from))
        .chain(std::iter::once(std::path::PathBuf::from("/tmp")))
        .collect();
    // Flatpak and Snap Discord put the socket in a subdirectory
    for base in bases {
        for sub in ["", "app/com.discordapp.Discord", "snap.discord"] {
            let path = base.join(sub).join(format!("discord-ipc-{i}"));
            if let Ok(s) = std::os::unix::net::UnixStream::connect(&path) {
                let _ = s.set_read_timeout(Some(Duration::from_secs(5)));
                let _ = s.set_write_timeout(Some(Duration::from_secs(5)));
                return Some(s);
            }
        }
    }
    None
}

#[cfg(windows)]
fn connect(i: u32) -> Option<Stream> {
    std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(format!(r"\\.\pipe\discord-ipc-{i}"))
        .ok()
}
