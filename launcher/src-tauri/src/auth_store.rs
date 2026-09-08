//! Session persistence: `<data>/session.json` plus an OS-keychain mirror of
//! the Microsoft refresh token.
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
    let bytes = std::fs::read(session_path(data_dir)).ok()?;
    let mut session: Session = serde_json::from_slice(&bytes).ok()?;
    // Prefer the keychain copy of the refresh token when available.
    if let Ok(entry) = keyring::Entry::new(SERVICE, KEY_ACCOUNT) {
        if let Ok(rt) = entry.get_password() {
            if !rt.trim().is_empty() {
                session.refresh_token = rt;
            }
        }
    }
    Some(session)
}

pub fn save_session(data_dir: &Path, session: &Session) {
    let path = session_path(data_dir);
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Ok(text) = serde_json::to_string_pretty(session) {
        let _ = std::fs::write(&path, text);
    }
    if let Ok(entry) = keyring::Entry::new(SERVICE, KEY_ACCOUNT) {
        let _ = entry.set_password(&session.refresh_token);
    }
}

pub fn clear_session(data_dir: &Path) {
    let _ = std::fs::remove_file(session_path(data_dir));
    if let Ok(entry) = keyring::Entry::new(SERVICE, KEY_ACCOUNT) {
        let _ = entry.delete_credential();
    }
}
