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
    let theirs = tokio::task::spawn_blocking(move || metas(&jars_in(&mods))).await.unwrap_or_default();
    let have = mod_set(&theirs);
    let have = |id: &str| have.contains_key(id);
    // the player installed PolyPlus (or Dusk+) themselves: theirs runs
    if have(MOD_ID) {
        return Vec::new();
    }
    let Some(fork) = fork_jar(state, target).await else {
        tracing::warn!("Dusk+ for {target} isn't available; launching without it");
        return Vec::new();
    };
    let mut out = vec![fork];
    for (mod_id, project) in LIBS {
        if have(mod_id) {
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
    if !have(crate::hosting::E4MC_MOD_ID) {
        match crate::hosting::relay_jar(state, game_version).await {
            Some(jar) => out.push(jar),
            None => tracing::warn!("could not get e4mc for {game_version}; in-game hosting will have no address"),
        }
    }
    // No Polydium here: added to every instance it moved the game onto its
    // Vulkan backend, which crashed with Astra and Iris shaders, and its
    // OpenGL mode cost frames. An instance that wants it has it in its mods.
    //
    // The instance's own copies of the libraries stand in for ours, and its
    // other mods sit next to them: an old Kotlin or a mod that breaks
    // OneConfig stops the loader before the game opens. Then this launch
    // goes without Dusk+ rather than not at all.
    let added = out.clone();
    let ours = tokio::task::spawn_blocking(move || metas(&added)).await.unwrap_or_default();
    if let Some(why) = clash(&theirs, &ours) {
        tracing::warn!("launching without Dusk+: {why}");
        return Vec::new();
    }
    out
}

/// fabric.mod.json of each jar that has one.
fn metas(jars: &[PathBuf]) -> Vec<serde_json::Value> {
    jars.iter().filter_map(|p| crate::mods::fabric_meta(p)).collect()
}

fn text<'a>(meta: &'a serde_json::Value, key: &str) -> &'a str {
    meta.get(key).and_then(|v| v.as_str()).unwrap_or("")
}

/// Every id the jars answer to, its own and what it provides, with its version.
fn mod_set(metas: &[serde_json::Value]) -> std::collections::HashMap<String, String> {
    let mut set = std::collections::HashMap::new();
    for m in metas {
        let version = text(m, "version").to_string();
        let provides = m.get("provides").and_then(|v| v.as_array()).into_iter().flatten().filter_map(|v| v.as_str());
        for id in std::iter::once(text(m, "id")).chain(provides).filter(|id| !id.is_empty()) {
            set.insert(id.to_string(), version.clone());
        }
    }
    set
}

/// A depends/breaks table: id to the version ranges, any of which counts.
fn ranges(meta: &serde_json::Value, key: &str) -> Vec<(String, Vec<String>)> {
    let Some(table) = meta.get(key).and_then(|v| v.as_object()) else { return Vec::new() };
    table
        .iter()
        .map(|(id, v)| {
            let any = match v {
                serde_json::Value::Array(a) => a.iter().filter_map(|s| s.as_str().map(str::to_string)).collect(),
                v => vec![v.as_str().unwrap_or("*").to_string()],
            };
            (id.clone(), any)
        })
        .collect()
}

/// Why the jars we add can't load next to the instance's mods, when the
/// loader would refuse them: something ours needs is missing or too old, or
/// one side's `breaks` names the other. (`conflicts` only warns, so it's left.)
fn clash(theirs: &[serde_json::Value], ours: &[serde_json::Value]) -> Option<String> {
    // the loader, the game, Java and Fabric API are the launcher's to get right
    const SKIP: [&str; 5] = ["minecraft", "java", "fabricloader", "fabric-api", "fabric"];
    let their_set = mod_set(theirs);
    let our_set = mod_set(ours);
    let mut all = their_set.clone();
    all.extend(our_set.clone());
    for m in ours {
        let name = text(m, "id");
        for (dep, any) in ranges(m, "depends") {
            if SKIP.contains(&dep.as_str()) {
                continue;
            }
            match all.get(&dep) {
                None => return Some(format!("{name} needs {dep}, which isn't there")),
                Some(v) if !in_ranges(&any, v) => {
                    return Some(format!("{name} needs {dep} {}, the instance has {v}", any.join(" or ")));
                }
                _ => {}
            }
        }
        for (other, any) in ranges(m, "breaks") {
            if let Some(v) = their_set.get(&other).filter(|v| in_ranges(&any, v)) {
                return Some(format!("{name} can't run with {other} {v} from the instance's mods"));
            }
        }
    }
    for m in theirs {
        for (other, any) in ranges(m, "breaks") {
            if let Some(v) = our_set.get(&other).filter(|v| in_ranges(&any, v)) {
                return Some(format!("{} in the instance's mods can't run with {other} {v}", text(m, "id")));
            }
        }
    }
    None
}

/// Fabric's version ranges: any of the alternatives, each a space-separated
/// list of terms that all hold (`>=1.2`, `<2`, `~1.4`, `^3.0`, `1.21.x`, `*`).
fn in_ranges(any: &[String], version: &str) -> bool {
    any.is_empty() || any.iter().any(|r| r.split_whitespace().all(|term| holds(term, version)))
}

fn holds(term: &str, version: &str) -> bool {
    use std::cmp::Ordering::*;
    if term == "*" {
        return true;
    }
    let (op, want) = ["^", "~", ">=", "<=", ">", "<", "="]
        .iter()
        .find_map(|op| term.strip_prefix(op).map(|rest| (*op, rest)))
        .unwrap_or(("=", term));
    let (Some(have), Some(want_v)) = (Ver::parse(version), Ver::parse(want)) else {
        // a version we can't read (a placeholder, a date) isn't ours to block on
        return true;
    };
    let ord = have.cmp(&want_v);
    let same = |k: usize| have.prefix(k) == want_v.prefix(k);
    match op {
        ">=" => ord != Less,
        "<=" => ord != Greater,
        ">" => ord == Greater,
        "<" => ord == Less,
        // ~1.4.2 keeps 1.4, ~1 keeps 1; ^3.0 keeps 3
        "~" => ord != Less && same(want_v.core.len().clamp(1, 2)),
        "^" => ord != Less && same(1),
        _ if want.split('.').any(|p| matches!(p, "x" | "X" | "*")) => {
            same(want.split('.').take_while(|p| !matches!(*p, "x" | "X" | "*")).count())
        }
        _ => ord == Equal,
    }
}

/// A version as Fabric compares it: numeric core, then a pre-release that
/// sorts below the plain release; build metadata after `+` doesn't count.
#[derive(PartialEq, Eq)]
struct Ver {
    core: Vec<u64>,
    pre: Option<String>,
}

impl Ver {
    fn parse(s: &str) -> Option<Ver> {
        let s = s.split('+').next()?.trim();
        let (core, pre) = match s.split_once('-') {
            Some((c, p)) => (c, Some(p.to_string())),
            None => (s, None),
        };
        let core = core
            .split('.')
            .map(|p| if matches!(p, "x" | "X" | "*") { Some(0) } else { p.parse().ok() })
            .collect::<Option<Vec<u64>>>()?;
        (!core.is_empty()).then_some(Ver { core, pre })
    }

    /// The first `k` parts, missing ones as 0.
    fn prefix(&self, k: usize) -> Vec<u64> {
        (0..k).map(|i| self.core.get(i).copied().unwrap_or(0)).collect()
    }
}

impl Ord for Ver {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        let n = self.core.len().max(other.core.len());
        let at = |v: &Ver, i: usize| v.core.get(i).copied().unwrap_or(0);
        (0..n).map(|i| at(self, i).cmp(&at(other, i))).find(|o| o.is_ne()).unwrap_or_else(|| match (&self.pre, &other.pre) {
            (None, None) => std::cmp::Ordering::Equal,
            (None, Some(_)) => std::cmp::Ordering::Greater,
            (Some(_), None) => std::cmp::Ordering::Less,
            (Some(a), Some(b)) => a.cmp(b),
        })
    }
}

impl PartialOrd for Ver {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
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
    fn reads_fabric_version_ranges() {
        let r = |s: &str| vec![s.to_string()];
        assert!(in_ranges(&r(">=1.14.1+kotlin.2.4.20"), "1.14.2+kotlin.2.4.21"));
        assert!(!in_ranges(&r(">=1.14.1+kotlin.2.4.20"), "1.13.4+kotlin.2.2.0"));
        assert!(in_ranges(&r("<0.8.12-beta.1"), "0.8.11"));
        assert!(!in_ranges(&r("<0.8.12-beta.1"), "0.8.12"));
        assert!(in_ranges(&r(">=1.21.11 <=1.21.11"), "1.21.11"));
        assert!(!in_ranges(&r(">=1.21.11 <=1.21.11"), "1.21.10"));
        assert!(in_ranges(&r("~1.4.2"), "1.4.9"));
        assert!(!in_ranges(&r("~1.4.2"), "1.5.0"));
        assert!(in_ranges(&r("^3.0.7"), "3.9.0"));
        assert!(!in_ranges(&r("^3.0.7"), "4.0.0"));
        assert!(in_ranges(&r("1.21.x"), "1.21.4"));
        assert!(in_ranges(&r("*"), "anything"));
        assert!(in_ranges(&[], "1.0"));
        assert!(in_ranges(&r(">=1.0"), "${version}"));
        assert!(in_ranges(&["<1".to_string(), ">=2".to_string()], "2.1"));
    }

    #[test]
    fn spots_instances_our_jars_cannot_join() {
        use serde_json::json;
        let ours = vec![
            json!({"id": "polyplus", "version": "2.4.1", "depends": {"minecraft": "1.21.11", "fabric-language-kotlin": ">=1.14.1", "oneconfigv1": ">=1.9.0"}, "breaks": {"auto-reauth": "*"}}),
            json!({"id": "oneconfig", "version": "1.9.5", "provides": ["oneconfigv1"], "depends": {"fabric-language-kotlin": ">=1.14.1+kotlin.2.4.20"}}),
        ];
        let kotlin = |v: &str| json!({"id": "fabric-language-kotlin", "version": v});
        assert_eq!(clash(&[kotlin("1.14.2+kotlin.2.4.21")], &ours), None);
        assert!(clash(&[kotlin("1.13.4+kotlin.2.2.0")], &ours).unwrap().contains("fabric-language-kotlin"));
        assert!(clash(&[], &ours).unwrap().contains("isn't there"));
        let reauth = json!({"id": "auto-reauth", "version": "1.0"});
        assert!(clash(&[kotlin("1.14.2"), reauth], &ours).unwrap().contains("auto-reauth"));
        let old_hud = json!({"id": "vanillahud", "version": "2.0", "breaks": {"oneconfig": "<2"}});
        assert!(clash(&[kotlin("1.14.2"), old_hud], &ours).unwrap().contains("vanillahud"));
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
