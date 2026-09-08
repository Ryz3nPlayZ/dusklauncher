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
        .send()
        .await?;
    Ok(decode(check(resp, "search").await?, "search").await?)
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
    pub dependency_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Version {
    pub id: String,
    pub name: String,
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
}

pub async fn project_versions(client: &reqwest::Client, project_id: &str) -> Result<Vec<Version>> {
    let resp = client
        .get(format!("{MODRINTH_API}/project/{project_id}/version"))
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .send()
        .await?;
    Ok(decode(check(resp, "project versions").await?, "project versions").await?)
}

pub async fn version(client: &reqwest::Client, version_id: &str) -> Result<Version> {
    let resp = client
        .get(format!("{MODRINTH_API}/version/{version_id}"))
        .header(reqwest::header::USER_AGENT, USER_AGENT)
        .send()
        .await?;
    Ok(decode(check(resp, "version").await?, "version").await?)
}

/// Newest version of a project playable on this game version + loader.
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
            ("limit", "1".to_string()),
        ]);
    if let Some(l) = loader {
        req = req.query(&[("loaders", format!("[\"{l}\"]"))]);
    }
    let resp = req.send().await?;
    let versions: Vec<Version> =
        decode(check(resp, "project version lookup").await?, "project version lookup").await?;
    versions.into_iter().next().ok_or_else(|| {
        crate::Error::Other(format!("no release for Minecraft {game_version}"))
    })
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
            .send()
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
    let mut zip = zip::ZipArchive::new(reader).map_err(|e| crate::Error::Other(e.to_string()))?;
    let mut entry = zip
        .by_name("modrinth.index.json")
        .map_err(|e| crate::Error::Other(format!("no modrinth.index.json: {e}")))?;
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
    // report in chunks to avoid unbounded closure state
    let chunk = 24;
    for group in downloads.chunks(chunk) {
        download::download_all(client, group.to_vec(), 8, |_| {}).await?;
        done += group.len() as u64;
        on_progress(done, total);
    }
    Ok(total)
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
