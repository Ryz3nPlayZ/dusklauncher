//! Singleplayer worlds in an instance's `saves/`: backups and delete.
//!
//! Backups follow the game's own "Make Backup" button — a zip in the
//! instance's `backups/` named `<yyyy-MM-dd_HH-mm-ss>_<world>.zip` with the
//! world folder at its root — so unzipping one into `saves/` restores it.
//! Deleting moves the world to the OS trash, never for good.

use crate::appstate::AppState;
use std::io::Write;
use std::path::{Path, PathBuf};
use tauri::State;

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
async fn ensure_closed(state: &AppState, profile_id: &str) -> Result<(), String> {
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
}
