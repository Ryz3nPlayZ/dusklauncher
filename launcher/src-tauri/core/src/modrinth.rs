//! Modrinth API: modpack search and one-click install (.mrpack).
//!
//! Search:  GET /v2/search?query&facets&index&offset&limit
//! Versions: GET /v2/project/{id}/version
//! Install: download the primary .mrpack, parse modrinth.index.json,
//!          download every file into the profile dir, extract overrides.

use crate::download::{self, Download};
use crate::Result;
use serde::{Deserialize, Serialize};
use std::path::Path;

pub const MODRINTH_API: &str = "https://api.modrinth.com/v2";
const USER_AGENT: &str = concat!("DuskLauncher/", env!("CARGO_PKG_VERSION"), " (github.com/dusklauncher)");

/// Modrinth calls are small JSON requests, so a request still silent after
/// 10s is a dead connection — a pooled keep-alive socket that went stale
/// while the launcher sat open through a sleep or a network change — not a
/// slow answer. Retry once on a fresh connection instead of leaving the
/// browse page on LOADING… until the client-wide 30s timeout.
trait SendRetry {
    async fn send_retry(self) -> reqwest::Result<reqwest::Response>;
}

impl SendRetry for reqwest::RequestBuilder {
    async fn send_retry(self) -> reqwest::Result<reqwest::Response> {
        let Some(again) = self.try_clone() else { return self.send().await };
        match self.timeout(std::time::Duration::from_secs(10)).send().await {
            Err(e) if e.is_timeout() || e.is_connect() || e.is_request() => {
                again.timeout(std::time::Duration::from_secs(15)).send().await
            }
            other => other,
        }
    }
}

/// Turn a non-2xx Modrinth response into an error carrying the status AND a
/// body snippet. A bare `error_for_status` hides the reason (rate limit?
/// bad facet? outage?) and the UI can only shrug.
async fn check(resp: reqwest::Response, what: &str) -> Result<reqwest::Response> {
    if resp.status().is_success() {
        return Ok(resp);
    }
    let status = resp.status();
    let body: String = resp.text().await.unwrap_or_default().chars().take(240).collect();
    Err(crate::Error::Other(format!(
        "Modrinth {what} failed (HTTP {status}): {body}"
    )))
}

/// Decode a 2xx Modrinth response as JSON, but report WHAT came back when it
/// isn't parseable. A bare reqwest decode error ("error decoding response
/// body") hides whether we got a block page, an empty body, or a schema
/// change — the status, content type, and a body snippet answer that.
async fn decode<T>(resp: reqwest::Response, what: &str) -> Result<T>
where
    T: serde::de::DeserializeOwned,
{
    let status = resp.status();
    let ctype = resp
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("?")
        .to_string();
    let text = resp.text().await.unwrap_or_default();
    serde_json::from_str(&text).map_err(|e| decode_error(what, status, &ctype, &text, &e))
}

/// Sync formatter for a failed Modrinth decode, so the message itself is unit
/// testable without spinning a runtime.
fn decode_error(
    what: &str,
    status: reqwest::StatusCode,
    ctype: &str,
    text: &str,
    e: &serde_json::Error,
) -> crate::Error {
    let snippet: String = text.chars().take(240).collect();
    crate::Error::Other(format!(
        "Modrinth {what}: bad response (HTTP {status}, {ctype}): {snippet} … parse error: {e}"
    ))
}

/// Modrinth search hits are snake_case on the wire (project_id, icon_url,
/// date_modified) — no rename_all here. (A camelCase rename once broke every
/// search with a bare decode error.)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchHit {
    pub project_id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub author: String,
    pub downloads: u64,
    pub follows: u64,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub date_modified: Option<String>,
    #[serde(default)]
    pub categories: Vec<String>,
    #[serde(default)]
    pub versions: Vec<String>,
    #[serde(default)]
    pub loaders: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResponse {
    pub hits: Vec<SearchHit>,
    pub offset: u64,
    pub limit: u64,
    pub total_hits: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct SearchParams {
    pub query: String,
    /// AND of OR-groups: [["project_type:modpack"], ["categories:adventure"]]
    pub facets: Vec<Vec<String>>,
    pub index: String, // relevance | downloads | follows | newest | updated
    pub offset: u64,
    pub limit: u64,
}

pub async fn search(client: &reqwest::Client, params: &SearchParams) -> Result<SearchResponse> {
    let facets = serde_json::to_string(&params.facets)?;
    let resp = client
        .get(format!("{MODRINTH_API}/search"))
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .query(&[
            ("query", params.query.as_str()),
            ("facets", facets.as_str()),
            ("index", params.index.as_str()),
            ("offset", &params.offset.to_string()),
            ("limit", &params.limit.to_string()),
        ])
        .send_retry()
        .await?;
    decode(check(resp, "search").await?, "search").await
}

// ── versions ───────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionFile {
    pub url: String,
    pub filename: String,
    #[serde(default)]
    pub primary: bool,
    #[serde(default)]
    pub size: u64,
    #[serde(default)]
    pub hashes: std::collections::HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionDependency {
    #[serde(default)]
    pub version_id: Option<String>,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub dependency_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Version {
    pub id: String,
    #[serde(default)]
    pub project_id: String,
    pub name: String,
    #[serde(default)]
    pub version_number: String,
    #[serde(default)]
    pub changelog: Option<String>,
    #[serde(default)]
    pub files: Vec<VersionFile>,
    #[serde(default)]
    pub dependencies: Vec<VersionDependency>,
    #[serde(default)]
    pub game_versions: Vec<String>,
    #[serde(default)]
    pub loaders: Vec<String>,
    #[serde(default, rename = "date_published")]
    pub published: Option<String>,
    /// "release" | "beta" | "alpha"
    #[serde(default)]
    pub version_type: String,
    #[serde(default)]
    pub downloads: u64,
}

pub async fn project_versions(client: &reqwest::Client, project_id: &str) -> Result<Vec<Version>> {
    let resp = client
        .get(format!("{MODRINTH_API}/project/{project_id}/version"))
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .send_retry()
        .await?;
    decode(check(resp, "project versions").await?, "project versions").await
}

pub async fn version(client: &reqwest::Client, version_id: &str) -> Result<Version> {
    let resp = client
        .get(format!("{MODRINTH_API}/version/{version_id}"))
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .send_retry()
        .await?;
    decode(check(resp, "version").await?, "version").await
}

/// Which Modrinth version each file hash belongs to — `POST /version_files`.
/// Hashes Modrinth doesn't know (local jars, hand-built packs) are simply
/// absent from the map.
pub async fn version_files(
    client: &reqwest::Client,
    hashes: &[String],
) -> Result<std::collections::HashMap<String, Version>> {
    if hashes.is_empty() {
        return Ok(Default::default());
    }
    let resp = client
        .post(format!("{MODRINTH_API}/version_files"))
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .json(&serde_json::json!({ "hashes": hashes, "algorithm": "sha1" }))
        .send_retry()
        .await?;
    decode(check(resp, "version lookup").await?, "version lookup").await
}

/// The newest version of each hash's project that still fits this game
/// version and one of `loaders` — `POST /version_files/update`. A hash
/// already on its newest fitting version maps to that same version; unknown
/// hashes are absent. Always pass loaders: without them Modrinth happily
/// answers a Fabric jar with the NeoForge build.
pub async fn version_files_update(
    client: &reqwest::Client,
    hashes: &[String],
    loaders: &[&str],
    game_version: &str,
) -> Result<std::collections::HashMap<String, Version>> {
    if hashes.is_empty() {
        return Ok(Default::default());
    }
    let body = serde_json::json!({
        "hashes": hashes,
        "algorithm": "sha1",
        "loaders": loaders,
        "game_versions": [game_version],
    });
    let resp = client
        .post(format!("{MODRINTH_API}/version_files/update"))
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .json(&body)
        .send_retry()
        .await?;
    decode(check(resp, "update lookup").await?, "update lookup").await
}

// ── tags (the filter vocabularies) ─────────────────────────────────────────

/// One `/tag/category` entry: `project_type` says which browse page it
/// belongs to; `header` groups it ("categories", "features", "resolutions",
/// "performance impact").
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct CategoryTag {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub project_type: String,
    #[serde(default)]
    pub header: String,
}

/// One `/tag/loader` entry — `supported_project_types` tells whether a
/// loader means anything for modpacks / mods / shaders.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct LoaderTag {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub supported_project_types: Vec<String>,
}

/// One `/tag/game_version` entry, newest first on the wire.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct GameVersionTag {
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub version_type: String, // release | snapshot | alpha | beta
    #[serde(default)]
    pub major: bool,
}

async fn tag<T: serde::de::DeserializeOwned>(client: &reqwest::Client, name: &str) -> Result<Vec<T>> {
    let resp = client
        .get(format!("{MODRINTH_API}/tag/{name}"))
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .send_retry()
        .await?;
    let what = format!("tag/{name}");
    decode(check(resp, &what).await?, &what).await
}

pub async fn category_tags(client: &reqwest::Client) -> Result<Vec<CategoryTag>> {
    tag(client, "category").await
}

pub async fn loader_tags(client: &reqwest::Client) -> Result<Vec<LoaderTag>> {
    tag(client, "loader").await
}

pub async fn game_version_tags(client: &reqwest::Client) -> Result<Vec<GameVersionTag>> {
    tag(client, "game_version").await
}

// ── project details (detail page: body, gallery, links, compat) ────────────

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct GalleryImage {
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub title: Option<String>,
}

/// Modrinth project on the wire — everything optional except id so a schema
/// addition upstream degrades to an empty section, never a decode failure.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Project {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub slug: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub downloads: u64,
    #[serde(default)]
    pub followers: u64,
    #[serde(default)]
    pub categories: Vec<String>,
    #[serde(default)]
    pub additional_categories: Vec<String>,
    #[serde(default)]
    pub loaders: Vec<String>,
    #[serde(default)]
    pub game_versions: Vec<String>,
    #[serde(default)]
    pub gallery: Vec<GalleryImage>,
    #[serde(default)]
    pub discord_url: Option<String>,
    #[serde(default)]
    pub issues_url: Option<String>,
    #[serde(default)]
    pub source_url: Option<String>,
    #[serde(default)]
    pub wiki_url: Option<String>,
    #[serde(default)]
    pub client_side: String,
    #[serde(default)]
    pub server_side: String,
    #[serde(default)]
    pub published: Option<String>,
    #[serde(default)]
    pub updated: Option<String>,
}

pub async fn project(client: &reqwest::Client, project_id: &str) -> Result<Project> {
    let resp = client
        .get(format!("{MODRINTH_API}/project/{project_id}"))
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .send_retry()
        .await?;
    decode(check(resp, "project").await?, "project").await
}

/// Newest version of a project playable on this game version + loader —
/// the newest release, or the newest beta/alpha when there is no release.
/// `loader` is the Modrinth loader slug (`fabric`, `forge`, ...).
/// Pass `None` for loader-agnostic types (resource packs, shaders).
pub async fn project_version_for(
    client: &reqwest::Client,
    project_id: &str,
    game_version: &str,
    loader: &str,
) -> Result<Version> {
    project_version_for_loader(client, project_id, game_version, Some(loader)).await
}

pub async fn project_version_for_loader(
    client: &reqwest::Client,
    project_id: &str,
    game_version: &str,
    loader: Option<&str>,
) -> Result<Version> {
    let versions = project_versions_for_loader(client, project_id, game_version, loader).await?;
    newest_preferring_release(versions).ok_or_else(|| {
        crate::Error::Other(format!("no release for Minecraft {game_version}"))
    })
}

/// Every version of a project playable on this game version + loader,
/// newest first.
pub async fn project_versions_for_loader(
    client: &reqwest::Client,
    project_id: &str,
    game_version: &str,
    loader: Option<&str>,
) -> Result<Vec<Version>> {
    let mut req = client
        .get(format!("{MODRINTH_API}/project/{project_id}/version"))
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .query(&[
            ("game_versions", format!("[\"{game_version}\"]")),
            ("include_changelog", "false".to_string()),
        ]);
    if let Some(l) = loader {
        req = req.query(&[("loaders", format!("[\"{l}\"]"))]);
    }
    let resp = req.send_retry()
        .await?;
    decode(check(resp, "project version lookup").await?, "project version lookup").await
}

/// The newest release build; a beta or alpha only when the project has no
/// release at all for that game version (then the newest of those).
/// Modrinth lists versions newest first.
pub fn newest_preferring_release(versions: Vec<Version>) -> Option<Version> {
    let release = versions.iter().position(|v| v.version_type == "release");
    let mut versions = versions;
    match release {
        Some(i) => Some(versions.swap_remove(i)),
        None => versions.into_iter().next(),
    }
}

// ── .mrpack ────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MrpackFile {
    pub path: String,
    pub hashes: std::collections::HashMap<String, String>,
    #[serde(default)]
    pub env: Option<serde_json::Value>,
    pub downloads: Vec<String>,
    /// 0 when a pack leaves it out
    #[serde(default, rename = "fileSize")]
    pub file_size: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MrpackIndex {
    #[serde(rename = "formatVersion")]
    pub format_version: u32,
    pub game: String,
    #[serde(rename = "versionId")]
    pub version_id: String,
    pub name: String,
    #[serde(default)]
    pub files: Vec<MrpackFile>,
    #[serde(default)]
    pub dependencies: std::collections::HashMap<String, String>,
}

impl MrpackIndex {
    pub fn minecraft_version(&self) -> Option<&String> {
        self.dependencies.get("minecraft")
    }
    pub fn loader(&self) -> Option<(&str, &String)> {
        if let Some(v) = self.dependencies.get("fabric-loader") {
            return Some(("fabric", v));
        }
        if let Some(v) = self.dependencies.get("neoforge") {
            return Some(("neoforge", v));
        }
        if let Some(v) = self.dependencies.get("forge") {
            return Some(("forge", v));
        }
        if let Some(v) = self.dependencies.get("quilt-loader") {
            return Some(("quilt", v));
        }
        None
    }
}

/// Fetch the primary .mrpack bytes of a version.
pub async fn download_mrpack(client: &reqwest::Client, ver: &Version) -> Result<Vec<u8>> {
    let file = ver
        .files
        .iter()
        .find(|f| f.primary)
        .or_else(|| ver.files.first())
        .ok_or_else(|| crate::Error::Other("version has no files".into()))?;
    let bytes = check(
        client
            .get(&file.url)
            .header(reqwest::header::USER_AGENT, USER_AGENT)
            .send_retry()
        .await?,
        "modpack download",
    )
    .await?
    .bytes()
    .await?;
    // the buffer itself, not a copy of it
    Ok(bytes.into())
}

/// Parse `modrinth.index.json` out of an .mrpack (zip) in memory.
pub fn parse_mrpack_index(bytes: &[u8]) -> Result<MrpackIndex> {
    let reader = std::io::Cursor::new(bytes);
    let mut zip = zip::ZipArchive::new(reader)
        .map_err(|_| crate::Error::Other("That file isn't a modpack: it isn't a zip.".into()))?;
    if zip.index_for_name("modrinth.index.json").is_none() {
        // a CurseForge pack names its mods by CurseForge id only, which needs
        // CurseForge's API key to download
        let why = if zip.index_for_name("manifest.json").is_some() {
            "That's a CurseForge pack; Dusk installs Modrinth packs (.mrpack). Install it in the CurseForge app, \
             then bring it over with FROM ANOTHER LAUNCHER, or look for the pack on Modrinth."
        } else {
            "That zip isn't a Modrinth modpack: it has no modrinth.index.json."
        };
        return Err(crate::Error::Other(why.into()));
    }
    let mut entry = zip.by_name("modrinth.index.json").map_err(|e| crate::Error::Other(e.to_string()))?;
    let mut text = String::new();
    std::io::Read::read_to_string(&mut entry, &mut text)?;
    let mut index: MrpackIndex = serde_json::from_str(&text)?;
    // the format's rules: a path leaving the instance fails the whole pack,
    // and a file the client can't use (a server-only mod) isn't fetched
    if let Some(bad) = index.files.iter().find(|f| !inside(&f.path)) {
        return Err(crate::Error::Other(format!(
            "This modpack wants to put a file outside its instance ({}), so it wasn't installed.",
            bad.path
        )));
    }
    index.files.retain(|f| f.env.as_ref().and_then(|e| e.get("client")).and_then(|c| c.as_str()) != Some("unsupported"));
    Ok(index)
}

/// The paths an .mrpack's `overrides/` and `client-overrides/` hold,
/// relative and `/`-separated, without writing anything.
pub fn override_paths(bytes: &[u8]) -> Vec<String> {
    let Ok(zip) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) else { return Vec::new() };
    let mut out: Vec<String> = Vec::new();
    for name in zip.file_names() {
        let rel = name.strip_prefix("overrides/").or_else(|| name.strip_prefix("client-overrides/"));
        if let Some(rel) = rel.filter(|r| !r.is_empty() && !r.ends_with('/') && inside(r)) {
            if !out.iter().any(|o| o == rel) {
                out.push(rel.to_string());
            }
        }
    }
    out
}

/// A plain relative path: no root, drive, `..` or `.` anywhere in it.
pub fn inside(path: &str) -> bool {
    !path.is_empty()
        && !path.contains('\\')
        && Path::new(path).components().all(|c| matches!(c, std::path::Component::Normal(_)))
}

/// Extract an .mrpack's `overrides/` tree, then its `client-overrides/` over
/// it, into a profile root — each file only where `may_write(rel)` says so.
/// Returns the paths written, relative to the root and `/`-separated.
pub fn extract_overrides(bytes: &[u8], profile_root: &Path, may_write: impl Fn(&str) -> bool) -> Result<Vec<String>> {
    let reader = std::io::Cursor::new(bytes);
    let mut zip = zip::ZipArchive::new(reader).map_err(|e| crate::Error::Other(e.to_string()))?;
    let mut written = Vec::new();
    for prefix in ["overrides/", "client-overrides/"] {
        for i in 0..zip.len() {
            let mut entry = zip.by_index(i).map_err(|e| crate::Error::Other(e.to_string()))?;
            if entry.is_dir() {
                continue;
            }
            let Some(name) = entry.enclosed_name() else { continue };
            let name = name.to_string_lossy().replace('\\', "/");
            let Some(rel) = name.strip_prefix(prefix) else { continue };
            if rel.is_empty() || !may_write(rel) {
                continue;
            }
            let out = profile_root.join(rel);
            if let Some(parent) = out.parent() {
                std::fs::create_dir_all(parent)?;
            }
            // streamed: an entry's declared size isn't worth trusting
            let mut file = std::io::BufWriter::new(std::fs::File::create(&out)?);
            std::io::copy(&mut entry, &mut file)?;
            std::io::Write::flush(&mut file)?;
            if !written.iter().any(|w| w == rel) {
                written.push(rel.to_string());
            }
        }
    }
    Ok(written)
}

/// Downloads for the index files (mods, configs...) into the profile root.
pub fn index_downloads(index: &MrpackIndex, profile_root: &Path) -> Vec<Download> {
    index
        .files
        .iter()
        .map(|f| Download {
            url: f.downloads.first().cloned().unwrap_or_default(),
            dest: profile_root.join(&f.path),
            sha1: f.hashes.get("sha1").cloned(),
            // a size of 0 is a pack that didn't say, not an empty file
            size: (f.file_size > 0).then_some(f.file_size),
        })
        .filter(|d| !d.url.is_empty())
        .collect()
}

/// Primary mod file downloads for required dependency versions.
pub fn dependency_downloads(versions: &[Version], profile_root: &Path) -> Vec<Download> {
    versions
        .iter()
        .filter_map(|v| {
            let f = v.files.iter().find(|f| f.primary).or_else(|| v.files.first())?;
            // a bare file name, so it lands in mods/ and nowhere else
            if !inside(&f.filename) || f.filename.contains('/') {
                return None;
            }
            Some(Download {
                url: f.url.clone(),
                dest: profile_root.join("mods").join(&f.filename),
                sha1: f.hashes.get("sha1").cloned(),
                size: Some(f.size),
            })
        })
        .collect()
}

/// Download a batch, reporting per-file progress.
/// Fetch a pack's files. With `shared`, the content pool (see `pool`),
/// files another instance already has are linked from it first and the
/// ones fetched here go into it after.
pub async fn download_files(
    client: &reqwest::Client,
    downloads: Vec<Download>,
    shared: Option<&Path>,
    on_progress: impl Fn(u64, u64),
) -> Result<u64> {
    let total = downloads.len() as u64;
    let mut done = 0u64;
    let pooled = match shared {
        Some(pool) => {
            let (pool, list) = (pool.to_path_buf(), downloads.clone());
            tokio::task::spawn_blocking(move || {
                crate::pool::fill_from(&pool, &list);
                (pool, list)
            })
            .await
            .ok()
        }
        None => None,
    };
    // one worker pool for the whole pack: chunking it made each batch wait
    // on its slowest file before the next started
    download::download_all(client, downloads, 10, |ev| {
        if let download::ProgressEvent::FileDone { .. } = ev {
            done += 1;
            on_progress(done, total);
        }
    })
    .await?;
    if let Some((pool, list)) = pooled {
        let _ = tokio::task::spawn_blocking(move || crate::pool::add_from(&pool, &list)).await;
    }
    Ok(total)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_curseforge_pack_is_named_as_one() {
        use std::io::Write;
        let zip_of = |name: &str| {
            let mut w = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
            w.start_file(name, zip::write::SimpleFileOptions::default()).unwrap();
            w.write_all(b"{}").unwrap();
            w.finish().unwrap().into_inner()
        };
        let err = parse_mrpack_index(&zip_of("manifest.json")).unwrap_err().to_string();
        assert!(err.contains("CurseForge pack"), "{err}");
        let err = parse_mrpack_index(&zip_of("readme.txt")).unwrap_err().to_string();
        assert!(err.contains("no modrinth.index.json"), "{err}");
        assert!(parse_mrpack_index(b"not a zip").unwrap_err().to_string().contains("isn't a zip"));
    }

    fn mrpack(index: &str, extra: &[(&str, &str)]) -> Vec<u8> {
        use std::io::Write;
        let mut w = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        w.start_file("modrinth.index.json", zip::write::SimpleFileOptions::default()).unwrap();
        w.write_all(index.as_bytes()).unwrap();
        for (name, body) in extra {
            w.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            w.write_all(body.as_bytes()).unwrap();
        }
        w.finish().unwrap().into_inner()
    }

    fn index_with(files: &str) -> String {
        format!(r#"{{"formatVersion":1,"game":"minecraft","versionId":"1","name":"P","files":[{files}],"dependencies":{{"minecraft":"1.21.11"}}}}"#)
    }

    #[test]
    fn pack_files_stay_inside_and_on_the_client() {
        let file = |path: &str, env: &str| {
            let path = path.replace('\\', "\\\\");
            format!(r#"{{"path":"{path}","hashes":{{}},"downloads":["https://cdn.modrinth.com/x"]{env}}}"#)
        };
        for bad in ["../evil.jar", "mods/../../evil.jar", "/etc/evil", "./mods/a.jar", "mods\\..\\..\\x.jar", ""] {
            let err = parse_mrpack_index(&mrpack(&index_with(&file(bad, "")), &[])).unwrap_err().to_string();
            assert!(err.contains("outside its instance"), "{bad}: {err}");
        }
        let files = [
            file("mods/a.jar", ""),
            file("mods/server-only.jar", r#","env":{"client":"unsupported","server":"required"}"#),
            file("mods/opt.jar", r#","env":{"client":"optional","server":"unsupported"}"#),
        ]
        .join(",");
        let index = parse_mrpack_index(&mrpack(&index_with(&files), &[])).unwrap();
        let paths: Vec<_> = index.files.iter().map(|f| f.path.as_str()).collect();
        assert_eq!(paths, ["mods/a.jar", "mods/opt.jar"]);

        // the format's camelCase fileSize is what a file already there is
        // checked against; a pack without one checks the hash alone
        let sized = r#"{"path":"mods/a.jar","hashes":{"sha1":"ab"},"downloads":["https://cdn.modrinth.com/x"],"fileSize":1234}"#;
        let index = parse_mrpack_index(&mrpack(&index_with(&[sized, &file("mods/b.jar", "")].join(",")), &[])).unwrap();
        let downloads = index_downloads(&index, Path::new("/i"));
        assert_eq!(downloads[0].size, Some(1234));
        assert_eq!(downloads[1].size, None);
        assert!(serde_json::to_string(&index.files[0]).unwrap().contains(r#""fileSize":1234"#));
    }

    #[test]
    fn client_overrides_land_over_overrides() {
        let pack = mrpack(
            &index_with(""),
            &[("overrides/config/a.txt", "common"), ("client-overrides/config/a.txt", "client"), ("overrides/options.txt", "o")],
        );
        let root = std::env::temp_dir().join(format!("dusk-overrides-{}", std::process::id()));
        let written = extract_overrides(&pack, &root, |rel| rel != "options.txt").unwrap();
        assert_eq!(written, ["config/a.txt"]);
        assert_eq!(std::fs::read_to_string(root.join("config/a.txt")).unwrap(), "client");
        assert!(!root.join("options.txt").exists());
        assert_eq!(override_paths(&pack), ["config/a.txt", "options.txt"]);
        let _ = std::fs::remove_dir_all(&root);
    }

    /// The real api.modrinth.com search payload (captured) must parse into
    /// our structs. If Modrinth changes shape, this fails instead of users.
    #[test]
    fn search_response_matches_live_api_shape() {
        let raw = include_str!("../tests/fixtures/modrinth_search.json");
        let resp: SearchResponse = serde_json::from_str(raw).expect("fixture must parse");
        assert_eq!(resp.hits.len(), 3);
        assert!(resp.total_hits > 0);
        assert!(!resp.hits[0].project_id.is_empty());
        assert!(!resp.hits[0].title.is_empty());
    }

    /// A block page / empty body must produce a message carrying status,
    /// content type, and a snippet — never a bare "error decoding".
    #[test]
    fn decode_error_carries_status_ctype_and_snippet() {
        let html = "<html><head><title>Blocked</title></head></html>";
        let serde_err = serde_json::from_str::<SearchResponse>(html).unwrap_err();
        let msg = decode_error(
            "search",
            reqwest::StatusCode::OK,
            "text/html",
            html,
            &serde_err,
        )
        .to_string();
        assert!(msg.contains("HTTP 200"), "{msg}");
        assert!(msg.contains("text/html"), "{msg}");
        assert!(msg.contains("Blocked"), "{msg}");
        assert!(msg.contains("parse error"), "{msg}");
    }

    #[test]
    #[ignore = "live network call"]
    fn update_lookup_stays_on_the_loader_live() {
        tokio::runtime::Runtime::new().expect("tokio runtime").block_on(async {
            let client = reqwest::Client::new();
            // an old Fabric Sodium for 1.21.1 (mc1.21-0.5.11)
            let old = "d67e66ea4bb2409997b636dae4203d33764cdcc8".to_string();
            let found = super::version_files_update(&client, std::slice::from_ref(&old), &["fabric"], "1.21.1")
                .await
                .expect("update lookup");
            let v = found.get(&old).expect("Modrinth knows the hash");
            assert_eq!(v.project_id, "AANobbMI");
            assert!(v.loaders.iter().any(|l| l == "fabric"), "{:?}", v.loaders);
            assert!(v.game_versions.iter().any(|g| g == "1.21.1"), "{:?}", v.game_versions);
            assert_ne!(v.version_number, "mc1.21-0.5.11");
        });
    }
}
