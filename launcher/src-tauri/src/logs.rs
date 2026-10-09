//! The LOG tab's helpers: the instance's `logs/latest.log` for when the
//! launcher wasn't watching the run (it was restarted, or the game was
//! launched elsewhere); the older runs' logs (`logs/*.log.gz`) and crash
//! reports to pick from, like Prism's Other Logs; and sharing a log on
//! mclo.gs — the paste site the Minecraft community reads logs on — with this
//! machine's details taken out.

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

/// Lines the game logged at ERROR/FATAL, exceptions printed on their own (a
/// crash report's, or one a mod dumped to the console), and the stack traces
/// under them, read as stderr so the tab colours them like a live run.
fn classify(text: &str) -> Vec<GameLogLine> {
    let mut out = Vec::new();
    let mut in_error = false;
    for line in text.lines() {
        let trimmed = line.trim_start();
        let level_error = line.contains("/ERROR]") || line.contains("/FATAL]") || throwable(line);
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

/// `java.lang.NullPointerException: …`, `net.foo.BarError` — a Java
/// throwable's class name opening the line.
fn throwable(line: &str) -> bool {
    let name = line.split([':', ' ']).next().unwrap_or("");
    name.contains('.')
        && !name.starts_with('.')
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '$' || c == '_')
        && (name.ends_with("Exception") || name.ends_with("Error") || name.ends_with("Throwable"))
}

#[tauri::command]
pub async fn read_latest_log(state: State<'_, AppState>, profile_id: String) -> Result<Option<LatestLog>, String> {
    let root = crate::servers::profile_root(&state, &profile_id)?;
    tokio::task::spawn_blocking(move || read_tail(&root.join("logs").join("latest.log")))
        .await
        .map_err(|e| e.to_string())?
}

/// The end of a log — plain or gzipped — as the LOG tab shows it. `None`
/// when there's no such file.
fn read_tail(path: &std::path::Path) -> Result<Option<LatestLog>, String> {
    let Ok(mut file) = std::fs::File::open(path) else {
        return Ok(None);
    };
    let meta = file.metadata().map_err(|e| e.to_string())?;
    let modified = millis(&meta);
    let mut bytes = Vec::new();
    let skip = if path.extension().is_some_and(|e| e == "gz") {
        // a whole run's log: tens of MB at worst once unpacked
        flate2::read::GzDecoder::new(file).take(64 << 20).read_to_end(&mut bytes).map_err(|e| e.to_string())?;
        let skip = bytes.len().saturating_sub(MAX_BYTES as usize);
        bytes.drain(..skip);
        skip as u64
    } else {
        let skip = meta.len().saturating_sub(MAX_BYTES);
        file.seek(SeekFrom::Start(skip)).map_err(|e| e.to_string())?;
        file.read_to_end(&mut bytes).map_err(|e| e.to_string())?;
        skip
    };
    let mut text = String::from_utf8_lossy(&bytes).into_owned();
    if skip > 0 {
        // started mid-line: drop the partial one
        let cut = text.find('\n').map_or(text.len(), |i| i + 1);
        text.drain(..cut);
    }
    Ok(Some(LatestLog { lines: classify(&text), modified }))
}

fn millis(meta: &std::fs::Metadata) -> i64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// The folders the LOG tab's picker reads, and the files it takes from each.
const LOG_DIRS: [(&str, &[&str]); 2] = [("logs", &[".log", ".log.gz"]), ("crash-reports", &[".txt"])];

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LogFile {
    /// `logs/2026-10-01-1.log.gz`, `crash-reports/crash-….txt`
    pub path: String,
    pub modified: i64,
    pub size: u64,
}

/// An instance's older logs and crash reports, newest first. latest.log
/// isn't one of them: the tab shows that unless another is picked.
#[tauri::command]
pub async fn list_logs(state: State<'_, AppState>, profile_id: String) -> Result<Vec<LogFile>, String> {
    let root = crate::servers::profile_root(&state, &profile_id)?;
    tokio::task::spawn_blocking(move || {
        let mut out = Vec::new();
        for (dir, exts) in LOG_DIRS {
            let Ok(entries) = std::fs::read_dir(root.join(dir)) else { continue };
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                if name == "latest.log" || !exts.iter().any(|e| name.ends_with(e)) {
                    continue;
                }
                let Ok(meta) = entry.metadata() else { continue };
                if meta.is_file() {
                    out.push(LogFile { path: format!("{dir}/{name}"), modified: millis(&meta), size: meta.len() });
                }
            }
        }
        out.sort_by_key(|f| std::cmp::Reverse(f.modified));
        out.truncate(300);
        out
    })
    .await
    .map_err(|e| e.to_string())
}

/// One of `list_logs`' files.
#[tauri::command]
pub async fn read_log(state: State<'_, AppState>, profile_id: String, path: String) -> Result<LatestLog, String> {
    let root = crate::servers::profile_root(&state, &profile_id)?;
    let file = log_file(&root, &path).ok_or("That isn't one of the instance's logs.")?;
    tokio::task::spawn_blocking(move || read_tail(&file)?.ok_or_else(|| "That log is gone.".to_string()))
        .await
        .map_err(|e| e.to_string())?
}

/// `path` inside the instance, when it names a file `list_logs` lists.
fn log_file(root: &std::path::Path, path: &str) -> Option<std::path::PathBuf> {
    let (dir, name) = path.split_once('/')?;
    let (_, exts) = LOG_DIRS.iter().find(|(d, _)| *d == dir)?;
    let plain = !name.is_empty() && !name.contains(['/', '\\']) && !name.starts_with('.') && exts.iter().any(|e| name.ends_with(e));
    plain.then(|| root.join(dir).join(name))
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
        // Windows paths turn up with either slash, and doubled inside JSON
        Some(home) => [home.to_string(), home.replace('\\', "/"), home.replace('\\', "\\\\")]
            .iter()
            .fold(out, |text, h| text.replace(h.as_str(), "~")),
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
    fn older_logs_read_through_gzip_and_stay_inside() {
        let root = std::env::temp_dir().join(format!("dusk-logs-{}", std::process::id()));
        std::fs::create_dir_all(root.join("logs")).unwrap();
        let gz = root.join("logs").join("2026-10-01-1.log.gz");
        let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        std::io::Write::write_all(&mut enc, b"[10:00:00] [main/INFO]: hi\n[10:00:01] [main/ERROR]: bad\n").unwrap();
        std::fs::write(&gz, enc.finish().unwrap()).unwrap();
        let log = read_tail(&gz).unwrap().unwrap();
        assert_eq!(log.lines.len(), 2);
        assert_eq!(log.lines[1].stream, "err");
        assert_eq!(log_file(&root, "logs/2026-10-01-1.log.gz"), Some(gz));
        assert!(log_file(&root, "crash-reports/crash-1.txt").is_some());
        for bad in ["logs/../options.txt", "logs/", "saves/x.log", "logs/a/b.log", "logs/..\\x.log", "logs/x.json"] {
            assert_eq!(log_file(&root, bad), None, "{bad}");
        }
        assert!(read_tail(&root.join("logs").join("none.log")).unwrap().is_none());
        let _ = std::fs::remove_dir_all(&root);
    }

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
        // a crash report: the exception opens the red part, the blank line ends it
        let report = "Description: Rendering overlay\n\n\
                      java.lang.NullPointerException: Cannot invoke x\n\
                      \tat a.b.C.d(C.java:1)\n\
                      \n\
                      A detailed walkthrough of the error";
        let s: Vec<_> = classify(report).iter().map(|l| l.stream.clone()).collect();
        assert_eq!(s, ["out", "out", "err", "err", "out", "out"]);
        assert!(throwable("net.foo.Bar$BadError") && !throwable("Error: plain") && !throwable("Exception"));
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
        let win = redact("C:/Users/alex/a and C:\\Users\\alex\\b and \"C:\\\\Users\\\\alex\"", Some("C:\\Users\\alex"));
        assert!(!win.contains("alex"), "{win}");
    }
}
