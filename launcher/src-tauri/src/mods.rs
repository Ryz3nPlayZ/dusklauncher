//! Per-profile mod management + Modrinth mod search/install.
//!
//! Convention: a disabled mod is the same jar renamed to `<name>.jar.disabled`
//! (the game only loads `*.jar`). `mod_filenames` on the profile tracks known
//! jars; the filesystem is the source of truth for enabled state.

use crate::appstate::AppState;
use crate::commands::ProgressPayload;
use fasterlauncher_core::modrinth as mr;
use fasterlauncher_core::profile::Profile;
use serde::Serialize;
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, State};
use tauri_plugin_dialog::DialogExt;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileModDto {
    pub filename: String,
    pub size: u64,
    pub enabled: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModHitDto {
    pub id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub author: String,
    pub downloads: u64,
    pub icon_url: Option<String>,
    pub versions: Vec<String>,
    pub loaders: Vec<String>,
}

/// Keep only the file name: profile mods must never escape the mods dir.
fn sanitize_filename(name: &str) -> Result<String, String> {
    let base = std::path::Path::new(name)
        .file_name()
        .and_then(|s| s.to_str())
        .ok_or_else(|| "invalid mod filename".to_string())?;
    if base.is_empty() || base == "." || base == ".." {
        return Err("invalid mod filename".to_string());
    }
    Ok(base.to_string())
}

fn profile_and_mods(state: &AppState, profile_id: &str) -> Result<(Profile, PathBuf), String> {
    profile_and_content(state, profile_id, "mod")
}

/// Content kinds: `mod` → mods/, `resourcepack` → resourcepacks/,
/// `shader` → shaderpacks/. One code path for all three so the launcher and
/// the in-game menu share the same install semantics.
fn content_kind(kind: &str) -> Result<&'static str, String> {
    match kind {
        "mod" => Ok("mod"),
        "resourcepack" => Ok("resourcepack"),
        "shader" => Ok("shader"),
        _ => Err("unknown content kind (want mod|resourcepack|shader)".into()),
    }
}

fn profile_and_content(
    state: &AppState,
    profile_id: &str,
    kind: &str,
) -> Result<(Profile, PathBuf), String> {
    let kind = content_kind(kind)?;
    let store = state.profiles.lock().unwrap();
    let profile = store
        .profiles
        .iter()
        .find(|p| p.id == profile_id)
        .cloned()
        .ok_or_else(|| "profile not found".to_string())?;
    let dirs = profile.dirs(&state.data_dir);
    let dir = match kind {
        "resourcepack" => dirs.resourcepacks,
        "shader" => dirs.shaderpacks,
        _ => dirs.mods,
    };
    Ok((profile, dir))
}

/// Extensions a kind loads. Disabled content is `<name>.<ext>.disabled`.
fn kind_extensions(kind: &str) -> &'static [&'static str] {
    match kind {
        "mod" => &["jar"],
        _ => &["zip"],
    }
}

fn dto_for(path: &PathBuf, filename: String, enabled: bool) -> ProfileModDto {
    let size = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
    ProfileModDto { filename, size, enabled }
}

#[tauri::command]
pub fn list_profile_mods(state: State<AppState>, profile_id: String) -> Result<Vec<ProfileModDto>, String> {
    list_profile_content(state, profile_id, "mod".into())
}

/// Installed content of one kind for a profile (mods, resource packs, shaders).
#[tauri::command]
pub fn list_profile_content(
    state: State<AppState>,
    profile_id: String,
    kind: String,
) -> Result<Vec<ProfileModDto>, String> {
    let kind = content_kind(&kind)?;
    let (_, dir) = profile_and_content(&state, &profile_id, kind)?;
    let mut out = Vec::new();
    let Ok(rd) = std::fs::read_dir(&dir) else { return Ok(out) };
    for entry in rd.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        for ext in kind_extensions(kind) {
            let on = format!(".{ext}");
            let off = format!(".{ext}.disabled");
            if let Some(base) = name.strip_suffix(&off) {
                out.push(dto_for(&entry.path(), format!("{base}{on}"), false));
                break;
            } else if name.ends_with(&on) {
                out.push(dto_for(&entry.path(), name.clone(), true));
                break;
            }
        }
    }
    out.sort_by(|a, b| a.filename.cmp(&b.filename));
    Ok(out)
}

#[tauri::command]
pub fn remove_profile_mod(
    state: State<AppState>,
    profile_id: String,
    filename: String,
) -> Result<(), String> {
    remove_profile_content(state, profile_id, filename, "mod".into())
}

#[tauri::command]
pub fn remove_profile_content(
    state: State<AppState>,
    profile_id: String,
    filename: String,
    kind: String,
) -> Result<(), String> {
    let kind = content_kind(&kind)?;
    let filename = sanitize_filename(&filename)?;
    let (_, dir) = profile_and_content(&state, &profile_id, kind)?;
    let _ = std::fs::remove_file(dir.join(format!("{filename}.disabled")));
    std::fs::remove_file(dir.join(&filename)).map_err(|e| e.to_string())?;
    if kind == "mod" {
        let _ = state.patch_profile(&profile_id, |p| {
            p.mod_filenames.retain(|f| f != &filename);
        });
    }
    Ok(())
}

#[tauri::command]
pub fn set_mod_enabled(
    state: State<AppState>,
    profile_id: String,
    filename: String,
    enabled: bool,
) -> Result<ProfileModDto, String> {
    set_content_enabled(state, profile_id, filename, enabled, "mod".into())
}

#[tauri::command]
pub fn set_content_enabled(
    state: State<AppState>,
    profile_id: String,
    filename: String,
    enabled: bool,
    kind: String,
) -> Result<ProfileModDto, String> {
    let kind = content_kind(&kind)?;
    let filename = sanitize_filename(&filename)?;
    if !kind_extensions(kind).iter().any(|e| filename.ends_with(&format!(".{e}"))) {
        return Err("unexpected file type for this content kind".into());
    }
    let (_, dir) = profile_and_content(&state, &profile_id, kind)?;
    let on = dir.join(&filename);
    let off = dir.join(format!("{filename}.disabled"));
    if enabled {
        if !on.exists() {
            std::fs::rename(&off, &on).map_err(|e| e.to_string())?;
        }
        Ok(dto_for(&on, filename, true))
    } else {
        if !off.exists() {
            std::fs::rename(&on, &off).map_err(|e| e.to_string())?;
        }
        Ok(dto_for(&off, filename, false))
    }
}

/// Pick a local `.jar` and copy it into the profile's mods.
#[tauri::command]
pub async fn import_local_mod(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
) -> Result<Option<ProfileModDto>, String> {
    let (_, mods) = profile_and_mods(&state, &profile_id)?;
    let picked = app
        .dialog()
        .file()
        .add_filter("Minecraft mod (JAR)", &["jar"])
        .blocking_pick_file();
    let Some(file) = picked else { return Ok(None) };
    let path = file.into_path().map_err(|e| e.to_string())?;
    if path.extension().and_then(|e| e.to_str()) != Some("jar") {
        return Err("Only .jar mods can be imported".into());
    }
    let filename = sanitize_filename(&path.file_name().and_then(|s| s.to_str()).unwrap_or("mod.jar"))?;
    std::fs::create_dir_all(&mods).map_err(|e| e.to_string())?;
    std::fs::copy(&path, mods.join(&filename)).map_err(|e| e.to_string())?;
    let _ = state.patch_profile(&profile_id, |p| {
        if !p.mod_filenames.contains(&filename) {
            p.mod_filenames.push(filename.clone());
        }
    });
    Ok(Some(dto_for(&mods.join(&filename), filename, true)))
}

#[tauri::command]
pub async fn search_mods(
    state: State<'_, AppState>,
    query: String,
    game_version: String,
    loader: String,
    limit: u32,
) -> Result<Vec<ModHitDto>, String> {
    search_content(state, query, game_version, loader, limit, "mod".into()).await
}

/// Modrinth search for any installable kind (mod | resourcepack | shader),
/// pre-filtered to the profile's game version (and loader, for mods).
#[tauri::command]
pub async fn search_content(
    state: State<'_, AppState>,
    query: String,
    game_version: String,
    loader: String,
    limit: u32,
    kind: String,
) -> Result<Vec<ModHitDto>, String> {
    let kind = content_kind(&kind)?;
    let mut facets: Vec<Vec<String>> = vec![vec![format!("project_type:{kind}")]];
    if !game_version.trim().is_empty() {
        facets.push(vec![format!("versions:{game_version}")]);
    }
    if kind == "mod" && !loader.trim().is_empty() {
        facets.push(vec![format!("loaders:{loader}")]);
    }
    let params = mr::SearchParams {
        query,
        facets,
        index: "relevance".into(),
        offset: 0,
        limit: limit.clamp(5, 40) as u64,
    };
    let resp = mr::search(&state.client, &params).await.map_err(|e| e.to_string())?;
    Ok(resp
        .hits
        .into_iter()
        .map(|h| ModHitDto {
            id: h.project_id,
            slug: h.slug,
            title: h.title,
            description: h.description,
            author: h.author,
            downloads: h.downloads,
            icon_url: h.icon_url,
            versions: h.versions,
            loaders: h.loaders,
        })
        .collect())
}

/// Install the newest release of a Modrinth project into a profile's mods.
/// Emits `launch-progress` with stage `mods`.
#[tauri::command]
pub async fn install_mod_to_profile(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    project_id: String,
) -> Result<ProfileModDto, String> {
    install_content_to_profile(app, state, profile_id, project_id, "mod".into()).await
}

/// Install any kind (mod | resourcepack | shader) into the right directory.
/// Emits `launch-progress` with stage `mods`.
#[tauri::command]
pub async fn install_content_to_profile(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    project_id: String,
    kind: String,
) -> Result<ProfileModDto, String> {
    let kind = content_kind(&kind)?;
    let (profile, dir) = profile_and_content(&state, &profile_id, kind)?;
    if kind == "mod" && profile.loader == fasterlauncher_core::profile::Loader::Vanilla {
        return Err("Mods need a mod-loader instance — create a Fabric instance first.".into());
    }
    let loader = (kind == "mod").then(|| profile.loader.as_str());
    let ver = mr::project_version_for_loader(&state.client, &project_id, &profile.game_version, loader)
        .await
        .map_err(|e| e.to_string())?;
    let file = ver
        .files
        .iter()
        .find(|f| f.primary)
        .or_else(|| ver.files.first())
        .ok_or_else(|| "mod version has no files".to_string())?
        .clone();
    let filename = sanitize_filename(&file.filename)?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let dest = dir.join(&filename);
    let total = file.size;
    fasterlauncher_core::download::download_one(
        &state.client,
        &fasterlauncher_core::download::Download {
            url: file.url,
            dest: dest.clone(),
            sha1: file.hashes.get("sha1").cloned(),
            size: Some(file.size),
        },
    )
    .await
    .map_err(|e| e.to_string())?;
    let _ = app.emit(
        "launch-progress",
        ProgressPayload {
            profile_id: profile_id.clone(),
            stage: "mods".into(),
            done: 1,
            total: 1,
            done_bytes: total,
            total_bytes: total,
        },
    );
    let _ = state.patch_profile(&profile_id, |p| {
        if kind == "mod" && !p.mod_filenames.contains(&filename) {
            p.mod_filenames.push(filename.clone());
        }
    });
    Ok(dto_for(&dest, filename, true))
}

/// Copy the bundled FasterClient mod (if present next to the launcher data)
/// into a profile. Packaging fills this in at release time; until then it is
/// a clear no-op message rather than a silent skip.
#[tauri::command]
pub fn install_bundled_client_mod(
    state: State<AppState>,
    profile_id: String,
) -> Result<Option<ProfileModDto>, String> {
    let candidates = [
        state.data_dir.join("client-mod.jar"),
        state.data_dir.join("bundled").join("client-mod.jar"),
    ];
    let Some(src) = candidates.into_iter().find(|p| p.exists()) else {
        return Err("Bundled client mod is not packaged in this build yet.".into());
    };
    let (_, mods) = profile_and_mods(&state, &profile_id)?;
    let filename = "fasterclient.jar".to_string();
    std::fs::create_dir_all(&mods).map_err(|e| e.to_string())?;
    std::fs::copy(&src, mods.join(&filename)).map_err(|e| e.to_string())?;
    let _ = state.patch_profile(&profile_id, |p| {
        if !p.mod_filenames.contains(&filename) {
            p.mod_filenames.push(filename.clone());
        }
    });
    Ok(Some(dto_for(&mods.join(&filename), filename, true)))
}
