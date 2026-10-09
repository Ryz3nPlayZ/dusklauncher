//! Modrinth modpack commands: search + one-click install.

use crate::appstate::AppState;
use crate::commands::{dto, ProfileDto};
use fasterlauncher_core::modrinth as mr;
use fasterlauncher_core::profile::{default_jvm_args, Loader, PackLink, Profile};
use serde::{Deserialize, Serialize};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;
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
    install_version_inner(app, state, version, None, false).await
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
    // the version isn't checked against the project; `id` is part of the
    // command's shape only
    let _ = id;
    install_version_inner(app, state, &version, name, false).await
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
    // the pack, its listed dependencies and its icon, fetched side by side
    let (bytes, dep_versions, project) = tokio::join!(
        mr::download_mrpack(&state.client, version),
        required_deps(&state, version),
        mr::project(&state.client, &version.project_id),
    );
    let bytes = bytes.map_err(|e| e.to_string())?;
    let installed = install_mrpack_bytes(app, state.clone(), bytes, &dep_versions, name, dusk, link_to(version)).await?;
    // the pack's Modrinth icon becomes the instance's picture
    let icon = project.ok().and_then(|p| p.icon_url);
    let Some(url) = icon.filter(|_| !version.project_id.is_empty()) else { return Ok(installed) };
    crate::icons::fetch_pack_icon(&state, &installed.id, &url).await;
    let store = state.profiles.lock().unwrap();
    Ok(store.profiles.iter().find(|p| p.id == installed.id).map(|p| dto(p, &state.data_dir)).unwrap_or(installed))
}

/// The mods a pack version names as required dependencies (fabric-api and
/// friends) — rare, as packs list their mods in the index.
async fn required_deps(state: &AppState, version: &mr::Version) -> Vec<mr::Version> {
    let mut out = Vec::new();
    for dep in &version.dependencies {
        if dep.dependency_type == "required" {
            if let Some(vid) = &dep.version_id {
                if let Ok(v) = mr::version(&state.client, vid).await {
                    out.push(v);
                }
            }
        }
    }
    out
}

/// What an instance installed from `version` remembers of it; the files are
/// filled in once they're in place.
fn link_to(version: &mr::Version) -> Option<PackLink> {
    (!version.project_id.is_empty()).then(|| PackLink {
        project_id: version.project_id.clone(),
        version_id: version.id.clone(),
        version_number: version.version_number.clone(),
        files: Vec::new(),
    })
}

/// Import a modpack from a local `.mrpack` (native picker). Same install as
/// a Modrinth pack — only the bytes come from disk instead of the API. A
/// file Modrinth recognises (one downloaded from it) links the instance to
/// its pack, so it can update like one installed here.
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
    let bytes = tokio::fs::read(&path).await.map_err(|e| format!("could not read {}: {e}", path.display()))?;
    let sha1 = fasterlauncher_core::download::sha1_hex(&bytes);
    let link = match mr::version_files(&state.client, std::slice::from_ref(&sha1)).await {
        Ok(found) => found.get(&sha1).and_then(link_to),
        Err(_) => None,
    };
    install_mrpack_bytes(app, state, bytes, &[], None, false, link).await.map(Some)
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
    let bytes = tokio::fs::read(&path).await.map_err(|e| e.to_string())?;
    install_mrpack_bytes(app, state, bytes, &[], None, pack == "dusk-essentials", None).await
}

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

/// Defaults for the launcher's own instances (the bundled pack, and every
/// Dusk profile once its essentials land). The pack ships a sodium-extra config that renders at
/// Retina resolution on macOS (4x the pixels), so that flag is merged in
/// rather than seeded-if-absent like other instances get at launch. Sodium
/// may queue 3 frames ahead of the GPU; 2 trims a frame of input latency
/// (about 4 ms at 240 fps) for next to no throughput.
pub(crate) fn seed_dusk_defaults(root: &Path) {
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
    if let Ok(s) = serde_json::to_vec_pretty(&json) {
        // the player's whole Sodium config: never leave it half written
        let _ = fasterlauncher_core::write_atomic(&path, &s);
    }
}

/// Install an .mrpack already in memory: a profile pinned to the pack's
/// versions, its overrides extracted, its files (and `deps`) downloaded.
async fn install_mrpack_bytes(
    app: AppHandle,
    state: State<'_, AppState>,
    bytes: Vec<u8>,
    dep_versions: &[mr::Version],
    name: Option<String>,
    dusk: bool,
    link: Option<PackLink>,
) -> Result<ProfileDto, String> {
    let index = mr::parse_mrpack_index(&bytes).map_err(|e| e.to_string())?;

    let mc_version = index
        .minecraft_version()
        .cloned()
        .ok_or("modpack does not declare a minecraft version")?;
    let (loader, loader_version) = pack_loader(&index)?;

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
        play_secs: 0,
        memory_mb: None,
        java_path: None,
        group: None,
        icon: None,
        pack: None,
        hooks: Default::default(),
        fullscreen: None,
    };
    let dirs = profile.dirs(&state.data_dir);
    {
        let mut store = state.profiles.lock().unwrap();
        store.profiles.push(profile.clone());
        state.save_profiles(&store);
    }

    let filled = fill_pack_instance(&app, &state, &profile, Arc::new(bytes), &index, dep_versions, dusk, link).await;
    if let Err(e) = filled {
        // half a pack is no instance: take it back out rather than leave one
        // that's missing mods with nothing saying so
        {
            let mut store = state.profiles.lock().unwrap();
            store.profiles.retain(|p| p.id != profile.id);
            state.save_profiles(&store);
        }
        let root = dirs.root.clone();
        let _ = tokio::task::spawn_blocking(move || std::fs::remove_dir_all(root)).await;
        return Err(e);
    }
    tracing::info!(pack = %name, "modpack installed");

    let stored = state.patch_profile(&profile.id, |_| {}).unwrap_or(profile);
    Ok(dto(&stored, &state.data_dir))
}

/// Put a just-added pack instance's files in place: its overrides, then
/// everything its index (and `dep_versions`) downloads.
#[allow(clippy::too_many_arguments)]
async fn fill_pack_instance(
    app: &AppHandle,
    state: &AppState,
    profile: &Profile,
    bytes: Arc<Vec<u8>>,
    index: &mr::MrpackIndex,
    dep_versions: &[mr::Version],
    dusk: bool,
    link: Option<PackLink>,
) -> Result<(), String> {
    let dirs = profile.dirs(&state.data_dir);
    // overrides (configs, shaderpacks, resourcepacks — and, for a pack
    // exported from here or Prism, the mods themselves)
    std::fs::create_dir_all(&dirs.root).map_err(|e| e.to_string())?;
    let overrides = {
        // shared with the unpacking thread, not copied: a pack can be large
        let (pack, root) = (bytes, dirs.root.clone());
        tokio::task::spawn_blocking(move || mr::extract_overrides(&pack, &root, |_| true))
            .await
            .map_err(|e| e.to_string())?
            .map_err(|e| format!("Couldn't unpack the modpack's files: {e}"))?
    };
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
        p.pack = link.map(|l| PackLink { files: pack_files(index, dep_versions, overrides), ..l });
    });

    let mut downloads = mr::index_downloads(index, &dirs.root);
    downloads.extend(mr::dependency_downloads(dep_versions, &dirs.root));
    let total = downloads.len() as u64;
    let profile_id = profile.id.clone();
    let app2 = app.clone();
    mr::download_files(&state.client, downloads, Some(&state.content_pool()), move |done, _| {
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
    Ok(())
}

/// The loader a pack runs on, or why Dusk can't run it.
fn pack_loader(index: &mr::MrpackIndex) -> Result<(Loader, Option<String>), String> {
    match index.loader() {
        Some(("fabric", v)) => Ok((Loader::Fabric, Some(v.clone()))),
        Some(("neoforge", v)) => Ok((Loader::NeoForge, Some(v.clone()))),
        // a Forge/Quilt pack's mods would sit in mods/ and never load
        Some((other, _)) => {
            let name = if other == "forge" { "Forge" } else { "Quilt" };
            Err(format!("This modpack needs {name}, which Dusk can't run yet (Fabric and NeoForge only)."))
        }
        None => Ok((Loader::Vanilla, None)),
    }
}

/// Everything a pack version put in the instance: its index files, its
/// dependency mods and the overrides it wrote.
fn pack_files(index: &mr::MrpackIndex, deps: &[mr::Version], overrides: Vec<String>) -> Vec<String> {
    let mut files: Vec<String> = index.files.iter().map(|f| f.path.clone()).collect();
    for v in deps {
        if let Some(f) = v.files.iter().find(|f| f.primary).or_else(|| v.files.first()) {
            files.push(format!("mods/{}", f.filename));
        }
    }
    for o in overrides {
        if !files.contains(&o) {
            files.push(o);
        }
    }
    files
}

/// Files a version change never overwrites or removes, even when the pack
/// ships them: the player's own settings and server list.
const PLAYER_FILES: &[&str] = &["options.txt", "servers.dat", "servers.dat_old"];

/// What a version change does on disk: out with the files the old version
/// shipped and the new one doesn't, then the new overrides — over the old
/// version's own files, never over one the player made or turned off.
/// Returns the overrides written.
fn swap_pack_files(root: &Path, old: &[String], new: &[String], bytes: &[u8]) -> Vec<String> {
    for rel in old {
        if new.contains(rel) || PLAYER_FILES.contains(&rel.as_str()) || !mr::inside(rel) {
            continue;
        }
        let _ = std::fs::remove_file(root.join(rel));
        let _ = std::fs::remove_file(root.join(format!("{rel}.disabled")));
    }
    mr::extract_overrides(bytes, root, |rel| {
        !PLAYER_FILES.contains(&rel)
            && !root.join(format!("{rel}.disabled")).exists()
            && (old.iter().any(|o| o == rel) || !root.join(rel).exists())
    })
    .unwrap_or_default()
}

/// Move an instance installed from a Modrinth pack to another of the pack's
/// versions, like the Modrinth App's and CurseForge's pack updates: the
/// game, loader and pack files follow the version; worlds, settings, and
/// mods and files the player added stay. A pack mod the player turned off
/// stays off when the new version keeps it.
#[tauri::command]
pub async fn update_modpack(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    version_id: String,
) -> Result<ProfileDto, String> {
    crate::worlds::ensure_closed(&state, &profile_id).await?;
    let (link, root) = {
        let store = state.profiles.lock().unwrap();
        let p = store.profiles.iter().find(|p| p.id == profile_id).ok_or("profile not found")?;
        let link = p.pack.clone().ok_or("This instance didn't come from a Modrinth pack.")?;
        (link, p.dirs(&state.data_dir).root)
    };
    let version = mr::version(&state.client, &version_id).await.map_err(|e| e.to_string())?;
    if version.project_id != link.project_id {
        return Err("That version belongs to another pack.".into());
    }
    let bytes = mr::download_mrpack(&state.client, &version).await.map_err(|e| e.to_string())?;
    let index = mr::parse_mrpack_index(&bytes).map_err(|e| e.to_string())?;
    let mc_version = index.minecraft_version().cloned().ok_or("modpack does not declare a minecraft version")?;
    let (loader, loader_version) = pack_loader(&index)?;
    let deps = required_deps(&state, &version).await;

    // what the installed version shipped; a link without the list (one
    // brought over from another launcher) reads it off that version's pack
    let old = if link.files.is_empty() {
        let old_pack = match mr::version(&state.client, &link.version_id).await {
            Ok(v) => mr::download_mrpack(&state.client, &v).await.ok(),
            Err(_) => None,
        };
        old_pack
            .and_then(|b| Some(pack_files(&mr::parse_mrpack_index(&b).ok()?, &[], mr::override_paths(&b))))
            .unwrap_or_default()
    } else {
        link.files.clone()
    };
    let new_listed = pack_files(&index, &deps, Vec::new());

    let overrides = {
        let (root, old, new) = (root.clone(), old.clone(), new_listed.clone());
        tokio::task::spawn_blocking(move || swap_pack_files(&root, &old, &new, &bytes))
            .await
            .map_err(|e| e.to_string())?
    };

    // the new version's files; one the player turned off stays off
    let mut downloads = mr::index_downloads(&index, &root);
    downloads.extend(mr::dependency_downloads(&deps, &root));
    downloads.retain(|d| !Path::new(&format!("{}.disabled", d.dest.display())).exists());
    let total = downloads.len() as u64;
    let (app2, id2) = (app.clone(), profile_id.clone());
    mr::download_files(&state.client, downloads, Some(&state.content_pool()), move |done, _| {
        let _ = app2.emit(
            "launch-progress",
            crate::commands::ProgressPayload {
                profile_id: id2.clone(),
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

    let mods = root.join("mods");
    let updated = state
        .patch_profile(&profile_id, |p| {
            p.game_version = mc_version;
            p.loader = loader;
            p.loader_version = loader_version;
            p.mod_filenames = std::fs::read_dir(&mods)
                .map(|rd| {
                    rd.flatten()
                        .map(|e| e.file_name().to_string_lossy().into_owned())
                        .filter(|n| n.ends_with(".jar"))
                        .collect()
                })
                .unwrap_or_default();
            p.pack = Some(PackLink {
                project_id: version.project_id.clone(),
                version_id: version.id.clone(),
                version_number: version.version_number.clone(),
                files: pack_files(&index, &deps, overrides),
            });
        })
        .ok_or("profile not found")?;
    tracing::info!(profile = %profile_id, version = %version.version_number, "modpack version changed");
    Ok(dto(&updated, &state.data_dir))
}

// ── export ─────────────────────────────────────────────────────────────────

/// What travels with an exported instance. Everything under these goes into
/// the pack's `overrides/`; the game's own installs (versions, natives) and
/// the shared caches never do, and neither do worlds, logs or screenshots —
/// the same cut Prism makes.
const EXPORT_DIRS: &[&str] = &["mods", "config", "resourcepacks", "shaderpacks", "datapacks"];
const EXPORT_FILES: &[&str] = &["options.txt", "servers.dat"];

/// Export an instance as a `.mrpack` (native save dialog): the version and
/// loader pinned in `modrinth.index.json`, the mods and packs Modrinth hosts
/// linked by hash (so the pack is small and installers fetch them), and the
/// rest — configs, options, files from elsewhere — as overrides. It opens in
/// Prism, the Modrinth app, or back here. Resolves to a summary of what went
/// in, or `None` when the dialog is cancelled.
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

    let (index, embedded) = pack_index(&state, &profile).await?;
    let index_json = serde_json::to_string_pretty(&index).map_err(|e| e.to_string())?;
    let n_linked = index.files.len();
    let n_embedded = embedded.len();
    tokio::task::spawn_blocking(move || {
        let written = std::fs::File::create(&out_path)
            .map_err(|e| e.to_string())
            .and_then(|f| write_mrpack(std::io::BufWriter::new(f), &index_json, &embedded))
            .and_then(|mut w| w.flush().map_err(|e| e.to_string()));
        if written.is_err() {
            // half a zip is no pack
            let _ = std::fs::remove_file(&out_path);
        }
        written
    })
    .await
    .map_err(|e| e.to_string())??;
    tracing::info!(pack = %profile.name, linked = n_linked, embedded = n_embedded, "instance exported");
    Ok(Some(format!("{n_linked} linked from Modrinth, {n_embedded} included")))
}

/// An instance as a pack: its version and loader, the mods and packs
/// Modrinth hosts linked by hash, and the rest of [`export_files`] to embed.
/// Offline, or with Modrinth down, everything is embedded.
async fn pack_index(state: &AppState, profile: &Profile) -> Result<(mr::MrpackIndex, Vec<(String, PathBuf)>), String> {
    let mut dependencies = std::collections::HashMap::new();
    dependencies.insert("minecraft".to_string(), profile.game_version.clone());
    let loader_key = match profile.loader {
        Loader::Fabric => Some("fabric-loader"),
        Loader::NeoForge => Some("neoforge"),
        Loader::Vanilla => None,
    };
    if let (Some(key), Some(v)) = (loader_key, &profile.loader_version) {
        dependencies.insert(key.to_string(), v.clone());
    }

    let root = profile.dirs(&state.data_dir).root;
    // what goes in, and the sha1 of each file Modrinth might host
    let (files, hashed) = tokio::task::spawn_blocking(move || {
        let files = export_files(&root);
        let hashed: Vec<(String, String)> = files
            .iter()
            .filter(|(rel, _)| linkable(rel))
            .filter_map(|(rel, path)| {
                let bytes = std::fs::read(path).ok()?;
                Some((rel.clone(), fasterlauncher_core::download::sha1_hex(&bytes)))
            })
            .collect();
        (files, hashed)
    })
    .await
    .map_err(|e| e.to_string())?;
    let hashes: Vec<String> = hashed.iter().map(|(_, h)| h.clone()).collect();
    let found = match mr::version_files(&state.client, &hashes).await {
        Ok(found) => found,
        Err(e) => {
            tracing::warn!(error = %e, "couldn't look the pack's files up on Modrinth; embedding them");
            Default::default()
        }
    };
    let (linked, embedded) = split_export(files, &hashed, &found);
    let index = mr::MrpackIndex {
        format_version: 1,
        game: "minecraft".into(),
        version_id: "1.0.0".into(),
        name: profile.name.clone(),
        files: linked,
        dependencies,
    };
    Ok((index, embedded))
}

/// What a shared instance carries besides its links: the configs and the
/// server list. Mods and packs Modrinth doesn't host stay home (they aren't
/// ours to hand on, and a code holds 4 MB), and so does options.txt —
/// render distance and keybinds are the player's, not the pack's.
fn shareable(rel: &str) -> bool {
    !(rel == "options.txt" || ["mods/", "resourcepacks/", "shaderpacks/"].iter().any(|d| rel.starts_with(d)))
}

/// The most a share code holds (the Dusk service refuses more).
const SHARE_MAX_BYTES: usize = 4 * 1024 * 1024;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SharedInstance {
    pub code: String,
    /// mods and packs the friend gets from Modrinth
    pub linked: usize,
    /// ones Modrinth doesn't host, which they'll have to get themselves
    pub left_out: Vec<String>,
}

/// Share an instance with a code: its version, loader, the mods and packs
/// Modrinth hosts and its configs go up to the Dusk service as a small pack,
/// and anyone signed in can install it from the code for 30 days.
#[tauri::command]
pub async fn share_instance(state: State<'_, AppState>, profile_id: String) -> Result<SharedInstance, String> {
    let profile = {
        let store = state.profiles.lock().unwrap();
        store.profiles.iter().find(|p| p.id == profile_id).cloned().ok_or("profile not found")?
    };
    let (index, embedded) = pack_index(&state, &profile).await?;
    let (embedded, left): (Vec<_>, Vec<_>) = embedded.into_iter().partition(|(rel, _)| shareable(rel));
    let left_out: Vec<String> = left
        .into_iter()
        .filter(|(rel, _)| rel != "options.txt" && !rel.ends_with(".disabled"))
        .map(|(rel, _)| rel.rsplit('/').next().unwrap_or(&rel).to_string())
        .collect();
    let index_json = serde_json::to_string_pretty(&index).map_err(|e| e.to_string())?;
    let linked = index.files.len();
    let bytes = tokio::task::spawn_blocking(move || {
        write_mrpack(std::io::Cursor::new(Vec::new()), &index_json, &embedded).map(std::io::Cursor::into_inner)
    })
    .await
    .map_err(|e| e.to_string())??;
    if bytes.len() > SHARE_MAX_BYTES {
        return Err(format!(
            "This instance's configs come to {:.1} MB and a code holds 4 MB — export it as a .mrpack instead.",
            bytes.len() as f64 / 1_048_576.0
        ));
    }
    #[derive(Deserialize)]
    struct Shared {
        code: String,
    }
    let resp = crate::dusk::send(&state, reqwest::Method::POST, "/v1/packs", Some(crate::dusk::Body::Raw(bytes, "application/zip")))
        .await?;
    let Shared { code } = crate::dusk::parse(resp).await?;
    tracing::info!(pack = %profile.name, linked, left_out = left_out.len(), "instance shared");
    // read out as two halves: ABCD-EFGH
    let code = if code.len() == 8 { format!("{}-{}", &code[..4], &code[4..]) } else { code };
    Ok(SharedInstance { code, linked, left_out })
}

/// Install an instance a friend shared, from its code.
#[tauri::command]
pub async fn import_shared_instance(app: AppHandle, state: State<'_, AppState>, code: String) -> Result<ProfileDto, String> {
    let code: String = code.chars().filter(|c| c.is_ascii_alphanumeric()).collect();
    if code.is_empty() {
        return Err("Type the code your friend sent.".into());
    }
    let resp = crate::dusk::send(&state, reqwest::Method::GET, &format!("/v1/packs/{code}"), None).await?;
    if !resp.status().is_success() {
        return Err(crate::dusk::parse::<serde_json::Value>(resp).await.unwrap_err());
    }
    let bytes = resp.bytes().await.map_err(|e| format!("Couldn't download the instance: {e}"))?;
    install_mrpack_bytes(app, state, bytes.into(), &[], None, false, None).await
}

/// Never worth shipping: the OS's folder litter.
const EXPORT_JUNK: &[&str] = &[".DS_Store", "Thumbs.db", "desktop.ini"];

/// Every file an export takes, as (path in the pack, path on disk): the
/// content and config folders and a few files at the root. DuskClient's own
/// jar stays out — the launcher adds it at launch, and it isn't ours to
/// hand out inside someone's pack.
fn export_files(root: &Path) -> Vec<(String, PathBuf)> {
    fn walk(root: &Path, dir: &Path, out: &mut Vec<(String, PathBuf)>) {
        let Ok(rd) = std::fs::read_dir(dir) else { return };
        for entry in rd.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            if EXPORT_JUNK.contains(&name.as_str()) {
                continue;
            }
            if path.is_dir() {
                walk(root, &path, out);
                continue;
            }
            let Ok(rel) = path.strip_prefix(root) else { continue };
            let rel = rel.to_string_lossy().replace('\\', "/");
            if rel.strip_prefix("mods/").is_some_and(|f| crate::cosmetics::CLIENT_MOD_JARS.contains(&f)) {
                continue;
            }
            out.push((rel, path));
        }
    }
    let mut out = Vec::new();
    for dir in EXPORT_DIRS {
        walk(root, &root.join(dir), &mut out);
    }
    for name in EXPORT_FILES {
        let p = root.join(name);
        if p.is_file() {
            out.push((name.to_string(), p));
        }
    }
    out.sort();
    out
}

/// Whether Modrinth could host this file: a mod or pack right in its folder,
/// turned on (a pack's file list has no "off").
fn linkable(rel: &str) -> bool {
    ["mods/", "resourcepacks/", "shaderpacks/"].iter().any(|dir| {
        rel.strip_prefix(dir).is_some_and(|f| !f.contains('/') && !f.ends_with(".disabled"))
    })
}

/// Split the export into the files linked by hash (`found`: Modrinth's
/// version per sha1) and those embedded as overrides.
fn split_export(
    files: Vec<(String, PathBuf)>,
    hashed: &[(String, String)],
    found: &std::collections::HashMap<String, mr::Version>,
) -> (Vec<mr::MrpackFile>, Vec<(String, PathBuf)>) {
    let sha1_of: std::collections::HashMap<&str, &str> =
        hashed.iter().map(|(rel, h)| (rel.as_str(), h.as_str())).collect();
    let mut linked = Vec::new();
    let mut embedded = Vec::new();
    for (rel, path) in files {
        let hosted = sha1_of.get(rel.as_str()).and_then(|sha1| {
            let v = found.get(*sha1)?;
            let f = v.files.iter().find(|f| f.hashes.get("sha1").map(String::as_str) == Some(*sha1))?;
            // the format wants both hashes
            let sha512 = f.hashes.get("sha512")?;
            Some(mr::MrpackFile {
                path: rel.clone(),
                hashes: [("sha1".to_string(), sha1.to_string()), ("sha512".to_string(), sha512.clone())].into(),
                env: None,
                downloads: vec![f.url.clone()],
                file_size: f.size,
            })
        });
        match hosted {
            Some(f) => linked.push(f),
            None => embedded.push((rel, path)),
        }
    }
    (linked, embedded)
}

/// Write the pack: the index, then each embedded file under `overrides/`,
/// streamed rather than read whole.
fn write_mrpack<W: Write + std::io::Seek>(out: W, index_json: &str, embedded: &[(String, PathBuf)]) -> Result<W, String> {
    let mut zip = zip::ZipWriter::new(out);
    let opts = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    zip.start_file("modrinth.index.json", opts).map_err(|e| e.to_string())?;
    zip.write_all(index_json.as_bytes()).map_err(|e| e.to_string())?;
    for (rel, path) in embedded {
        let mut src = std::fs::File::open(path).map_err(|e| format!("{rel}: {e}"))?;
        let big = src.metadata().map(|m| m.len() >= u32::MAX as u64).unwrap_or(false);
        zip.start_file(format!("overrides/{rel}"), opts.large_file(big)).map_err(|e| e.to_string())?;
        std::io::copy(&mut src, &mut zip).map_err(|e| format!("{rel}: {e}"))?;
    }
    zip.finish().map_err(|e| e.to_string())
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
    fn an_export_links_what_modrinth_hosts_and_embeds_the_rest() {
        let root = std::env::temp_dir().join(format!("dusk-export-{}", now_millis()));
        let write = |rel: &str, body: &[u8]| {
            let f = root.join(rel);
            std::fs::create_dir_all(f.parent().unwrap()).unwrap();
            std::fs::write(f, body).unwrap();
        };
        write("mods/sodium.jar", b"sodium");
        write("mods/homemade.jar", b"mine");
        write("mods/off.jar.disabled", b"sodium");
        write("mods/duskclient-1.21.1.jar", b"ours");
        write("mods/.DS_Store", b"x");
        write("resourcepacks/Stay True.zip", b"pack");
        write("config/sodium-options.json", b"{}");
        write("options.txt", b"fov:1");
        write("saves/World/level.dat", b"x");

        let files = export_files(&root);
        let rels: Vec<&str> = files.iter().map(|(r, _)| r.as_str()).collect();
        assert_eq!(
            rels,
            [
                "config/sodium-options.json",
                "mods/homemade.jar",
                "mods/off.jar.disabled",
                "mods/sodium.jar",
                "options.txt",
                "resourcepacks/Stay True.zip",
            ]
        );
        let hashed: Vec<(String, String)> = files
            .iter()
            .filter(|(rel, _)| linkable(rel))
            .map(|(rel, p)| (rel.clone(), fasterlauncher_core::download::sha1_hex(&std::fs::read(p).unwrap())))
            .collect();
        assert_eq!(hashed.len(), 3);
        let sodium = fasterlauncher_core::download::sha1_hex(b"sodium");
        let pack = fasterlauncher_core::download::sha1_hex(b"pack");
        let version = |sha1: &str, sha512: Option<&str>| -> mr::Version {
            let mut hashes = serde_json::json!({ "sha1": sha1 });
            if let Some(h) = sha512 {
                hashes["sha512"] = h.into();
            }
            serde_json::from_value(serde_json::json!({
                "id": "v", "name": "v",
                "files": [{ "url": format!("https://cdn.modrinth.com/{sha1}"), "filename": "f", "size": 6, "hashes": hashes }],
            }))
            .unwrap()
        };
        // the pack's listing has no sha512, so it can't be linked
        let found = [(sodium.clone(), version(&sodium, Some("s512"))), (pack.clone(), version(&pack, None))].into();
        let (linked, embedded) = split_export(files, &hashed, &found);
        assert_eq!(linked.len(), 1);
        assert_eq!(linked[0].path, "mods/sodium.jar");
        assert_eq!(linked[0].hashes["sha512"], "s512");
        assert_eq!(linked[0].downloads, [format!("https://cdn.modrinth.com/{sodium}")]);
        let embedded_rels: Vec<&str> = embedded.iter().map(|(r, _)| r.as_str()).collect();
        assert_eq!(
            embedded_rels,
            ["config/sodium-options.json", "mods/homemade.jar", "mods/off.jar.disabled", "options.txt", "resourcepacks/Stay True.zip"]
        );

        let out = root.join("out.mrpack");
        write_mrpack(std::fs::File::create(&out).unwrap(), "{}", &embedded).unwrap();
        let mut zip = zip::ZipArchive::new(std::fs::File::open(&out).unwrap()).unwrap();
        assert_eq!(zip.len(), 6);
        let mut body = String::new();
        std::io::Read::read_to_string(&mut zip.by_name("overrides/options.txt").unwrap(), &mut body).unwrap();
        assert_eq!(body, "fov:1");

        // a share code carries the configs; the mods and packs Modrinth
        // doesn't host and the player's own options stay home
        let shared: Vec<&str> = embedded_rels.iter().copied().filter(|r| shareable(r)).collect();
        assert_eq!(shared, ["config/sodium-options.json"]);
        let bytes = write_mrpack(std::io::Cursor::new(Vec::new()), "{}", &[]).unwrap().into_inner();
        assert!(bytes.starts_with(b"PK\x03\x04"), "the service checks for a zip");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn a_version_change_swaps_the_packs_files_and_leaves_the_players() {
        let root = std::env::temp_dir().join(format!("dusk-swap-{}", now_millis()));
        let write = |rel: &str, text: &str| {
            let f = root.join(rel);
            std::fs::create_dir_all(f.parent().unwrap()).unwrap();
            std::fs::write(f, text).unwrap();
        };
        // what version 1 put there, and what the player did since
        write("mods/old.jar", "old");
        write("mods/kept.jar", "kept");
        write("mods/gone.jar.disabled", "turned off, then dropped by the pack");
        write("config/pack.toml", "v1");
        write("config/theirs.toml", "player's");
        write("options.txt", "player's options");
        write("mods/mine.jar", "player's mod");
        write("config/extra.toml.disabled", "player turned this off");
        let old: Vec<String> = ["mods/old.jar", "mods/kept.jar", "mods/gone.jar", "config/pack.toml", "options.txt"]
            .map(String::from)
            .to_vec();

        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        for (name, text) in [
            ("overrides/config/pack.toml", "v2"),
            ("overrides/config/theirs.toml", "pack's"),
            ("overrides/config/new.toml", "new"),
            ("overrides/config/extra.toml", "pack's extra"),
            ("overrides/options.txt", "pack's options"),
        ] {
            zip.start_file(name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(text.as_bytes()).unwrap();
        }
        let bytes = zip.finish().unwrap().into_inner();
        let new: Vec<String> = ["mods/kept.jar"].map(String::from).to_vec();

        let written = swap_pack_files(&root, &old, &new, &bytes);
        let read = |rel: &str| std::fs::read_to_string(root.join(rel)).ok();
        // dropped by the new version, on or off
        assert!(read("mods/old.jar").is_none() && read("mods/gone.jar.disabled").is_none());
        assert_eq!(read("mods/kept.jar").as_deref(), Some("kept"));
        // the pack's own file follows it; a new one arrives
        assert_eq!(read("config/pack.toml").as_deref(), Some("v2"));
        assert_eq!(read("config/new.toml").as_deref(), Some("new"));
        // the player's stay as they were
        assert_eq!(read("config/theirs.toml").as_deref(), Some("player's"));
        assert_eq!(read("options.txt").as_deref(), Some("player's options"));
        assert_eq!(read("mods/mine.jar").as_deref(), Some("player's mod"));
        assert!(read("config/extra.toml").is_none());
        written.iter().for_each(|w| assert!(["config/pack.toml", "config/new.toml"].contains(&w.as_str()), "{w}"));
        let _ = std::fs::remove_dir_all(&root);
    }

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
