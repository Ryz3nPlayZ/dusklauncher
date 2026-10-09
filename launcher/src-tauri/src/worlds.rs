//! Singleplayer worlds in an instance's `saves/`: backups, import and delete.
//!
//! Backups follow the game's own "Make Backup" button — a zip in the
//! instance's `backups/` named `<yyyy-MM-dd_HH-mm-ss>_<world>.zip` with the
//! world folder at its root — so unzipping one into `saves/` restores it.
//! Deleting moves the world to the OS trash, never for good. Importing takes
//! a world zip (a backup, or a map off the web — the world folder at the
//! root or a few folders down) or a world folder, and adds it under a name
//! the instance doesn't use yet.

use crate::appstate::AppState;
use std::io::Write;
use std::path::{Path, PathBuf};
use serde::Serialize;
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;

/// The world folder `name` names in this profile, or an error when it isn't
/// a plain world directly inside `saves/`.
fn world_dir(state: &AppState, profile_id: &str, name: &str) -> Result<(PathBuf, PathBuf), String> {
    let root = {
        let store = state.profiles.lock().unwrap();
        let profile = store
            .profiles
            .iter()
            .find(|p| p.id == profile_id)
            .ok_or("profile not found")?;
        profile.dirs(&state.data_dir).root
    };
    let dir = root.join("saves").join(name);
    if name.is_empty() || name.contains(['/', '\\']) || name == "." || name == ".." || !dir.join("level.dat").is_file() {
        return Err("That world isn't in this instance.".into());
    }
    Ok((root, dir))
}

/// A world can't be zipped or trashed while its instance is open: the game
/// holds `session.lock` and rewrites region files under us.
pub(crate) async fn ensure_closed(state: &AppState, profile_id: &str) -> Result<(), String> {
    if state.running_game.lock().await.as_ref().is_some_and(|g| g.profile_id == profile_id) {
        return Err("Close the game first.".into());
    }
    Ok(())
}

/// Zip a world into `<instance>/backups/`. Resolves to the zip's file name.
#[tauri::command]
pub async fn backup_world(state: State<'_, AppState>, profile_id: String, name: String) -> Result<String, String> {
    let (root, dir) = world_dir(&state, &profile_id, &name)?;
    ensure_closed(&state, &profile_id).await?;
    let file_name = format!("{}_{}.zip", chrono::Local::now().format("%Y-%m-%d_%H-%M-%S"), name);
    let backups = root.join("backups");
    let out = backups.join(&file_name);
    tokio::task::spawn_blocking(move || -> Result<(), String> {
        std::fs::create_dir_all(&backups).map_err(|e| e.to_string())?;
        let result = write_zip(&dir, &name, &out);
        if result.is_err() {
            let _ = std::fs::remove_file(&out);
        }
        result
    })
    .await
    .map_err(|e| e.to_string())??;
    tracing::info!(world = %file_name, "world backed up");
    Ok(file_name)
}

/// Backups the launcher makes on its own go in `backups/auto/`, apart from
/// the ones made by hand, so pruning only ever deletes its own.
const AUTO_BACKUPS: &str = "auto";

/// After a session: zip every world the game saved since `since` into
/// `backups/auto/`, then keep only the newest `keep` there for each.
/// Returns the worlds backed up.
pub(crate) fn auto_backup(root: &Path, since: std::time::SystemTime, keep: usize) -> Vec<String> {
    let dir = root.join("backups").join(AUTO_BACKUPS);
    let Ok(entries) = std::fs::read_dir(root.join("saves")) else { return Vec::new() };
    let mut done = Vec::new();
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        // the game rewrites level.dat on every save and on quit
        let played = std::fs::metadata(entry.path().join("level.dat"))
            .and_then(|m| m.modified())
            .is_ok_and(|t| t >= since);
        if name.starts_with('.') || !played {
            continue;
        }
        if std::fs::create_dir_all(&dir).is_err() {
            break;
        }
        let out = dir.join(format!("{}_{name}.zip", chrono::Local::now().format("%Y-%m-%d_%H-%M-%S")));
        if let Err(e) = write_zip(&entry.path(), &name, &out) {
            let _ = std::fs::remove_file(&out);
            tracing::warn!(world = %name, "auto backup failed: {e}");
            continue;
        }
        prune_backups(&dir, &name, keep);
        done.push(name);
    }
    done
}

/// Delete all but the newest `keep` of one world's backups in `dir`. Names
/// start with a sortable timestamp, so the newest sort last.
fn prune_backups(dir: &Path, world: &str, keep: usize) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    let suffix = format!("_{world}.zip");
    let mut mine: Vec<String> = entries
        .flatten()
        .map(|e| e.file_name().to_string_lossy().to_string())
        // `yyyy-MM-dd_HH-mm-ss` then `_<world>.zip`: a world named `b`
        // doesn't claim `..._a_b.zip`
        .filter(|f| f.len() == 19 + suffix.len() && f.ends_with(&suffix))
        .collect();
    mine.sort();
    let extra = mine.len().saturating_sub(keep);
    for old in &mine[..extra] {
        let _ = std::fs::remove_file(dir.join(old));
    }
}

/// One zip in an instance's `backups/` (made by hand) or `backups/auto/`
/// (made after a session).
#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct WorldBackup {
    /// where it is under `backups/`: `<zip>` or `auto/<zip>`
    pub file: String,
    /// the world it was made from, read off its name
    pub world: String,
    /// when it was written, ms since the epoch
    pub made: u64,
    pub size: u64,
    pub auto: bool,
}

/// The instance's world backups, newest first.
#[tauri::command]
pub fn list_world_backups(state: State<AppState>, profile_id: String) -> Result<Vec<WorldBackup>, String> {
    Ok(read_backups(&crate::servers::profile_root(&state, &profile_id)?.join("backups")))
}

fn read_backups(dir: &Path) -> Vec<WorldBackup> {
    let mut out = Vec::new();
    for auto in [false, true] {
        let Ok(entries) = std::fs::read_dir(if auto { dir.join(AUTO_BACKUPS) } else { dir.to_path_buf() }) else { continue };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            let Ok(meta) = entry.metadata() else { continue };
            if !meta.is_file() || name.starts_with('.') || !name.to_ascii_lowercase().ends_with(".zip") {
                continue;
            }
            out.push(WorldBackup {
                file: if auto { format!("{AUTO_BACKUPS}/{name}") } else { name.clone() },
                world: world_name("", &name[..name.len() - 4]),
                made: meta
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map_or(0, |d| d.as_millis() as u64),
                size: meta.len(),
                auto,
            });
        }
    }
    out.sort_by(|a, b| b.made.cmp(&a.made).then_with(|| b.file.cmp(&a.file)));
    out
}

/// Unzip a backup into `saves/` as a world of its own, listed as
/// "<name> (backup <date>)". The world it was made from is left as it is,
/// so restoring never loses anything. Resolves to the new world's folder.
#[tauri::command]
pub async fn restore_world_backup(state: State<'_, AppState>, profile_id: String, file: String) -> Result<String, String> {
    let root = crate::servers::profile_root(&state, &profile_id)?;
    ensure_closed(&state, &profile_id).await?;
    let zip = backup_path(&root, &file)?;
    let saves = root.join("saves");
    let folder = tokio::task::spawn_blocking(move || restore_into(&zip, &saves))
        .await
        .map_err(|e| e.to_string())??;
    tracing::info!(backup = %file, world = %folder, "world backup restored");
    Ok(folder)
}

/// A file name from [`list_world_backups`], found inside `backups/`: a zip
/// directly in it or in `auto/`, never anywhere else.
fn backup_path(root: &Path, file: &str) -> Result<PathBuf, String> {
    let (sub, name) = match file.split_once('/') {
        Some((AUTO_BACKUPS, name)) => (Some(AUTO_BACKUPS), name),
        Some(_) => return Err("That isn't one of this instance's backups.".into()),
        None => (None, file),
    };
    if name.is_empty() || name.starts_with('.') || name.contains(['/', '\\']) || !name.to_ascii_lowercase().ends_with(".zip") {
        return Err("That isn't one of this instance's backups.".into());
    }
    let mut path = root.join("backups");
    if let Some(sub) = sub {
        path.push(sub);
    }
    path.push(name);
    if !path.is_file() {
        return Err("That backup isn't there any more.".into());
    }
    Ok(path)
}

fn restore_into(zip: &Path, saves: &Path) -> Result<String, String> {
    std::fs::create_dir_all(saves).map_err(|e| e.to_string())?;
    let folder = unzip_world(zip, saves)?.ok_or("That backup has no world in it.")?;
    // named apart from the world it was made from, which the game lists too
    let dir = saves.join(&folder);
    if let Some(level) = level_info(&dir.join("level.dat")).level_name {
        let stem = zip.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
        let label = match backup_stamp(&stem) {
            Some(t) => format!("{level} (backup {} {})", &t[..10], t[11..16].replace('-', ":")),
            None => format!("{level} (backup)"),
        };
        if let Ok(label) = clean_level_name(&label) {
            if let Err(e) = write_level_name(&dir, &label) {
                tracing::warn!(world = %folder, "restored, but couldn't rename it: {e}");
            }
        }
    }
    Ok(folder)
}

fn write_zip(dir: &Path, name: &str, out: &Path) -> Result<(), String> {
    let file = std::fs::File::create(out).map_err(|e| format!("Couldn't write the backup: {e}"))?;
    let mut zip = zip::ZipWriter::new(file);
    let opts = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .large_file(true);
    add_tree(&mut zip, dir, dir, name, opts)?;
    zip.finish().map_err(|e| e.to_string())?;
    Ok(())
}

fn add_tree(
    zip: &mut zip::ZipWriter<std::fs::File>,
    base: &Path,
    dir: &Path,
    prefix: &str,
    opts: zip::write::SimpleFileOptions,
) -> Result<(), String> {
    let rd = std::fs::read_dir(dir).map_err(|e| e.to_string())?;
    for entry in rd.flatten() {
        let path = entry.path();
        let Ok(kind) = entry.file_type() else { continue };
        // symlinks are skipped: a backup is the world's own files only
        if kind.is_symlink() {
            continue;
        }
        let rel = path.strip_prefix(base).map_err(|e| e.to_string())?;
        let entry_name = format!("{prefix}/{}", rel.to_string_lossy().replace('\\', "/"));
        if kind.is_dir() {
            zip.add_directory(format!("{entry_name}/"), opts).map_err(|e| e.to_string())?;
            add_tree(zip, base, &path, prefix, opts)?;
        } else if entry.file_name() != "session.lock" {
            zip.start_file(entry_name, opts).map_err(|e| e.to_string())?;
            let mut src = std::fs::File::open(&path).map_err(|e| e.to_string())?;
            std::io::copy(&mut src, zip).map_err(|e| e.to_string())?;
        }
    }
    zip.flush().map_err(|e| e.to_string())
}

/// Move a world to the OS trash.
#[tauri::command]
pub async fn delete_world(state: State<'_, AppState>, profile_id: String, name: String) -> Result<(), String> {
    let (_, dir) = world_dir(&state, &profile_id, &name)?;
    ensure_closed(&state, &profile_id).await?;
    tokio::task::spawn_blocking(move || trash::delete(&dir))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| format!("Couldn't move it to the trash: {e}"))
}

/// What a world's `level.dat` says about it, for its row in the WORLDS tab.
#[derive(Serialize, Default, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LevelInfo {
    /// the name the game lists it under (the folder can differ)
    pub level_name: Option<String>,
    /// "survival", "creative", "adventure" or "spectator"
    pub game_mode: Option<&'static str>,
    pub hardcore: bool,
    pub cheats: bool,
    /// the version it was last played in, e.g. "1.21.11"
    pub version: Option<String>,
    /// as text: JS numbers can't hold every seed
    pub seed: Option<String>,
}

/// `level.dat` (gzipped NBT) → its `Data` fields. Default when unreadable.
pub(crate) fn level_info(level_dat: &Path) -> LevelInfo {
    let Some(bytes) = read_gz(level_dat) else { return LevelInfo::default() };
    let mut info = parse_level(&bytes);
    if info.seed.is_none() {
        // 26.1+ moved the seed out to its own file
        let settings = level_dat.with_file_name("data").join("minecraft").join("world_gen_settings.dat");
        info.seed = read_gz(&settings)
            .and_then(|b| crate::servers::read_nbt(&b))
            .and_then(|r| r.get("data")?.get("seed")?.int())
            .map(|s| s.to_string());
    }
    info
}

fn read_gz(path: &Path) -> Option<Vec<u8>> {
    use std::io::Read;
    let file = std::fs::File::open(path).ok()?;
    let mut bytes = Vec::new();
    // these are a few KB; the cap guards against a corrupt one
    flate2::read::GzDecoder::new(file).take(8 << 20).read_to_end(&mut bytes).ok()?;
    Some(bytes)
}

/// RENAME on a world: the name the game lists it under (`Data.LevelName`),
/// as the game's own Edit World screen sets it; the folder keeps its name.
/// The old `level.dat` is kept as `level.dat_old`, as the game does on save.
#[tauri::command]
pub async fn rename_world(state: State<'_, AppState>, profile_id: String, name: String, level_name: String) -> Result<(), String> {
    let (_, dir) = world_dir(&state, &profile_id, &name)?;
    ensure_closed(&state, &profile_id).await?;
    let level_name = clean_level_name(&level_name)?;
    tokio::task::spawn_blocking(move || write_level_name(&dir, &level_name))
        .await
        .map_err(|e| e.to_string())?
}

/// COPY on a world, like Prism's: a duplicate listed as `level_name`, in a
/// folder named after it. Resolves to the new folder's name.
#[tauri::command]
pub async fn duplicate_world(state: State<'_, AppState>, profile_id: String, name: String, level_name: String) -> Result<String, String> {
    let (root, dir) = world_dir(&state, &profile_id, &name)?;
    ensure_closed(&state, &profile_id).await?;
    let level_name = clean_level_name(&level_name)?;
    let saves = root.join("saves");
    let folder = tokio::task::spawn_blocking(move || duplicate_into(&dir, &saves, &level_name))
        .await
        .map_err(|e| e.to_string())??;
    tracing::info!(from = %name, to = %folder, "world copied");
    Ok(folder)
}

fn duplicate_into(src: &Path, saves: &Path, level_name: &str) -> Result<String, String> {
    let folder = free_name(saves, &clean_name(level_name));
    // dot-named while it fills, so the world list never shows half a copy
    let tmp = saves.join(format!(".importing-{folder}"));
    let result = copy_tree(src, &tmp)
        .map_err(|e| format!("Couldn't copy the world: {e}"))
        .and_then(|()| write_level_name(&tmp, level_name))
        .and_then(|()| std::fs::rename(&tmp, saves.join(&folder)).map_err(|e| format!("Couldn't copy the world: {e}")));
    if result.is_err() {
        let _ = std::fs::remove_dir_all(&tmp);
    }
    result.map(|()| folder)
}

/// A name for `Data.LevelName`: trimmed, at most 100 characters, and never
/// empty. NUL and characters past U+FFFF are written differently in Java's
/// modified UTF-8; they're left out so plain UTF-8 is exactly right.
fn clean_level_name(raw: &str) -> Result<String, String> {
    let name: String = raw.trim().chars().filter(|c| *c != '\0' && (*c as u32) <= 0xFFFF).take(100).collect();
    if name.is_empty() {
        return Err("The name can't be empty.".into());
    }
    Ok(name)
}

/// Set the world in `dir`'s `Data.LevelName`, keeping the old `level.dat`
/// as `level.dat_old` the way the game does on save.
fn write_level_name(dir: &Path, level_name: &str) -> Result<(), String> {
    let path = dir.join("level.dat");
    let bytes = read_gz(&path).ok_or("Couldn't read the world's level.dat.")?;
    let renamed = set_level_name(&bytes, level_name).ok_or("The world's level.dat has no name to change.")?;
    let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    gz.write_all(&renamed).map_err(|e| e.to_string())?;
    let out = gz.finish().map_err(|e| e.to_string())?;
    let tmp = dir.join("level.dat_new");
    std::fs::write(&tmp, out).map_err(|e| e.to_string())?;
    let _ = std::fs::copy(&path, dir.join("level.dat_old"));
    std::fs::rename(&tmp, &path).map_err(|e| e.to_string())
}

/// `bytes` (uncompressed level.dat) with `Data.LevelName` swapped for
/// `name` and every other byte left as it was.
fn set_level_name(bytes: &[u8], name: &str) -> Option<Vec<u8>> {
    let (start, end) = level_name_span(bytes)?;
    let len = u16::try_from(name.len()).ok()?;
    let mut out = Vec::with_capacity(bytes.len() + name.len());
    out.extend_from_slice(&bytes[..start]);
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(name.as_bytes());
    out.extend_from_slice(&bytes[end..]);
    Some(out)
}

/// Where `Data.LevelName`'s string (length prefix included) sits.
fn level_name_span(b: &[u8]) -> Option<(usize, usize)> {
    // the root: a compound with a (usually empty) name
    if *b.first()? != 10 {
        return None;
    }
    let i = nbt_skip_string(b, 1)?;
    let fields = |b: &[u8], mut i: usize, want: &str, want_kind: u8| -> Option<(usize, usize)> {
        loop {
            let kind = *b.get(i)?;
            if kind == 0 {
                return None;
            }
            let name_at = i + 1;
            let value_at = nbt_skip_string(b, name_at)?;
            let n = u16::from_be_bytes(b.get(name_at..name_at + 2)?.try_into().ok()?) as usize;
            if kind == want_kind && b.get(name_at + 2..name_at + 2 + n)? == want.as_bytes() {
                return Some((value_at, nbt_skip(b, value_at, kind, 0)?));
            }
            i = nbt_skip(b, value_at, kind, 0)?;
        }
    };
    let (data, _) = fields(b, i, "Data", 10)?;
    fields(b, data, "LevelName", 8)
}

fn nbt_skip_string(b: &[u8], i: usize) -> Option<usize> {
    let n = u16::from_be_bytes(b.get(i..i + 2)?.try_into().ok()?) as usize;
    let end = i + 2 + n;
    (end <= b.len()).then_some(end)
}

/// The end of a `kind` payload starting at `i`.
fn nbt_skip(b: &[u8], i: usize, kind: u8, depth: u32) -> Option<usize> {
    if depth > 512 {
        return None;
    }
    let count = |i: usize| -> Option<usize> { usize::try_from(i32::from_be_bytes(b.get(i..i + 4)?.try_into().ok()?)).ok() };
    let end = match kind {
        1 => i + 1,
        2 => i + 2,
        3 | 5 => i + 4,
        4 | 6 => i + 8,
        7 => i + 4 + count(i)?,
        8 => return nbt_skip_string(b, i),
        9 => {
            let elem = *b.get(i)?;
            let n = count(i + 1)?;
            let mut j = i + 5;
            for _ in 0..n {
                j = nbt_skip(b, j, elem, depth + 1)?;
            }
            j
        }
        10 => {
            let mut j = i;
            loop {
                let k = *b.get(j)?;
                if k == 0 {
                    break j + 1;
                }
                j = nbt_skip(b, nbt_skip_string(b, j + 1)?, k, depth + 1)?;
            }
        }
        11 => i + 4 + count(i)?.checked_mul(4)?,
        12 => i + 4 + count(i)?.checked_mul(8)?,
        _ => return None,
    };
    (end <= b.len()).then_some(end)
}

fn parse_level(bytes: &[u8]) -> LevelInfo {
    use crate::servers::Tag;
    let root = crate::servers::read_nbt(bytes);
    let Some(data) = root.as_ref().and_then(|r| r.get("Data")) else { return LevelInfo::default() };
    let int = |key: &str| data.get(key).and_then(Tag::int);
    LevelInfo {
        level_name: data.get("LevelName").and_then(Tag::str).map(str::to_string).filter(|n| !n.is_empty()),
        game_mode: int("GameType").and_then(|g| match g {
            0 => Some("survival"),
            1 => Some("creative"),
            2 => Some("adventure"),
            3 => Some("spectator"),
            _ => None,
        }),
        // 26.1+ keeps it with the difficulty
        hardcore: int("hardcore")
            .or_else(|| data.get("difficulty_settings")?.get("hardcore")?.int())
            == Some(1),
        cheats: int("allowCommands") == Some(1),
        version: data
            .get("Version")
            .and_then(|v| v.get("Name"))
            .and_then(Tag::str)
            .map(str::to_string),
        // 1.16+ keeps it under WorldGenSettings; older worlds at the top
        seed: data
            .get("WorldGenSettings")
            .and_then(|w| w.get("seed"))
            .or_else(|| data.get("RandomSeed"))
            .and_then(Tag::int)
            .map(|s| s.to_string()),
    }
}

/// What a zip or folder holds, world-wise: the path prefix of its shallowest
/// `level.dat` ("" at the root, "Map/" one folder down). macOS's
/// `__MACOSX/` shadow copies don't count.
fn world_prefix<'a>(names: impl Iterator<Item = &'a str>) -> Option<String> {
    names
        .filter(|n| !n.starts_with("__MACOSX/"))
        .filter_map(|n| n.strip_suffix("level.dat").filter(|p| p.is_empty() || p.ends_with('/')))
        .min_by_key(|p| p.matches('/').count())
        .map(str::to_string)
}

/// A folder name every OS takes: no path or reserved characters, no
/// trailing dots or spaces (Windows drops them).
fn clean_name(raw: &str) -> String {
    let cleaned: String = raw
        .chars()
        .map(|c| if c.is_control() || r#"<>:"/\|?*"#.contains(c) { '_' } else { c })
        .collect();
    let cleaned = cleaned.trim().trim_end_matches(['.', ' ']).to_string();
    if cleaned.is_empty() || cleaned.starts_with('.') { "World".into() } else { cleaned }
}

/// `name`, or `name (1)`, `name (2)`… — the first one `saves/` doesn't have.
fn free_name(saves: &Path, name: &str) -> String {
    let mut out = name.to_string();
    let mut n = 1;
    while saves.join(&out).exists() {
        out = format!("{name} ({n})");
        n += 1;
    }
    out
}

/// The name a world should land under: its folder's, or — for a zip with the
/// world at its root — the zip's, less a backup's `yyyy-MM-dd_HH-mm-ss_`.
fn world_name(prefix: &str, file_stem: &str) -> String {
    let folder = prefix.trim_end_matches('/').rsplit('/').next().unwrap_or("");
    if !folder.is_empty() {
        return clean_name(folder);
    }
    clean_name(if backup_stamp(file_stem).is_some() { &file_stem[20..] } else { file_stem })
}

/// The `yyyy-MM-dd_HH-mm-ss` a backup's name starts with, when it is one of
/// ours (or the game's): the stamp, an `_`, then the world.
fn backup_stamp(file_stem: &str) -> Option<&str> {
    let b = file_stem.as_bytes();
    let stamped = b.len() > 20
        && b[19] == b'_'
        && b[..19].iter().enumerate().all(|(i, c)| match i {
            4 | 7 => *c == b'-',
            10 => *c == b'_',
            13 | 16 => *c == b'-',
            _ => c.is_ascii_digit(),
        });
    stamped.then(|| &file_stem[..19])
}

fn unzip_world(zip_path: &Path, dest: &Path) -> Result<Option<String>, String> {
    let file = std::fs::File::open(zip_path).map_err(|e| e.to_string())?;
    let mut zip = zip::ZipArchive::new(file).map_err(|_| "That zip can't be read.".to_string())?;
    let Some(prefix) = world_prefix(zip.file_names()) else { return Ok(None) };
    let stem = zip_path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let name = free_name(dest, &world_name(&prefix, &stem));
    let tmp = dest.join(format!(".importing-{name}"));
    let result = (|| -> Result<(), String> {
        for i in 0..zip.len() {
            let mut entry = zip.by_index(i).map_err(|e| e.to_string())?;
            let Some(rel) = entry.name().strip_prefix(prefix.as_str()).map(str::to_string) else { continue };
            // enclosed_name-style check: no absolute paths, no `..`
            let rel_path = Path::new(&rel);
            if rel.is_empty()
                || rel_path.components().any(|c| !matches!(c, std::path::Component::Normal(_)))
                || rel_path.file_name().is_some_and(|n| n == "session.lock")
            {
                continue;
            }
            let out = tmp.join(rel_path);
            if entry.is_dir() {
                std::fs::create_dir_all(&out).map_err(|e| e.to_string())?;
            } else {
                if let Some(parent) = out.parent() {
                    std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
                }
                let mut f = std::fs::File::create(&out).map_err(|e| e.to_string())?;
                std::io::copy(&mut entry, &mut f).map_err(|e| e.to_string())?;
            }
        }
        std::fs::rename(&tmp, dest.join(&name)).map_err(|e| e.to_string())
    })();
    if result.is_err() {
        let _ = std::fs::remove_dir_all(&tmp);
    }
    result.map(|()| Some(name))
}

fn copy_tree(from: &Path, to: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for entry in std::fs::read_dir(from)?.flatten() {
        let kind = entry.file_type()?;
        let target = to.join(entry.file_name());
        if kind.is_dir() {
            copy_tree(&entry.path(), &target)?;
        } else if kind.is_file() && entry.file_name() != "session.lock" {
            std::fs::copy(entry.path(), target)?;
        }
    }
    Ok(())
}

fn copy_world(folder: &Path, dest: &Path) -> Result<Option<String>, String> {
    // the folder itself, or a lone world folder inside it
    let src = if folder.join("level.dat").is_file() {
        folder.to_path_buf()
    } else {
        let inner: Vec<_> = std::fs::read_dir(folder)
            .map_err(|e| e.to_string())?
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.join("level.dat").is_file())
            .collect();
        match inner.as_slice() {
            [one] => one.clone(),
            _ => return Ok(None),
        }
    };
    if src.starts_with(dest) {
        return Err("That world is already in this instance.".into());
    }
    let stem = src.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let name = free_name(dest, &clean_name(&stem));
    let tmp = dest.join(format!(".importing-{name}"));
    let result = copy_tree(&src, &tmp)
        .and_then(|()| std::fs::rename(&tmp, dest.join(&name)))
        .map_err(|e| format!("Couldn't copy the world: {e}"));
    if result.is_err() {
        let _ = std::fs::remove_dir_all(&tmp);
    }
    result.map(|()| Some(name))
}

/// Put the world a zip or folder holds into `saves/`. `None` when it holds no world.
pub(crate) fn import_into(saves: &Path, path: &Path) -> Result<Option<String>, String> {
    std::fs::create_dir_all(saves).map_err(|e| e.to_string())?;
    if path.is_dir() {
        copy_world(path, saves)
    } else if path.extension().is_some_and(|e| e.eq_ignore_ascii_case("zip")) {
        unzip_world(path, saves)
    } else {
        Ok(None)
    }
}

/// Does this zip hold a world (so it isn't a resource pack)?
pub(crate) fn zip_holds_world(zip: &zip::ZipArchive<std::fs::File>) -> bool {
    world_prefix(zip.file_names()).is_some()
}

#[derive(Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ImportedWorlds {
    /// the folder names the worlds landed under
    pub added: Vec<String>,
    /// file names that held no world
    pub skipped: Vec<String>,
}

fn saves_of(state: &AppState, profile_id: &str) -> Result<PathBuf, String> {
    Ok(crate::servers::profile_root(state, profile_id)?.join("saves"))
}

fn import_all(saves: &Path, paths: Vec<PathBuf>) -> Result<ImportedWorlds, String> {
    let mut out = ImportedWorlds::default();
    for path in paths {
        match import_into(saves, &path)? {
            Some(name) => out.added.push(name),
            None => out.skipped.push(path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()),
        }
    }
    Ok(out)
}

/// Worlds dropped onto the WORLDS tab: zips and folders.
#[tauri::command]
pub async fn import_world_paths(
    state: State<'_, AppState>,
    profile_id: String,
    paths: Vec<String>,
) -> Result<ImportedWorlds, String> {
    let saves = saves_of(&state, &profile_id)?;
    let paths = paths.into_iter().map(PathBuf::from).collect();
    tokio::task::spawn_blocking(move || import_all(&saves, paths)).await.map_err(|e| e.to_string())?
}

/// Pick world zips and add them. Empty when the picker is cancelled.
#[tauri::command]
pub async fn import_world(app: AppHandle, state: State<'_, AppState>, profile_id: String) -> Result<ImportedWorlds, String> {
    let saves = saves_of(&state, &profile_id)?;
    let Some(picked) = app.dialog().file().add_filter("World (ZIP)", &["zip"]).blocking_pick_files() else {
        return Ok(ImportedWorlds::default());
    };
    let paths = picked.into_iter().filter_map(|f| f.into_path().ok()).collect();
    tokio::task::spawn_blocking(move || import_all(&saves, paths)).await.map_err(|e| e.to_string())?
}

/* ── datapacks: a world's `datapacks/` ─────────────────────────────────── */

/// One pack in a world's `datapacks/`: a zip, or a folder with a `pack.mcmeta`.
#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Datapack {
    /// its name on disk, without a `.disabled`
    pub file: String,
    /// pack.mcmeta's description, formatting stripped
    pub description: Option<String>,
    pub enabled: bool,
    pub folder: bool,
    pub size: u64,
    pub icon: Option<String>,
}

fn datapacks_dir(state: &AppState, profile_id: &str, world: &str) -> Result<PathBuf, String> {
    Ok(world_dir(state, profile_id, world)?.1.join("datapacks"))
}

/// A pack's description as plain text: a string, or a text component
/// (`{"text":…,"extra":[…]}` or a list of them), less `§` codes.
fn plain_text(v: &serde_json::Value) -> String {
    let raw = match v {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Array(parts) => parts.iter().map(plain_text).collect(),
        serde_json::Value::Object(o) => {
            let mut s = o.get("text").and_then(|t| t.as_str()).unwrap_or("").to_string();
            if let Some(extra) = o.get("extra") {
                s.push_str(&plain_text(extra));
            }
            s
        }
        _ => String::new(),
    };
    let mut out = String::with_capacity(raw.len());
    let mut chars = raw.chars();
    while let Some(c) = chars.next() {
        if c == '§' {
            chars.next();
        } else {
            out.push(c);
        }
    }
    out
}

fn describe(meta: Option<Vec<u8>>, icon: Option<Vec<u8>>) -> (Option<String>, Option<String>) {
    use base64::Engine;
    let description = meta
        .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
        .and_then(|v| v.get("pack")?.get("description").map(plain_text))
        .map(|d| d.trim().to_string())
        .filter(|d| !d.is_empty());
    let icon = icon
        .filter(|b| b.len() <= 256 * 1024)
        .map(|b| format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(b)));
    (description, icon)
}

fn read_pack(path: &Path, file: String, enabled: bool) -> Option<Datapack> {
    if path.is_dir() {
        let meta = std::fs::read(path.join("pack.mcmeta")).ok()?;
        let (description, icon) = describe(Some(meta), std::fs::read(path.join("pack.png")).ok());
        return Some(Datapack { file, description, enabled, folder: true, size: crate::commands::dir_size(path), icon });
    }
    let size = std::fs::metadata(path).ok()?.len();
    let mut zip = std::fs::File::open(path).ok().and_then(|f| zip::ZipArchive::new(f).ok());
    let mut entry = |name: &str, cap| zip.as_mut().and_then(|z| crate::mods::read_zip_entry(z, name, cap));
    let meta = entry("pack.mcmeta", 64 * 1024);
    let icon = entry("pack.png", 256 * 1024);
    let (description, icon) = describe(meta, icon);
    Some(Datapack { file, description, enabled, folder: false, size, icon })
}

/// The packs in a world, by name. A zip named `x.zip.disabled` is off: the
/// game only reads `.zip` files, and drops it from the world until it's back.
fn list_packs(dir: &Path) -> Vec<Datapack> {
    let Ok(rd) = std::fs::read_dir(dir) else { return Vec::new() };
    let mut out: Vec<Datapack> = rd
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().into_owned();
            let path = e.path();
            if let Some(base) = name.strip_suffix(".disabled").filter(|b| b.to_ascii_lowercase().ends_with(".zip")) {
                read_pack(&path, base.to_string(), false)
            } else if name.to_ascii_lowercase().ends_with(".zip") || path.is_dir() {
                read_pack(&path, name, true)
            } else {
                None
            }
        })
        .collect();
    out.sort_by_key(|p| p.file.to_lowercase());
    out
}

/// A pack's file name from the UI: a plain name directly in `datapacks/`.
fn pack_path(dir: &Path, file: &str, enabled: bool) -> Result<PathBuf, String> {
    if file.is_empty() || file.contains(['/', '\\']) || file == "." || file == ".." {
        return Err("That pack isn't in this world.".into());
    }
    let path = if enabled { dir.join(file) } else { dir.join(format!("{file}.disabled")) };
    if !path.exists() {
        return Err("That pack isn't in this world.".into());
    }
    Ok(path)
}

#[tauri::command(async)]
pub fn list_datapacks(state: State<AppState>, profile_id: String, world: String) -> Result<Vec<Datapack>, String> {
    Ok(list_packs(&datapacks_dir(&state, &profile_id, &world)?))
}

/// Turn a zip pack on or off (`.disabled`); the world picks it up next time it opens.
#[tauri::command]
pub async fn set_datapack_enabled(
    state: State<'_, AppState>,
    profile_id: String,
    world: String,
    file: String,
    enabled: bool,
) -> Result<(), String> {
    let dir = datapacks_dir(&state, &profile_id, &world)?;
    ensure_closed(&state, &profile_id).await?;
    let from = pack_path(&dir, &file, !enabled)?;
    if from.is_dir() {
        return Err("A pack folder can't be turned off here; remove it or turn it off in game with /datapack.".into());
    }
    let to = if enabled { dir.join(&file) } else { dir.join(format!("{file}.disabled")) };
    std::fs::rename(from, to).map_err(|e| e.to_string())
}

/// Move a pack to the trash.
#[tauri::command]
pub async fn remove_datapack(
    state: State<'_, AppState>,
    profile_id: String,
    world: String,
    file: String,
    enabled: bool,
) -> Result<(), String> {
    let dir = datapacks_dir(&state, &profile_id, &world)?;
    ensure_closed(&state, &profile_id).await?;
    let path = pack_path(&dir, &file, enabled)?;
    tokio::task::spawn_blocking(move || trash::delete(&path))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| format!("Couldn't move it to the trash: {e}"))
}

/// Copy zips (or pack folders) into a world's `datapacks/`. Resolves to the
/// names they landed under; anything that isn't a datapack is left out.
fn add_packs(dir: &Path, paths: Vec<PathBuf>) -> Result<ImportedWorlds, String> {
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let mut out = ImportedWorlds::default();
    for path in paths {
        let file_name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let is_pack = if path.is_dir() {
            path.join("pack.mcmeta").is_file()
        } else {
            path.extension().is_some_and(|e| e.eq_ignore_ascii_case("zip"))
                && std::fs::File::open(&path)
                    .ok()
                    .and_then(|f| zip::ZipArchive::new(f).ok())
                    .is_some_and(|mut z| z.by_name("pack.mcmeta").is_ok())
        };
        if !is_pack || path.starts_with(dir) {
            out.skipped.push(file_name);
            continue;
        }
        let (stem, ext) = match path.is_dir() {
            true => (clean_name(&file_name), String::new()),
            false => (
                clean_name(path.file_stem().map(|s| s.to_string_lossy()).as_deref().unwrap_or("pack")),
                ".zip".to_string(),
            ),
        };
        let mut name = format!("{stem}{ext}");
        let mut n = 1;
        while dir.join(&name).exists() || dir.join(format!("{name}.disabled")).exists() {
            name = format!("{stem} ({n}){ext}");
            n += 1;
        }
        let target = dir.join(&name);
        let copied = if path.is_dir() { copy_tree(&path, &target) } else { std::fs::copy(&path, &target).map(|_| ()) };
        copied.map_err(|e| format!("Couldn't copy {file_name}: {e}"))?;
        out.added.push(name);
    }
    Ok(out)
}

/// Datapacks dropped onto a world's pack list.
#[tauri::command]
pub async fn add_datapack_paths(
    state: State<'_, AppState>,
    profile_id: String,
    world: String,
    paths: Vec<String>,
) -> Result<ImportedWorlds, String> {
    let dir = datapacks_dir(&state, &profile_id, &world)?;
    let paths = paths.into_iter().map(PathBuf::from).collect();
    tokio::task::spawn_blocking(move || add_packs(&dir, paths)).await.map_err(|e| e.to_string())?
}

/// Download a Modrinth datapack's newest file for the instance's game
/// version into a world. Resolves to the file name it was saved as.
#[tauri::command]
pub async fn install_datapack(
    state: State<'_, AppState>,
    profile_id: String,
    world: String,
    project_id: String,
) -> Result<String, String> {
    use fasterlauncher_core::modrinth as mr;
    let dir = datapacks_dir(&state, &profile_id, &world)?;
    let game = {
        let store = state.profiles.lock().unwrap();
        store.profiles.iter().find(|p| p.id == profile_id).ok_or("profile not found")?.game_version.clone()
    };
    let ver = mr::project_version_for_loader(&state.client, &project_id, &game, Some("datapack"))
        .await
        .map_err(|_| format!("It has no datapack for Minecraft {game}."))?;
    let file = ver.files.iter().find(|f| f.primary).or_else(|| ver.files.first()).ok_or("That version has no files.")?;
    let name = crate::mods::sanitize_filename(&file.filename)?;
    if !name.to_ascii_lowercase().ends_with(".zip") {
        return Err("That version isn't a datapack zip.".into());
    }
    if dir.join(format!("{name}.disabled")).exists() {
        return Err(format!("{name} is in this world already, turned off."));
    }
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let dl = fasterlauncher_core::download::Download {
        url: file.url.clone(),
        dest: dir.join(&name),
        sha1: file.hashes.get("sha1").cloned(),
        size: Some(file.size),
    };
    fasterlauncher_core::download::download_one(&state.client, &dl).await.map_err(|e| e.to_string())?;
    Ok(name)
}

/// Pick datapack zips and add them to a world. Empty when the picker is cancelled.
#[tauri::command]
pub async fn add_datapacks(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    world: String,
) -> Result<ImportedWorlds, String> {
    let dir = datapacks_dir(&state, &profile_id, &world)?;
    let Some(picked) = app.dialog().file().add_filter("Datapack (ZIP)", &["zip"]).blocking_pick_files() else {
        return Ok(ImportedWorlds::default());
    };
    let paths = picked.into_iter().filter_map(|f| f.into_path().ok()).collect();
    tokio::task::spawn_blocking(move || add_packs(&dir, paths)).await.map_err(|e| e.to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backups_hold_the_world_folder_at_the_root() {
        let tmp = std::env::temp_dir().join(format!("dusk-worlds-{}", std::process::id()));
        let world = tmp.join("saves").join("My World");
        std::fs::create_dir_all(world.join("region")).unwrap();
        std::fs::write(world.join("level.dat"), b"lvl").unwrap();
        std::fs::write(world.join("session.lock"), b"x").unwrap();
        std::fs::write(world.join("region").join("r.0.0.mca"), b"chunks").unwrap();
        let out = tmp.join("backup.zip");
        write_zip(&world, "My World", &out).unwrap();

        let mut zip = zip::ZipArchive::new(std::fs::File::open(&out).unwrap()).unwrap();
        let mut names: Vec<String> = (0..zip.len()).map(|i| zip.by_index(i).unwrap().name().to_string()).collect();
        names.sort();
        assert_eq!(names, ["My World/level.dat", "My World/region/", "My World/region/r.0.0.mca"]);
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn a_session_backs_up_the_worlds_it_played_and_keeps_the_newest() {
        let tmp = std::env::temp_dir().join(format!("dusk-auto-{}", std::process::id()));
        let saves = tmp.join("saves");
        let world = |name: &str| {
            std::fs::create_dir_all(saves.join(name)).unwrap();
            std::fs::write(saves.join(name).join("level.dat"), b"lvl").unwrap();
        };
        world("Old");
        let since = std::time::SystemTime::now();
        std::thread::sleep(std::time::Duration::from_millis(20));
        world("Played");
        world("b");
        let auto = tmp.join("backups").join("auto");
        std::fs::create_dir_all(&auto).unwrap();
        for f in ["2020-01-01_00-00-00_Played.zip", "2020-01-02_00-00-00_Played.zip", "2020-01-01_00-00-00_a_b.zip"] {
            std::fs::write(auto.join(f), b"zip").unwrap();
        }

        let mut done = auto_backup(&tmp, since, 2);
        done.sort();
        assert_eq!(done, ["Played", "b"]);
        let mut left: Vec<String> =
            std::fs::read_dir(&auto).unwrap().flatten().map(|e| e.file_name().to_string_lossy().to_string()).collect();
        left.sort();
        // Played: the oldest went; a_b isn't b's, so it stays
        assert_eq!(left.len(), 4);
        assert_eq!(left[0], "2020-01-01_00-00-00_a_b.zip");
        assert_eq!(left[1], "2020-01-02_00-00-00_Played.zip");
        assert!(left[2..].iter().all(|f| !f.starts_with("2020")));
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn a_copy_gets_its_own_folder_and_name() {
        let tmp = std::env::temp_dir().join(format!("dusk-copy-{}", std::process::id()));
        let saves = tmp.join("saves");
        let world = saves.join("My World");
        std::fs::create_dir_all(world.join("region")).unwrap();
        // { "": { Data: { LevelName: "My World" } } }
        let mut nbt = vec![10, 0, 0, 10, 0, 4];
        nbt.extend_from_slice(b"Data");
        nbt.extend_from_slice(&[8, 0, 9]);
        nbt.extend_from_slice(b"LevelName");
        nbt.extend_from_slice(&[0, 8]);
        nbt.extend_from_slice(b"My World");
        nbt.extend_from_slice(&[0, 0]);
        let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        gz.write_all(&nbt).unwrap();
        std::fs::write(world.join("level.dat"), gz.finish().unwrap()).unwrap();
        std::fs::write(world.join("session.lock"), b"x").unwrap();
        std::fs::write(world.join("region").join("r.0.0.mca"), b"chunks").unwrap();
        std::fs::create_dir_all(saves.join("Copy: 1")).unwrap();

        let folder = duplicate_into(&world, &saves, "Copy: 1").unwrap();
        assert_eq!(folder, "Copy_ 1");
        let copy = saves.join(&folder);
        assert_eq!(level_info(&copy.join("level.dat")).level_name.as_deref(), Some("Copy: 1"));
        assert_eq!(std::fs::read(copy.join("region").join("r.0.0.mca")).unwrap(), b"chunks");
        assert!(!copy.join("session.lock").exists());
        // the original is untouched, and nothing is left half-made
        assert_eq!(level_info(&world.join("level.dat")).level_name.as_deref(), Some("My World"));
        assert!(!saves.join(".importing-Copy_ 1").exists());
        assert_eq!(duplicate_into(&world, &saves, "Copy: 1").unwrap(), "Copy_ 1 (1)");
        assert!(clean_level_name("  \0 ").is_err());
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn a_backup_restores_beside_its_world_under_its_own_name() {
        let tmp = std::env::temp_dir().join(format!("dusk-restore-{}", std::process::id()));
        let root = tmp.join("instance");
        let saves = root.join("saves");
        let world = saves.join("My World");
        std::fs::create_dir_all(world.join("region")).unwrap();
        let mut nbt = vec![10, 0, 0, 10, 0, 4];
        nbt.extend_from_slice(b"Data");
        nbt.extend_from_slice(&[8, 0, 9]);
        nbt.extend_from_slice(b"LevelName");
        nbt.extend_from_slice(&[0, 8]);
        nbt.extend_from_slice(b"My World");
        nbt.extend_from_slice(&[0, 0]);
        let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        gz.write_all(&nbt).unwrap();
        std::fs::write(world.join("level.dat"), gz.finish().unwrap()).unwrap();
        std::fs::write(world.join("region").join("r.0.0.mca"), b"chunks").unwrap();
        let backups = root.join("backups");
        std::fs::create_dir_all(backups.join(AUTO_BACKUPS)).unwrap();
        write_zip(&world, "My World", &backups.join("2026-10-09_14-03-27_My World.zip")).unwrap();
        write_zip(&world, "My World", &backups.join(AUTO_BACKUPS).join("2026-10-08_09-00-00_My World.zip")).unwrap();
        std::fs::write(backups.join("notes.txt"), b"not a backup").unwrap();

        let list = read_backups(&backups);
        let mut files: Vec<&str> = list.iter().map(|b| b.file.as_str()).collect();
        files.sort();
        assert_eq!(files, ["2026-10-09_14-03-27_My World.zip", "auto/2026-10-08_09-00-00_My World.zip"]);
        assert!(list.iter().all(|b| b.world == "My World" && b.size > 0));
        assert_eq!(list.iter().filter(|b| b.auto).count(), 1);

        let zip = backup_path(&root, "2026-10-09_14-03-27_My World.zip").unwrap();
        let folder = restore_into(&zip, &saves).unwrap();
        assert_eq!(folder, "My World (1)");
        let restored = saves.join(&folder);
        assert_eq!(level_info(&restored.join("level.dat")).level_name.as_deref(), Some("My World (backup 2026-10-09 14:03)"));
        assert_eq!(std::fs::read(restored.join("region").join("r.0.0.mca")).unwrap(), b"chunks");
        // the original stays as it was
        assert_eq!(level_info(&world.join("level.dat")).level_name.as_deref(), Some("My World"));
        assert!(backup_path(&root, "auto/2026-10-08_09-00-00_My World.zip").is_ok());

        // only zips inside backups/ and backups/auto/
        for bad in ["../saves/My World/level.dat", "auto/../../x.zip", "other/x.zip", "notes.txt", ".hidden.zip", "", "missing.zip"] {
            assert!(backup_path(&root, bad).is_err(), "{bad}");
        }
        assert_eq!(backup_stamp("2026-10-09_14-03-27_My World"), Some("2026-10-09_14-03-27"));
        assert_eq!(backup_stamp("My World"), None);
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn finds_the_world_and_names_it() {
        assert_eq!(world_prefix(["level.dat", "region/r.0.0.mca"].into_iter()), Some("".into()));
        assert_eq!(
            world_prefix(["__MACOSX/Map/level.dat", "Map/", "Map/level.dat", "Map/DIM1/x/level.dat"].into_iter()),
            Some("Map/".into())
        );
        assert_eq!(world_prefix(["pack.mcmeta", "assets/x.png"].into_iter()), None);
        assert_eq!(world_name("Skyblock v2/", "whatever"), "Skyblock v2");
        assert_eq!(world_name("", "2024-01-05_18-22-03_My World"), "My World");
        assert_eq!(world_name("", "Parkour: Map?"), "Parkour_ Map_");
        assert_eq!(world_name("", ".."), "World");
    }

    #[test]
    fn level_dat_fields() {
        // TAG_Compound "" { Data: { LevelName, GameType, hardcore, Version{Name}, WorldGenSettings{seed} } }
        let mut b = vec![10, 0, 0, 10, 0, 4];
        b.extend(b"Data");
        let str_tag = |b: &mut Vec<u8>, k: &str, v: &str| {
            b.push(8);
            b.extend((k.len() as u16).to_be_bytes());
            b.extend(k.as_bytes());
            b.extend((v.len() as u16).to_be_bytes());
            b.extend(v.as_bytes());
        };
        str_tag(&mut b, "LevelName", "My Base");
        b.extend([3, 0, 8]);
        b.extend(b"GameType");
        b.extend(1i32.to_be_bytes());
        b.extend([1, 0, 8]);
        b.extend(b"hardcore");
        b.push(1);
        b.extend([10, 0, 7]);
        b.extend(b"Version");
        str_tag(&mut b, "Name", "1.21.11");
        b.push(0);
        b.extend([10, 0, 16]);
        b.extend(b"WorldGenSettings");
        b.extend([4, 0, 4]);
        b.extend(b"seed");
        b.extend((-4_172_144_997_902_289_642i64).to_be_bytes());
        b.extend([0, 0, 0]);
        assert_eq!(
            parse_level(&b),
            LevelInfo {
                level_name: Some("My Base".into()),
                game_mode: Some("creative"),
                hardcore: true,
                cheats: false,
                version: Some("1.21.11".into()),
                seed: Some("-4172144997902289642".into()),
            }
        );
        assert_eq!(parse_level(b"junk"), LevelInfo::default());
        // RENAME changes the name and nothing else
        let renamed = set_level_name(&b, "Über Base ✨").unwrap();
        let info = parse_level(&renamed);
        assert_eq!(info.level_name.as_deref(), Some("Über Base ✨"));
        assert_eq!((info.version, info.seed, info.hardcore), (Some("1.21.11".into()), Some("-4172144997902289642".into()), true));
        assert_eq!(set_level_name(&renamed, "My Base").unwrap(), b);
        assert!(set_level_name(b"junk", "x").is_none());
        // a cut-off file is left alone
        assert!(set_level_name(&b[..b.len() - 4], "x").is_none());
    }

    #[test]
    fn datapacks_list_add_and_toggle() {
        let tmp = std::env::temp_dir().join(format!("dusk-packs-{}", std::process::id()));
        let src = tmp.join("src");
        std::fs::create_dir_all(src.join("Folder Pack")).unwrap();
        std::fs::write(src.join("Folder Pack").join("pack.mcmeta"), br#"{"pack":{"pack_format":48,"description":"A folder"}}"#).unwrap();
        let zip_path = src.join("Trees.zip");
        {
            let mut z = zip::ZipWriter::new(std::fs::File::create(&zip_path).unwrap());
            z.start_file("pack.mcmeta", zip::write::SimpleFileOptions::default()).unwrap();
            z.write_all(br#"{"pack":{"pack_format":48,"description":[{"text":"\u00a7aBetter "},{"text":"trees"}]}}"#).unwrap();
            z.finish().unwrap();
        }
        std::fs::write(src.join("notes.zip"), b"not a zip").unwrap();
        let dir = tmp.join("datapacks");

        let r = add_packs(&dir, vec![zip_path.clone(), src.join("Folder Pack"), src.join("notes.zip")]).unwrap();
        assert_eq!(r.added, ["Trees.zip", "Folder Pack"]);
        assert_eq!(r.skipped, ["notes.zip"]);
        assert_eq!(add_packs(&dir, vec![zip_path]).unwrap().added, ["Trees (1).zip"]);

        std::fs::rename(dir.join("Trees.zip"), dir.join("Trees.zip.disabled")).unwrap();
        let packs = list_packs(&dir);
        let summary: Vec<_> = packs.iter().map(|p| (p.file.as_str(), p.enabled, p.folder, p.description.as_deref())).collect();
        assert_eq!(
            summary,
            [
                ("Folder Pack", true, true, Some("A folder")),
                ("Trees (1).zip", true, false, Some("Better trees")),
                ("Trees.zip", false, false, Some("Better trees")),
            ]
        );
        assert!(pack_path(&dir, "Trees.zip", false).is_ok());
        assert!(pack_path(&dir, "../src", true).is_err());
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn imports_a_zip_beside_what_is_there() {
        let tmp = std::env::temp_dir().join(format!("dusk-import-{}", std::process::id()));
        let world = tmp.join("src").join("Map");
        std::fs::create_dir_all(world.join("region")).unwrap();
        std::fs::write(world.join("level.dat"), b"lvl").unwrap();
        std::fs::write(world.join("region").join("r.0.0.mca"), b"chunks").unwrap();
        let zip = tmp.join("Map.zip");
        write_zip(&world, "Map", &zip).unwrap();
        let saves = tmp.join("saves");
        std::fs::create_dir_all(saves.join("Map")).unwrap();

        assert_eq!(import_into(&saves, &zip).unwrap(), Some("Map (1)".into()));
        assert_eq!(std::fs::read(saves.join("Map (1)").join("region").join("r.0.0.mca")).unwrap(), b"chunks");
        assert_eq!(import_into(&saves, &tmp.join("src")).unwrap(), Some("Map (2)".into()));
        assert!(saves.join("Map (2)").join("level.dat").is_file());
        assert_eq!(import_into(&saves, &world.join("level.dat")).unwrap(), None);
        let _ = std::fs::remove_dir_all(&tmp);
    }
}
