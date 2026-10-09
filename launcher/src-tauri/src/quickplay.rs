//! "Jump back in": the worlds and servers played most recently, across every
//! instance.
//!
//! 1.20+ is launched with `--quickPlayPath` ([`launch::QUICK_PLAY_LOG`]); the
//! game rewrites that file each session with a one-entry list naming where
//! the session went. The game only keeps the last one, so every read folds it
//! into the instance's own history (`quickPlay/dusk-history.json`) first.

use crate::appstate::AppState;
use fasterlauncher_core::launch;
use serde::{Deserialize, Serialize};
use std::path::Path;
use tauri::State;

/// How many places each instance remembers.
const KEEP: usize = 20;

/// One entry of the game's log (`QuickPlayLog.QuickPlayEntry`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
struct Entry {
    /// singleplayer | multiplayer | realms
    #[serde(rename = "type")]
    kind: String,
    /// the save's folder name, or the server address
    id: String,
    name: String,
    /// an ISO-8601 instant
    last_played_time: String,
    #[serde(default)]
    gamemode: String,
}

impl Entry {
    fn played_ms(&self) -> i64 {
        chrono::DateTime::parse_from_rfc3339(&self.last_played_time)
            .map(|t| t.timestamp_millis())
            .unwrap_or(0)
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecentPlay {
    pub profile_id: String,
    pub profile_name: String,
    /// "world" | "server"
    pub kind: &'static str,
    /// the save's folder name, or the server address
    pub id: String,
    pub name: String,
    /// unix millis
    pub last_played: i64,
    pub gamemode: String,
}

fn read_list(path: &Path) -> Vec<Entry> {
    std::fs::read(path)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

/// Fold the game's latest log into the instance's history and return the
/// history, newest first.
fn absorb(root: &Path) -> Vec<Entry> {
    let history_path = root.join("quickPlay").join("dusk-history.json");
    let mut history = read_list(&history_path);
    let latest = read_list(&root.join(launch::QUICK_PLAY_LOG));
    let before = history.clone();
    for e in latest {
        history.retain(|h| !(h.kind == e.kind && h.id == e.id));
        history.push(e);
    }
    history.sort_by_key(|e| std::cmp::Reverse(e.played_ms()));
    history.truncate(KEEP);
    if history != before {
        if let Ok(json) = serde_json::to_vec_pretty(&history) {
            let _ = fasterlauncher_core::write_atomic(&history_path, &json);
        }
    }
    history
}

/// The most recent worlds and servers across all instances. Worlds that are
/// gone and Realms (which this launcher can't open) are left out.
#[tauri::command]
pub async fn recent_plays(state: State<'_, AppState>, limit: Option<usize>) -> Result<Vec<RecentPlay>, String> {
    let profiles: Vec<(String, String, std::path::PathBuf)> = {
        let store = state.profiles.lock().unwrap();
        store
            .profiles
            .iter()
            .map(|p| (p.id.clone(), p.name.clone(), p.dirs(&state.data_dir).root))
            .collect()
    };
    let limit = limit.unwrap_or(5).min(KEEP);
    tokio::task::spawn_blocking(move || {
        let mut out = Vec::new();
        for (profile_id, profile_name, root) in profiles {
            for e in absorb(&root) {
                let kind = match e.kind.as_str() {
                    "singleplayer"
                        if !e.id.contains(['/', '\\'])
                            && e.id != ".."
                            && root.join("saves").join(&e.id).join("level.dat").is_file() =>
                    {
                        "world"
                    }
                    "multiplayer" if crate::servers::valid_address(&e.id) => "server",
                    _ => continue,
                };
                out.push(RecentPlay {
                    profile_id: profile_id.clone(),
                    profile_name: profile_name.clone(),
                    kind,
                    last_played: e.played_ms(),
                    id: e.id,
                    name: e.name,
                    gamemode: e.gamemode,
                });
            }
        }
        out.sort_by_key(|r| std::cmp::Reverse(r.last_played));
        out.truncate(limit);
        out
    })
    .await
    .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_games_log_folds_into_the_history() {
        let root = std::env::temp_dir().join(format!("dusk-quickplay-{}", std::process::id()));
        let log = root.join(launch::QUICK_PLAY_LOG);
        std::fs::create_dir_all(log.parent().unwrap()).unwrap();
        // the shape QuickPlayLog writes
        let write = |kind: &str, id: &str, when: &str| {
            std::fs::write(
                &log,
                format!(
                    r#"[{{"type":"{kind}","id":"{id}","name":"{id}","lastPlayedTime":"{when}","gamemode":"survival"}}]"#
                ),
            )
            .unwrap();
        };
        write("multiplayer", "mc.example.net", "2026-10-01T10:00:00.123456Z");
        assert_eq!(absorb(&root).len(), 1);
        write("singleplayer", "New World", "2026-10-02T10:00:00Z");
        write("singleplayer", "New World", "2026-10-02T10:00:00Z");
        let h = absorb(&root);
        assert_eq!(h.iter().map(|e| e.id.as_str()).collect::<Vec<_>>(), ["New World", "mc.example.net"]);
        // playing a place again moves it to the front instead of repeating it
        write("multiplayer", "mc.example.net", "2026-10-03T10:00:00Z");
        let h = absorb(&root);
        assert_eq!(h.iter().map(|e| e.id.as_str()).collect::<Vec<_>>(), ["mc.example.net", "New World"]);
        assert_eq!(h[0].played_ms(), 1_791_021_600_000);
        let _ = std::fs::remove_dir_all(&root);
    }
}
