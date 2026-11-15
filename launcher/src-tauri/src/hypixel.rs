//! The 1.8.9 instance for Hypixel: Forge, the player's own OptiFine file (its
//! terms bar shipping it), Dusk Client, their settings and keys, and Pylon
//! switched off.

use crate::appstate::AppState;
use crate::commands::{dto, new_profile, ProfileDto};
use fasterlauncher_core::{download, forge, modrinth as mr, profile::Loader};
use serde::Serialize;
use std::path::{Path, PathBuf};
use tauri::{AppHandle, State};
use tauri_plugin_dialog::DialogExt;

/// Pylon 0.2.7, the build tested under Forge and OptiFine.
const PYLON_VERSION: &str = "QPGKOis1";
/// The 1.8.9 Dusk Client build this launcher ships (same file name in
/// `resources/` as the modern jars). Carries the HUD layout the player set
/// up on a newer instance, `config/duskclient-hud.json`, with it.
const DUSK_189_JAR: &str = "duskclient-1.8.9.jar";
/// Always added, by Modrinth version id: EntityCulling 1.5.0 (skips drawing
/// players, items and chests behind walls; in every popular 1.8.9 PvP pack),
/// Phosphor 7 (a faster lighting engine, so placing and blowing up blocks
/// doesn't stutter) and McMouser 1.0.3 (macOS mouse fixes: side buttons,
/// Shift+Scroll, Ctrl+Click no longer a right click).
const PERFORMANCE_MODS: [(&str, &str); 3] =
    [("EntityCulling", "WiIRU3UP"), ("Phosphor", "iECFoMt9"), ("McMouser", "wrXBdoQ8")];

const NOT_OPTIFINE: &str = "That isn't OptiFine for 1.8.9. Download OptiFine 1.8.9 from optifine.net and add that file.";

fn check_optifine(path: &Path) -> Result<(), String> {
    let file = std::fs::File::open(path).map_err(|e| format!("Couldn't open that file: {e}"))?;
    let mut zip = zip::ZipArchive::new(file).map_err(|_| NOT_OPTIFINE.to_string())?;
    let config = crate::mods::read_zip_entry(&mut zip, "Config.class", 1 << 20).ok_or(NOT_OPTIFINE)?;
    let version = b"OptiFine_1.8.9_";
    if config.windows(version.len()).any(|w| w == version) {
        Ok(())
    } else {
        Err(NOT_OPTIFINE.into())
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OptifineFile {
    path: String,
    name: String,
}

/// Ask for the OptiFine file. `None` when the picker is cancelled.
#[tauri::command]
pub async fn pick_optifine(app: AppHandle) -> Result<Option<OptifineFile>, String> {
    let Some(picked) = app.dialog().file().add_filter("OptiFine (JAR)", &["jar"]).blocking_pick_file() else {
        return Ok(None);
    };
    let path = picked.into_path().map_err(|e| e.to_string())?;
    check_optifine(&path)?;
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    Ok(Some(OptifineFile { path: path.display().to_string(), name }))
}

/// The OptiFine build the instance is tested with, fetched from optifine.net
/// onto the player's machine (it is never shipped with the launcher).
const OPTIFINE_FILE: &str = "OptiFine_1.8.9_HD_U_M5.jar";
const OPTIFINE_SHA1: &str = "d362d58a28f5373b141b9e426e8e160638bfafcd";
const OPTIFINE_SIZE: u64 = 2_585_014;
const OPTIFINE_FAILED: &str =
    "Couldn't download OptiFine. Get it from optifine.net with GET OPTIFINE, then use ADD THE FILE.";

/// The mirror page links the file as `downloadx?f=<file>&x=<token>`, with a
/// fresh token each time.
fn optifine_link(page: &str) -> Option<String> {
    let start = format!("downloadx?f={OPTIFINE_FILE}&x=");
    let at = page.find(&start)?;
    let token: String = page[at + start.len()..].chars().take_while(|c| c.is_ascii_alphanumeric()).collect();
    (!token.is_empty()).then(|| format!("https://optifine.net/{start}{token}"))
}

/// Download OptiFine in the background, so the player doesn't have to leave
/// the launcher. Reuses the copy from last time when it is still intact.
#[tauri::command]
pub async fn download_optifine(state: State<'_, AppState>) -> Result<OptifineFile, String> {
    let dest = state.data_dir.join("optifine").join(OPTIFINE_FILE);
    let intact = match tokio::fs::metadata(&dest).await {
        Ok(m) if m.len() == OPTIFINE_SIZE => download::sha1_file(&dest).await.ok().as_deref() == Some(OPTIFINE_SHA1),
        _ => false,
    };
    if !intact {
        let page = async {
            let url = format!("https://optifine.net/adloadx?f={OPTIFINE_FILE}");
            state.client.get(url).send().await?.error_for_status()?.text().await
        }
        .await
        .map_err(|e| {
            tracing::warn!("OptiFine page: {e}");
            OPTIFINE_FAILED.to_string()
        })?;
        let url = optifine_link(&page).ok_or_else(|| {
            tracing::warn!("OptiFine page had no download link");
            OPTIFINE_FAILED.to_string()
        })?;
        let dl = download::Download {
            url,
            dest: dest.clone(),
            sha1: Some(OPTIFINE_SHA1.into()),
            size: Some(OPTIFINE_SIZE),
        };
        download::download_one(&state.client, &dl).await.map_err(|e| {
            tracing::warn!("OptiFine download: {e}");
            OPTIFINE_FAILED.to_string()
        })?;
    }
    check_optifine(&dest)?;
    Ok(OptifineFile { path: dest.display().to_string(), name: OPTIFINE_FILE.into() })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HypixelInstance {
    profile: ProfileDto,
    /// what didn't make it in, for the player to know about
    note: Option<String>,
}

async fn add_modrinth(
    app: &AppHandle,
    state: &State<'_, AppState>,
    profile_id: &str,
    version_id: &str,
    on: bool,
) -> Result<(), String> {
    let (_, dir) = crate::mods::profile_and_content(state, profile_id, "mod")?;
    let version = mr::version(&state.client, version_id).await.map_err(|e| e.to_string())?;
    let added =
        crate::mods::install_version_file(app.clone(), state.clone(), profile_id.to_string(), "mod", dir, version).await?;
    if !on {
        crate::mods::set_content_enabled(state.clone(), profile_id.to_string(), added.filename, false, "mod".into())?;
    }
    Ok(())
}

/// Bring the copied instance's Dusk HUD layout and module states across. The
/// 1.8.9 build reads the same ids, so the file works as it is; entries for
/// modules this build doesn't have are kept in the file untouched.
fn copy_dusk_config(from: &Path, to: &Path) -> Result<(), String> {
    let source = from.join("config").join("duskclient-hud.json");
    let text = match std::fs::read_to_string(&source) {
        Ok(t) => t,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()), // they never opened the menu there
        Err(e) => return Err(e.to_string()),
    };
    let dir = to.join("config");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("duskclient-hud.json"), text).map_err(|e| e.to_string())
}

/// Make the instance. It exists once this returns, even when a step after
/// saving it fell through: `note` says which.
#[tauri::command]
pub async fn create_hypixel_instance(
    app: AppHandle,
    state: State<'_, AppState>,
    name: String,
    optifine: Option<String>,
    copy_from: Option<String>,
    pylon: bool,
) -> Result<HypixelInstance, String> {
    let optifine = optifine.map(PathBuf::from);
    if let Some(path) = &optifine {
        check_optifine(path)?;
    }
    let settings_from = copy_from.as_deref().map(|id| crate::servers::profile_root(&state, id)).transpose()?;

    let name = Some(name.trim().to_string()).filter(|n| !n.is_empty()).unwrap_or_else(|| "Hypixel 1.8.9".into());
    let profile = new_profile(&state, name, forge::GAME_VERSION.into(), Loader::Forge, None);
    let (id, root) = (profile.id.clone(), profile.dirs(&state.data_dir).root);
    {
        let mut store = state.profiles.lock().unwrap();
        store.profiles.push(profile);
        state.save_profiles(&store);
    }

    let mut notes = Vec::new();
    if let Some(path) = &optifine {
        if let Err(e) = crate::mods::copy_in(&state, &id, "mod", path) {
            notes.push(format!("OptiFine wasn't added: {e}"));
        }
    }
    match crate::cosmetics::bundled_client_mod_jar(&app, &state.data_dir, DUSK_189_JAR) {
        Some(jar) => {
            if let Err(e) = crate::mods::copy_in(&state, &id, "mod", &jar) {
                notes.push(format!("Dusk Client wasn't added: {e}"));
            }
        }
        None => notes.push("Dusk Client wasn't found in this launcher build.".into()),
    }
    if let Some(from) = &settings_from {
        if let Err(e) = crate::carry::copy_options_to_1_8_9(from, &root) {
            notes.push(format!("Your settings weren't copied: {e}"));
        }
        if let Err(e) = copy_dusk_config(from, &root) {
            notes.push(format!("Your Dusk layout wasn't copied: {e}"));
        }
    }
    let mut missing = Vec::new();
    for (mod_name, version) in PERFORMANCE_MODS {
        if let Err(e) = add_modrinth(&app, &state, &id, version, true).await {
            tracing::warn!("{mod_name} wasn't added: {e}");
            missing.push(mod_name);
        }
    }
    if !missing.is_empty() {
        notes.push(format!("{} couldn't be downloaded. You can add it later from the Mods tab.", missing.join(", ")));
    }
    if let Err(e) = add_modrinth(&app, &state, &id, PYLON_VERSION, pylon).await {
        tracing::warn!("Pylon wasn't added: {e}");
        notes.push("Pylon couldn't be downloaded. You can add it later from the Mods tab.".into());
    }

    let store = state.profiles.lock().unwrap();
    let profile = store.profiles.iter().find(|p| p.id == id).ok_or("profile not found")?;
    Ok(HypixelInstance { profile: dto(profile, &state.data_dir), note: (!notes.is_empty()).then(|| notes.join(" ")) })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn jar(dir: &Path, name: &str, entry: &str, contents: &[u8]) -> PathBuf {
        let path = dir.join(name);
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
        zip.start_file(entry, zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(contents).unwrap();
        zip.finish().unwrap();
        path
    }

    #[test]
    fn optifine_link_is_read_off_the_mirror_page() {
        let page = "<a href='downloadx?f=OptiFine_1.8.9_HD_U_M5.jar&x=99d0c54bb7ee38edb720902ce1b309d9' onclick='x'>";
        assert_eq!(
            optifine_link(page).as_deref(),
            Some("https://optifine.net/downloadx?f=OptiFine_1.8.9_HD_U_M5.jar&x=99d0c54bb7ee38edb720902ce1b309d9")
        );
        assert_eq!(optifine_link("<a href='downloadx?f=OptiFine_1.8.9_HD_U_M5.jar&x='>"), None);
        assert_eq!(optifine_link("<a href='downloadx?f=OptiFine_1.8.9_HD_U_M6.jar&x=abc'>"), None);
    }

    #[test]
    fn only_optifine_for_1_8_9_is_taken() {
        let dir = std::env::temp_dir().join(format!("dusk-optifine-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let good = jar(&dir, "a.jar", "Config.class", b"\x01\x00\x16OptiFine_1.8.9_HD_U_M5\x01");
        let newer = jar(&dir, "b.jar", "Config.class", b"OptiFine_1.12.2_HD_U_G5");
        let other = jar(&dir, "c.jar", "mcmod.info", b"[]");
        let not_a_jar = dir.join("d.jar");
        std::fs::write(&not_a_jar, "text").unwrap();

        assert!(check_optifine(&good).is_ok());
        assert_eq!(check_optifine(&newer).unwrap_err(), NOT_OPTIFINE);
        assert_eq!(check_optifine(&other).unwrap_err(), NOT_OPTIFINE);
        assert_eq!(check_optifine(&not_a_jar).unwrap_err(), NOT_OPTIFINE);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
