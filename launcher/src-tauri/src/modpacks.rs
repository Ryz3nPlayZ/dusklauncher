//! Modrinth modpack commands: search + one-click install.

use crate::appstate::AppState;
use crate::commands::{dto, ProfileDto};
use fasterlauncher_core::modrinth as mr;
use fasterlauncher_core::profile::{default_jvm_args, Loader, Profile};
use serde::{Deserialize, Serialize};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_dialog::DialogExt;

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ModpackFacets {
    pub categories: Vec<String>,
    pub versions: Vec<String>,
    pub loaders: Vec<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackHitDto {
    pub id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub author: String,
    pub downloads: u64,
    pub follows: u64,
    pub icon_url: Option<String>,
    pub updated_at: Option<String>,
    pub categories: Vec<String>,
    pub versions: Vec<String>,
    pub loaders: Vec<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackSearchDto {
    pub hits: Vec<ModpackHitDto>,
    pub total: u64,
    pub page: u64,
    pub page_size: u64,
}

fn hit_dto(h: &mr::SearchHit) -> ModpackHitDto {
    ModpackHitDto {
        id: h.project_id.clone(),
        slug: h.slug.clone(),
        title: h.title.clone(),
        description: h.description.clone(),
        author: h.author.clone(),
        downloads: h.downloads,
        follows: h.follows,
        icon_url: h.icon_url.clone(),
        updated_at: h.date_modified.clone(),
        categories: h.categories.clone(),
        versions: h.versions.clone(),
        loaders: h.loaders.clone(),
    }
}

#[tauri::command]
pub async fn search_modpacks(
    state: State<'_, AppState>,
    query: String,
    facets: ModpackFacets,
    page: u32,
    page_size: u32,
    sort: String,
) -> Result<ModpackSearchDto, String> {
    search_projects(state, "modpack".into(), query, facets, page, page_size, sort).await
}

/// The same paged, faceted, sorted search for any Modrinth project type —
/// the browse page for mods / resource packs / shaders runs on this.
#[tauri::command]
pub async fn search_projects(
    state: State<'_, AppState>,
    kind: String,
    query: String,
    facets: ModpackFacets,
    page: u32,
    page_size: u32,
    sort: String,
) -> Result<ModpackSearchDto, String> {
    let project_type = match kind.as_str() {
        "modpack" | "mod" | "resourcepack" | "shader" => kind.as_str(),
        _ => return Err("unknown project kind (want modpack|mod|resourcepack|shader)".into()),
    };
    let mut groups: Vec<Vec<String>> = vec![vec![format!("project_type:{project_type}")]];
    if !facets.categories.is_empty() {
        groups.push(facets.categories.iter().map(|c| format!("categories:{c}")).collect());
    }
    if !facets.versions.is_empty() {
        groups.push(facets.versions.iter().map(|v| format!("versions:{v}")).collect());
    }
    // Modrinth's search index files loaders under `categories` — there is no
    // `loaders:` facet type, and asking for one quietly mangled the results.
    if !facets.loaders.is_empty() {
        groups.push(facets.loaders.iter().map(|l| format!("categories:{l}")).collect());
    }
    let index = match sort.as_str() {
        "downloads" | "follows" | "newest" | "updated" => sort.clone(),
        _ => "relevance".into(),
    };
    let limit = page_size.clamp(5, 40) as u64;
    let params = mr::SearchParams {
        query,
        facets: groups,
        index,
        offset: page.saturating_mul(page_size) as u64,
        limit,
    };
    let resp = mr::search(&state.client, &params).await.map_err(|e| e.to_string())?;
    Ok(ModpackSearchDto {
        hits: resp.hits.iter().map(hit_dto).collect(),
        total: resp.total_hits,
        page: page as u64,
        page_size: resp.limit,
    })
}

// ── tags ───────────────────────────────────────────────────────────────────

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct GameVersionTagDto {
    pub version: String,
    pub version_type: String,
    pub major: bool,
}

/// The filter vocabularies for one browse page: Modrinth's own categories
/// for the project type (grouped by header), every loader that applies to
/// it, and the full game-version list — releases and snapshots alike, so
/// the UI decides what to show.
#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ProjectTagsDto {
    pub categories: Vec<CategoryTagDto>,
    pub loaders: Vec<String>,
    pub game_versions: Vec<GameVersionTagDto>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct CategoryTagDto {
    pub name: String,
    pub header: String,
}

struct TagCache {
    categories: Vec<mr::CategoryTag>,
    loaders: Vec<mr::LoaderTag>,
    game_versions: Vec<mr::GameVersionTag>,
}

/// The three tag lists change a few times a year; one fetch per process
/// is plenty. A failed fetch is not cached, so a flaky first call retries.
static TAGS: tokio::sync::OnceCell<TagCache> = tokio::sync::OnceCell::const_new();

#[tauri::command]
pub async fn modrinth_tags(state: State<'_, AppState>, kind: String) -> Result<ProjectTagsDto, String> {
    let project_type = match kind.as_str() {
        "modpack" | "mod" | "resourcepack" | "shader" => kind.as_str(),
        _ => return Err("unknown project kind (want modpack|mod|resourcepack|shader)".into()),
    };
    let client = state.client.clone();
    let cache = TAGS
        .get_or_try_init(|| async {
            let (categories, loaders, game_versions) = tokio::try_join!(
                mr::category_tags(&client),
                mr::loader_tags(&client),
                mr::game_version_tags(&client),
            )?;
            Ok::<_, fasterlauncher_core::Error>(TagCache { categories, loaders, game_versions })
        })
        .await
        .map_err(|e| e.to_string())?;
    Ok(ProjectTagsDto {
        categories: cache
            .categories
            .iter()
            .filter(|c| c.project_type == project_type)
            .map(|c| CategoryTagDto { name: c.name.clone(), header: c.header.clone() })
            .collect(),
        loaders: cache
            .loaders
            .iter()
            .filter(|l| l.supported_project_types.iter().any(|t| t == project_type))
            .map(|l| l.name.clone())
            .collect(),
        game_versions: cache
            .game_versions
            .iter()
            .map(|v| GameVersionTagDto {
                version: v.version.clone(),
                version_type: v.version_type.clone(),
                major: v.major,
            })
            .collect(),
    })
}

/// Install a modpack: creates a profile with pinned versions, downloads all
/// modpack files (mods/configs) plus required dependencies, extracts
/// overrides. Emits `launch-progress` events with stage `mods`.
#[tauri::command]
pub async fn install_modpack(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
) -> Result<ProfileDto, String> {
    let versions = mr::project_versions(&state.client, &id)
        .await
        .map_err(|e| e.to_string())?;
    let version = versions
        .first()
        .ok_or_else(|| "modpack has no versions".to_string())?;
    let dusk = id == DUSK_PACK_ID;
    install_version_inner(app, state, version, None, dusk).await
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackVersionDto {
    pub id: String,
    pub name: String,
    pub version_number: String,
    pub changelog: Option<String>,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub published: Option<String>,
    pub version_type: String,
    pub downloads: u64,
}

/// Themed version picker data: every Modrinth version of a pack with the
/// game versions + loaders it supports, so the UI can offer real choices
/// instead of silently installing latest.
#[tauri::command]
pub async fn list_modpack_versions(
    state: State<'_, AppState>,
    id: String,
) -> Result<Vec<ModpackVersionDto>, String> {
    let versions = mr::project_versions(&state.client, &id)
        .await
        .map_err(|e| e.to_string())?;
    Ok(versions
        .into_iter()
        .map(|v| ModpackVersionDto {
            id: v.id,
            name: v.name,
            version_number: v.version_number,
            changelog: v.changelog,
            game_versions: v.game_versions,
            loaders: v.loaders,
            published: v.published,
            version_type: v.version_type,
            downloads: v.downloads,
        })
        .collect())
}

/// Full project details for the detail page: long body, gallery, links,
/// compatibility. Everything the search hit doesn't carry.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackGalleryDto {
    pub url: String,
    pub title: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackProjectDto {
    pub id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub body: String,
    pub icon_url: Option<String>,
    pub downloads: u64,
    pub follows: u64,
    pub categories: Vec<String>,
    pub loaders: Vec<String>,
    pub game_versions: Vec<String>,
    pub gallery: Vec<ModpackGalleryDto>,
    pub discord_url: Option<String>,
    pub issues_url: Option<String>,
    pub source_url: Option<String>,
    pub wiki_url: Option<String>,
    pub client_side: String,
    pub server_side: String,
    pub published: Option<String>,
    pub updated: Option<String>,
}

#[tauri::command]
pub async fn get_modpack_project(
    state: State<'_, AppState>,
    id: String,
) -> Result<ModpackProjectDto, String> {
    let p = mr::project(&state.client, &id).await.map_err(|e| e.to_string())?;
    let mut categories = p.categories;
    categories.extend(p.additional_categories);
    Ok(ModpackProjectDto {
        id: p.id,
        slug: p.slug,
        title: p.title,
        description: p.description,
        body: p.body,
        icon_url: p.icon_url,
        downloads: p.downloads,
        follows: p.followers,
        categories,
        loaders: p.loaders,
        game_versions: p.game_versions,
        gallery: p
            .gallery
            .into_iter()
            .map(|g| ModpackGalleryDto { url: g.url, title: g.title })
            .collect(),
        discord_url: p.discord_url,
        issues_url: p.issues_url,
        source_url: p.source_url,
        wiki_url: p.wiki_url,
        client_side: p.client_side,
        server_side: p.server_side,
        published: p.published,
        updated: p.updated,
    })
}

/// Install a specific Modrinth version of a pack (from the picker modal).
#[tauri::command]
pub async fn install_modpack_version(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    version_id: String,
    name: Option<String>,
) -> Result<ProfileDto, String> {
    let version = mr::version(&state.client, &version_id)
        .await
        .map_err(|e| e.to_string())?;
    // the version isn't checked against the project; `id` only says whether
    // this is the launcher's own pack
    let dusk = id == DUSK_PACK_ID;
    install_version_inner(app, state, &version, name, dusk).await
}

/// `name`: what the user typed in the install dialog; `None` falls back to
/// the pack's own title.
async fn install_version_inner(
    app: AppHandle,
    state: State<'_, AppState>,
    version: &mr::Version,
    name: Option<String>,
    dusk: bool,
) -> Result<ProfileDto, String> {
    let bytes = mr::download_mrpack(&state.client, version)
        .await
        .map_err(|e| e.to_string())?;

    // required dependency mods (fabric-api and friends)
    let mut dep_versions = Vec::new();
    for dep in &version.dependencies {
        if dep.dependency_type == "required" {
            if let Some(vid) = &dep.version_id {
                if let Ok(v) = mr::version(&state.client, vid).await {
                    dep_versions.push(v);
                }
            }
        }
    }
    install_mrpack_bytes(app, state, &bytes, &dep_versions, name, dusk).await
}

/// Import a modpack from a local `.mrpack` (native picker). Same install as
/// a Modrinth pack — only the bytes come from disk instead of the API.
/// Resolves `None` when the picker is cancelled.
#[tauri::command]
pub async fn import_mrpack(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<Option<ProfileDto>, String> {
    let picked = app
        .dialog()
        .file()
        .add_filter("Modrinth modpack", &["mrpack", "zip"])
        .blocking_pick_file();
    let Some(file) = picked else { return Ok(None) };
    let path = file.into_path().map_err(|e| e.to_string())?;
    let bytes = std::fs::read(&path).map_err(|e| format!("could not read {}: {e}", path.display()))?;
    install_mrpack_bytes(app, state, &bytes, &[], None, false).await.map(Some)
}

/// Where a pack shipped inside the app bundle lives
/// (`resources/modpacks/<pack>.mrpack`; see cosmetics::bundled_client_mod_jar
/// for the same lookup ladder).
fn bundled_pack_path(app: &AppHandle, data_dir: &Path, pack: &str) -> Option<PathBuf> {
    let filename = format!("{pack}.mrpack");
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(res) = app.path().resource_dir() {
        candidates.push(res.join("resources").join("modpacks").join(&filename));
        candidates.push(res.join("modpacks").join(&filename));
    }
    if cfg!(debug_assertions) {
        let src_tauri = Path::new(env!("CARGO_MANIFEST_DIR"));
        candidates.push(src_tauri.join("resources").join("modpacks").join(&filename));
    }
    candidates.push(data_dir.join("bundled").join(&filename));
    candidates.into_iter().find(|p| p.is_file())
}

/// Install one of the packs bundled with the launcher — currently
/// `dusk-essentials`, the default instance seeded on first run.
#[tauri::command]
pub async fn install_bundled_pack(
    app: AppHandle,
    state: State<'_, AppState>,
    pack: String,
) -> Result<ProfileDto, String> {
    let path = bundled_pack_path(&app, &state.data_dir, &pack)
        .ok_or_else(|| format!("bundled pack \"{pack}\" is not packaged in this build"))?;
    let bytes = std::fs::read(&path).map_err(|e| e.to_string())?;
    install_mrpack_bytes(app, state, &bytes, &[], None, pack == "dusk-essentials").await
}

/// Modrinth project of the pack the launcher installs as its default
/// instance (`DUSK_PACK` in the frontend).
const DUSK_PACK_ID: &str = "IDrxZk6D";

/// Video settings the launcher's own instance starts with, tuned like a
/// Prism setup: auto GUI scale, cheaper shadows/blending/mipmaps/clouds and
/// a shorter simulation distance. Only for a fresh install, so nothing a
/// player chose is touched; keys a version doesn't know are ignored.
const DUSK_OPTIONS: &str = "guiScale:0
entityShadows:false
biomeBlendRadius:1
mipmapLevels:2
cloudRange:32
simulationDistance:5
entityDistanceScaling:0.75
";

/// Defaults for the launcher's own instance, applied right after its
/// overrides land. The pack ships a sodium-extra config that renders at
/// Retina resolution on macOS (4x the pixels), so that flag is merged in
/// rather than seeded-if-absent like other instances get at launch. Sodium
/// may queue 3 frames ahead of the GPU; 2 trims a frame of input latency
/// (about 4 ms at 240 fps) for next to no throughput.
fn seed_dusk_defaults(root: &Path) {
    let options = root.join("options.txt");
    if !options.exists() {
        let _ = std::fs::write(&options, DUSK_OPTIONS);
    }
    merge_config(root, "sodium-options.json", "advanced", "cpu_render_ahead_limit", 2.into());
    if cfg!(target_os = "macos") {
        merge_config(root, "sodium-extra-options.json", "extra_settings", "reduce_resolution_on_mac", true.into());
    }
}

/// Set `section.key` in `config/<file>`, keeping everything else in it.
fn merge_config(root: &Path, file: &str, section: &str, key: &str, value: serde_json::Value) {
    let path = root.join("config").join(file);
    let mut json: serde_json::Value = std::fs::read(&path)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .filter(serde_json::Value::is_object)
        .unwrap_or_else(|| serde_json::json!({}));
    let sec = &mut json[section];
    if !sec.is_object() {
        *sec = serde_json::json!({});
    }
    sec[key] = value;
    let _ = std::fs::create_dir_all(root.join("config"));
    if let Ok(s) = serde_json::to_string_pretty(&json) {
        let _ = std::fs::write(&path, s);
    }
}

/// Install an .mrpack already in memory: a profile pinned to the pack's
/// versions, its overrides extracted, its files (and `deps`) downloaded.
async fn install_mrpack_bytes(
    app: AppHandle,
    state: State<'_, AppState>,
    bytes: &[u8],
    dep_versions: &[mr::Version],
    name: Option<String>,
    dusk: bool,
) -> Result<ProfileDto, String> {
    let index = mr::parse_mrpack_index(bytes).map_err(|e| e.to_string())?;

    let mc_version = index
        .minecraft_version()
        .cloned()
        .ok_or("modpack does not declare a minecraft version")?;
    let (loader, loader_version) = match index.loader() {
        Some(("fabric", v)) => (Loader::Fabric, Some(v.clone())),
        _ => (Loader::Vanilla, None), // neoforge/forge not supported yet (roadmap)
    };

    // unique name from what the user typed, else the pack title
    let base_name = name
        .map(|n| n.trim().to_string())
        .filter(|n| !n.is_empty())
        .unwrap_or_else(|| index.name.to_uppercase());
    let name = {
        let store = state.profiles.lock().unwrap();
        let mut n = base_name.clone();
        let mut i = 2;
        while store.profiles.iter().any(|p| p.name == n) {
            n = format!("{base_name} {i}");
            i += 1;
        }
        n
    };

    let jvm_args = {
        let settings = state.settings.lock().unwrap();
        let parsed: Vec<String> = settings
            .default_jvm_args
            .split_whitespace()
            .map(str::to_string)
            .collect();
        if parsed.is_empty() { default_jvm_args() } else { parsed }
    };

    let profile = Profile {
        id: format!("p{}", now_millis()),
        name: name.clone(),
        game_version: mc_version,
        loader,
        loader_version,
        jvm_args,
        resolution: {
            let s = state.settings.lock().unwrap();
            (s.width, s.height)
        },
        mod_filenames: index
            .files
            .iter()
            .filter(|f| f.path.starts_with("mods/"))
            .map(|f| f.path.trim_start_matches("mods/").to_string())
            .collect(),
        server: None,
        created_at: now_millis(),
        last_played: None,
        memory_mb: None,
        java_path: None,
    };
    let dirs = profile.dirs(&state.data_dir);
    {
        let mut store = state.profiles.lock().unwrap();
        store.profiles.push(profile.clone());
        state.save_profiles(&store);
    }

    // overrides (configs, shaderpacks, resourcepacks — and, for a pack
    // exported from here or Prism, the mods themselves)
    std::fs::create_dir_all(&dirs.root).map_err(|e| e.to_string())?;
    let _ = mr::extract_overrides(bytes, &dirs.root);
    if dusk {
        seed_dusk_defaults(&dirs.root);
    }
    // mods that arrived as overrides count too
    let _ = state.patch_profile(&profile.id, |p| {
        if let Ok(rd) = std::fs::read_dir(&dirs.mods) {
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().to_string();
                if name.ends_with(".jar") && !p.mod_filenames.contains(&name) {
                    p.mod_filenames.push(name);
                }
            }
        }
    });

    let mut downloads = mr::index_downloads(&index, &dirs.root);
    downloads.extend(mr::dependency_downloads(dep_versions, &dirs.root));
    let total = downloads.len() as u64;
    let profile_id = profile.id.clone();
    let app2 = app.clone();
    let downloaded = mr::download_files(&state.client, downloads, move |done, _| {
        let _ = app2.emit(
            "launch-progress",
            crate::commands::ProgressPayload {
                profile_id: profile_id.clone(),
                stage: "mods".into(),
                done,
                total,
                done_bytes: 0,
                total_bytes: 0,
            },
        );
    })
    .await
    .map_err(|e| e.to_string())?;
    tracing::info!(pack = %name, files = downloaded, "modpack installed");

    let _ = state.patch_profile(&profile.id, |_| {});
    Ok(dto(&profile))
}

// ── export ─────────────────────────────────────────────────────────────────

/// What travels with an exported instance. Everything under these goes into
/// the pack's `overrides/`; the game's own installs (versions, natives) and
/// the shared caches never do, and neither do worlds, logs or screenshots —
/// the same cut Prism makes.
const EXPORT_DIRS: &[&str] = &["mods", "config", "resourcepacks", "shaderpacks", "datapacks"];
const EXPORT_FILES: &[&str] = &["options.txt", "servers.dat"];

/// Export an instance as a `.mrpack` (native save dialog): the version and
/// loader pinned in `modrinth.index.json`, the folder contents as
/// overrides — so it opens in Prism, the Modrinth app, or back here.
/// Resolves to the written path, or `None` when the dialog is cancelled.
#[tauri::command]
pub async fn export_instance(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
) -> Result<Option<String>, String> {
    let profile = {
        let store = state.profiles.lock().unwrap();
        store
            .profiles
            .iter()
            .find(|p| p.id == profile_id)
            .cloned()
            .ok_or("profile not found")?
    };
    let safe_name: String = profile
        .name
        .chars()
        .map(|c| if c.is_alphanumeric() || c == ' ' || c == '-' || c == '_' { c } else { '_' })
        .collect();
    let picked = app
        .dialog()
        .file()
        .add_filter("Modrinth modpack", &["mrpack"])
        .set_file_name(format!("{}.mrpack", safe_name.trim()))
        .blocking_save_file();
    let Some(file) = picked else { return Ok(None) };
    let out_path = file.into_path().map_err(|e| e.to_string())?;

    let mut dependencies = std::collections::HashMap::new();
    dependencies.insert("minecraft".to_string(), profile.game_version.clone());
    if profile.loader == Loader::Fabric {
        if let Some(v) = &profile.loader_version {
            dependencies.insert("fabric-loader".to_string(), v.clone());
        }
    }
    let index = mr::MrpackIndex {
        format_version: 1,
        game: "minecraft".into(),
        version_id: "1.0.0".into(),
        name: profile.name.clone(),
        files: Vec::new(),
        dependencies,
    };
    let index_json = serde_json::to_string_pretty(&index).map_err(|e| e.to_string())?;

    let dirs = profile.dirs(&state.data_dir);
    let root = dirs.root.clone();
    let written = tokio::task::spawn_blocking(move || -> Result<usize, String> {
        let file = std::fs::File::create(&out_path).map_err(|e| e.to_string())?;
        let mut zip = zip::ZipWriter::new(file);
        let opts = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        zip.start_file("modrinth.index.json", opts).map_err(|e| e.to_string())?;
        zip.write_all(index_json.as_bytes()).map_err(|e| e.to_string())?;
        let mut count = 0;
        for dir in EXPORT_DIRS {
            count += zip_tree(&mut zip, &root, &root.join(dir), opts)?;
        }
        for name in EXPORT_FILES {
            let p = root.join(name);
            if p.is_file() {
                zip.start_file(format!("overrides/{name}"), opts).map_err(|e| e.to_string())?;
                zip.write_all(&std::fs::read(&p).map_err(|e| e.to_string())?)
                    .map_err(|e| e.to_string())?;
                count += 1;
            }
        }
        zip.finish().map_err(|e| e.to_string())?;
        Ok(count)
    })
    .await
    .map_err(|e| e.to_string())??;
    tracing::info!(pack = %profile.name, files = written, "instance exported");
    Ok(Some(format!("{written} files")))
}

/// Recursively add `dir` to the zip under `overrides/<path relative to root>`.
fn zip_tree(
    zip: &mut zip::ZipWriter<std::fs::File>,
    root: &Path,
    dir: &Path,
    opts: zip::write::SimpleFileOptions,
) -> Result<usize, String> {
    let Ok(rd) = std::fs::read_dir(dir) else { return Ok(0) };
    let mut count = 0;
    for entry in rd.flatten() {
        let path = entry.path();
        if path.is_dir() {
            count += zip_tree(zip, root, &path, opts)?;
            continue;
        }
        let rel = path.strip_prefix(root).map_err(|e| e.to_string())?;
        let name = format!("overrides/{}", rel.to_string_lossy().replace('\\', "/"));
        zip.start_file(name, opts).map_err(|e| e.to_string())?;
        zip.write_all(&std::fs::read(&path).map_err(|e| e.to_string())?)
            .map_err(|e| e.to_string())?;
        count += 1;
    }
    Ok(count)
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dusk_defaults_merge_into_the_packs_config_and_keep_player_options() {
        let root = std::env::temp_dir().join(format!("dusk-seed-{}", now_millis()));
        std::fs::create_dir_all(root.join("config")).unwrap();
        let cfg = root.join("config").join("sodium-extra-options.json");
        std::fs::write(&cfg, r#"{"extra_settings":{"reduce_resolution_on_mac":false,"cloud_height":160}}"#).unwrap();

        std::fs::write(root.join("config").join("sodium-options.json"), r#"{"quality":{"weather_quality":"FAST"},"advanced":{"cpu_render_ahead_limit":3}}"#).unwrap();

        seed_dusk_defaults(&root);
        assert!(std::fs::read_to_string(root.join("options.txt")).unwrap().contains("guiScale:0"));
        let sodium: serde_json::Value =
            serde_json::from_slice(&std::fs::read(root.join("config").join("sodium-options.json")).unwrap()).unwrap();
        assert_eq!(sodium["advanced"]["cpu_render_ahead_limit"], 2);
        assert_eq!(sodium["quality"]["weather_quality"], "FAST");
        if cfg!(target_os = "macos") {
            let v: serde_json::Value = serde_json::from_slice(&std::fs::read(&cfg).unwrap()).unwrap();
            assert_eq!(v["extra_settings"]["reduce_resolution_on_mac"], true);
            assert_eq!(v["extra_settings"]["cloud_height"], 160);
        }

        std::fs::write(root.join("options.txt"), "guiScale:3\n").unwrap();
        seed_dusk_defaults(&root);
        assert_eq!(std::fs::read_to_string(root.join("options.txt")).unwrap(), "guiScale:3\n");
        let _ = std::fs::remove_dir_all(&root);
    }
}
