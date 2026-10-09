//! FROM ANOTHER LAUNCHER in the new-instance dialog: find the instances
//! Prism Launcher, CurseForge, the Modrinth App and Mojang's launcher keep,
//! and copy one in as a Dusk instance — its mods, config, worlds, packs,
//! options and server list — on the same game version and loader. Nothing
//! in the other launcher is changed. Their downloaded game files, logs and
//! account files stay behind: Dusk fetches the game itself and never reads
//! another launcher's credentials.

use crate::appstate::AppState;
use crate::commands::{dto, ProfileDto};
use fasterlauncher_core::profile::{Loader, PackLink};
use serde::Serialize;
use serde_json::Value;
use std::path::{Path, PathBuf};
use tauri::State;

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ExternalInstanceDto {
    /// "Prism Launcher", "CurseForge", "Modrinth App", "Minecraft Launcher"
    pub source: String,
    pub name: String,
    /// None when the launcher's files don't say
    pub game_version: Option<String>,
    /// vanilla | fabric | neoforge | forge | quilt | …
    pub loader: String,
    pub loader_version: Option<String>,
    /// the game folder that gets copied
    pub path: String,
    pub mods: usize,
    pub worlds: usize,
    /// why it can't come in, when it can't
    pub blocked: Option<String>,
    /// the group the other launcher files it under; the import keeps it
    pub group: Option<String>,
    /// the other launcher's picture for it, when it has its own
    #[serde(skip)]
    pub icon: Option<PathBuf>,
    /// the Modrinth pack it was installed from, so it can change version here
    #[serde(skip)]
    pub pack: Option<PackLink>,
}

/// Every instance the other launchers on this machine have.
#[tauri::command]
pub async fn scan_external_instances(state: State<'_, AppState>) -> Result<Vec<ExternalInstanceDto>, String> {
    let mut found = tokio::task::spawn_blocking(scan_all).await.map_err(|e| e.to_string())?;
    // Mojang's launcher profiles can say "latest-release": the version that is
    if found.iter().any(|f| matches!(f.game_version.as_deref(), Some("latest-release" | "latest-snapshot"))) {
        let manifest = state.manifest(&state.client).await.ok();
        for f in &mut found {
            let latest = match f.game_version.as_deref() {
                Some("latest-release") => manifest.as_ref().map(|m| m.latest.release.clone()),
                Some("latest-snapshot") => manifest.as_ref().map(|m| m.latest.snapshot.clone()),
                _ => continue,
            };
            f.game_version = latest;
        }
    }
    for f in &mut found {
        if f.blocked.is_none() && f.game_version.is_none() {
            f.blocked = Some("its Minecraft version isn't recorded".into());
        }
    }
    Ok(found)
}

/// Copy the instance at `path` (one `scan_external_instances` found) into a
/// new Dusk instance.
#[tauri::command]
pub async fn import_external_instance(state: State<'_, AppState>, path: String) -> Result<ProfileDto, String> {
    let found = scan_external_instances(state.clone()).await?;
    let ext = found.into_iter().find(|f| f.path == path).ok_or("That instance isn't there any more.")?;
    if let Some(why) = &ext.blocked {
        return Err(format!("{} can't be imported: {why}.", ext.name));
    }
    let game_version = ext.game_version.clone().ok_or("its Minecraft version isn't recorded")?;
    let name = {
        let store = state.profiles.lock().unwrap();
        let taken = |n: &str| store.profiles.iter().any(|p| p.name == n);
        let mut name = ext.name.clone();
        let mut n = 2;
        while taken(&name) {
            name = format!("{} ({n})", ext.name);
            n += 1;
        }
        name
    };
    let mut profile = crate::commands::new_profile(&state, name, game_version, Loader::parse(&ext.loader), None);
    profile.loader_version = ext.loader_version.clone().filter(|_| profile.loader != Loader::Vanilla);
    profile.group = ext.group.as_deref().map(|g| g.trim().chars().take(32).collect::<String>()).filter(|g| !g.is_empty());
    // the files list is left empty: an update reads it off the installed
    // version's pack. The version number shows; Modrinth has it when the
    // other launcher didn't keep it
    profile.pack = match ext.pack.clone() {
        Some(mut link) if link.version_number.is_empty() => {
            if let Ok(v) = fasterlauncher_core::modrinth::version(&state.client, &link.version_id).await {
                link.version_number = v.version_number;
            }
            Some(link)
        }
        link => link,
    };
    let to = profile.dirs(&state.data_dir).root;
    let from = PathBuf::from(&ext.path);
    let dest = to.clone();
    let copied = tokio::task::spawn_blocking(move || copy_game_dir(&from, &dest)).await.map_err(|e| e.to_string())?;
    if let Err(e) = copied {
        let _ = std::fs::remove_dir_all(&to);
        return Err(format!("couldn't copy {}: {e}", ext.name));
    }
    profile.mod_filenames = std::fs::read_dir(to.join("mods"))
        .map(|rd| {
            rd.flatten()
                .filter_map(|e| e.file_name().into_string().ok())
                .filter(|n| n.ends_with(".jar"))
                .collect()
        })
        .unwrap_or_default();
    let id = profile.id.clone();
    {
        let mut store = state.profiles.lock().unwrap();
        store.profiles.push(profile);
        state.save_profiles(&store);
    }
    if let Some(bytes) = match &ext.icon {
        Some(icon) => tokio::fs::read(icon).await.ok(),
        None => None,
    } {
        if let Err(e) = crate::icons::apply(&state, &id, &bytes) {
            tracing::debug!("{}'s picture not kept: {e}", ext.name);
        }
    }
    let store = state.profiles.lock().unwrap();
    store.profiles.iter().find(|p| p.id == id).map(|p| dto(p, &state.data_dir)).ok_or_else(|| "profile not found".into())
}

fn scan_all() -> Vec<ExternalInstanceDto> {
    let mut out = Vec::new();
    let home = dirs::home_dir().unwrap_or_default();
    let data = dirs::data_dir().unwrap_or_default();

    let mut prism_roots = vec![data.join("PrismLauncher"), home.join(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher")];
    if cfg!(target_os = "linux") {
        prism_roots.push(data.join("multimc"));
    }
    for root in prism_roots {
        out.extend(scan_prism(&root));
    }
    for root in [home.join("curseforge/minecraft/Instances"), home.join("Documents/curseforge/minecraft/Instances")] {
        out.extend(scan_curseforge(&root));
    }
    for root in [data.join("ModrinthApp"), data.join("com.modrinth.theseus")] {
        out.extend(scan_modrinth(&root));
    }
    let vanilla = if cfg!(target_os = "macos") {
        data.join("minecraft")
    } else if cfg!(windows) {
        data.join(".minecraft")
    } else {
        home.join(".minecraft")
    };
    out.extend(scan_vanilla(&vanilla));
    let mut seen = std::collections::HashSet::new();
    out.retain(|f| seen.insert(f.path.clone()));
    out
}

fn entry(source: &str, name: String, game_dir: &Path, version: (Option<String>, String, Option<String>)) -> ExternalInstanceDto {
    let (game_version, loader, loader_version) = version;
    let count = |dir: &str, keep: &dyn Fn(&Path) -> bool| {
        std::fs::read_dir(game_dir.join(dir)).map(|rd| rd.flatten().filter(|e| keep(&e.path())).count()).unwrap_or(0)
    };
    let blocked = match loader.as_str() {
        "vanilla" | "fabric" | "neoforge" => None,
        "forge" => Some("Dusk runs Fabric and NeoForge, not Forge".to_string()),
        "quilt" => Some("Dusk doesn't run Quilt instances".to_string()),
        other => Some(format!("Dusk doesn't run {other} instances")),
    };
    ExternalInstanceDto {
        source: source.into(),
        name,
        game_version,
        loader,
        loader_version,
        path: game_dir.display().to_string(),
        mods: count("mods", &|p| p.extension().is_some_and(|x| x == "jar")),
        worlds: count("saves", &|p| p.join("level.dat").is_file()),
        blocked,
        group: None,
        icon: None,
        pack: None,
    }
}

// ── Prism Launcher / MultiMC ──────────────────────────────────────────────

fn scan_prism(root: &Path) -> Vec<ExternalInstanceDto> {
    // InstanceDir in the launcher's config moves the instances folder
    let cfg = std::fs::read_to_string(root.join("prismlauncher.cfg"))
        .or_else(|_| std::fs::read_to_string(root.join("multimc.cfg")))
        .unwrap_or_default();
    let dir = ini_value(&cfg, "InstanceDir").map(PathBuf::from).unwrap_or_else(|| "instances".into());
    let instances = if dir.is_absolute() { dir } else { root.join(dir) };
    let Ok(rd) = std::fs::read_dir(&instances) else { return Vec::new() };
    let groups = std::fs::read_to_string(instances.join("instgroups.json")).map(|s| prism_groups(&s)).unwrap_or_default();
    let mut out = Vec::new();
    for inst in rd.flatten().map(|e| e.path()) {
        let Ok(pack) = std::fs::read_to_string(inst.join("mmc-pack.json")) else { continue };
        let Some(game_dir) = [".minecraft", "minecraft"].iter().map(|d| inst.join(d)).find(|d| d.is_dir()) else { continue };
        let cfg = std::fs::read_to_string(inst.join("instance.cfg")).unwrap_or_default();
        let name = ini_value(&cfg, "name").unwrap_or_else(|| file_name(&inst));
        let mut e = entry("Prism Launcher", name, &game_dir, parse_mmc_pack(&pack));
        e.group = groups.get(&file_name(&inst)).cloned();
        // iconKey names a file in the launcher's icons folder; the built-in
        // ones ("default", "grass", …) have none and stay behind
        e.icon = ini_value(&cfg, "iconKey")
            .filter(|k| !k.contains(['/', '\\']) && k != "..")
            .and_then(|k| ["png", "jpg", "jpeg", "gif", "webp"].iter().map(|x| root.join("icons").join(format!("{k}.{x}"))).find(|p| p.is_file()));
        e.pack = prism_pack(&cfg);
        out.push(e);
    }
    out
}

/// A Prism instance installed from a Modrinth pack names it in instance.cfg
/// (ManagedPack, ManagedPackType, ManagedPackID, ManagedPackVersionID).
fn prism_pack(cfg: &str) -> Option<PackLink> {
    if ini_value(cfg, "ManagedPack").as_deref() != Some("true") || ini_value(cfg, "ManagedPackType").as_deref() != Some("modrinth") {
        return None;
    }
    Some(PackLink {
        project_id: ini_value(cfg, "ManagedPackID").filter(|v| !v.is_empty())?,
        version_id: ini_value(cfg, "ManagedPackVersionID").filter(|v| !v.is_empty())?,
        version_number: ini_value(cfg, "ManagedPackVersionName").unwrap_or_default(),
        files: Vec::new(),
    })
}

/// instgroups.json: `{"groups": {"<group>": {"instances": ["<folder>", …]}}}`,
/// read as folder → group.
fn prism_groups(json: &str) -> std::collections::HashMap<String, String> {
    let v: Value = serde_json::from_str(json).unwrap_or_default();
    let mut out = std::collections::HashMap::new();
    for (group, g) in v["groups"].as_object().into_iter().flatten() {
        for inst in g["instances"].as_array().into_iter().flatten().filter_map(Value::as_str) {
            out.insert(inst.to_string(), group.clone());
        }
    }
    out
}

fn ini_value(text: &str, key: &str) -> Option<String> {
    text.lines()
        .find_map(|l| l.strip_prefix(key)?.trim_start().strip_prefix('=').map(|v| v.trim().to_string()))
        .filter(|v| !v.is_empty())
}

fn parse_mmc_pack(json: &str) -> (Option<String>, String, Option<String>) {
    let v: Value = serde_json::from_str(json).unwrap_or_default();
    let mut game = None;
    let mut loader = ("vanilla".to_string(), None);
    for c in v["components"].as_array().into_iter().flatten() {
        let version = c["version"].as_str().map(str::to_string);
        match c["uid"].as_str().unwrap_or("") {
            "net.minecraft" => game = version,
            "net.fabricmc.fabric-loader" => loader = ("fabric".into(), version),
            "net.neoforged" => loader = ("neoforge".into(), version),
            "net.minecraftforge" => loader = ("forge".into(), version),
            "org.quiltmc.quilt-loader" => loader = ("quilt".into(), version),
            _ => {}
        }
    }
    (game, loader.0, loader.1)
}

// ── CurseForge ────────────────────────────────────────────────────────────

fn scan_curseforge(root: &Path) -> Vec<ExternalInstanceDto> {
    let Ok(rd) = std::fs::read_dir(root) else { return Vec::new() };
    let mut out = Vec::new();
    for inst in rd.flatten().map(|e| e.path()) {
        let Ok(text) = std::fs::read_to_string(inst.join("minecraftinstance.json")) else { continue };
        let v: Value = serde_json::from_str(&text).unwrap_or_default();
        let name = v["name"].as_str().map(str::to_string).unwrap_or_else(|| file_name(&inst));
        out.push(entry("CurseForge", name, &inst, parse_curseforge(&v)));
    }
    out
}

fn parse_curseforge(v: &Value) -> (Option<String>, String, Option<String>) {
    let game = v["gameVersion"]
        .as_str()
        .or_else(|| v["baseModLoader"]["minecraftVersion"].as_str())
        .map(str::to_string);
    // "fabric-0.16.10-1.21.11", "neoforge-21.1.77", "forge-47.2.0"
    let (loader, loader_version) = match v["baseModLoader"]["name"].as_str() {
        None | Some("") => ("vanilla".to_string(), None),
        Some(name) => {
            let (kind, rest) = name.split_once('-').unwrap_or((name, ""));
            let version = match kind {
                "fabric" | "quilt" => rest.split('-').next(),
                _ => Some(rest),
            };
            (kind.to_ascii_lowercase(), version.filter(|s| !s.is_empty()).map(str::to_string))
        }
    };
    (game, loader, loader_version)
}

// ── Modrinth App ──────────────────────────────────────────────────────────

/// A Modrinth App profile row: path, name, game version, loader, loader version.
type ModrinthProfileRow = (String, String, String, String, Option<String>);

fn scan_modrinth(root: &Path) -> Vec<ExternalInstanceDto> {
    let db = root.join("app.db");
    if !db.is_file() {
        return Vec::new();
    }
    let rows = (|| -> rusqlite::Result<Vec<ModrinthProfileRow>> {
        let conn = rusqlite::Connection::open_with_flags(&db, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)?;
        let mut stmt = conn.prepare("SELECT path, name, game_version, mod_loader, mod_loader_version FROM profiles")?;
        let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)))?;
        rows.collect()
    })();
    let rows = match rows {
        Ok(rows) => rows,
        Err(e) => {
            tracing::warn!("could not read the Modrinth App's instances: {e}");
            return Vec::new();
        }
    };
    // groups are a JSON list per profile; read on their own so an app
    // version without the column still lists its instances
    let extra = |sql: &str| -> std::collections::HashMap<String, String> {
        (|| -> rusqlite::Result<Vec<(String, Option<String>)>> {
            let conn = rusqlite::Connection::open_with_flags(&db, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)?;
            let mut stmt = conn.prepare(sql)?;
            let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?;
            rows.collect()
        })()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|(path, v)| Some((path, v?)))
        .collect()
    };
    let groups: std::collections::HashMap<String, String> = extra("SELECT path, groups FROM profiles")
        .into_iter()
        .filter_map(|(path, groups)| {
            let first = serde_json::from_str::<Vec<String>>(&groups).ok()?.into_iter().find(|g| !g.trim().is_empty())?;
            Some((path, first))
        })
        .collect();
    let icons = extra("SELECT path, icon_path FROM profiles");
    let projects = extra("SELECT path, linked_project_id FROM profiles");
    let versions = extra("SELECT path, linked_version_id FROM profiles");
    rows.into_iter()
        .filter_map(|(path, name, game, loader, loader_version)| {
            let dir = root.join("profiles").join(&path);
            dir.is_dir().then(|| {
                let loader = loader.to_ascii_lowercase();
                let mut e = entry("Modrinth App", name, &dir, (Some(game), loader, loader_version.filter(|v| !v.is_empty())));
                e.group = groups.get(&path).cloned();
                e.icon = icons.get(&path).map(PathBuf::from).filter(|p| p.is_file());
                e.pack = match (projects.get(&path), versions.get(&path)) {
                    (Some(project), Some(version)) if !project.is_empty() && !version.is_empty() => Some(PackLink {
                        project_id: project.clone(),
                        version_id: version.clone(),
                        version_number: String::new(),
                        files: Vec::new(),
                    }),
                    _ => None,
                };
                e
            })
        })
        .collect()
}

// ── Minecraft Launcher ────────────────────────────────────────────────────

fn scan_vanilla(mc: &Path) -> Vec<ExternalInstanceDto> {
    if !mc.is_dir() {
        return Vec::new();
    }
    let profiles: Value = std::fs::read(mc.join("launcher_profiles.json"))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default();
    let mut out = Vec::new();
    // profiles without their own gameDir share one folder, and only one entry
    // per folder is kept: the one last played, which is what's in it
    let mut listed: Vec<&Value> = profiles["profiles"].as_object().into_iter().flat_map(|m| m.values()).collect();
    listed.sort_by(|a, b| b["lastUsed"].as_str().unwrap_or("").cmp(a["lastUsed"].as_str().unwrap_or("")));
    for p in listed {
        let Some(id) = p["lastVersionId"].as_str() else { continue };
        let dir = p["gameDir"].as_str().map(PathBuf::from).unwrap_or_else(|| mc.to_path_buf());
        if !dir.is_dir() {
            continue;
        }
        let name = match (p["name"].as_str().filter(|n| !n.is_empty()), p["type"].as_str()) {
            (Some(n), _) => n.to_string(),
            (None, Some("latest-snapshot")) => "Latest snapshot".to_string(),
            _ => "Minecraft".to_string(),
        };
        out.push(entry("Minecraft Launcher", name, &dir, parse_version_id(id)));
    }
    // no launcher profiles (a folder another tool set up): the folder itself,
    // on the newest version it has installed
    if out.is_empty() && (mc.join("saves").is_dir() || mc.join("options.txt").is_file()) {
        let newest = std::fs::read_dir(mc.join("versions")).ok().and_then(|rd| {
            rd.flatten()
                .filter(|e| e.path().join(format!("{}.json", e.file_name().to_string_lossy())).is_file())
                .max_by_key(|e| e.metadata().and_then(|m| m.modified()).ok())
                .and_then(|e| e.file_name().into_string().ok())
        });
        let version = newest.as_deref().map(parse_version_id).unwrap_or((None, "vanilla".into(), None));
        out.push(entry("Minecraft Launcher", "Minecraft".into(), mc, version));
    }
    out
}

/// A version folder name as Mojang's launcher sees it:
/// `1.21.11`, `fabric-loader-0.16.10-1.21.11`, `neoforge-21.1.77`,
/// `1.20.1-forge-47.2.0`, `quilt-loader-0.26.0-1.21`, `latest-release`.
fn parse_version_id(id: &str) -> (Option<String>, String, Option<String>) {
    if let Some(rest) = id.strip_prefix("fabric-loader-").or_else(|| id.strip_prefix("quilt-loader-")) {
        let loader = if id.starts_with("fabric") { "fabric" } else { "quilt" };
        return match rest.split_once('-') {
            Some((lv, game)) => (Some(game.to_string()), loader.into(), Some(lv.to_string())),
            None => (None, loader.into(), Some(rest.to_string())),
        };
    }
    if let Some(v) = id.strip_prefix("neoforge-") {
        return (neoforge_game_version(v), "neoforge".into(), Some(v.to_string()));
    }
    if let Some((game, forge)) = id.split_once("-forge-") {
        return (Some(game.to_string()), "forge".into(), Some(forge.to_string()));
    }
    if id.to_ascii_lowercase().contains("optifine") {
        return (id.split('-').next().map(str::to_string), "optifine".into(), None);
    }
    (Some(id.to_string()), "vanilla".into(), None)
}

/// NeoForge numbers its builds after the game: 21.1.x is 1.21.1, 21.0.x is
/// 1.21, 26.1.0.x is 26.1.
fn neoforge_game_version(v: &str) -> Option<String> {
    let mut parts = v.split(['.', '-']);
    let major: u32 = parts.next()?.parse().ok()?;
    let minor: u32 = parts.next()?.parse().ok()?;
    Some(if major >= 26 {
        let patch: u32 = parts.next().and_then(|p| p.parse().ok()).unwrap_or(0);
        if patch == 0 { format!("{major}.{minor}") } else { format!("{major}.{minor}.{patch}") }
    } else if minor == 0 {
        format!("1.{major}")
    } else {
        format!("1.{major}.{minor}")
    })
}

fn file_name(p: &Path) -> String {
    p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

// ── copying ───────────────────────────────────────────────────────────────

/// Whether a top-level entry of another launcher's game folder stays
/// behind: downloaded game files (Dusk fetches its own), logs and caches,
/// that launcher's own metadata, and anything holding its accounts.
fn left_behind(name: &str) -> bool {
    const SKIP: &[&str] = &[
        "versions", "libraries", "assets", "natives", "bin", "runtime", "logs", "crash-reports", "debug", "jfr",
        "downloads", "webcache", "webcache2", "minecraftinstance.json", "profile.json", "usercache.json",
        "usernamecache.json", "treatment_tags.json",
    ];
    name.starts_with('.') || name.starts_with("launcher_") || name.ends_with(".log") || SKIP.contains(&name)
}

fn copy_game_dir(from: &Path, to: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for e in std::fs::read_dir(from)?.flatten() {
        let name = e.file_name();
        if left_behind(&name.to_string_lossy()) {
            continue;
        }
        copy_entry(&e.path(), &to.join(&name))?;
    }
    Ok(())
}

/// Files and folders as they are; symlinks are skipped, not followed, and
/// a world's session.lock stays with the original.
fn copy_entry(from: &Path, to: &Path) -> std::io::Result<()> {
    let ty = std::fs::symlink_metadata(from)?.file_type();
    if ty.is_dir() {
        std::fs::create_dir_all(to)?;
        for e in std::fs::read_dir(from)?.flatten() {
            if e.file_name() != "session.lock" {
                copy_entry(&e.path(), &to.join(e.file_name()))?;
            }
        }
    } else if ty.is_file() {
        std::fs::copy(from, to)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_prism_modrinth_pack_keeps_its_link() {
        let cfg = "[General]\nManagedPack=true\nManagedPackID=1KVo5zza\nManagedPackName=Fabulously Optimized\nManagedPackType=modrinth\nManagedPackVersionID=abc123\nManagedPackVersionName=6.4.0\nname=FO\n";
        let link = prism_pack(cfg).unwrap();
        assert_eq!((link.project_id.as_str(), link.version_id.as_str(), link.version_number.as_str()), ("1KVo5zza", "abc123", "6.4.0"));
        assert!(prism_pack(&cfg.replace("=modrinth", "=flame")).is_none());
        assert!(prism_pack(&cfg.replace("ManagedPack=true", "ManagedPack=false")).is_none());
        assert!(prism_pack("name=plain\n").is_none());
    }

    #[test]
    fn reads_version_ids() {
        assert_eq!(
            parse_version_id("fabric-loader-0.16.10-1.21.11"),
            (Some("1.21.11".into()), "fabric".into(), Some("0.16.10".into()))
        );
        assert_eq!(
            parse_version_id("neoforge-21.1.77"),
            (Some("1.21.1".into()), "neoforge".into(), Some("21.1.77".into()))
        );
        assert_eq!(neoforge_game_version("21.0.167"), Some("1.21".into()));
        assert_eq!(neoforge_game_version("26.1.0.12-beta"), Some("26.1".into()));
        assert_eq!(parse_version_id("1.20.1-forge-47.2.0").1, "forge");
        assert_eq!(parse_version_id("26.2"), (Some("26.2".into()), "vanilla".into(), None));
    }

    #[test]
    fn reads_prism_and_curseforge_metadata() {
        let pack = r#"{"components":[{"uid":"org.lwjgl3","version":"3.3.3"},{"uid":"net.minecraft","version":"1.21.11"},{"uid":"net.fabricmc.intermediary","version":"1.21.11"},{"uid":"net.fabricmc.fabric-loader","version":"0.16.10"}]}"#;
        assert_eq!(parse_mmc_pack(pack), (Some("1.21.11".into()), "fabric".into(), Some("0.16.10".into())));
        assert_eq!(ini_value("InstanceType=OneSix\nname=My Pack\n", "name"), Some("My Pack".into()));
        let groups = prism_groups(r#"{"formatVersion":"1","groups":{"PvP":{"hidden":false,"instances":["1.21.11","Fabulously"]}}}"#);
        assert_eq!(groups.get("Fabulously").map(String::as_str), Some("PvP"));
        assert!(prism_groups("not json").is_empty());
        let cf: Value = serde_json::from_str(r#"{"name":"x","gameVersion":"1.21.1","baseModLoader":{"name":"neoforge-21.1.77","minecraftVersion":"1.21.1"}}"#).unwrap();
        assert_eq!(parse_curseforge(&cf), (Some("1.21.1".into()), "neoforge".into(), Some("21.1.77".into())));
        let cf: Value = serde_json::from_str(r#"{"name":"x","gameVersion":"1.21.11","baseModLoader":{"name":"fabric-0.16.10-1.21.11"}}"#).unwrap();
        assert_eq!(parse_curseforge(&cf).2, Some("0.16.10".into()));
    }

    #[test]
    fn profiles_sharing_the_game_folder_list_the_one_last_played() {
        let mc = std::env::temp_dir().join(format!("dusk-import-vanilla-{}", std::process::id()));
        std::fs::create_dir_all(&mc).unwrap();
        std::fs::write(
            mc.join("launcher_profiles.json"),
            r#"{"profiles":{"a":{"name":"","type":"latest-release","lastVersionId":"latest-release","lastUsed":"2026-01-01T00:00:00.000Z"},
                "b":{"name":"fabric","lastVersionId":"fabric-loader-0.16.10-1.21.11","lastUsed":"2026-05-01T00:00:00.000Z"}}}"#,
        )
        .unwrap();
        let mut seen = std::collections::HashSet::new();
        let kept: Vec<_> = scan_vanilla(&mc).into_iter().filter(|f| seen.insert(f.path.clone())).collect();
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].loader, "fabric");
        let _ = std::fs::remove_dir_all(&mc);
    }

    #[test]
    fn leaves_launcher_files_and_downloads_behind() {
        for name in ["launcher_accounts.json", "launcher_msa_credentials.bin", "versions", ".fabric", "logs", "latest.log"] {
            assert!(left_behind(name), "{name}");
        }
        for name in ["mods", "config", "saves", "options.txt", "servers.dat", "resourcepacks", "screenshots"] {
            assert!(!left_behind(name), "{name}");
        }
    }
}
