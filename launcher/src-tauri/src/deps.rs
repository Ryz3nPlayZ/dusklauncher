//! Missing Fabric dependencies: a mod whose `fabric.mod.json` `depends` on a
//! mod id nothing in `mods/` provides makes Fabric refuse to start, with an
//! error most players never get to read. The CONTENT tab checks before then
//! and offers the missing mod from Modrinth.
//!
//! What counts as provided: each enabled jar's `id` and `provides`, and the
//! same for the jars it bundles (`jars`) — Fabric API is one jar holding
//! ~80 module jars, and mods depend on those module ids directly.
//!
//! The same read also catches the other things Fabric stops on: a library
//! in a version the mod doesn't take, one mod in mods/ twice, and mods that
//! declare they break each other. Version ranges are read the way Fabric
//! Loader reads them; one that can't be read is never reported.

use crate::appstate::AppState;
use serde::Serialize;
use std::collections::{BTreeMap, HashMap};
use std::io::{Read, Seek};
use tauri::State;

/// fabric.mod.json files are small; bundled jars are read into memory to
/// look inside them, so those get a bigger cap
const META_CAP: u64 = 256 * 1024;
const NESTED_CAP: u64 = 64 << 20;

/// Ids the game and Fabric Loader themselves provide.
const BUILT_IN: &[&str] = &["minecraft", "java", "fabricloader", "fabric-loader", "mixinextras"];

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct MissingDep {
    /// the mod id the jars depend on
    pub id: String,
    /// what players call it
    pub label: String,
    /// the Modrinth project to install it from
    pub project: String,
    /// display names of the mods that need it
    pub needed_by: Vec<String>,
    /// a disabled jar in mods/ that would provide it (by its enabled name,
    /// `x.jar`), to turn back on instead
    pub disabled_file: Option<String>,
}

/// Mod id → (name players know, Modrinth slug), for the common libraries
/// whose id and slug differ. Anything else is tried under its own id.
fn known(id: &str) -> (String, String) {
    let (label, slug) = match id {
        "fabric" | "fabric-api" => ("Fabric API", "fabric-api"),
        "fabric-language-kotlin" => ("Fabric Language Kotlin", "fabric-language-kotlin"),
        "fabric-language-scala" => ("Fabric Language Scala", "fabric-language-scala"),
        // lucko's, not a Fabric API module despite the name
        "fabric-permissions-api-v0" => ("fabric-permissions-api", "fabric-permissions-api"),
        _ if id.starts_with("fabric-") => ("Fabric API", "fabric-api"),
        "cloth-config" | "cloth-config2" => ("Cloth Config API", "cloth-config"),
        "modmenu" => ("Mod Menu", "modmenu"),
        "yet_another_config_lib_v3" | "yet-another-config-lib" => ("YetAnotherConfigLib", "yacl"),
        "architectury" => ("Architectury API", "architectury-api"),
        "geckolib" => ("GeckoLib", "geckolib"),
        "owo" => ("oωo", "owo-lib"),
        "sodium" => ("Sodium", "sodium"),
        "iris" => ("Iris Shaders", "iris"),
        "forgeconfigapiport" => ("Forge Config API Port", "forge-config-api-port"),
        "puzzleslib" => ("Puzzles Lib", "puzzles-lib"),
        "resourcefullib" => ("Resourceful Lib", "resourceful-lib"),
        "midnightlib" => ("MidnightLib", "midnightlib"),
        "trinkets" => ("Trinkets", "trinkets"),
        "playeranimator" => ("playerAnimator", "playeranimator"),
        "creativecore" => ("CreativeCore", "creativecore"),
        _ => return (id.to_string(), id.to_string()),
    };
    (label.to_string(), slug.to_string())
}

fn read_entry<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, name: &str, cap: u64) -> Option<Vec<u8>> {
    let mut f = zip.by_name(name).ok()?;
    if f.size() > cap {
        return None;
    }
    let mut buf = Vec::with_capacity(f.size() as usize);
    f.read_to_end(&mut buf).ok()?;
    Some(buf)
}

fn meta_of<R: Read + Seek>(zip: &mut zip::ZipArchive<R>) -> Option<serde_json::Value> {
    serde_json::from_slice(&read_entry(zip, "fabric.mod.json", META_CAP)?).ok()
}

/// The ids a jar provides — its own, its `provides`, and its bundled jars'
/// (two levels down) — each with the version it comes in.
fn provided<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, meta: &serde_json::Value, depth: u32, out: &mut HashMap<String, String>) {
    let version = meta.get("version").and_then(|v| v.as_str()).unwrap_or_default();
    let own = meta.get("id").and_then(|v| v.as_str()).into_iter();
    let also = meta.get("provides").and_then(|v| v.as_array()).into_iter().flatten().filter_map(|v| v.as_str());
    for id in own.chain(also) {
        // a top-level id wins over a copy bundled deeper down
        out.entry(id.to_string()).or_insert_with(|| version.to_string());
    }
    if depth >= 2 {
        return;
    }
    for nested in meta.get("jars").and_then(|v| v.as_array()).into_iter().flatten() {
        let Some(file) = nested.get("file").and_then(|v| v.as_str()) else { continue };
        let Some(bytes) = read_entry(zip, file, NESTED_CAP) else { continue };
        let Ok(mut inner) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) else { continue };
        if let Some(inner_meta) = meta_of(&mut inner) {
            provided(&mut inner, &inner_meta, depth + 1, out);
        }
    }
}

/// Fabric's reading of a version: numeric core and pre-release, build
/// metadata (`+mc1.21.11`) ignored.
#[derive(Debug, PartialEq)]
struct SemVer {
    core: Vec<u64>,
    pre: Option<String>,
}

fn parse_semver(s: &str) -> Option<SemVer> {
    let s = s.split('+').next()?;
    let (core, pre) = match s.split_once('-') {
        Some((core, pre)) => (core, Some(pre)),
        None => (s, None),
    };
    let valid_pre = |p: &str| p.split('.').all(|id| !id.is_empty() && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-'));
    if pre.is_some_and(|p| !valid_pre(p)) {
        return None;
    }
    let core = core.split('.').map(|c| c.parse().ok()).collect::<Option<Vec<u64>>>()?;
    Some(SemVer { core, pre: pre.map(str::to_string) })
}

impl SemVer {
    /// missing components count as 0, like Fabric
    fn part(&self, i: usize) -> u64 {
        self.core.get(i).copied().unwrap_or(0)
    }
}

fn cmp_semver(a: &SemVer, b: &SemVer) -> std::cmp::Ordering {
    use std::cmp::Ordering::*;
    for i in 0..a.core.len().max(b.core.len()) {
        match a.part(i).cmp(&b.part(i)) {
            Equal => {}
            o => return o,
        }
    }
    match (&a.pre, &b.pre) {
        (None, None) => Equal,
        (Some(_), None) => Less,
        (None, Some(_)) => Greater,
        (Some(x), Some(y)) => {
            let num = |s: &str| !s.is_empty() && s.chars().all(|c| c.is_ascii_digit());
            let (mut xs, mut ys) = (x.split('.'), y.split('.'));
            loop {
                match (xs.next(), ys.next()) {
                    (None, None) => return Equal,
                    (None, Some(_)) => return Less,
                    (Some(_), None) => return Greater,
                    (Some(p), Some(q)) => {
                        let o = match (num(p), num(q)) {
                            (true, true) => p.len().cmp(&q.len()).then(p.cmp(q)),
                            (true, false) => Less,
                            (false, true) => Greater,
                            (false, false) => p.cmp(q),
                        };
                        if o != Equal {
                            return o;
                        }
                    }
                }
            }
        }
    }
}

/// One space-separated part of a Fabric version predicate (`>=1.2`, `~0.5`,
/// `1.21.x`, `*`) against a version. None when it can't be told — a
/// version or bound that isn't semver — so nothing is flagged on a guess.
fn term_holds(version: &str, term: &str) -> Option<bool> {
    use std::cmp::Ordering::*;
    if term == "*" {
        return Some(true);
    }
    let (op, rest) = [">=", "<=", ">", "<", "=", "~", "^"]
        .iter()
        .find_map(|op| term.strip_prefix(op).map(|rest| (*op, rest)))
        .unwrap_or(("=", term));
    // x-ranges: 1.x is ^1, 1.2.x is ~1.2
    let (op, rest) = match rest.rsplit_once('.') {
        Some((base, "x" | "X" | "*")) if op == "=" => (if base.contains('.') { "~" } else { "^" }, base),
        Some((_, "x" | "X" | "*")) => return None,
        _ if matches!(rest, "x" | "X") => return Some(true),
        _ => (op, rest),
    };
    let (have, want) = (parse_semver(version)?, parse_semver(rest)?);
    let o = cmp_semver(&have, &want);
    Some(match op {
        ">=" => o != Less,
        "<=" => o != Greater,
        ">" => o == Greater,
        "<" => o == Less,
        "~" => o != Less && have.part(0) == want.part(0) && have.part(1) == want.part(1),
        "^" => o != Less && have.part(0) == want.part(0),
        _ => o == Equal,
    })
}

/// A `depends`/`breaks` value against a version: a string is every term at
/// once, an array is any of its strings.
fn satisfies(version: &str, predicate: &serde_json::Value) -> Option<bool> {
    fn all(version: &str, s: &str) -> Option<bool> {
        let mut out = Some(true);
        for term in s.split(' ').filter(|t| !t.is_empty()) {
            match term_holds(version, term) {
                Some(false) => return Some(false),
                None => out = None,
                Some(true) => {}
            }
        }
        out
    }
    match predicate {
        serde_json::Value::String(s) => all(version, s),
        serde_json::Value::Array(list) if !list.is_empty() => {
            let mut out = Some(false);
            for s in list {
                match all(version, s.as_str()?) {
                    Some(true) => return Some(true),
                    None => out = None,
                    Some(false) => {}
                }
            }
            out
        }
        _ => None,
    }
}

/// How a predicate reads to a player: `>=0.110` or `~1.21.4 or ~1.21.5`
fn predicate_text(predicate: &serde_json::Value) -> String {
    match predicate {
        serde_json::Value::Array(list) => list.iter().filter_map(|v| v.as_str()).collect::<Vec<_>>().join(" or "),
        v => v.as_str().unwrap_or("*").to_string(),
    }
}

/// One jar in mods/: what it provides and, when enabled, what it needs.
#[derive(Clone)]
struct Jar {
    filename: String,
    enabled: bool,
    id: Option<String>,
    name: String,
    version: String,
    /// every mod id inside it → that module's version
    provides: HashMap<String, String>,
    depends: Vec<(String, serde_json::Value)>,
    breaks: Vec<(String, serde_json::Value)>,
}

fn read_jar(path: &std::path::Path, filename: String, enabled: bool) -> Option<Jar> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()?;
    let meta = meta_of(&mut zip)?;
    let mut provides = HashMap::new();
    provided(&mut zip, &meta, 0, &mut provides);
    let str_of = |key: &str| meta.get(key).and_then(|v| v.as_str()).map(str::to_string);
    let entries = |key: &str| {
        meta.get(key)
            .and_then(|v| v.as_object())
            .map(|m| m.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
            .unwrap_or_default()
    };
    Some(Jar {
        name: str_of("name").or_else(|| str_of("id")).unwrap_or_else(|| filename.clone()),
        id: str_of("id"),
        version: str_of("version").unwrap_or_default(),
        filename,
        enabled,
        provides,
        depends: entries("depends"),
        breaks: entries("breaks"),
    })
}

/// A library the mods need that mods/ has, but in a version they don't take.
#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Mismatch {
    /// the jar that has it ("Fabric API"), or "Minecraft"
    pub name: String,
    pub version: String,
    /// its file in mods/ — None for Minecraft itself
    pub file: Option<String>,
    pub needed_by: Vec<Needer>,
}

#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Needer {
    pub name: String,
    pub file: String,
    /// the version range it asks for, as written
    pub wants: String,
}

/// The same mod in mods/ more than once — Fabric stops on it.
#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Duplicate {
    pub name: String,
    /// the newest copy
    pub keep: String,
    pub keep_version: String,
    /// the older copies, to turn off
    pub extra: Vec<String>,
}

/// A mod that says it doesn't work alongside another one present (its `breaks`).
#[derive(Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Clash {
    pub name: String,
    pub file: String,
    pub other: String,
    pub other_version: String,
    pub other_file: String,
}

#[derive(Serialize, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct ModProblems {
    pub missing: Vec<MissingDep>,
    pub mismatched: Vec<Mismatch>,
    pub duplicates: Vec<Duplicate>,
    pub clashes: Vec<Clash>,
}

fn newer(a: &str, b: &str) -> bool {
    match (parse_semver(a), parse_semver(b)) {
        (Some(x), Some(y)) => cmp_semver(&x, &y) == std::cmp::Ordering::Greater,
        _ => a > b,
    }
}

/// `game` is the instance's Minecraft version when it's a plain release, so
/// mods made for another one show; snapshots aren't semver and are skipped.
fn find_problems(jars: &[Jar], game: Option<&str>) -> ModProblems {
    let enabled: Vec<&Jar> = jars.iter().filter(|j| j.enabled).collect();
    // every id an enabled jar has → (that module's version, the jar)
    let mut providers: HashMap<&str, Vec<(&str, Option<&Jar>)>> = HashMap::new();
    for jar in &enabled {
        for (id, version) in &jar.provides {
            providers.entry(id).or_default().push((version, Some(*jar)));
        }
    }
    let game = game.filter(|v| parse_semver(v).is_some_and(|s| s.pre.is_none()));
    if let Some(v) = game {
        providers.insert("minecraft", vec![(v, None)]);
    }
    let off: HashMap<&str, &str> = jars
        .iter()
        .filter(|j| !j.enabled)
        .flat_map(|j| j.provides.keys().map(move |id| (id.as_str(), j.filename.as_str())))
        .collect();

    let mut out = ModProblems::default();
    // grouped by what players would install: every missing fabric-* module is one Fabric API
    let mut by_project: BTreeMap<String, MissingDep> = BTreeMap::new();
    let mut by_provider: BTreeMap<String, Mismatch> = BTreeMap::new();
    for jar in &enabled {
        for (id, predicate) in &jar.depends {
            // the loader's own (Java, MixinExtras) always fit: it brings its newest copy
            if id != "minecraft" && BUILT_IN.contains(&id.as_str()) {
                continue;
            }
            let Some(found) = providers.get(id.as_str()) else {
                if id == "minecraft" {
                    continue;
                }
                let (label, project) = known(id);
                let entry = by_project.entry(project.clone()).or_insert_with(|| MissingDep {
                    id: id.clone(),
                    label,
                    project,
                    needed_by: Vec::new(),
                    disabled_file: None,
                });
                if entry.disabled_file.is_none() {
                    entry.disabled_file = off.get(id.as_str()).map(|f| f.to_string());
                }
                if !entry.needed_by.contains(&jar.name) {
                    entry.needed_by.push(jar.name.clone());
                }
                continue;
            };
            // wrong version only when every copy is known not to fit
            if !found.iter().all(|(v, _)| satisfies(v, predicate) == Some(false)) {
                continue;
            }
            let (name, version, file) = match found[0] {
                (module, Some(p)) => {
                    // a library bundled inside another mod goes by its own name and version
                    let label = known(id).0;
                    if p.id.as_deref() == Some(id.as_str()) || label == p.name {
                        (p.name.clone(), p.version.clone(), Some(p.filename.clone()))
                    } else {
                        (format!("{label} (inside {})", p.name), module.to_string(), Some(p.filename.clone()))
                    }
                }
                (v, None) => ("Minecraft".to_string(), v.to_string(), None),
            };
            let entry = by_provider.entry(name.clone()).or_insert_with(|| Mismatch { name, version, file, needed_by: Vec::new() });
            if !entry.needed_by.iter().any(|n| n.file == jar.filename) {
                entry.needed_by.push(Needer { name: jar.name.clone(), file: jar.filename.clone(), wants: predicate_text(predicate) });
            }
        }
        for (id, predicate) in &jar.breaks {
            let Some(found) = providers.get(id.as_str()) else { continue };
            let others: Vec<_> = found.iter().filter_map(|(v, j)| Some((*v, (*j)?))).filter(|(_, j)| j.filename != jar.filename).collect();
            if others.is_empty() || !others.iter().all(|(v, _)| satisfies(v, predicate) == Some(true)) {
                continue;
            }
            let other = others[0].1;
            out.clashes.push(Clash {
                name: jar.name.clone(),
                file: jar.filename.clone(),
                other: other.name.clone(),
                other_version: other.version.clone(),
                other_file: other.filename.clone(),
            });
        }
    }
    out.missing = by_project.into_values().collect();
    out.mismatched = by_provider.into_values().collect();

    let mut by_id: BTreeMap<&str, Vec<&Jar>> = BTreeMap::new();
    for jar in &enabled {
        if let Some(id) = &jar.id {
            by_id.entry(id).or_default().push(jar);
        }
    }
    for (_, mut copies) in by_id.into_iter().filter(|(_, c)| c.len() > 1) {
        copies.sort_by(|a, b| {
            if newer(&a.version, &b.version) {
                std::cmp::Ordering::Less
            } else if newer(&b.version, &a.version) {
                std::cmp::Ordering::Greater
            } else {
                a.filename.cmp(&b.filename)
            }
        });
        out.duplicates.push(Duplicate {
            name: copies[0].name.clone(),
            keep: copies[0].filename.clone(),
            keep_version: copies[0].version.clone(),
            extra: copies[1..].iter().map(|j| j.filename.clone()).collect(),
        });
    }
    out
}

/// What would stop the instance's Fabric mods from loading: libraries
/// missing or in the wrong version, the same mod twice, mods that say they
/// break each other. Empty for instances that don't run Fabric.
#[tauri::command]
pub async fn mod_problems(state: State<'_, AppState>, profile_id: String) -> Result<ModProblems, String> {
    let (fabric, dir, game) = {
        let store = state.profiles.lock().unwrap();
        let profile = store.profiles.iter().find(|p| p.id == profile_id).ok_or("profile not found")?;
        (profile.loader.as_str() == "fabric", profile.dirs(&state.data_dir).mods, profile.game_version.clone())
    };
    if !fabric {
        return Ok(ModProblems::default());
    }
    tokio::task::spawn_blocking(move || find_problems(&read_folder(&dir), Some(&game)))
        .await
        .map_err(|e| e.to_string())
}

/// Every jar in a mods folder, on or off.
fn read_folder(dir: &std::path::Path) -> Vec<Jar> {
    std::fs::read_dir(dir)
        .map(|rd| {
            rd.flatten()
                .filter_map(|e| {
                    let filename = e.file_name().to_string_lossy().into_owned();
                    let enabled = filename.ends_with(".jar");
                    // disabled jars go by the name they have when on, like the CONTENT list
                    let logical = match filename.strip_suffix(".disabled") {
                        Some(base) if base.ends_with(".jar") => base.to_string(),
                        _ if enabled => filename,
                        _ => return None,
                    };
                    read_jar(&e.path(), logical, enabled)
                })
                .collect()
        })
        .unwrap_or_default()
}

/// A version range the way players read it: `0.8.4 or 0.8.12`, `>=0.8.1`.
fn plain_range(range: &str) -> String {
    range
        .split(' ')
        .map(|t| t.strip_prefix('=').unwrap_or(t).split('+').next().unwrap_or(t))
        .collect::<Vec<_>>()
        .join(" ")
}

fn short_version(v: &str) -> &str {
    v.split('+').next().unwrap_or(v)
}

/// What would stop the game loading with `new` in place of `old` that
/// wasn't already stopping it with `old`, one plain line each: a mod that
/// pins another version of it, a library it needs in another version, mods
/// that say they break each other. Both are enabled jars in `jars`.
fn new_problems(jars: &[Jar], old: &str, new: &str, game: Option<&str>) -> Vec<String> {
    let without = |skip: &str| jars.iter().filter(|j| j.filename != skip).cloned().collect::<Vec<_>>();
    // the new file stands where the old one did, so its own old problems match
    let at = |f: &str| if f == new { old.to_string() } else { f.to_string() };
    let lines = |p: &ModProblems| {
        let mut out = Vec::new();
        for m in &p.mismatched {
            for n in &m.needed_by {
                out.push((
                    format!("needs|{}|{}|{}", at(&n.file), m.name, n.wants),
                    format!("{} needs {} {}, not {}", n.name, m.name, plain_range(&n.wants), short_version(&m.version)),
                ));
            }
        }
        for c in &p.clashes {
            out.push((
                format!("breaks|{}|{}", at(&c.file), at(&c.other_file)),
                format!("{} doesn't work with {} {}", c.name, c.other, short_version(&c.other_version)),
            ));
        }
        out
    };
    let before: std::collections::HashSet<String> =
        lines(&find_problems(&without(new), game)).into_iter().map(|(k, _)| k).collect();
    let mut out: Vec<String> = Vec::new();
    for (key, text) in lines(&find_problems(&without(old), game)) {
        if !before.contains(&key) && !out.contains(&text) {
            out.push(text);
        }
    }
    out
}

/// After a mod update has downloaded `new` next to `old` in `mods_dir`, what
/// it would break (see [`new_problems`]). Empty when nothing, or when the
/// two can't be told apart.
pub fn update_problems(mods_dir: &std::path::Path, old: &str, new: &str, game: &str) -> Vec<String> {
    if old == new {
        return Vec::new();
    }
    let jars: Vec<Jar> = read_folder(mods_dir).into_iter().filter(|j| j.enabled).collect();
    if !jars.iter().any(|j| j.filename == old) || !jars.iter().any(|j| j.filename == new) {
        return Vec::new();
    }
    new_problems(&jars, old, new, Some(game))
}

/// The version in a new file's name, read the way the installed file's name
/// holds its version: `sodium-fabric-0.8.12+mc1.21.11.jar` at 0.8.12 makes
/// `sodium-fabric-0.8.14+mc1.21.11.jar` 0.8.14. None when the names don't
/// line up.
fn version_from_name(old_name: &str, old_version: &str, new_name: &str) -> Option<String> {
    let core = short_version(old_version);
    if core.is_empty() {
        return None;
    }
    let at = old_name.find(core)?;
    let (prefix, suffix) = (&old_name[..at], &old_name[at + core.len()..]);
    let mid = new_name.strip_prefix(prefix)?.strip_suffix(suffix)?;
    parse_semver(mid)?;
    Some(mid.to_string())
}

/// The enabled jars of a mods folder, read once to vet a list of updates
/// before any of them download.
pub struct ModsFolder(Vec<Jar>);

impl ModsFolder {
    pub fn read(dir: &std::path::Path) -> Self {
        Self(read_folder(dir).into_iter().filter(|j| j.enabled).collect())
    }

    /// Why updating `old` to the file `new` would stop the game loading,
    /// because another mod pins the version it has now (Voxy takes only
    /// certain Sodium builds). None when it wouldn't, or when the new
    /// version can't be read off its name; the download is checked again.
    pub fn held_back(&self, old: &str, new: &str) -> Option<String> {
        let target = self.0.iter().find(|j| j.filename == old)?;
        let id = target.id.as_deref()?;
        let next = version_from_name(old, &target.version, new)?;
        let mut why = Vec::new();
        for j in self.0.iter().filter(|j| j.filename != old) {
            for (_, range) in j.depends.iter().filter(|(d, _)| d == id) {
                if satisfies(&target.version, range) != Some(false) && satisfies(&next, range) == Some(false) {
                    why.push(format!("{} needs {} {}", j.name, target.name, plain_range(&predicate_text(range))));
                }
            }
            for (_, range) in j.breaks.iter().filter(|(d, _)| d == id) {
                if satisfies(&target.version, range) != Some(true) && satisfies(&next, range) == Some(true) {
                    why.push(format!("{} doesn't work with {} {}", j.name, target.name, short_version(&next)));
                }
            }
        }
        (!why.is_empty()).then(|| why.join("; "))
    }
}

/// The ranges the enabled jars in `mods_dir`, and the jars they bundle (two
/// levels down), put on the dependency `dep` (`fabricloader`, `java`). A
/// bundled module can ask for more than its jar does: C2ME's natives-math
/// wants Java 22 while C2ME itself says 21. Only fabric.mod.json files are
/// read, so this stays quick enough to run on every launch.
pub fn requirements(mods_dir: &std::path::Path, dep: &str) -> Vec<serde_json::Value> {
    fn walk<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, dep: &str, depth: u32, out: &mut Vec<serde_json::Value>) {
        let Some(meta) = meta_of(zip) else { return };
        if let Some(range) = meta.get("depends").and_then(|d| d.get(dep)) {
            out.push(range.clone());
        }
        if depth >= 2 {
            return;
        }
        for nested in meta.get("jars").and_then(|v| v.as_array()).into_iter().flatten() {
            let Some(file) = nested.get("file").and_then(|v| v.as_str()) else { continue };
            let Some(bytes) = read_entry(zip, file, NESTED_CAP) else { continue };
            if let Ok(mut inner) = zip::ZipArchive::new(std::io::Cursor::new(bytes)) {
                walk(&mut inner, dep, depth + 1, out);
            }
        }
    }
    let Ok(rd) = std::fs::read_dir(mods_dir) else { return Vec::new() };
    let mut out = Vec::new();
    for e in rd.flatten().filter(|e| e.file_name().to_string_lossy().ends_with(".jar")) {
        if let Some(mut zip) = std::fs::File::open(e.path()).ok().and_then(|f| zip::ZipArchive::new(f).ok()) {
            walk(&mut zip, dep, 0, &mut out);
        }
    }
    out
}

/// Whether `version` of Fabric Loader takes every range; a range that can't
/// be read never counts against it.
pub fn loader_satisfies(version: &str, requirements: &[serde_json::Value]) -> bool {
    requirements.iter().all(|r| satisfies(version, r) != Some(false))
}

/// The Mojang runtimes a launch can move up to, oldest first.
const RUNTIMES: &[(u32, &str)] = &[(17, "java-runtime-gamma"), (21, "java-runtime-delta"), (25, "java-runtime-epsilon")];

/// When a mod's `java` range (from [`requirements`]) shuts out `major`, the
/// oldest newer Mojang runtime every range takes, as (major, component).
pub fn java_for(major: u32, requirements: &[serde_json::Value]) -> Option<(u32, &'static str)> {
    if loader_satisfies(&major.to_string(), requirements) {
        return None;
    }
    RUNTIMES.iter().copied().find(|(m, _)| *m > major && loader_satisfies(&m.to_string(), requirements))
}

/// Of `builds` (newest first, `(version, stable)`), the newest stable one
/// that takes every range, else the newest build at all that does.
pub fn loader_for(builds: &[(String, bool)], requirements: &[serde_json::Value]) -> Option<String> {
    let fits = |v: &&(String, bool)| loader_satisfies(&v.0, requirements);
    builds
        .iter()
        .filter(|b| b.1)
        .find(fits)
        .or_else(|| builds.iter().find(fits))
        .map(|b| b.0.clone())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn java_moves_up_only_for_a_mod_that_needs_it() {
        let c2me = vec![serde_json::json!(">=21"), serde_json::json!(">=22")];
        assert_eq!(java_for(21, &c2me), Some((25, "java-runtime-epsilon")));
        assert_eq!(java_for(25, &c2me), None);
        assert_eq!(java_for(21, &[serde_json::json!(">=17")]), None);
        assert_eq!(java_for(21, &[serde_json::json!(">=99")]), None);
    }

    #[test]
    fn requirements_reach_bundled_jars() {
        let mut inner = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        inner.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
        inner.write_all(br#"{"id":"natives","depends":{"java":">=22"}}"#).unwrap();
        let inner = inner.finish().unwrap().into_inner();
        let dir = std::env::temp_dir().join(format!("dusk-reqs-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let mut outer = zip::ZipWriter::new(std::fs::File::create(dir.join("c2me.jar")).unwrap());
        let opts = zip::write::SimpleFileOptions::default();
        outer.start_file("fabric.mod.json", opts).unwrap();
        outer
            .write_all(br#"{"id":"c2me","depends":{"java":">=21","fabricloader":">=0.16"},"jars":[{"file":"META-INF/jars/n.jar"}]}"#)
            .unwrap();
        outer.start_file("META-INF/jars/n.jar", opts).unwrap();
        outer.write_all(&inner).unwrap();
        outer.finish().unwrap();
        assert_eq!(requirements(&dir, "java"), vec![serde_json::json!(">=21"), serde_json::json!(">=22")]);
        assert_eq!(requirements(&dir, "fabricloader"), vec![serde_json::json!(">=0.16")]);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn loader_bump_picks_newest_stable_that_fits() {
        let reqs = vec![serde_json::json!(">=0.16.14"), serde_json::json!(">=0.19.5")];
        assert!(!loader_satisfies("0.18.4", &reqs));
        assert!(loader_satisfies("0.19.5", &reqs));
        let builds = vec![
            ("0.20.0-beta.1".to_string(), false),
            ("0.19.6".to_string(), true),
            ("0.19.5".to_string(), true),
            ("0.18.4".to_string(), true),
        ];
        assert_eq!(loader_for(&builds, &reqs).as_deref(), Some("0.19.6"));
        assert_eq!(loader_for(&builds, &[serde_json::json!(">=0.20.0-beta.1")]).as_deref(), Some("0.20.0-beta.1"));
        assert_eq!(loader_for(&builds, &[serde_json::json!(">=9")]), None);
    }

    fn jar_bytes(meta: &str, nested: &[(&str, Vec<u8>)]) -> Vec<u8> {
        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let opts = zip::write::SimpleFileOptions::default();
        zip.start_file("fabric.mod.json", opts).unwrap();
        zip.write_all(meta.as_bytes()).unwrap();
        for (name, bytes) in nested {
            zip.start_file(*name, opts).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap().into_inner()
    }

    fn jar(bytes: Vec<u8>, filename: &str, enabled: bool) -> Jar {
        // tests run in parallel and reuse file names
        static N: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let n = N.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let tmp = std::env::temp_dir().join(format!("dusk-deps-{}-{n}-{filename}", std::process::id()));
        std::fs::write(&tmp, bytes).unwrap();
        let out = read_jar(&tmp, filename.into(), enabled).unwrap();
        let _ = std::fs::remove_file(&tmp);
        out
    }

    fn voxy_folder(sodium: &str) -> Vec<Jar> {
        vec![
            jar(
                jar_bytes(r#"{"id":"voxy","name":"Voxy","version":"0.2.16-beta","depends":{"sodium":["=0.8.4","=0.8.11","=0.8.12"]}}"#, &[]),
                "voxy-0.2.16-beta+1.21.11.jar",
                true,
            ),
            jar(
                jar_bytes(r#"{"id":"reeses-sodium-options","name":"Reese's Sodium Options","version":"2.2.3","depends":{"sodium":">=0.8.11"}}"#, &[]),
                "reeses-sodium-options-fabric-2.2.3+mc1.21.11.jar",
                true,
            ),
            jar(
                jar_bytes(&format!(r#"{{"id":"sodium","name":"Sodium","version":"{sodium}+mc1.21.11"}}"#), &[]),
                &format!("sodium-fabric-{sodium}+mc1.21.11.jar"),
                true,
            ),
        ]
    }

    #[test]
    fn update_a_pinned_mod_is_held_back_by_its_name() {
        let folder = ModsFolder(voxy_folder("0.8.12"));
        let old = "sodium-fabric-0.8.12+mc1.21.11.jar";
        assert_eq!(
            folder.held_back(old, "sodium-fabric-0.8.14+mc1.21.11.jar").as_deref(),
            Some("Voxy needs Sodium 0.8.4 or 0.8.11 or 0.8.12")
        );
        // a newer one Voxy takes, and names that don't line up, go through
        assert_eq!(ModsFolder(voxy_folder("0.8.11")).held_back("sodium-fabric-0.8.11+mc1.21.11.jar", old), None);
        assert_eq!(folder.held_back(old, "sodium-0.8.14.jar"), None);
        assert_eq!(version_from_name("voxy-0.2.15-beta+1.21.11.jar", "0.2.15-beta", "voxy-0.2.16-beta+1.21.11.jar").as_deref(), Some("0.2.16-beta"));
    }

    #[test]
    fn update_problems_are_only_the_new_ones() {
        let mut jars = voxy_folder("0.8.12");
        jars.push(jar(
            jar_bytes(r#"{"id":"sodium","name":"Sodium","version":"0.8.14+mc1.21.11"}"#, &[]),
            "sodium-fabric-0.8.14+mc1.21.11.jar",
            true,
        ));
        let old = "sodium-fabric-0.8.12+mc1.21.11.jar";
        assert_eq!(
            new_problems(&jars, old, "sodium-fabric-0.8.14+mc1.21.11.jar", None),
            vec!["Voxy needs Sodium 0.8.4 or 0.8.11 or 0.8.12, not 0.8.14".to_string()]
        );
        // Reese's asking for a Sodium the folder doesn't have, after its update
        let mut jars = voxy_folder("0.8.12");
        jars.push(jar(
            jar_bytes(r#"{"id":"reeses-sodium-options","name":"Reese's Sodium Options","version":"2.2.4","depends":{"sodium":"=0.8.14+mc1.21.11"}}"#, &[]),
            "reeses-sodium-options-fabric-2.2.4+mc1.21.11.jar",
            true,
        ));
        assert_eq!(
            new_problems(&jars, "reeses-sodium-options-fabric-2.2.3+mc1.21.11.jar", "reeses-sodium-options-fabric-2.2.4+mc1.21.11.jar", None),
            vec!["Reese's Sodium Options needs Sodium 0.8.14, not 0.8.12".to_string()]
        );
        // an update that fits says nothing
        let mut jars = voxy_folder("0.8.11");
        jars.push(jar(jar_bytes(r#"{"id":"sodium","name":"Sodium","version":"0.8.12+mc1.21.11"}"#, &[]), old, true));
        assert!(new_problems(&jars, "sodium-fabric-0.8.11+mc1.21.11.jar", old, None).is_empty());
    }

    #[test]
    fn bundled_modules_count_and_missing_ones_group() {
        let module = jar_bytes(r#"{"id":"fabric-rendering-v1"}"#, &[]);
        let api = jar_bytes(
            r#"{"id":"fabric-api","provides":["fabric"],"jars":[{"file":"META-INF/jars/r.jar"}]}"#,
            &[("META-INF/jars/r.jar", module)],
        );
        let needs = jar_bytes(
            r#"{"id":"a","name":"Mod A","depends":{"fabricloader":">=0.16","minecraft":"*","fabric-rendering-v1":"*","cloth-config2":"*"}}"#,
            &[],
        );
        let also = jar_bytes(r#"{"id":"b","name":"Mod B","depends":{"cloth-config":"*","fabric-key-binding-api-v1":"*"}}"#, &[]);
        let cloth = jar_bytes(r#"{"id":"cloth-config","provides":["cloth-config2"]}"#, &[]);

        // with Fabric API on: only Cloth Config is missing, needed by both
        let jars = [jar(api.clone(), "api.jar", true), jar(needs.clone(), "a.jar", true), jar(also.clone(), "b.jar", true)];
        let missing = find_problems(&jars, None).missing;
        assert_eq!(missing.len(), 2, "{missing:?}");
        let cloth_dep = missing.iter().find(|m| m.project == "cloth-config").unwrap();
        assert_eq!(cloth_dep.needed_by, ["Mod A", "Mod B"]);
        assert_eq!(cloth_dep.label, "Cloth Config API");
        // fabric-key-binding-api-v1 isn't in this tiny test Fabric API
        assert!(missing.iter().any(|m| m.project == "fabric-api"));
        assert_eq!(known("fabric-permissions-api-v0").1, "fabric-permissions-api");

        // Fabric API turned off: one entry for it, pointing at the disabled jar
        let jars = [
            jar(api, "api.jar", false),
            jar(needs, "a.jar", true),
            jar(cloth, "cloth.jar", true),
            jar(also, "b.jar", true),
        ];
        let missing = find_problems(&jars, None).missing;
        assert_eq!(missing.len(), 1, "{missing:?}");
        assert_eq!(missing[0].label, "Fabric API");
        assert_eq!(missing[0].needed_by, ["Mod A", "Mod B"]);
        assert_eq!(missing[0].disabled_file.as_deref(), Some("api.jar"));
    }

    #[test]
    fn fabric_version_ranges() {
        let t = |v: &str, p: &str| satisfies(v, &serde_json::json!(p));
        assert_eq!(t("0.116.0+1.21.11", ">=0.110.0"), Some(true));
        assert_eq!(t("0.100.0+1.21.1", ">=0.110.0"), Some(false));
        // Fabric's ~ pins major.minor only
        assert_eq!(t("1.21.11", "~1.21.4"), Some(true));
        assert_eq!(t("1.22.0", "~1.21.4"), Some(false));
        assert_eq!(t("1.21.11", "1.21.x"), Some(true));
        assert_eq!(t("1.21.11", ">=1.21 <1.22"), Some(true));
        assert_eq!(t("0.5.8", "<0.6"), Some(true));
        assert_eq!(t("0.6.0-beta.2", "<0.6"), Some(true));
        assert_eq!(t("0.6.0-beta.2", ">=0.6.0-beta.10"), Some(false));
        assert_eq!(t("2.1", "^1.4"), Some(false));
        assert_eq!(t("anything", "*"), Some(true));
        // not semver: never a verdict
        assert_eq!(t("mc1.21-2.0", ">=1.0"), None);
        assert_eq!(t("1.0", ">=1.21.11-"), None);
        assert_eq!(satisfies("1.21.5", &serde_json::json!(["~1.21.4", "~1.21.5"])), Some(true));
        assert_eq!(satisfies("1.21.6", &serde_json::json!(["1.21.4", "1.21.5"])), Some(false));
    }

    #[test]
    fn wrong_versions_twins_and_breaks() {
        let api = jar_bytes(r#"{"id":"fabric-api","name":"Fabric API","version":"0.100.0+1.21.1"}"#, &[]);
        let old = jar_bytes(r#"{"id":"sodium","name":"Sodium","version":"0.5.8+mc1.21.1"}"#, &[]);
        let new = jar_bytes(r#"{"id":"sodium","name":"Sodium","version":"0.6.13+mc1.21.11"}"#, &[]);
        let iris = jar_bytes(
            r#"{"id":"iris","name":"Iris","version":"1.8.0","depends":{"fabric-api":">=0.110","minecraft":"~1.21.11"},"breaks":{"sodium":"<0.6","optifabric":"*"}}"#,
            &[],
        );
        let old_mc = jar_bytes(
            r#"{"id":"x","name":"Old Mod","version":"1.0","depends":{"minecraft":"1.21.4","mixinextras":">=0.5.0"},"provides":["mixinextras"]}"#,
            &[],
        );
        let jars = [
            jar(api, "api.jar", true),
            jar(old, "sodium-old.jar", true),
            jar(new, "sodium-new.jar", true),
            jar(iris, "iris.jar", true),
            jar(old_mc, "old.jar", true),
        ];
        let p = find_problems(&jars, Some("1.21.11"));
        assert!(p.missing.is_empty(), "{:?}", p.missing);
        let names: Vec<_> = p.mismatched.iter().map(|m| m.name.as_str()).collect();
        assert_eq!(names, ["Fabric API", "Minecraft"]);
        assert_eq!(p.mismatched[0].needed_by[0].wants, ">=0.110");
        assert_eq!(p.mismatched[1].needed_by[0].name, "Old Mod");
        assert_eq!(p.duplicates.len(), 1);
        assert_eq!(p.duplicates[0].keep, "sodium-new.jar");
        assert_eq!(p.duplicates[0].extra, ["sodium-old.jar"]);
        // Iris breaks Sodium <0.6, but the new copy isn't — only flagged when every copy is
        assert!(p.clashes.is_empty(), "{:?}", p.clashes);
        // a snapshot instance doesn't judge Minecraft ranges
        assert_eq!(find_problems(&jars, Some("25w14a")).mismatched.len(), 1);
    }
}
