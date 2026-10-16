//! Clips, replays and screen recordings across every instance
//! (`<instance>/clips/`, `<instance>/replays/` and `<instance>/videos/`,
//! written by the Dusk client), newest first, with the details each carries
//! (an `.mcpr`'s duration, server, game version and thumbnail; an `.mp4`'s
//! length and the frame the client cached for it) and delete-to-trash.
//!
//! Paths coming back from the UI are only trusted after [`resolve`] has
//! confirmed they name an `.mcpr` directly inside clips or replays, or an
//! `.mp4` directly inside videos.

use crate::appstate::AppState;
use serde::Serialize;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;
use tauri::State;

const KINDS: [&str; 2] = ["clips", "replays"];
const VIDEOS: &str = "videos";
/// A thumbnail bigger than this isn't one the client wrote.
const THUMB_MAX: u64 = 4 * 1024 * 1024;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Recording {
    pub path: String,
    pub name: String,
    /// "clip", "replay" or "video"
    pub kind: String,
    pub profile_id: String,
    pub profile_name: String,
    /// unix millis (file modified time)
    pub recorded_at: u64,
    pub size: u64,
    pub duration_ms: u64,
    /// the server address, or "" for singleplayer
    pub server: String,
    pub mc_version: String,
}

fn has_ext(path: &Path, ext: &str) -> bool {
    path.extension().and_then(|e| e.to_str()).is_some_and(|e| e.eq_ignore_ascii_case(ext))
}

pub(crate) fn is_mcpr(path: &Path) -> bool {
    has_ext(path, "mcpr")
}

/// `path` checked to be an `.mcpr` directly inside an instance's clips or
/// replays folder, or an `.mp4` directly inside its videos folder; answers
/// the canonical path.
pub(crate) fn resolve(state: &AppState, path: &str) -> Result<PathBuf, String> {
    let not_found = || "That recording isn't there any more.".to_string();
    let path = std::fs::canonicalize(path).map_err(|_| not_found())?;
    let profiles_root = std::fs::canonicalize(state.data_dir.join("profiles")).map_err(|_| not_found())?;
    let rel = path.strip_prefix(&profiles_root).map_err(|_| not_found())?;
    let parts: Vec<_> = rel.components().collect();
    let in_replays = parts.len() == 3 && KINDS.iter().any(|k| parts[1].as_os_str() == *k) && is_mcpr(&path);
    let in_videos = parts.len() == 3 && parts[1].as_os_str() == VIDEOS && has_ext(&path, "mp4");
    if !(in_replays || in_videos) || !path.is_file() {
        return Err(not_found());
    }
    Ok(path)
}

/// `metaData.json` from inside the recording.
fn read_meta(path: &Path) -> Option<serde_json::Value> {
    let file = std::fs::File::open(path).ok()?;
    let mut zip = zip::ZipArchive::new(file).ok()?;
    let mut entry = zip.by_name("metaData.json").ok()?;
    let mut text = String::new();
    entry.read_to_string(&mut text).ok()?;
    serde_json::from_str(&text).ok()
}

/// An `.mp4`'s length from its `moov/mvhd` box, which the client's
/// faststart remux puts at the front.
fn mp4_duration_ms(path: &Path) -> Option<u64> {
    use std::io::{Seek, SeekFrom};
    let mut f = std::fs::File::open(path).ok()?;
    let len = f.metadata().ok()?.len();
    // walk boxes: (end of the parent, path of names still to find)
    let mut end = len;
    let mut at = 0u64;
    let mut want: &[&[u8; 4]] = &[b"moov", b"mvhd"];
    while at + 8 <= end {
        f.seek(SeekFrom::Start(at)).ok()?;
        let mut head = [0u8; 8];
        f.read_exact(&mut head).ok()?;
        let mut size = u32::from_be_bytes(head[0..4].try_into().ok()?) as u64;
        let mut body = at + 8;
        if size == 1 {
            let mut big = [0u8; 8];
            f.read_exact(&mut big).ok()?;
            size = u64::from_be_bytes(big);
            body += 8;
        } else if size == 0 {
            size = end - at;
        }
        if size < body - at || at + size > end {
            return None;
        }
        if &head[4..8] == want[0] {
            if want.len() == 1 {
                let mut v = [0u8; 4];
                f.read_exact(&mut v).ok()?;
                let (scale, dur) = if v[0] == 1 {
                    let mut b = [0u8; 28];
                    f.read_exact(&mut b).ok()?;
                    (u32::from_be_bytes(b[16..20].try_into().ok()?) as u64, u64::from_be_bytes(b[20..28].try_into().ok()?))
                } else {
                    let mut b = [0u8; 16];
                    f.read_exact(&mut b).ok()?;
                    (u32::from_be_bytes(b[8..12].try_into().ok()?) as u64, u32::from_be_bytes(b[12..16].try_into().ok()?) as u64)
                };
                return (scale > 0).then(|| dur.saturating_mul(1000) / scale);
            }
            want = &want[1..];
            end = at + size;
            at = body;
            continue;
        }
        at += size;
    }
    None
}

/// Where the client keeps the frame it shows for `video`.
fn video_thumb_file(video: &Path) -> Option<PathBuf> {
    let name = video.file_name()?.to_string_lossy().to_string();
    Some(video.parent()?.join(".thumbs").join(format!("{name}.png")))
}

fn modified_ms(meta: &std::fs::Metadata) -> u64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[tauri::command]
pub async fn list_recordings(state: State<'_, AppState>) -> Result<Vec<Recording>, String> {
    let profiles: Vec<_> = state.profiles.lock().unwrap().profiles.clone();
    let data_dir = state.data_dir.clone();
    // opening every zip is disk work; keep it off the main thread
    tauri::async_runtime::spawn_blocking(move || {
        let mut out = Vec::new();
        for p in profiles {
            let root = p.dirs(&data_dir).root;
            for kind in KINDS {
                let Ok(entries) = std::fs::read_dir(root.join(kind)) else { continue };
                for e in entries.flatten() {
                    let path = e.path();
                    if !is_mcpr(&path) {
                        continue;
                    }
                    let Ok(meta) = e.metadata() else { continue };
                    if !meta.is_file() {
                        continue;
                    }
                    let recorded_at = modified_ms(&meta);
                    let info = read_meta(&path).unwrap_or_default();
                    let text = |k: &str| info.get(k).and_then(|v| v.as_str()).unwrap_or("").to_string();
                    let singleplayer = info.get("singleplayer").and_then(|v| v.as_bool()).unwrap_or(false);
                    out.push(Recording {
                        path: path.to_string_lossy().to_string(),
                        name: e.file_name().to_string_lossy().to_string(),
                        kind: kind.trim_end_matches('s').to_string(),
                        profile_id: p.id.clone(),
                        profile_name: p.name.clone(),
                        recorded_at,
                        size: meta.len(),
                        duration_ms: info.get("duration").and_then(|v| v.as_u64()).unwrap_or(0),
                        server: if singleplayer { String::new() } else { text("serverName") },
                        mc_version: text("mcversion"),
                    });
                }
            }
            let Ok(entries) = std::fs::read_dir(root.join(VIDEOS)) else { continue };
            for e in entries.flatten() {
                let path = e.path();
                if !has_ext(&path, "mp4") {
                    continue;
                }
                let Ok(meta) = e.metadata() else { continue };
                if !meta.is_file() {
                    continue;
                }
                out.push(Recording {
                    name: e.file_name().to_string_lossy().to_string(),
                    kind: "video".to_string(),
                    profile_id: p.id.clone(),
                    profile_name: p.name.clone(),
                    recorded_at: modified_ms(&meta),
                    size: meta.len(),
                    duration_ms: mp4_duration_ms(&path).unwrap_or(0),
                    server: String::new(),
                    mc_version: String::new(),
                    path: path.to_string_lossy().to_string(),
                });
            }
        }
        out.sort_by_key(|r| std::cmp::Reverse(r.recorded_at));
        out
    })
    .await
    .map_err(|e| e.to_string())
}

/// The recording's `thumb.png` (a video's cached frame) as a data URL, or
/// None when it has none.
#[tauri::command]
pub async fn recording_thumb(state: State<'_, AppState>, path: String) -> Result<Option<String>, String> {
    let path = resolve(&state, &path)?;
    tauri::async_runtime::spawn_blocking(move || {
        use base64::Engine;
        if has_ext(&path, "mp4") {
            let thumb = video_thumb_file(&path)?;
            if std::fs::metadata(&thumb).ok()?.len() > THUMB_MAX {
                return None;
            }
            let bytes = std::fs::read(thumb).ok()?;
            return Some(format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes)));
        }
        let file = std::fs::File::open(&path).ok()?;
        let mut zip = zip::ZipArchive::new(file).ok()?;
        let entry = zip.by_name("thumb.png").ok()?;
        if entry.size() > THUMB_MAX {
            return None;
        }
        let mut bytes = Vec::with_capacity(entry.size() as usize);
        entry.take(THUMB_MAX).read_to_end(&mut bytes).ok()?;
        Some(format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes)))
    })
    .await
    .map_err(|e| e.to_string())
}

/// Move a recording to the OS trash (recoverable), never a hard delete.
#[tauri::command(async)]
pub fn delete_recording(state: State<AppState>, path: String) -> Result<(), String> {
    let path = resolve(&state, &path)?;
    trash::delete(&path).map_err(|e| format!("Couldn't move it to the trash: {e}"))?;
    if let Some(thumb) = video_thumb_file(&path).filter(|_| has_ext(&path, "mp4")) {
        let _ = std::fs::remove_file(thumb);
    }
    Ok(())
}

/// Play a screen recording in the system's player.
#[tauri::command]
pub fn open_recording(app: tauri::AppHandle, state: State<AppState>, path: String) -> Result<(), String> {
    let path = resolve(&state, &path)?;
    use tauri_plugin_opener::OpenerExt;
    app.opener()
        .open_path(path.to_string_lossy().to_string(), None::<&str>)
        .map_err(|e| format!("Couldn't open it: {e}"))
}

/// Show the file selected in Finder / Explorer.
#[tauri::command]
pub fn reveal_recording(state: State<AppState>, path: String) -> Result<(), String> {
    let path = resolve(&state, &path)?;
    tauri_plugin_opener::reveal_item_in_dir(path).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn boxed(name: &[u8; 4], body: &[u8]) -> Vec<u8> {
        let mut b = ((body.len() + 8) as u32).to_be_bytes().to_vec();
        b.extend_from_slice(name);
        b.extend_from_slice(body);
        b
    }

    #[test]
    fn reads_mp4_length_from_mvhd() {
        let mut mvhd = vec![0u8; 4]; // version 0, flags
        mvhd.extend_from_slice(&[0; 8]); // created, modified
        mvhd.extend_from_slice(&1000u32.to_be_bytes());
        mvhd.extend_from_slice(&30_500u32.to_be_bytes());
        mvhd.extend_from_slice(&[0; 80]);
        let mut file = boxed(b"ftyp", b"isom\0\0\x02\0");
        file.extend(boxed(b"moov", &[boxed(b"mvhd", &mvhd), boxed(b"trak", &[0; 16])].concat()));
        file.extend(boxed(b"mdat", &[0; 64]));
        let p = std::env::temp_dir().join(format!("dusk-mp4-{}.mp4", std::process::id()));
        std::fs::write(&p, file).unwrap();
        let got = mp4_duration_ms(&p);
        let _ = std::fs::remove_file(&p);
        assert_eq!(got, Some(30_500));
    }
}
