//! The art Mojang publishes for each Java release (the same square images the
//! official launcher shows beside its patch notes), for the NEW INSTANCE
//! version cards. Read from launchercontent.mojang.com at runtime — nothing of
//! Mojang's is bundled — and cached under `<data>/cache/` so the cards keep
//! their pictures offline.

use crate::appstate::AppState;
use serde::{Deserialize, Serialize};
use tauri::State;

const NOTES_URL: &str = "https://launchercontent.mojang.com/v2/javaPatchNotes.json";
const CONTENT_BASE: &str = "https://launchercontent.mojang.com";

#[derive(Deserialize)]
struct Notes {
    entries: Vec<Entry>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Entry {
    version: String,
    #[serde(rename = "type")]
    kind: String,
    image: Option<Image>,
    #[serde(default)]
    short_text: String,
}

#[derive(Deserialize)]
struct Image {
    url: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReleaseArt {
    pub version: String,
    /// "release" | "snapshot"
    pub kind: String,
    pub image_url: String,
    /// the first line of the patch notes ("Wilderness Bound is out now…")
    pub blurb: String,
}

fn parse(bytes: &[u8]) -> Option<Vec<ReleaseArt>> {
    let notes: Notes = serde_json::from_slice(bytes).ok()?;
    Some(
        notes
            .entries
            .into_iter()
            .filter_map(|e| {
                let url = e.image?.url;
                let image_url = if url.starts_with("http") { url } else { format!("{CONTENT_BASE}{url}") };
                let blurb = e.short_text.split(['.', '!']).next().unwrap_or("").trim().to_string();
                Some(ReleaseArt { version: e.version, kind: e.kind, image_url, blurb })
            })
            .collect(),
    )
}

/// Every patch-notes entry that has a picture, newest first. A failed fetch
/// falls back to the last copy on disk.
#[tauri::command]
pub async fn release_art(state: State<'_, AppState>) -> Result<Vec<ReleaseArt>, String> {
    let cache = state.data_dir.join("cache").join("java-patch-notes.json");
    let fresh = async {
        let resp = state.client.get(NOTES_URL).send().await.ok()?.error_for_status().ok()?;
        resp.bytes().await.ok()
    }
    .await;
    if let Some(bytes) = fresh {
        if let Some(art) = parse(&bytes) {
            if let Some(dir) = cache.parent() {
                let _ = std::fs::create_dir_all(dir);
            }
            let _ = std::fs::write(&cache, &bytes);
            return Ok(art);
        }
    }
    std::fs::read(&cache)
        .ok()
        .and_then(|b| parse(&b))
        .ok_or_else(|| "release art unavailable offline".into())
}

#[cfg(test)]
mod tests {
    #[test]
    fn resolves_relative_images_and_trims_the_blurb() {
        let json = br#"{"version":1,"entries":[
            {"version":"26.3","type":"release","image":{"title":"x","url":"/v2/images/a.jpg"},
             "shortText":"Wilderness Bound is out now! Pack up."},
            {"version":"26.3-rc-1","type":"snapshot","shortText":"no image"}]}"#;
        let art = super::parse(json).unwrap();
        assert_eq!(art.len(), 1);
        assert_eq!(art[0].image_url, "https://launchercontent.mojang.com/v2/images/a.jpg");
        assert_eq!(art[0].blurb, "Wilderness Bound is out now");
    }
}
