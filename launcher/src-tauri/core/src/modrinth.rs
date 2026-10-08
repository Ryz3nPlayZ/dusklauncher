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
    let versions: Vec<Version> =
        decode(check(resp, "project version lookup").await?, "project version lookup").await?;
    newest_preferring_release(versions).ok_or_else(|| {
        crate::Error::Other(format!("no release for Minecraft {game_version}"))
    })
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
    #[serde(default)]
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
    Ok(bytes.to_vec())
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
    Ok(serde_json::from_str(&text)?)
}

/// Extract the `overrides/` tree of an .mrpack into a profile root.
pub fn extract_overrides(bytes: &[u8], profile_root: &Path) -> Result<usize> {
    let reader = std::io::Cursor::new(bytes);
    let mut zip = zip::ZipArchive::new(reader).map_err(|e| crate::Error::Other(e.to_string()))?;
    let mut count = 0;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i).map_err(|e| crate::Error::Other(e.to_string()))?;
        if entry.is_dir() {
            continue;
        }
        let Some(name) = entry.enclosed_name() else { continue };
        let name = name.display().to_string();
        let Some(rel) = name.strip_prefix("overrides/") else { continue };
        if rel.is_empty() {
            continue;
        }
        let out = profile_root.join(rel);
        if let Some(parent) = out.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut buf = Vec::with_capacity(entry.size() as usize);
        std::io::Read::read_to_end(&mut entry, &mut buf)?;
        std::fs::write(&out, &buf)?;
        count += 1;
    }
    Ok(count)
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
            size: Some(f.file_size),
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
pub async fn download_files(
    client: &reqwest::Client,
    downloads: Vec<Download>,
    on_progress: impl Fn(u64, u64),
) -> Result<u64> {
    let total = downloads.len() as u64;
    let mut done = 0u64;
    // one pool for the whole pack: chunking it made each batch wait on its
    // slowest file before the next started
    download::download_all(client, downloads, 10, |ev| {
        if let download::ProgressEvent::FileDone { .. } = ev {
            done += 1;
            on_progress(done, total);
        }
    })
    .await?;
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
            let found = super::version_files_update(&client, &[old.clone()], &["fabric"], "1.21.1")
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
