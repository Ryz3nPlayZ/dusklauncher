//! Dusk+, the menus, lobby, settings screen and chat UI that ride on top of
//! DuskClient. It is a GPL-3.0 fork of Polyfrost's PolyPlus (source in
//! `duskplus/`), built per game version by CI and attached to each launcher
//! release as `duskplus-<mc>.jar`. Like DuskClient it goes in through
//! `-Dfabric.addMods`, so mods/ stays as its owner left it. It needs three
//! libraries that live on Modrinth unchanged: OneConfig, polycompose and
//! Fabric Language Kotlin. All four go in together or none do: the fork
//! without OneConfig stops the game at mod resolution.

use crate::appstate::AppState;
use fasterlauncher_core::{download, modrinth as mr};
use std::path::{Path, PathBuf};

/// OneConfig's newest builds need this loader; older pins launch without Dusk+.
const MIN_LOADER: [u32; 3] = [0, 19, 5];

/// The fork's own mod id, and the libraries with their Modrinth projects.
pub const MOD_ID: &str = "polyplus";
const LIBS: [(&str, &str); 3] = [
    ("oneconfig", "oneconfig"),
    ("polycompose", "polycompose"),
    ("fabric-language-kotlin", "fabric-language-kotlin"),
];

/// The fork build a game version runs: exact for 1.21.x, the release line
/// for 26.x (one build covers 26.1, 26.1.1 and so on).
pub fn target_for(game_version: &str) -> Option<&'static str> {
    const EXACT: [&str; 6] = ["1.21.1", "1.21.4", "1.21.5", "1.21.8", "1.21.10", "1.21.11"];
    const LINES: [&str; 3] = ["26.1", "26.2", "26.3"];
    if let Some(v) = EXACT.iter().find(|v| **v == game_version) {
        return Some(v);
    }
    LINES
        .iter()
        .find(|line| game_version == **line || game_version.strip_prefix(**line).is_some_and(|rest| rest.starts_with('.')))
        .copied()
}

pub fn loader_ok(loader_version: Option<&str>) -> bool {
    let Some(v) = loader_version else { return true };
    let mut have = [0u32; 3];
    for (slot, part) in have.iter_mut().zip(v.split(['.', '+', '-']).take(3)) {
        match part.parse() {
            Ok(n) => *slot = n,
            Err(_) => return true,
        }
    }
    have >= MIN_LOADER
}

/// Jars to add for this launch: the fork plus whichever libraries the
/// instance doesn't carry itself. Empty when Dusk+ doesn't apply or a piece
/// couldn't be had (then the game starts with DuskClient alone).
pub async fn jars(state: &AppState, game_version: &str, mods_dir: &Path) -> Vec<PathBuf> {
    let Some(target) = target_for(game_version) else { return Vec::new() };
    let mods = mods_dir.to_path_buf();
    let have = tokio::task::spawn_blocking(move || crate::mods::fabric_mod_ids(&mods)).await.unwrap_or_default();
    // the player installed PolyPlus (or Dusk+) themselves: theirs runs
    if have.contains(MOD_ID) {
        return Vec::new();
    }
    let Some(fork) = fork_jar(state, target).await else {
        tracing::warn!("Dusk+ for {target} isn't available; launching without it");
        return Vec::new();
    };
    let mut out = vec![fork];
    for (mod_id, project) in LIBS {
        if have.contains(mod_id) {
            continue;
        }
        match library_jar(state, project, game_version).await {
            Some(jar) => out.push(jar),
            None => {
                tracing::warn!("could not get {project} for {game_version}; launching without Dusk+");
                return Vec::new();
            }
        }
    }
    // e4mc, so a world hosted from the social screen gets an address friends
    // can join. It sits idle until a world is opened to LAN.
    if !have.contains(crate::hosting::E4MC_MOD_ID) {
        match crate::hosting::relay_jar(state, game_version).await {
            Some(jar) => out.push(jar),
            None => tracing::warn!("could not get e4mc for {game_version}; in-game hosting will have no address"),
        }
    }
    // Polydium, Polyfrost's renderer, from Modrinth unchanged. Optional: the
    // game starts without it when it can't be had or would clash.
    if polydium_fits(&have, mods_dir) {
        if let Some(jar) = library_jar(state, POLYDIUM, game_version).await {
            out.push(jar);
        }
    }
    out
}

const POLYDIUM: &str = "polydium";

/// Polydium stops the game next to another renderer, or a Sodium older than
/// 0.8.12; then it stays out.
fn polydium_fits(have: &std::collections::HashSet<String>, mods_dir: &Path) -> bool {
    if ["polydium", "vulkanmod", "gpu_booster", "duskmetal"].iter().any(|id| have.contains(*id)) {
        return false;
    }
    if !have.contains("sodium") {
        return true;
    }
    jars_in(mods_dir).iter().filter_map(|p| crate::mods::fabric_meta(p)).any(|m| {
        m.get("id").and_then(|v| v.as_str()) == Some("sodium")
            && m.get("version").and_then(|v| v.as_str()).is_some_and(sodium_new_enough)
    })
}

/// 0.8.12 or newer, prereleases of 0.8.12 excluded (Polydium breaks below
/// 0.8.12-beta.1, so a beta counts as old: the safe side).
fn sodium_new_enough(version: &str) -> bool {
    let core = version.split(['+', '-']).next().unwrap_or("");
    let mut have = [0u32; 3];
    for (slot, part) in have.iter_mut().zip(core.split('.')) {
        match part.parse() {
            Ok(n) => *slot = n,
            Err(_) => return false,
        }
    }
    have > [0, 8, 12] || (have == [0, 8, 12] && !version.contains('-'))
}

fn safe(name: &str) -> String {
    name.chars()
        .map(|c| if c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_') { c } else { '_' })
        .collect()
}

/// A jar is the fork when its fabric.mod.json says so; a cut-off download or
/// an HTML error page isn't.
fn is_fork(path: &Path) -> bool {
    crate::mods::fabric_meta(path).and_then(|m| m.get("id")?.as_str().map(|id| id == MOD_ID)).unwrap_or(false)
}

/// A local build of the fork: `DUSK_PLUS_JAR` (a jar, or a folder holding
/// one) when set, else in debug builds the repo's own `duskplus/` output.
fn local_fork(target: &str) -> Option<PathBuf> {
    let pick = |dir: &Path| -> Option<PathBuf> {
        std::fs::read_dir(dir)
            .ok()?
            .flatten()
            .map(|e| e.path())
            .filter(|p| {
                let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
                name.ends_with(".jar") && !name.contains("-sources") && !name.contains("-dev")
            })
            .find(|p| is_fork(p))
    };
    if let Some(over) = std::env::var_os("DUSK_PLUS_JAR").map(PathBuf::from) {
        if over.is_file() {
            return is_fork(&over).then_some(over);
        }
        return pick(&over).or_else(|| pick(&over.join(format!("{target}-fabric/build/libs"))));
    }
    if cfg!(debug_assertions) {
        let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../duskplus/versions").join(format!("{target}-fabric/build/libs"));
        return pick(&dir);
    }
    None
}

/// The fork build this launcher release ships, fetched once into
/// `tools/duskplus/<target>/`. Offline, the copy fetched last time.
async fn fork_jar(state: &AppState, target: &str) -> Option<PathBuf> {
    if let Some(local) = local_fork(target) {
        return Some(local);
    }
    let dir = state.data_dir.join("tools").join("duskplus").join(safe(target));
    let release = env!("CARGO_PKG_VERSION");
    let dest = dir.join(format!("duskplus-{target}-{release}.jar"));
    if dest.is_file() && is_fork(&dest) {
        return Some(dest);
    }
    let dl = download::Download {
        url: format!("https://github.com/ryz3nplayz/dusklauncher/releases/download/v{release}/duskplus-{target}.jar"),
        dest: dest.clone(),
        sha1: None,
        size: None,
    };
    match download::download_one(&state.client, &dl).await {
        Ok(()) if is_fork(&dest) => {
            for old in jars_in(&dir).into_iter().filter(|p| *p != dest) {
                let _ = std::fs::remove_file(old);
            }
            Some(dest)
        }
        res => {
            if let Err(e) = res {
                tracing::warn!("could not fetch Dusk+ {target}: {e}");
            }
            let _ = std::fs::remove_file(&dest);
            jars_in(&dir).into_iter().find(|p| is_fork(p))
        }
    }
}

/// The newest Modrinth build of a library for `game_version`, kept in
/// `tools/duskplus/libs/<project>/<version>/`, replaced when a newer one is out.
async fn library_jar(state: &AppState, project: &str, game_version: &str) -> Option<PathBuf> {
    let dir = state.data_dir.join("tools").join("duskplus").join("libs").join(safe(project)).join(safe(game_version));
    let fetched = async {
        let ver = mr::project_version_for_loader(&state.client, project, game_version, Some("fabric"))
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
            for old in jars_in(&dir).into_iter().filter(|p| *p != jar) {
                let _ = std::fs::remove_file(old);
            }
            Some(jar)
        }
        Err(e) => {
            tracing::warn!("could not fetch {project} for {game_version}: {e}");
            jars_in(&dir).into_iter().next()
        }
    }
}

fn jars_in(dir: &Path) -> Vec<PathBuf> {
    std::fs::read_dir(dir)
        .map(|rd| rd.flatten().map(|e| e.path()).filter(|p| p.extension().is_some_and(|x| x == "jar")).collect())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn polydium_needs_sodium_0_8_12() {
        assert!(sodium_new_enough("0.8.12+mc1.21.11"));
        assert!(sodium_new_enough("0.9.0"));
        assert!(!sodium_new_enough("0.8.12-beta.1+mc1.21.11"));
        assert!(!sodium_new_enough("0.6.13+mc1.21.4"));
        assert!(!sodium_new_enough("weird"));
    }

    #[test]
    fn picks_the_fork_build_for_a_game_version() {
        assert_eq!(target_for("1.21.11"), Some("1.21.11"));
        assert_eq!(target_for("1.21.1"), Some("1.21.1"));
        assert_eq!(target_for("1.21.3"), None);
        assert_eq!(target_for("26.1"), Some("26.1"));
        assert_eq!(target_for("26.1.2"), Some("26.1"));
        assert_eq!(target_for("26.10"), None);
        assert_eq!(target_for("1.8.9"), None);
    }

    #[test]
    fn needs_a_new_enough_loader() {
        assert!(loader_ok(None));
        assert!(loader_ok(Some("0.19.5")));
        assert!(loader_ok(Some("0.20.0")));
        assert!(!loader_ok(Some("0.19.4")));
        assert!(!loader_ok(Some("0.17.2")));
    }
}
