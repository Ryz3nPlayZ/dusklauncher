//! Modrinth modpack commands: search + one-click install.

use crate::appstate::AppState;
use crate::commands::{dto, ProfileDto};
use fasterlauncher_core::modrinth as mr;
use fasterlauncher_core::profile::{default_jvm_args, Loader, Profile};
use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, State};

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
    let mut groups: Vec<Vec<String>> = vec![vec!["project_type:modpack".into()]];
    if !facets.categories.is_empty() {
        groups.push(facets.categories.iter().map(|c| format!("categories:{c}")).collect());
    }
    if !facets.versions.is_empty() {
        groups.push(facets.versions.iter().map(|v| format!("versions:{v}")).collect());
    }
    if !facets.loaders.is_empty() {
        groups.push(facets.loaders.iter().map(|l| format!("loaders:{l}")).collect());
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
    install_version_inner(app, state, version).await
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackVersionDto {
    pub id: String,
    pub name: String,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub published: Option<String>,
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
            game_versions: v.game_versions,
            loaders: v.loaders,
            published: v.published,
        })
        .collect())
}

/// Install a specific Modrinth version of a pack (from the picker modal).
#[tauri::command]
pub async fn install_modpack_version(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    version_id: String,
) -> Result<ProfileDto, String> {
    let version = mr::version(&state.client, &version_id)
        .await
        .map_err(|e| e.to_string())?;
    // guard: version must belong to the requested project is not enforced by
    // the API shape here; the id param documents intent and keeps the
    // frontend call self-describing.
    let _ = id;
    install_version_inner(app, state, &version).await
}

async fn install_version_inner(
    app: AppHandle,
    state: State<'_, AppState>,
    version: &mr::Version,
) -> Result<ProfileDto, String> {

    let bytes = mr::download_mrpack(&state.client, version)
        .await
        .map_err(|e| e.to_string())?;
    let index = mr::parse_mrpack_index(&bytes).map_err(|e| e.to_string())?;

    let mc_version = index
        .minecraft_version()
        .cloned()
        .ok_or("modpack does not declare a minecraft version")?;
    let (loader, loader_version) = match index.loader() {
        Some(("fabric", v)) => (Loader::Fabric, Some(v.clone())),
        _ => (Loader::Vanilla, None), // neoforge/forge not supported yet (roadmap)
    };

    // unique name from the pack title
    let base_name = index.name.to_uppercase();
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
    };
    let dirs = profile.dirs(&state.data_dir);
    {
        let mut store = state.profiles.lock().unwrap();
        store.profiles.push(profile.clone());
        state.save_profiles(&store);
    }

    // overrides (configs, shaderpacks, resourcepacks)
    std::fs::create_dir_all(&dirs.root).map_err(|e| e.to_string())?;
    let _ = mr::extract_overrides(&bytes, &dirs.root);

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

    let mut downloads = mr::index_downloads(&index, &dirs.root);
    downloads.extend(mr::dependency_downloads(&dep_versions, &dirs.root));
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

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
