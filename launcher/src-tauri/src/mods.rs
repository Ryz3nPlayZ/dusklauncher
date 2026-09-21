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
    /// display name from the archive's metadata (fabric.mod.json `name`,
    /// pack.mcmeta description), when it has one
    pub name: Option<String>,
    /// the archive's own icon as a `data:image/png;base64,…` URL
    pub icon: Option<String>,
}

/// One installed file matched back to the Modrinth project it came from.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstalledProjectDto {
    pub filename: String,
    pub project_id: String,
    pub version_id: String,
    pub version_number: String,
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
    let (name, icon) = archive_meta(path).unwrap_or_default();
    ProfileModDto { filename, size, enabled, name, icon }
}

/// Icons bigger than this stay out of the listing: the row draws them at
/// 48px, and a few multi-megabyte pack.pngs would bloat every refresh.
const MAX_ICON_BYTES: u64 = 512 * 1024;

fn read_zip_entry(zip: &mut zip::ZipArchive<std::fs::File>, name: &str, cap: u64) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut f = zip.by_name(name).ok()?;
    if f.size() > cap {
        return None;
    }
    let mut buf = Vec::with_capacity(f.size() as usize);
    f.read_to_end(&mut buf).ok()?;
    Some(buf)
}

/// Display name + icon straight out of the jar/zip: `fabric.mod.json`
/// (`name`, `icon` — a path or a size→path map) for mods, `pack.png` for
/// resource packs and shaders. Anything unreadable just has no icon.
fn archive_meta(path: &PathBuf) -> Option<(Option<String>, Option<String>)> {
    use base64::Engine;
    let file = std::fs::File::open(path).ok()?;
    let mut zip = zip::ZipArchive::new(file).ok()?;
    let mut name = None;
    let mut icon_path = None;
    if let Some(raw) = read_zip_entry(&mut zip, "fabric.mod.json", 256 * 1024) {
        if let Ok(meta) = serde_json::from_slice::<serde_json::Value>(&raw) {
            name = meta.get("name").and_then(|v| v.as_str()).map(str::to_string);
            icon_path = match meta.get("icon") {
                Some(serde_json::Value::String(s)) => Some(s.clone()),
                // {"16": "a.png", "128": "b.png"} — take the largest
                Some(serde_json::Value::Object(map)) => map
                    .iter()
                    .filter_map(|(k, v)| Some((k.parse::<u32>().ok()?, v.as_str()?)))
                    .max_by_key(|(k, _)| *k)
                    .map(|(_, v)| v.to_string()),
                _ => None,
            };
        }
    }
    if icon_path.is_none() && zip.by_name("pack.png").is_ok() {
        icon_path = Some("pack.png".into());
    }
    let icon = icon_path
        .and_then(|p| read_zip_entry(&mut zip, &p, MAX_ICON_BYTES))
        .map(|bytes| format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(bytes)));
    Some((name, icon))
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

/// Which Modrinth projects a profile's folder already holds, by hashing
/// every file (enabled or not) and asking Modrinth which version each hash
/// is. The browse and project pages read INSTALLED off this so a mod that
/// came with a modpack — or was dropped in by hand — isn't offered twice.
#[tauri::command]
pub async fn lookup_profile_content(
    state: State<'_, AppState>,
    profile_id: String,
    kind: String,
) -> Result<Vec<InstalledProjectDto>, String> {
    let kind = content_kind(&kind)?;
    let (_, dir) = profile_and_content(&state, &profile_id, kind)?;
    let mut files: Vec<(String, String)> = Vec::new();
    let Ok(rd) = std::fs::read_dir(&dir) else { return Ok(Vec::new()) };
    for entry in rd.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        for ext in kind_extensions(kind) {
            let on = format!(".{ext}");
            let off = format!(".{ext}.disabled");
            let filename = if let Some(base) = name.strip_suffix(&off) {
                format!("{base}{on}")
            } else if name.ends_with(&on) {
                name.clone()
            } else {
                continue;
            };
            if let Ok(hash) = fasterlauncher_core::download::sha1_file(&entry.path()).await {
                files.push((filename, hash));
            }
            break;
        }
    }
    let hashes: Vec<String> = files.iter().map(|(_, h)| h.clone()).collect();
    let found = mr::version_files(&state.client, &hashes)
        .await
        .map_err(|e| e.to_string())?;
    let mut out: Vec<InstalledProjectDto> = files
        .into_iter()
        .filter_map(|(filename, hash)| {
            let v = found.get(&hash)?;
            Some(InstalledProjectDto {
                filename,
                project_id: v.project_id.clone(),
                version_id: v.id.clone(),
                version_number: v.version_number.clone(),
            })
        })
        .collect();
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
        facets.push(vec![format!("categories:{loader}")]);
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
    install_version_file(app, state, profile_id, kind, dir, ver).await
}

/// Install one specific Modrinth version (picked on the project page) into
/// a profile — no compatibility lookup, the user chose the file.
#[tauri::command]
pub async fn install_content_version_to_profile(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    version_id: String,
    kind: String,
) -> Result<ProfileModDto, String> {
    let kind = content_kind(&kind)?;
    let (profile, dir) = profile_and_content(&state, &profile_id, kind)?;
    if kind == "mod" && profile.loader == fasterlauncher_core::profile::Loader::Vanilla {
        return Err("Mods need a mod-loader instance — create a Fabric instance first.".into());
    }
    let ver = mr::version(&state.client, &version_id).await.map_err(|e| e.to_string())?;
    install_version_file(app, state, profile_id, kind, dir, ver).await
}

/// Download a version's primary file into `dir` and register it.
async fn install_version_file(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    kind: &'static str,
    dir: std::path::PathBuf,
    ver: mr::Version,
) -> Result<ProfileModDto, String> {
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

/// Copy the bundled DuskClient mod (the build for the profile's game line)
/// into its `mods/`. Fabric instances get the mod force-loaded at launch
/// anyway (see `install_and_launch`); this exists for users who want a
/// visible copy they can disable from the mods list — a copy in `mods/`
/// takes precedence over the forced one so the jar is never loaded twice.
#[tauri::command]
pub fn install_bundled_client_mod(
    app: AppHandle,
    state: State<AppState>,
    profile_id: String,
) -> Result<Option<ProfileModDto>, String> {
    let (profile, mods) = profile_and_mods(&state, &profile_id)?;
    let Some(filename) = crate::cosmetics::client_mod_jar_for(&profile.game_version) else {
        return Err(format!(
            "DuskClient is built for {} — not for {}.",
            crate::cosmetics::CLIENT_MOD_GAME_VERSIONS,
            profile.game_version
        ));
    };
    let Some(src) = crate::cosmetics::bundled_client_mod_jar(&app, &state.data_dir, filename) else {
        return Err("Bundled client mod is not packaged in this build yet.".into());
    };
    let filename = filename.to_string();
    std::fs::create_dir_all(&mods).map_err(|e| e.to_string())?;
    std::fs::copy(&src, mods.join(&filename)).map_err(|e| e.to_string())?;
    let _ = state.patch_profile(&profile_id, |p| {
        if !p.mod_filenames.contains(&filename) {
            p.mod_filenames.push(filename.clone());
        }
    });
    Ok(Some(dto_for(&mods.join(&filename), filename, true)))
}
