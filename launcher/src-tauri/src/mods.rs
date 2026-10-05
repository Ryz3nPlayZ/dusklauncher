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
/// (`name`, `icon` — a path or a size→path map) or NeoForge's
/// `META-INF/neoforge.mods.toml` (`displayName`, `logoFile`) for mods,
/// `pack.png` for resource packs and shaders. Anything unreadable just has
/// no icon.
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
    if name.is_none() {
        if let Some(raw) = read_zip_entry(&mut zip, "META-INF/neoforge.mods.toml", 256 * 1024) {
            let text = String::from_utf8_lossy(&raw);
            name = toml_string(&text, "displayName");
            icon_path = icon_path.or_else(|| toml_string(&text, "logoFile"));
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

/// The first `key = "value"` in a mods.toml. Its first `[[mods]]` entry is
/// the jar's own mod, so the first match is the one to show; a value built
/// from `${file.jarVersion}`-style placeholders reads as missing.
fn toml_string(text: &str, key: &str) -> Option<String> {
    text.lines().find_map(|line| {
        let (k, v) = line.trim().split_once('=')?;
        if k.trim() != key {
            return None;
        }
        let v = v.trim();
        let quote = v.chars().next().filter(|c| *c == '"' || *c == '\'')?;
        let v = &v[1..];
        let v = &v[..v.find(quote)?];
        Some(v.to_string()).filter(|v| !v.is_empty() && !v.contains("${"))
    })
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
    let mut out: Vec<ProfileModDto> = content_files(&dir, kind)
        .into_iter()
        .map(|(filename, path, enabled)| dto_for(&path, filename, enabled))
        .collect();
    out.sort_by(|a, b| a.filename.cmp(&b.filename));
    Ok(out)
}

/// Every file of a kind in `dir` as (logical filename, path on disk,
/// enabled) — a disabled `x.jar.disabled` reads as `x.jar`.
fn content_files(dir: &std::path::Path, kind: &str) -> Vec<(String, PathBuf, bool)> {
    let mut out = Vec::new();
    let Ok(rd) = std::fs::read_dir(dir) else { return out };
    for entry in rd.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        for ext in kind_extensions(kind) {
            let on = format!(".{ext}");
            let off = format!(".{ext}.disabled");
            if let Some(base) = name.strip_suffix(&off) {
                out.push((format!("{base}{on}"), entry.path(), false));
                break;
            } else if name.ends_with(&on) {
                out.push((name.clone(), entry.path(), true));
                break;
            }
        }
    }
    out
}

/// (logical filename, sha1) for every file of a kind; unreadable files are skipped.
async fn hash_content(dir: &std::path::Path, kind: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for (filename, path, _) in content_files(dir, kind) {
        if let Ok(hash) = fasterlauncher_core::download::sha1_file(&path).await {
            out.push((filename, hash));
        }
    }
    out
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
    let files = hash_content(&dir, kind).await;
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

/// An installed file with a newer Modrinth version for this instance.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ContentUpdateDto {
    pub filename: String,
    pub project_id: String,
    pub current_version: String,
    pub version_id: String,
    pub version_number: String,
}

/// The Modrinth loader slugs a kind's files are published under: mods follow
/// the instance's loader, packs are `minecraft`, shaders whichever pipeline.
fn update_loaders(kind: &str, profile: &Profile) -> Vec<&'static str> {
    match kind {
        "mod" => vec![profile.loader.as_str()],
        "shader" => vec!["iris", "optifine", "canvas", "vanilla"],
        _ => vec!["minecraft"],
    }
}

/// Whether `latest` is really an update over `current`: a different
/// version, published later, and no less stable than what's installed (a
/// release never gets offered a beta or alpha).
fn is_update(current: &mr::Version, latest: &mr::Version) -> bool {
    fn rank(t: &str) -> u8 {
        match t {
            "alpha" => 0,
            "beta" => 1,
            _ => 2,
        }
    }
    latest.id != current.id
        && latest.project_id == current.project_id
        && rank(&latest.version_type) >= rank(&current.version_type)
        && match (&latest.published, &current.published) {
            // RFC 3339 in one zone (Modrinth's UTC) orders as text
            (Some(l), Some(c)) => l > c,
            _ => false,
        }
}

/// Installed files of a kind that have a newer Modrinth version for this
/// instance's game version and loader. Files Modrinth doesn't know (local
/// jars, the bundled client mod) never show up.
#[tauri::command]
pub async fn check_content_updates(
    state: State<'_, AppState>,
    profile_id: String,
    kind: String,
) -> Result<Vec<ContentUpdateDto>, String> {
    let kind = content_kind(&kind)?;
    let (profile, dir) = profile_and_content(&state, &profile_id, kind)?;
    if kind == "mod" && profile.loader == fasterlauncher_core::profile::Loader::Vanilla {
        return Ok(Vec::new());
    }
    let files = hash_content(&dir, kind).await;
    let hashes: Vec<String> = files.iter().map(|(_, h)| h.clone()).collect();
    let loaders = update_loaders(kind, &profile);
    let (current, latest) = tokio::join!(
        mr::version_files(&state.client, &hashes),
        mr::version_files_update(&state.client, &hashes, &loaders, &profile.game_version),
    );
    let current = current.map_err(|e| e.to_string())?;
    let latest = latest.map_err(|e| e.to_string())?;
    let mut out: Vec<ContentUpdateDto> = files
        .into_iter()
        .filter_map(|(filename, hash)| {
            let (cur, new) = (current.get(&hash)?, latest.get(&hash)?);
            is_update(cur, new).then(|| ContentUpdateDto {
                filename,
                project_id: new.project_id.clone(),
                current_version: cur.version_number.clone(),
                version_id: new.id.clone(),
                version_number: new.version_number.clone(),
            })
        })
        .collect();
    out.sort_by(|a, b| a.filename.cmp(&b.filename));
    Ok(out)
}

/// Replace an installed file with another version of its project: download
/// the new file, drop the old one, and keep it disabled if it was.
#[tauri::command]
pub async fn update_profile_content(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    kind: String,
    filename: String,
    version_id: String,
) -> Result<ProfileModDto, String> {
    let kind = content_kind(&kind)?;
    let old = sanitize_filename(&filename)?;
    let (_, dir) = profile_and_content(&state, &profile_id, kind)?;
    let was_enabled = dir.join(&old).exists();
    if !was_enabled && !dir.join(format!("{old}.disabled")).exists() {
        return Err("that file is no longer in the instance".into());
    }
    let ver = mr::version(&state.client, &version_id).await.map_err(|e| e.to_string())?;
    let added = install_version_file(app, state.clone(), profile_id.clone(), kind, dir.clone(), ver).await?;
    // the new file landed enabled at its own name; clear what it replaces
    let _ = std::fs::remove_file(dir.join(format!("{old}.disabled")));
    if added.filename != old {
        let _ = std::fs::remove_file(dir.join(&old));
        if kind == "mod" {
            let _ = state.patch_profile(&profile_id, |p| p.mod_filenames.retain(|f| f != &old));
        }
    }
    if was_enabled {
        return Ok(added);
    }
    let on = dir.join(&added.filename);
    let off = dir.join(format!("{}.disabled", added.filename));
    std::fs::rename(&on, &off).map_err(|e| e.to_string())?;
    Ok(dto_for(&off, added.filename, false))
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

/// Copy one local file into the profile's folder for `kind`.
fn copy_in(state: &AppState, profile_id: &str, kind: &'static str, path: &std::path::Path) -> Result<ProfileModDto, String> {
    let (_, dir) = profile_and_content(state, profile_id, kind)?;
    let filename = sanitize_filename(path.file_name().and_then(|s| s.to_str()).unwrap_or(""))?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    std::fs::copy(path, dir.join(&filename)).map_err(|e| e.to_string())?;
    if kind == "mod" {
        let _ = state.patch_profile(profile_id, |p| {
            if !p.mod_filenames.contains(&filename) {
                p.mod_filenames.push(filename.clone());
            }
        });
    }
    Ok(dto_for(&dir.join(&filename), filename, true))
}

/// What a loose file is by its contents: a `.jar` is a mod, a `.zip` with a
/// `shaders/` folder a shader pack, any other `.zip` a resource pack.
fn infer_kind(path: &std::path::Path) -> Option<&'static str> {
    let ext = path.extension()?.to_str()?.to_ascii_lowercase();
    match ext.as_str() {
        "jar" => Some("mod"),
        "zip" => {
            let zip = zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()?;
            let shaders = zip.file_names().any(|n| n.starts_with("shaders/"));
            Some(if shaders { "shader" } else { "resourcepack" })
        }
        _ => None,
    }
}

/// Pick local files of a kind (`.jar` mods, `.zip` packs — several at once)
/// and copy them into the profile's folder. Empty when the picker is cancelled.
#[tauri::command]
pub async fn import_local_content(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    kind: String,
) -> Result<Vec<ProfileModDto>, String> {
    let kind = content_kind(&kind)?;
    profile_and_content(&state, &profile_id, kind)?;
    let exts = kind_extensions(kind);
    let label = match kind {
        "mod" => "Minecraft mod (JAR)",
        "resourcepack" => "Resource pack (ZIP)",
        _ => "Shader pack (ZIP)",
    };
    let Some(picked) = app.dialog().file().add_filter(label, exts).blocking_pick_files() else {
        return Ok(Vec::new());
    };
    let mut out = Vec::new();
    for file in picked {
        let path = file.into_path().map_err(|e| e.to_string())?;
        let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("");
        if !exts.iter().any(|e| ext.eq_ignore_ascii_case(e)) {
            return Err(format!("Only .{} files go in this folder", exts.join(" / .")));
        }
        out.push(copy_in(&state, &profile_id, kind, &path)?);
    }
    Ok(out)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DroppedDto {
    pub added: Vec<ProfileModDto>,
    /// names of dropped files that aren't mods or packs
    pub skipped: Vec<String>,
}

/// Files dropped onto an instance: each goes to the folder its contents say
/// it belongs in; anything else is skipped and named back.
#[tauri::command]
pub fn import_content_paths(
    state: State<AppState>,
    profile_id: String,
    paths: Vec<String>,
) -> Result<DroppedDto, String> {
    let mut out = DroppedDto { added: Vec::new(), skipped: Vec::new() };
    for p in paths {
        let path = PathBuf::from(&p);
        let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or(p);
        match infer_kind(&path).filter(|_| path.is_file()) {
            Some(kind) => out.added.push(copy_in(&state, &profile_id, kind, &path)?),
            None => out.skipped.push(name),
        }
    }
    Ok(out)
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
        return Err("Mods need a mod-loader instance — switch it to Fabric or NeoForge first.".into());
    }
    let loader = (kind == "mod").then(|| profile.loader.as_str());
    let ver = mr::project_version_for_loader(&state.client, &project_id, &profile.game_version, loader)
        .await
        .map_err(|e| e.to_string())?;
    let deps = ver.dependencies.clone();
    let added = install_version_file(app.clone(), state.clone(), profile_id, kind, dir.clone(), ver).await?;
    if kind == "mod" {
        install_required_deps(&app, &state, &profile, &dir, deps).await;
    }
    Ok(added)
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
        return Err("Mods need a mod-loader instance — switch it to Fabric or NeoForge first.".into());
    }
    let ver = mr::version(&state.client, &version_id).await.map_err(|e| e.to_string())?;
    let deps = ver.dependencies.clone();
    let added = install_version_file(app.clone(), state.clone(), profile_id, kind, dir.clone(), ver).await?;
    if kind == "mod" {
        install_required_deps(&app, &state, &profile, &dir, deps).await;
    }
    Ok(added)
}

/// Most a single install pulls in: a real dependency tree is a handful of
/// libraries deep, so this only stops a misbehaving one.
const MAX_DEPENDENCY_INSTALLS: usize = 16;

/// The required dependencies of a mod just installed that the folder doesn't
/// already hold (Fabric API, Sodium under Sodium Extra, …), and theirs in
/// turn — each as the newest version for the instance, like a fresh install.
/// Best effort: a mod without a dependency crashes at launch, but a
/// dependency Modrinth can't resolve shouldn't fail the install that asked.
async fn install_required_deps(
    app: &AppHandle,
    state: &State<'_, AppState>,
    profile: &Profile,
    dir: &std::path::Path,
    deps: Vec<mr::VersionDependency>,
) {
    let files = hash_content(dir, "mod").await;
    let hashes: Vec<String> = files.iter().map(|(_, h)| h.clone()).collect();
    let mut held: std::collections::HashSet<String> = match mr::version_files(&state.client, &hashes).await {
        Ok(found) => found.into_values().map(|v| v.project_id).collect(),
        // can't tell what's there — installing blind could double up a mod
        Err(_) => return,
    };
    let mut queue = deps;
    let mut installed = 0;
    while let Some(dep) = queue.pop() {
        if dep.dependency_type != "required" || installed >= MAX_DEPENDENCY_INSTALLS {
            continue;
        }
        let project = match (dep.project_id, dep.version_id) {
            (Some(p), _) => p,
            (None, Some(v)) => match mr::version(&state.client, &v).await {
                Ok(v) => v.project_id,
                Err(_) => continue,
            },
            (None, None) => continue,
        };
        if !held.insert(project.clone()) {
            continue;
        }
        let Ok(ver) = mr::project_version_for_loader(
            &state.client,
            &project,
            &profile.game_version,
            Some(profile.loader.as_str()),
        )
        .await
        else {
            continue;
        };
        let next = ver.dependencies.clone();
        if install_version_file(app.clone(), state.clone(), profile.id.clone(), "mod", dir.to_path_buf(), ver)
            .await
            .is_ok()
        {
            installed += 1;
            queue.extend(next);
        }
    }
}

/// Fabric API on Modrinth. DuskClient needs it, and so do most Fabric mods.
const FABRIC_API: (&str, &str) = ("P7dR8mSH", "fabric-api");

/// The performance set the Dusk instance ships, as (Modrinth project, mod id):
/// renderer, game logic, memory, GUI batching, entity culling, misc
/// rendering fast paths and networking.
const PERFORMANCE_MODS: &[(&str, &str)] = &[
    ("sodium", "sodium"),
    ("lithium", "lithium"),
    ("ferrite-core", "ferritecore"),
    ("immediatelyfast", "immediatelyfast"),
    ("entityculling", "entityculling"),
    ("badoptimizations", "badoptimizations"),
    ("krypton", "krypton"),
];

/// A jar's parsed fabric.mod.json, if it has one.
pub(crate) fn fabric_meta(path: &std::path::Path) -> Option<serde_json::Value> {
    let file = std::fs::File::open(path).ok()?;
    let raw = read_zip_entry(&mut zip::ZipArchive::new(file).ok()?, "fabric.mod.json", 256 * 1024)?;
    serde_json::from_slice(&raw).ok()
}

/// Mod ids (and what they `provides`) of every enabled Fabric jar in `dir`,
/// read from each jar's fabric.mod.json, so a renamed jar still counts.
fn fabric_mod_ids(dir: &std::path::Path) -> std::collections::HashSet<String> {
    let mut ids = std::collections::HashSet::new();
    let Ok(entries) = std::fs::read_dir(dir) else { return ids };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("jar") {
            continue;
        }
        let Some(raw) = std::fs::File::open(&path)
            .ok()
            .and_then(|f| zip::ZipArchive::new(f).ok())
            .and_then(|mut zip| read_zip_entry(&mut zip, "fabric.mod.json", 256 * 1024))
        else {
            continue;
        };
        let Ok(meta) = serde_json::from_slice::<serde_json::Value>(&raw) else { continue };
        if let Some(id) = meta.get("id").and_then(|v| v.as_str()) {
            ids.insert(id.to_string());
        }
        for id in meta.get("provides").and_then(|v| v.as_array()).into_iter().flatten() {
            if let Some(id) = id.as_str() {
                ids.insert(id.to_string());
            }
        }
    }
    ids
}

/// Install each `(project, mod id)` whose mod isn't already in the profile's
/// mods/, as the newest version for the instance. Ones Modrinth has no build
/// of for this version are skipped. Returns how many were added.
async fn install_missing(
    app: &AppHandle,
    state: &State<'_, AppState>,
    profile: &Profile,
    dir: &std::path::Path,
    wanted: &[(&str, &str)],
) -> usize {
    let held = fabric_mod_ids(dir);
    let mut added = 0;
    for (project, id) in wanted {
        if held.contains(*id) {
            continue;
        }
        let Ok(ver) =
            mr::project_version_for_loader(&state.client, project, &profile.game_version, Some("fabric")).await
        else {
            continue;
        };
        if install_version_file(app.clone(), state.clone(), profile.id.clone(), "mod", dir.to_path_buf(), ver)
            .await
            .is_ok()
        {
            added += 1;
        }
    }
    added
}

/// Make sure a Fabric profile has Fabric API before launch. Returns whether
/// it's there afterwards: offline with no copy, it isn't, and the caller
/// leaves DuskClient out rather than have the loader refuse to start.
pub async fn ensure_fabric_api(app: &AppHandle, state: &State<'_, AppState>, profile: &Profile, dir: &std::path::Path) -> bool {
    if fabric_mod_ids(dir).contains(FABRIC_API.1) {
        return true;
    }
    install_missing(app, state, profile, dir, &[FABRIC_API]).await;
    fabric_mod_ids(dir).contains(FABRIC_API.1)
}

/// Dusk Essentials — what every Dusk profile is built from (docs/MODPACK.md):
/// the performance core plus a few utilities DuskClient doesn't cover.
/// Zoom, freelook, fullbright, ping, crosshair, food and container previews
/// and the background frame cap are DuskClient modules, so their third-party
/// equivalents are left out to avoid doubling up.
const DUSK_ESSENTIALS: &[(&str, &str)] = &[
    FABRIC_API,
    ("sodium", "sodium"),
    ("sodium-extra", "sodium-extra"),
    ("reeses-sodium-options", "reeses-sodium-options"),
    ("iris", "iris"),
    ("lithium", "lithium"),
    ("ferrite-core", "ferritecore"),
    ("immediatelyfast", "immediatelyfast"),
    ("entityculling", "entityculling"),
    ("moreculling", "moreculling"),
    ("badoptimizations", "badoptimizations"),
    ("krypton", "krypton"),
    ("modmenu", "modmenu"),
    ("betterf3", "betterf3"),
    ("chat-heads", "chat_heads"),
    ("held-item-info", "held-item-info"),
];

/// Fill a Fabric profile with Dusk Essentials for its game version: each
/// mod's newest release (or its newest build when it has no release), plus
/// their required libraries. Mods without a build for the version are
/// skipped. Returns how many files were added.
#[tauri::command]
pub async fn install_dusk_essentials(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
) -> Result<usize, String> {
    let (profile, dir) = profile_and_mods(&state, &profile_id)?;
    if profile.loader != fasterlauncher_core::profile::Loader::Fabric {
        return Err("Dusk Essentials is for Fabric instances.".into());
    }
    let held = fabric_mod_ids(&dir);
    let mut added = 0;
    let mut deps = Vec::new();
    for (project, id) in DUSK_ESSENTIALS {
        if held.contains(*id) {
            continue;
        }
        let Ok(ver) =
            mr::project_version_for_loader(&state.client, project, &profile.game_version, Some("fabric")).await
        else {
            continue;
        };
        let next = ver.dependencies.clone();
        if install_version_file(app.clone(), state.clone(), profile.id.clone(), "mod", dir.clone(), ver)
            .await
            .is_ok()
        {
            added += 1;
            deps.extend(next);
        }
    }
    // nothing resolved on a fresh instance: Modrinth is unreachable, and the
    // first-run seed falls back to the bundled pack on this error
    if added == 0 && held.is_empty() {
        return Err("Couldn't reach Modrinth for Dusk Essentials.".into());
    }
    install_required_deps(&app, &state, &profile, &dir, deps).await;
    if let Some(root) = dir.parent() {
        crate::modpacks::seed_dusk_defaults(root);
    }
    Ok(added)
}

/// Add the performance set (plus Fabric API) to a Fabric profile, skipping
/// anything already installed. Returns how many mods were added.
#[tauri::command]
pub async fn install_performance_mods(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
) -> Result<usize, String> {
    let (profile, dir) = profile_and_mods(&state, &profile_id)?;
    if profile.loader != fasterlauncher_core::profile::Loader::Fabric {
        return Err("Performance mods are for Fabric instances.".into());
    }
    let mut wanted = vec![FABRIC_API];
    wanted.extend_from_slice(PERFORMANCE_MODS);
    Ok(install_missing(&app, &state, &profile, &dir, &wanted).await)
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

#[cfg(test)]
mod tests {
    use super::toml_string;

    #[test]
    fn reads_the_first_mods_toml_value() {
        let toml = r#"
modLoader = "javafml"
[[mods]]
modId = "sodium"
version = "${file.jarVersion}"
displayName = "Sodium" # the renderer
logoFile='icon.png'
[[mods]]
displayName = "Other"
"#;
        assert_eq!(toml_string(toml, "displayName").as_deref(), Some("Sodium"));
        assert_eq!(toml_string(toml, "logoFile").as_deref(), Some("icon.png"));
        assert_eq!(toml_string(toml, "version"), None);
        assert_eq!(toml_string(toml, "missing"), None);
    }

    #[test]
    fn tells_packs_apart_by_their_contents() {
        use std::io::Write;
        let base = std::env::temp_dir().join(format!("dusk-kind-{}", std::process::id()));
        std::fs::create_dir_all(&base).unwrap();
        let zip_with = |name: &str, entry: &str| {
            let path = base.join(name);
            let mut z = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
            z.start_file(entry, zip::write::SimpleFileOptions::default()).unwrap();
            z.write_all(b"x").unwrap();
            z.finish().unwrap();
            path
        };
        assert_eq!(super::infer_kind(&zip_with("s.zip", "shaders/final.fsh")), Some("shader"));
        assert_eq!(super::infer_kind(&zip_with("r.zip", "pack.mcmeta")), Some("resourcepack"));
        assert_eq!(super::infer_kind(&zip_with("m.JAR", "fabric.mod.json")), Some("mod"));
        assert_eq!(super::infer_kind(&base.join("notes.txt")), None);
        let _ = std::fs::remove_dir_all(&base);
    }

    fn ver(id: &str, kind: &str, date: &str) -> super::mr::Version {
        serde_json::from_value(serde_json::json!({
            "id": id, "project_id": "p", "name": id, "version_type": kind,
            "date_published": date,
        }))
        .unwrap()
    }

    #[test]
    fn offers_only_newer_equally_stable_versions() {
        use super::is_update;
        let cur = ver("a", "release", "2025-01-01T00:00:00Z");
        assert!(is_update(&cur, &ver("b", "release", "2025-02-01T00:00:00Z")));
        assert!(!is_update(&cur, &cur), "same version");
        assert!(!is_update(&cur, &ver("b", "release", "2024-12-01T00:00:00Z")), "older");
        assert!(!is_update(&cur, &ver("b", "beta", "2025-02-01T00:00:00Z")), "release → beta");
        let beta = ver("a", "beta", "2025-01-01T00:00:00Z");
        assert!(is_update(&beta, &ver("b", "release", "2025-02-01T00:00:00Z")));
        assert!(is_update(&beta, &ver("b", "beta", "2025-02-01T00:00:00Z")));
        assert!(!is_update(&beta, &ver("b", "alpha", "2025-02-01T00:00:00Z")));
    }
}
