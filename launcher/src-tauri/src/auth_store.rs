//! Session persistence: `<data>/session.json` plus an OS-keychain mirror of
//! the Microsoft refresh token.
//!
//! Every account ever signed in is also stashed under
//! `<data>/accounts/<uuid>.json` (keychain entry `ms-refresh-token:<uuid>`),
//! so the account switcher can make any of them the active session again
//! without a browser round trip. `session.json` stays the one active
//! session everything else reads.
//!
//! Both copies hold the refresh token on purpose: the keychain is the
//! preferred source, the file keeps sign-in working where no keychain exists
//! (fresh Linux installs without dbus, CI smoke tests).

use fasterlauncher_core::auth::Session;
use std::path::{Path, PathBuf};

const SERVICE: &str = "DuskLauncher";
const KEY_ACCOUNT: &str = "ms-refresh-token";

fn session_path(data_dir: &Path) -> PathBuf {
    data_dir.join("session.json")
}

pub fn load_session(data_dir: &Path) -> Option<Session> {
    // the keychain copy of the refresh token wins when available
    read_session(&session_path(data_dir), KEY_ACCOUNT)
}

pub fn save_session(data_dir: &Path, session: &Session) {
    write_session(&session_path(data_dir), KEY_ACCOUNT, session);
    // keep the switcher's copy as fresh as the active one
    if let Some(path) = stash_path(data_dir, &session.uuid) {
        write_session(&path, &stash_key(&session.uuid), session);
    }
}

fn write_session(path: &Path, key: &str, session: &Session) {
    if let Ok(text) = serde_json::to_string_pretty(session) {
        let _ = fasterlauncher_core::write_atomic(path, text.as_bytes());
    }
    if let Ok(entry) = keyring::Entry::new(SERVICE, key) {
        let _ = entry.set_password(&session.refresh_token);
    }
}

fn read_session(path: &Path, key: &str) -> Option<Session> {
    let bytes = std::fs::read(path).ok()?;
    let mut session: Session = serde_json::from_slice(&bytes).ok()?;
    if let Ok(entry) = keyring::Entry::new(SERVICE, key) {
        if let Ok(rt) = entry.get_password() {
            if !rt.trim().is_empty() {
                session.refresh_token = rt;
            }
        }
    }
    Some(session)
}

// ── account stash (switcher) ───────────────────────────────────────────────

fn undashed(uuid: &str) -> Option<String> {
    let u = uuid.replace('-', "").to_ascii_lowercase();
    (u.len() == 32 && u.chars().all(|c| c.is_ascii_hexdigit())).then_some(u)
}

/// `None` for anything that isn't a real uuid — it names a file
fn stash_path(data_dir: &Path, uuid: &str) -> Option<PathBuf> {
    // the all-zero demo identity is never an account worth keeping
    let u = undashed(uuid).filter(|u| u.chars().any(|c| c != '0'))?;
    Some(data_dir.join("accounts").join(format!("{u}.json")))
}

fn stash_key(uuid: &str) -> String {
    format!("{KEY_ACCOUNT}:{}", uuid.replace('-', "").to_ascii_lowercase())
}

/// Every stashed account, the active one included (stashed on first sight
/// so sessions from before the switcher existed show up too).
pub fn list_stashed(data_dir: &Path) -> Vec<Session> {
    if let Some(active) = load_session(data_dir) {
        if let Some(path) = stash_path(data_dir, &active.uuid) {
            if !path.exists() {
                write_session(&path, &stash_key(&active.uuid), &active);
            }
        }
    }
    let Ok(dir) = std::fs::read_dir(data_dir.join("accounts")) else { return Vec::new() };
    let mut out: Vec<Session> = dir
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().to_string();
            let uuid = name.strip_suffix(".json")?;
            read_session(&e.path(), &stash_key(uuid))
        })
        .collect();
    out.sort_by_key(|s| s.username.to_lowercase());
    out
}

pub fn load_stashed(data_dir: &Path, uuid: &str) -> Option<Session> {
    read_session(&stash_path(data_dir, uuid)?, &stash_key(uuid))
}

pub fn remove_stashed(data_dir: &Path, uuid: &str) {
    if let Some(path) = stash_path(data_dir, uuid) {
        let _ = std::fs::remove_file(path);
    }
    if let Ok(entry) = keyring::Entry::new(SERVICE, &stash_key(uuid)) {
        let _ = entry.delete_credential();
    }
}

pub fn clear_session(data_dir: &Path) {
    let _ = std::fs::remove_file(session_path(data_dir));
    if let Ok(entry) = keyring::Entry::new(SERVICE, KEY_ACCOUNT) {
        let _ = entry.delete_credential();
    }
}
