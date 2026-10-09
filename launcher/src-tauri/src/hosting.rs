//! HOST on a world in the WORLDS tab: friends anywhere join a singleplayer
//! world without port forwarding. DuskClient opens the world to other players
//! as it loads (`-Ddusk.host`, see `WorldHost` in the client mod) and e4mc
//! (MIT, github.com/vgskye/e4mc-minecraft-architectury) relays that LAN
//! server under a public address. e4mc is loaded beside DuskClient through
//! `-Dfabric.addMods` for that launch only, so the instance's mods/ stays as
//! its owner left it, and one copy per game version is shared by every
//! instance.

use crate::appstate::AppState;
use fasterlauncher_core::{download, modrinth as mr};
use std::path::{Path, PathBuf};

/// e4mc's Modrinth project.
const E4MC: &str = "qANg5Jrr";
/// Its Fabric mod id, to tell when an instance already carries it.
pub const E4MC_MOD_ID: &str = "e4mc";

/// The e4mc build for `game_version`, fetched once into
/// `tools/hosting/<version>/` and replaced when Modrinth has a newer one.
/// Offline, the copy fetched last time; `None` when there's neither.
pub async fn relay_jar(state: &AppState, game_version: &str) -> Option<PathBuf> {
    let safe: String = game_version
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_') { c } else { '_' })
        .collect();
    let dir = state.data_dir.join("tools").join("hosting").join(safe);
    let fetched = async {
        let ver = mr::project_version_for_loader(&state.client, E4MC, game_version, Some("fabric"))
            .await
            .map_err(|e| e.to_string())?;
        let file = ver.files.iter().find(|f| f.primary).or_else(|| ver.files.first()).ok_or("no file")?;
        let dest = dir.join(crate::mods::sanitize_filename(&file.filename)?);
        let dl = download::Download {
            url: file.url.clone(),
            dest: dest.clone(),
            sha1: file.hashes.get("sha1").cloned(),
            size: Some(file.size),
        };
        download::download_one(&state.client, &dl).await.map_err(|e| e.to_string())?;
        Ok::<_, String>(dest)
    }
    .await;
    match fetched {
        Ok(jar) => {
            // the build it replaces
            for old in jars_in(&dir).into_iter().filter(|p| *p != jar) {
                let _ = std::fs::remove_file(old);
            }
            Some(jar)
        }
        Err(e) => {
            tracing::warn!("could not fetch e4mc for {game_version}: {e}");
            jars_in(&dir).into_iter().next()
        }
    }
}

fn jars_in(dir: &Path) -> Vec<PathBuf> {
    std::fs::read_dir(dir)
        .map(|rd| {
            rd.flatten()
                .map(|e| e.path())
                .filter(|p| p.extension().is_some_and(|x| x == "jar"))
                .collect()
        })
        .unwrap_or_default()
}

/// The public address e4mc logs once its relay is up
/// (`Domain assigned: abc.xyz.e4mc.link`).
pub fn relay_address(line: &str) -> Option<String> {
    let i = line.find("Domain assigned: ")?;
    let domain = line[i + "Domain assigned: ".len()..].trim().to_ascii_lowercase();
    crate::servers::valid_address(&domain).then_some(domain)
}

/// What DuskClient's `WorldHost` logs as it opens a world: `Some(true)`
/// once it's open and e4mc is fetching its address, `Some(false)` when it
/// couldn't be opened at all.
pub fn host_state(line: &str) -> Option<bool> {
    let i = line.find("[DuskHost] ")?;
    let rest = &line[i + "[DuskHost] ".len()..];
    if rest.starts_with("open on port") {
        Some(true)
    } else if rest.starts_with("could not open") {
        Some(false)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_relay_address_from_the_log() {
        assert_eq!(
            relay_address("[12:00:01] [e4mc-relay/INFO] (e4mc) Domain assigned: Brave-Fox.US.e4mc.link"),
            Some("brave-fox.us.e4mc.link".into())
        );
        assert_eq!(relay_address("[12:00:01] [Render thread/INFO]: Domain assigned: "), None);
        assert_eq!(relay_address("Connecting to example.org, 25565"), None);
    }

    #[test]
    fn reads_whether_the_world_opened() {
        assert_eq!(host_state("[12:00:00] [Server thread/INFO] (DuskClient) [DuskHost] open on port 51234"), Some(true));
        assert_eq!(host_state("[DuskHost] could not open the world to other players"), Some(false));
        assert_eq!(host_state("[DuskPresence] singleplayer"), None);
    }
}
