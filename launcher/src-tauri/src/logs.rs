//! The LOG tab's two helpers: the instance's `logs/latest.log` for when the
//! launcher wasn't watching the run (it was restarted, or the game was
//! launched elsewhere), and sharing a log on mclo.gs — the paste site the
//! Minecraft community reads logs on — with this machine's details taken out.

use crate::appstate::AppState;
use crate::commands::GameLogLine;
use serde::Serialize;
use std::io::{Read, Seek, SeekFrom};
use tauri::State;

/// The LOG tab keeps this many lines; latest.log is cut to match.
const MAX_LINES: usize = 5000;
/// Only the end of a huge log is read.
const MAX_BYTES: u64 = 2 << 20;
/// mclo.gs takes at most 25k lines / 10 MB.
const UPLOAD_LINES: usize = 25_000;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LatestLog {
    pub lines: Vec<GameLogLine>,
    /// unix millis the game last wrote it
    pub modified: i64,
}

/// Lines the game logged at ERROR/FATAL, and the stack traces under them,
/// read as stderr so the tab colours them like a live run.
fn classify(text: &str) -> Vec<GameLogLine> {
    let mut out = Vec::new();
    let mut in_error = false;
    for line in text.lines() {
        let trimmed = line.trim_start();
        let level_error = line.contains("/ERROR]") || line.contains("/FATAL]");
        let trace = in_error
            && (trimmed.starts_with("at ")
                || trimmed.starts_with("Caused by:")
                || trimmed.starts_with("...")
                || trimmed.starts_with("Suppressed:")
                || (!line.starts_with('[') && !line.is_empty()));
        in_error = level_error || trace;
        out.push(GameLogLine {
            line: line.to_string(),
            stream: if in_error { "err" } else { "out" }.into(),
        });
    }
    if out.len() > MAX_LINES {
        out.drain(..out.len() - MAX_LINES);
    }
    out
}

#[tauri::command]
pub async fn read_latest_log(state: State<'_, AppState>, profile_id: String) -> Result<Option<LatestLog>, String> {
    let root = crate::servers::profile_root(&state, &profile_id)?;
    tokio::task::spawn_blocking(move || {
        let path = root.join("logs").join("latest.log");
        let Ok(mut file) = std::fs::File::open(&path) else {
            return Ok(None);
        };
        let meta = file.metadata().map_err(|e| e.to_string())?;
        let modified = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);
        let skip = meta.len().saturating_sub(MAX_BYTES);
        file.seek(SeekFrom::Start(skip)).map_err(|e| e.to_string())?;
        let mut bytes = Vec::new();
        file.read_to_end(&mut bytes).map_err(|e| e.to_string())?;
        let mut text = String::from_utf8_lossy(&bytes).into_owned();
        if skip > 0 {
            // started mid-line: drop the partial one
            let cut = text.find('\n').map_or(text.len(), |i| i + 1);
            text.drain(..cut);
        }
        Ok(Some(LatestLog { lines: classify(&text), modified }))
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Takes out what a public log shouldn't carry: Minecraft session tokens
/// (JWTs, `--accessToken <x>`) and the home folder, which names the account.
fn redact(text: &str, home: Option<&str>) -> String {
    let mut out = String::with_capacity(text.len());
    let mut hide_next = false;
    for (i, line) in text.split('\n').enumerate() {
        if i > 0 {
            out.push('\n');
        }
        let mut words = Vec::new();
        for word in line.split(' ') {
            if hide_next && !word.is_empty() {
                words.push("<hidden>".to_string());
                hide_next = false;
                continue;
            }
            if word == "--accessToken" {
                hide_next = true;
            }
            words.push(redact_jwts(word));
        }
        // a flag at the end of a line still hides the next line's first word
        out.push_str(&words.join(" "));
    }
    match home.filter(|h| h.len() > 1) {
        Some(home) => out.replace(home, "~"),
        None => out,
    }
}

/// `eyJ…​.…​.…` (base64url header, payload, signature) → `<token>`
fn redact_jwts(word: &str) -> String {
    let mut out = String::new();
    let mut rest = word;
    while let Some(start) = rest.find("eyJ") {
        let tail = &rest[start..];
        let end = tail
            .find(|c: char| !(c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.'))
            .unwrap_or(tail.len());
        let candidate = &tail[..end];
        out.push_str(&rest[..start]);
        if candidate.split('.').filter(|p| !p.is_empty()).count() >= 3 && candidate.len() >= 40 {
            out.push_str("<token>");
        } else {
            out.push_str(candidate);
        }
        rest = &tail[end..];
    }
    out.push_str(rest);
    out
}

#[derive(serde::Deserialize)]
struct Pasted {
    success: bool,
    url: Option<String>,
    error: Option<String>,
}

/// Put a log on mclo.gs and return its link.
#[tauri::command]
pub async fn upload_log(state: State<'_, AppState>, text: String) -> Result<String, String> {
    let home = dirs::home_dir().map(|h| h.to_string_lossy().into_owned());
    let mut text = redact(&text, home.as_deref());
    let count = text.lines().count();
    if count > UPLOAD_LINES {
        let skip = text.lines().take(count - UPLOAD_LINES).map(|l| l.len() + 1).sum::<usize>();
        text.drain(..skip.min(text.len()));
    }
    if text.trim().is_empty() {
        return Err("The log is empty.".into());
    }
    let res = state
        .client
        .post("https://api.mclo.gs/1/log")
        .form(&[("content", text.as_str())])
        .send()
        .await
        .map_err(|_| "Couldn't reach mclo.gs.".to_string())?;
    let pasted: Pasted = res.json().await.map_err(|_| "mclo.gs sent back something odd.".to_string())?;
    match (pasted.success, pasted.url) {
        (true, Some(url)) => Ok(url),
        _ => Err(format!("mclo.gs said no: {}", pasted.error.unwrap_or_else(|| "unknown error".into()))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn errors_and_their_traces_read_as_stderr() {
        let log = "[12:00:00] [main/INFO]: ok\n\
                   [12:00:01] [Render thread/ERROR]: boom\n\
                   java.lang.RuntimeException: x\n\
                   \tat a.b.C.d(C.java:1)\n\
                   Caused by: y\n\
                   [12:00:02] [main/WARN]: fine again";
        let s: Vec<_> = classify(log).iter().map(|l| l.stream.clone()).collect();
        assert_eq!(s, ["out", "err", "err", "err", "err", "out"]);
    }

    #[test]
    fn tokens_and_home_come_out() {
        let jwt = format!("eyJhbGciOiJIUzI1NiJ9.{}.sig_abc-DEF", "x".repeat(40));
        let text = format!(
            "Loading from /Users/alex/Library/x\n--accessToken {jwt} --uuid 1\nauth={jwt};\nkeep eyJ.short"
        );
        let out = redact(&text, Some("/Users/alex"));
        assert!(!out.contains("alex"));
        assert!(out.contains("Loading from ~/Library/x"));
        assert!(out.contains("--accessToken <hidden> --uuid 1"));
        assert!(out.contains("auth=<token>;"));
        assert!(out.contains("keep eyJ.short"));
        assert!(!out.contains("sig_abc"));
    }
}
