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
    trash::delete(&dir).map_err(|e| format!("Couldn't move it to the trash: {e}"))
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
    let stamp = file_stem.as_bytes();
    let backup = stamp.len() > 20
        && stamp[19] == b'_'
        && stamp[..19].iter().enumerate().all(|(i, c)| match i {
            4 | 7 => *c == b'-',
            10 => *c == b'_',
            13 | 16 => *c == b'-',
            _ => c.is_ascii_digit(),
        });
    clean_name(if backup { &file_stem[20..] } else { file_stem })
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
