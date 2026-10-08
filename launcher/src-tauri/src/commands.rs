use crate::appstate::{Account, AppState, RunningGame};
use crate::settings::Settings;
use crate::{auth_flow, auth_store};
use fasterlauncher_core::auth::Session;
use fasterlauncher_core::launch::{self, LaunchEnv};
use fasterlauncher_core::natives;
use fasterlauncher_core::profile::{default_jvm_args, Loader, Profile};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager, State};

// ── DTOs (camelCase on the wire, matching src/lib/tauri.ts) ────────────────

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileDto {
    pub id: String,
    pub name: String,
    pub game_version: String,
    pub loader: String,
    pub loader_version: Option<String>,
    pub created_at: u64,
    pub last_played: Option<u64>,
    /// seconds played, all launches together
    pub play_secs: u64,
    pub jvm_args: Vec<String>,
    pub resolution: (u32, u32),
    pub server: Option<String>,
    pub mod_count: usize,
    /// deterministic seed for the procedural card banner
    pub art: u32,
    /// per-instance heap override (MB); null = launcher setting
    pub memory_mb: Option<u32>,
    /// per-instance java executable; null = launcher setting / provisioned
    pub java_path: Option<String>,
    pub group: Option<String>,
    /// absolute path of the instance's own picture; null = the stock banner
    pub icon: Option<String>,
    /// the Modrinth pack it was installed from; null = not from one
    pub pack: Option<PackDto>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PackDto {
    pub project_id: String,
    pub version_id: String,
    pub version_number: String,
}

pub fn dto(p: &Profile, data_dir: &std::path::Path) -> ProfileDto {
    ProfileDto {
        id: p.id.clone(),
        name: p.name.clone(),
        game_version: p.game_version.clone(),
        loader: p.loader.as_str().to_string(),
        loader_version: p.loader_version.clone(),
        created_at: p.created_at,
        last_played: p.last_played,
        play_secs: p.play_secs,
        jvm_args: p.jvm_args.clone(),
        resolution: p.resolution,
        server: p.server.clone(),
        mod_count: p.mod_filenames.len(),
        art: art_seed(&p.id),
        memory_mb: p.memory_mb,
        java_path: p.java_path.clone(),
        group: p.group.clone(),
        icon: crate::icons::icon_path(p, data_dir).map(|f| f.display().to_string()),
        pack: p.pack.as_ref().map(|l| PackDto {
            project_id: l.project_id.clone(),
            version_id: l.version_id.clone(),
            version_number: l.version_number.clone(),
        }),
    }
}

fn art_seed(id: &str) -> u32 {
    // FNV-1a
    let mut h: u32 = 2166136261;
    for b in id.bytes() {
        h ^= b as u32;
        h = h.wrapping_mul(16777619);
    }
    h
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ProfilePatch {
    pub name: Option<String>,
    pub game_version: Option<String>,
    pub loader: Option<String>,
    pub loader_version: Option<Option<String>>,
    pub jvm_args: Option<Vec<String>>,
    pub resolution: Option<(u32, u32)>,
    pub server: Option<Option<String>>,
    /// 0 clears the override (JSON null can't reach an Option<Option<_>>)
    pub memory_mb: Option<u32>,
    /// "" clears the override
    pub java_path: Option<String>,
    /// "" takes it out of its group
    pub group: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionDto {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub release_at: String,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ProgressPayload {
    pub profile_id: String,
    pub stage: String,
    pub done: u64,
    pub total: u64,
    pub done_bytes: u64,
    pub total_bytes: u64,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct GameStatePayload {
    pub profile_id: String,
    pub state: String, // starting | running | exited
    pub code: Option<i32>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct GameLogBatch {
    pub lines: Vec<GameLogLine>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct GameLogLine {
    pub line: String,
    pub stream: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfoDto {
    pub launcher_version: String,
    pub os: String,
    pub data_dir: String,
    /// this build has a Discord application id, so Rich Presence can work
    pub discord_available: bool,
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Emit progress at most ~15×/s per stage; the UI smooths the rest.
struct ProgressEmitter {
    app: AppHandle,
    profile_id: String,
    stage: String,
    done: u64,
    total: u64,
    done_bytes: u64,
    total_bytes: u64,
    last_emit: std::time::Instant,
}

impl ProgressEmitter {
    fn new(app: AppHandle, profile_id: &str, stage: &str, total: u64, total_bytes: u64) -> Self {
        Self {
            app,
            profile_id: profile_id.to_string(),
            stage: stage.to_string(),
            done: 0,
            total,
            done_bytes: 0,
            total_bytes,
            last_emit: std::time::Instant::now() - Duration::from_secs(1),
        }
    }

    fn bump(&mut self, bytes: u64) {
        self.done += 1;
        self.done_bytes += bytes;
        self.flush(false);
    }

    fn flush(&mut self, force: bool) {
        if !force && self.last_emit.elapsed() < Duration::from_millis(66) {
            return;
        }
        self.last_emit = std::time::Instant::now();
        let _ = self.app.emit(
            "launch-progress",
            ProgressPayload {
                profile_id: self.profile_id.clone(),
                stage: self.stage.clone(),
                done: self.done,
                total: self.total,
                done_bytes: self.done_bytes,
                total_bytes: self.total_bytes,
            },
        );
    }
}

// ── profiles ───────────────────────────────────────────────────────────────

#[tauri::command]
pub fn list_profiles(state: State<AppState>) -> Vec<ProfileDto> {
    state.profiles.lock().unwrap().profiles.iter().map(|p| dto(p, &state.data_dir)).collect()
}

#[tauri::command]
pub fn create_profile(
    state: State<AppState>,
    name: String,
    game_version: String,
    loader: String,
    server: Option<String>,
) -> ProfileDto {
    let profile = new_profile(&state, name, game_version, Loader::parse(&loader), server);
    let mut store = state.profiles.lock().unwrap();
    store.profiles.push(profile);
    state.save_profiles(&store);
    dto(store.profiles.last().unwrap(), &state.data_dir)
}

/// A fresh instance with the launcher-wide JVM args and window size, not
/// yet saved.
pub(crate) fn new_profile(state: &AppState, name: String, game_version: String, loader: Loader, server: Option<String>) -> Profile {
    let jvm_args = {
        let settings = state.settings.lock().unwrap();
        let parsed: Vec<String> = settings
            .default_jvm_args
            .split_whitespace()
            .map(str::to_string)
            .collect();
        if parsed.is_empty() { default_jvm_args() } else { parsed }
    };
    Profile {
        id: format!("p{}", now_millis()),
        name,
        game_version,
        loader,
        loader_version: None,
        jvm_args,
        resolution: {
            let s = state.settings.lock().unwrap();
            (s.width, s.height)
        },
        mod_filenames: Vec::new(),
        server,
        created_at: now_millis(),
        last_played: None,
        play_secs: 0,
        memory_mb: None,
        java_path: None,
        group: None,
        icon: None,
        pack: None,
    }
}

#[tauri::command]
pub fn update_profile(state: State<AppState>, id: String, patch: ProfilePatch) -> Result<ProfileDto, String> {
    state
        .patch_profile(&id, |p| {
            if let Some(name) = &patch.name {
                if !name.trim().is_empty() {
                    p.name = name.trim().to_string();
                }
            }
            // a pinned loader build belongs to its loader, and a NeoForge
            // build to exactly one game version: drop pins those edits orphan
            let (was_loader, was_game) = (p.loader, p.game_version.clone());
            if let Some(v) = &patch.game_version {
                p.game_version = v.clone();
            }
            if let Some(l) = &patch.loader {
                p.loader = Loader::parse(l);
            }
            if p.loader != was_loader || (p.loader == Loader::NeoForge && p.game_version != was_game) {
                p.loader_version = None;
            }
            if let Some(lv) = &patch.loader_version {
                p.loader_version = lv.clone();
            }
            if let Some(args) = &patch.jvm_args {
                p.jvm_args = args.clone();
            }
            if let Some(res) = patch.resolution {
                p.resolution = res;
            }
            if let Some(server) = &patch.server {
                p.server = server.clone().filter(|s| !s.trim().is_empty());
            }
            if let Some(mb) = patch.memory_mb {
                p.memory_mb = Some(mb).filter(|m| *m > 0);
            }
            if let Some(group) = &patch.group {
                p.group = Some(group.trim().chars().take(32).collect::<String>()).filter(|s| !s.is_empty());
            }
            if let Some(path) = &patch.java_path {
                p.java_path = Some(path.trim().to_string()).filter(|s| !s.is_empty());
            }
        })
        .map(|p| dto(&p, &state.data_dir))
        .ok_or_else(|| "profile not found".to_string())
}

/// DELETE INSTANCE: drops it from the list and moves its folder — worlds,
/// mods, config — to the trash (removed outright where there is none).
#[tauri::command]
pub async fn delete_profile(state: State<'_, AppState>, id: String) -> Result<(), String> {
    if state.running_game.lock().await.as_ref().is_some_and(|g| g.profile_id == id) {
        return Err("That instance is running — stop the game first.".into());
    }
    let dir = {
        let mut store = state.profiles.lock().unwrap();
        let Some(p) = store.profiles.iter().find(|p| p.id == id).cloned() else { return Ok(()) };
        store.profiles.retain(|p| p.id != id);
        state.save_profiles(&store);
        p.dirs(&state.data_dir).root
    };
    if dir.is_dir() {
        let gone = dir.clone();
        tokio::task::spawn_blocking(move || {
            if let Err(e) = trash::delete(&gone) {
                tracing::warn!("couldn't trash {}: {e}; removing it", gone.display());
                let _ = std::fs::remove_dir_all(&gone);
            }
        })
        .await
        .map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Top-level folders a duplicate leaves behind: the old instance's history,
/// not its setup.
const NOT_DUPLICATED: &[&str] = &["logs", "crash-reports", "screenshots"];

/// Copy `from` into `to` file by file; symlinks are skipped, not followed.
fn copy_tree(from: &std::path::Path, to: &std::path::Path, skip: &[&str]) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for e in std::fs::read_dir(from)? {
        let e = e?;
        let name = e.file_name();
        if skip.iter().any(|s| name == *s) {
            continue;
        }
        let ty = e.file_type()?;
        if ty.is_dir() {
            copy_tree(&e.path(), &to.join(&name), &[])?;
        } else if ty.is_file() {
            std::fs::copy(e.path(), to.join(&name))?;
        }
    }
    Ok(())
}

/// A new instance with this one's settings and a copy of its folder — mods,
/// config, packs and worlds — to try changes on without touching the
/// original. Named "<name> (copy)", numbered if that's taken.
#[tauri::command]
pub async fn duplicate_profile(state: State<'_, AppState>, id: String) -> Result<ProfileDto, String> {
    let (from, copy) = {
        let store = state.profiles.lock().unwrap();
        let src = store.profiles.iter().find(|p| p.id == id).cloned().ok_or("profile not found")?;
        let taken = |n: &str| store.profiles.iter().any(|p| p.name == n);
        let mut name = format!("{} (copy)", src.name);
        let mut n = 2;
        while taken(&name) {
            name = format!("{} (copy {n})", src.name);
            n += 1;
        }
        let from = src.dirs(&state.data_dir).root;
        let copy = Profile {
            id: format!("p{}", now_millis()),
            name,
            created_at: now_millis(),
            last_played: None,
            play_secs: 0,
            ..src
        };
        (from, copy)
    };
    let to = copy.dirs(&state.data_dir).root;
    if from.exists() {
        let dest = to.clone();
        let copied = tokio::task::spawn_blocking(move || copy_tree(&from, &dest, NOT_DUPLICATED))
            .await
            .map_err(|e| e.to_string())?;
        if let Err(e) = copied {
            let _ = std::fs::remove_dir_all(&to);
            return Err(format!("couldn't copy the instance folder: {e}"));
        }
    }
    let mut store = state.profiles.lock().unwrap();
    store.profiles.push(copy);
    state.save_profiles(&store);
    Ok(dto(store.profiles.last().unwrap(), &state.data_dir))
}

/// One world = one subdir of saves/ carrying a level.dat. Name, last
/// modification time (level.dat when present, else the dir), and total size.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldDto {
    pub name: String,
    pub modified: u64,
    pub size: u64,
    /// the world's `icon.png` (the game saves one on first exit), as a data URL
    pub icon: Option<String>,
    #[serde(flatten)]
    pub level: crate::worlds::LevelInfo,
}

/// REPAIR: forget which game files were verified and drop the extracted
/// natives and the offline-launch record, so the next launch hashes every
/// library and asset again, re-fetches anything damaged and re-extracts the
/// natives. Worlds, mods and settings aren't touched.
#[tauri::command(async)]
pub fn repair_profile(state: State<AppState>, id: String) -> Result<(), String> {
    let profile = state
        .profiles
        .lock()
        .unwrap()
        .profiles
        .iter()
        .find(|p| p.id == id)
        .cloned()
        .ok_or("profile not found")?;
    let dirs = profile.dirs(&state.data_dir);
    let _ = std::fs::remove_file(dirs.libraries.join(VERIFIED_CACHE_FILE));
    let _ = std::fs::remove_file(dirs.assets.join(VERIFIED_CACHE_FILE));
    let _ = std::fs::remove_file(dirs.root.join(LAST_INSTALL_FILE));
    for entry in std::fs::read_dir(&dirs.versions).into_iter().flatten().flatten() {
        if entry.file_name().to_string_lossy().ends_with("-natives") && entry.path().is_dir() {
            std::fs::remove_dir_all(entry.path()).map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

fn millis(t: std::time::SystemTime) -> u64 {
    t.duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

pub(crate) fn dir_size(dir: &std::path::Path) -> u64 {
    let mut total = 0u64;
    let mut stack = vec![dir.to_path_buf()];
    // capped walk: worlds can hold thousands of region files; 20k entries is
    // plenty for a size hint and keeps the call instant
    let mut seen = 0usize;
    while let Some(d) = stack.pop() {
        let Ok(rd) = std::fs::read_dir(&d) else { continue };
        for e in rd.flatten() {
            if seen > 20_000 {
                return total;
            }
            seen += 1;
            let p = e.path();
            if p.is_dir() {
                stack.push(p);
            } else {
                total += e.metadata().map(|m| m.len()).unwrap_or(0);
            }
        }
    }
    total
}

#[tauri::command(async)]
pub fn list_worlds(state: State<AppState>, profile_id: String) -> Result<Vec<WorldDto>, String> {
    let store = state.profiles.lock().unwrap();
    let profile = store
        .profiles
        .iter()
        .find(|p| p.id == profile_id)
        .cloned()
        .ok_or_else(|| "profile not found".to_string())?;
    drop(store);
    let saves = profile.dirs(&state.data_dir).root.join("saves");
    let mut out = Vec::new();
    let Ok(rd) = std::fs::read_dir(&saves) else { return Ok(out) };
    for entry in rd.flatten() {
        let path = entry.path();
        if !path.is_dir() || !path.join("level.dat").exists() {
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_string();
        let stamp = std::fs::metadata(path.join("level.dat"))
            .and_then(|m| m.modified())
            .or_else(|_| entry.metadata().and_then(|m| m.modified()))
            .map(millis)
            .unwrap_or(0);
        use base64::Engine;
        let icon = std::fs::read(path.join("icon.png"))
            .ok()
            .filter(|b| b.len() <= 256 * 1024)
            .map(|b| format!("data:image/png;base64,{}", base64::engine::general_purpose::STANDARD.encode(b)));
        let level = crate::worlds::level_info(&path.join("level.dat"));
        out.push(WorldDto { name, modified: stamp, size: dir_size(&path), icon, level });
    }
    out.sort_by_key(|w| std::cmp::Reverse(w.modified));
    Ok(out)
}

/// Reveal a profile folder in the OS file manager. `subdir` is allow-listed
/// so the frontend can never escape the profile root.
#[tauri::command]
pub fn show_in_folder(
    app: AppHandle,
    state: State<AppState>,
    profile_id: String,
    subdir: String,
) -> Result<(), String> {
    let allowed = ["", "mods", "resourcepacks", "shaderpacks", "saves", "logs", "screenshots", "backups"];
    if !allowed.contains(&subdir.as_str()) {
        return Err("unknown folder".to_string());
    }
    let store = state.profiles.lock().unwrap();
    let profile = store
        .profiles
        .iter()
        .find(|p| p.id == profile_id)
        .cloned()
        .ok_or_else(|| "profile not found".to_string())?;
    drop(store);
    let mut path = profile.dirs(&state.data_dir).root;
    if !subdir.is_empty() {
        path = path.join(&subdir);
    }
    std::fs::create_dir_all(&path).map_err(|e| e.to_string())?;
    use tauri_plugin_opener::OpenerExt;
    app.opener()
        .open_path(path.to_string_lossy().to_string(), None::<&str>)
        .map_err(|e| format!("could not open folder: {e}"))?;
    Ok(())
}

/// Reveal the launcher's own data folder (Settings → FILES).
#[tauri::command]
pub fn open_data_dir(app: AppHandle, state: State<AppState>) -> Result<(), String> {
    std::fs::create_dir_all(&state.data_dir).map_err(|e| e.to_string())?;
    use tauri_plugin_opener::OpenerExt;
    app.opener()
        .open_path(state.data_dir.to_string_lossy().to_string(), None::<&str>)
        .map_err(|e| format!("could not open folder: {e}"))?;
    Ok(())
}

// ── versions ───────────────────────────────────────────────────────────────

#[tauri::command]
pub async fn list_versions(state: State<'_, AppState>) -> Result<Vec<VersionDto>, String> {
    let manifest = state.manifest(&state.client).await?;
    Ok(manifest
        .versions
        .into_iter()
        .map(|v| VersionDto {
            id: v.id,
            kind: v.kind,
            release_at: v.release_time,
        })
        .collect())
}

/// The fabric loader the installer would pin for a new profile — the meta
/// endpoint's newest stable loader. The UI shows it next to the version
/// picker so "just choose Minecraft" is genuinely all a user must decide.
#[tauri::command]
pub async fn fabric_loader_version(state: State<'_, AppState>) -> Result<String, String> {
    fasterlauncher_core::fabric::latest_loader_version(&state.client)
        .await
        .map_err(|e| e.to_string())
}

// ── launch ─────────────────────────────────────────────────────────────────

/// Install (if needed) and launch a profile. Resolves once the game process
/// spawns; afterwards `game-log` / `game-state` events stream to the UI.
#[tauri::command]
pub async fn install_and_launch(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    join_server: Option<String>,
    watch_replay: Option<String>,
    open_world: Option<String>,
    host: Option<bool>,
) -> Result<(), String> {
    let host = host.unwrap_or(false);
    // Only one install+spawn at a time; the guard is held until the child spawns.
    let _launch_guard = state
        .launch_lock
        .try_lock()
        .map_err(|_| "a launch is already in progress".to_string())?;
    if state.running_game.lock().await.is_some() {
        return Err("the game is already running — stop it first".into());
    }

    let profile = {
        let store = state.profiles.lock().unwrap();
        store
            .profiles
            .iter()
            .find(|p| p.id == profile_id)
            .cloned()
            .ok_or("profile not found")?
    };
    // "Join" from the friends list: this launch goes straight to that
    // server (`--server`), without touching the saved instance
    let profile = match join_server.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(addr) => {
            if !crate::servers::valid_address(addr) {
                return Err("That server address doesn't look right.".into());
            }
            let mut p = profile;
            p.server = Some(addr.to_string());
            p
        }
        None => profile,
    };
    // PLAY on a world in the WORLDS tab: this launch opens that save
    // (Quick Play) instead of joining the instance's auto-join server
    let (profile, world) = match open_world.as_deref() {
        Some(name) => {
            let saves = profile.dirs(&state.data_dir).root.join("saves");
            if name.is_empty() || name.contains(['/', '\\']) || name == "." || name == ".." || !saves.join(name).join("level.dat").is_file() {
                return Err("That world isn't in this instance.".into());
            }
            let mut p = profile;
            p.server = None;
            (p, Some(name.to_string()))
        }
        None => (profile, None),
    };
    // HOST: DuskClient opens the world to other players and e4mc relays it
    // (see `hosting`), so it takes a Fabric instance on a line DuskClient
    // ships for
    if host {
        if world.is_none() {
            return Err("Pick a world to host.".into());
        }
        if profile.loader != Loader::Fabric || crate::cosmetics::client_mod_jar_for(&profile.game_version).is_none() {
            return Err(format!(
                "Hosting needs a Fabric instance on Minecraft {}.",
                crate::cosmetics::CLIENT_MOD_GAME_VERSIONS
            ));
        }
    }
    // WATCH on the media page: this launch opens that recording as soon as
    // the title screen is up. Only a clip or replay this instance recorded.
    let replay = match watch_replay.as_deref() {
        Some(path) => {
            let path = crate::recordings::resolve(&state, path)?;
            let root = std::fs::canonicalize(&profile.dirs(&state.data_dir).root).map_err(|e| e.to_string())?;
            if !path.starts_with(&root) {
                return Err("That recording belongs to another instance.".into());
            }
            Some(path)
        }
        None => None,
    };
    emit_state(&app, &profile_id, "starting", None);

    let session = launch_session(&state).await?;

    let client = state.client.clone();
    let dirs = profile.dirs(&state.data_dir);
    let app2 = app.clone();

    // the install check needs the network; without it, launch what
    // installed fine last time
    let (version, natives_dir) = match install_profile(&app2, &client, &state, &profile, &dirs).await {
        Ok(installed) => {
            save_last_install(&profile, &dirs, &installed);
            installed
        }
        Err(e) => match load_last_install(&profile, &dirs) {
            Some(installed) => {
                tracing::warn!("install check failed ({e}); launching the last good install offline");
                installed
            }
            None => return Err(e),
        },
    };

    let java_bin = java_for(&app, &client, &state, &profile, &dirs, &version.effective_java()).await?;

    let env = {
        let settings = state.settings.lock().unwrap();
        LaunchEnv {
            env_vars: settings.env_pairs(),
            prelaunch_hook: Some(settings.prelaunch_hook.clone()).filter(|s| !s.trim().is_empty()),
            wrapper_hook: Some(settings.wrapper_hook.clone()).filter(|s| !s.trim().is_empty()),
            post_exit_hook: Some(settings.post_exit_hook.clone()).filter(|s| !s.trim().is_empty()),
            hook_cwd: Some(dirs.root.clone()),
            world,
        }
    };

    // Heap: the instance override, else the launcher-wide MEMORY setting.
    // Either replaces whatever -Xmx/-Xms the stored args carry, so the
    // number the UI shows is the number the JVM gets.
    let profile = {
        let heap_mb = {
            let settings = state.settings.lock().unwrap();
            profile.memory_mb.filter(|m| *m > 0).unwrap_or(settings.memory_mb)
        };
        let mut p = profile.clone();
        if heap_mb > 0 {
            p.jvm_args.retain(|a| !a.starts_with("-Xmx") && !a.starts_with("-Xms"));
            p.jvm_args.insert(0, format!("-Xmx{heap_mb}M"));
            p.jvm_args.insert(0, format!("-Xms{}M", heap_mb.min(2048)));
        }
        p
    };

    // DuskClient is part of every Fabric instance: the bundled jar for the
    // profile's game line is handed to the loader through `-Dfabric.addMods`
    // rather than copied into mods/, so instances stay clean and every launch
    // runs the jar the launcher shipped with. A copy already in mods/ (from
    // "install bundled client mod") wins, otherwise the loader would see the
    // mod twice. The mod reads other players' loadouts from the Dusk service.
    // DuskClient needs Fabric API; fetch it if missing, and if that can't
    // happen (offline, no copy) leave DuskClient out so the game still starts.
    if profile.loader == Loader::Fabric {
        crate::cosmetics::refresh_client_mod_copy(&app, &state.data_dir, &dirs.mods, &profile.game_version);
    }
    // Only when DuskClient goes in: an instance that doesn't get it keeps
    // exactly the mods its owner chose.
    let injects_client = profile.loader == Loader::Fabric
        && !crate::cosmetics::client_mod_in_mods(&dirs.mods)
        && crate::cosmetics::client_mod_jar_for(&profile.game_version).is_some();
    let fabric_api_ok = !(injects_client || host) || crate::mods::ensure_fabric_api(&app, &profile, &dirs.mods).await;
    // the relay, unless the instance carries its own copy
    let relay = if host {
        if !fabric_api_ok {
            return Err("Hosting needs Fabric API, and it couldn't be downloaded. Check your connection.".into());
        }
        let mods = dirs.mods.clone();
        let has_own = tokio::task::spawn_blocking(move || crate::mods::fabric_mod_ids(&mods).contains(crate::hosting::E4MC_MOD_ID))
            .await
            .unwrap_or(false);
        match has_own {
            true => None,
            false => Some(crate::hosting::relay_jar(&state, &profile.game_version).await.ok_or_else(|| {
                format!("Couldn't get the relay hosting uses for Minecraft {}. Check your connection.", profile.game_version)
            })?),
        }
    } else {
        None
    };
    let profile = {
        let mut p = profile;
        p.jvm_args.retain(|a| {
            !a.starts_with("-Dfabric.addMods=")
                && !a.starts_with("-Ddusk.api=")
                && !a.starts_with("-Ddusk.loadout=")
                && !a.starts_with("-Ddusk.replay=")
                && !a.starts_with("-Ddusk.tools=")
                && !a.starts_with("-Ddusk.host=")
        });
        if p.loader == Loader::Fabric {
            let in_mods = crate::cosmetics::client_mod_in_mods(&dirs.mods);
            // jars the loader takes on top of mods/, one list
            let mut add_mods: Vec<std::path::PathBuf> = relay.into_iter().collect();
            match crate::cosmetics::client_mod_jar_for(&p.game_version) {
                None => tracing::warn!(
                    "bundled client mod targets {}; skipping it for {}",
                    crate::cosmetics::CLIENT_MOD_GAME_VERSIONS,
                    p.game_version
                ),
                Some(name) => match crate::cosmetics::bundled_client_mod_jar(&app, &state.data_dir, name) {
                    Some(_) if !in_mods && !fabric_api_ok => {
                        tracing::warn!("Fabric API is missing and couldn't be downloaded; launching without DuskClient")
                    }
                    Some(jar) if !in_mods => add_mods.insert(0, jar),
                    Some(_) => {}
                    None => tracing::warn!("bundled client mod {name} not found; launching without it"),
                },
            }
            if let Ok(list) = std::env::join_paths(&add_mods) {
                if !add_mods.is_empty() {
                    p.jvm_args.push(format!("-Dfabric.addMods={}", list.to_string_lossy()));
                }
            }
            if host {
                p.jvm_args.push("-Ddusk.host=1".into());
            }
            p.jvm_args.push(format!("-Ddusk.api={}", crate::dusk::api_base()));
            p.jvm_args.push(format!("-Ddusk.loadout={}", crate::cosmetics::loadout_path(&state.data_dir).display()));
            if let Some(replay) = &replay {
                p.jvm_args.push(format!("-Ddusk.replay={}", replay.display()));
            }
            // where the mod keeps tools it fetches itself (ffmpeg for video
            // export), shared by every instance instead of one copy each
            p.jvm_args.push(format!("-Ddusk.tools={}", state.data_dir.join("tools").display()));
            if let Err(e) = crate::cosmetics::write_loadout_to_instance(&state.data_dir, &dirs.root) {
                tracing::warn!("could not write cosmetics loadout to instance: {e}");
            }
        }
        p
    };
    let settings_snapshot = if profile.loader == Loader::Fabric && state.settings.lock().unwrap().sync_client_settings {
        crate::client_settings::sync(&state).await;
        match crate::client_settings::apply_to_instance(&state.data_dir, &dirs.root) {
            Ok(snap) => Some(snap),
            Err(e) => {
                tracing::warn!("could not apply synced client settings: {e}");
                None
            }
        }
    } else {
        None
    };
    seed_instance_config(&dirs.root);
    let spec = launch::build_launch_spec(&java_bin, &version, &profile, &dirs, &natives_dir, &session, &env);
    let mut child = launch::launch(&spec, &env).await.map_err(|e| e.to_string())?;
    let started = std::time::SystemTime::now();
    // the last lines of output, for explaining a crash
    let tail = std::sync::Arc::new(std::sync::Mutex::new(std::collections::VecDeque::<String>::new()));

    // stream stdout/stderr in batches, supervise exit
    let mut stdout = child.stdout.take();
    let mut stderr = child.stderr.take();
    let (tx, mut rx) = tokio::sync::mpsc::channel::<GameLogLine>(512);
    if let Some(out) = stdout.take() {
        let tx = tx.clone();
        tokio::spawn(async move {
            use tokio::io::{AsyncBufReadExt, BufReader};
            let mut reader = BufReader::new(out);
            let mut line = String::new();
            loop {
                line.clear();
                match reader.read_line(&mut line).await {
                    Ok(0) | Err(_) => break,
                    Ok(_) => {
                        let _ = tx
                            .send(GameLogLine {
                                line: line.trim_end().to_string(),
                                stream: "out".into(),
                            })
                            .await;
                    }
                }
            }
        });
    }
    if let Some(err) = stderr.take() {
        let tx = tx.clone();
        tokio::spawn(async move {
            use tokio::io::{AsyncBufReadExt, BufReader};
            let mut reader = BufReader::new(err);
            let mut line = String::new();
            loop {
                line.clear();
                match reader.read_line(&mut line).await {
                    Ok(0) | Err(_) => break,
                    Ok(_) => {
                        let _ = tx
                            .send(GameLogLine {
                                line: line.trim_end().to_string(),
                                stream: "err".into(),
                            })
                            .await;
                    }
                }
            }
        });
    }

    let app3 = app.clone();
    let tail2 = tail.clone();
    let forwarder = tokio::spawn(async move {
        let mut lines: Vec<GameLogLine> = Vec::new();
        let mut last_flush = tokio::time::Instant::now();
        loop {
            let timeout = if lines.is_empty() {
                Duration::from_secs(3600)
            } else {
                Duration::from_millis(120).saturating_sub(last_flush.elapsed())
            };
            let recv = rx.recv();
            let Ok(recv) = tokio::time::timeout(timeout, recv).await else {
                let _ = app3.emit("game-log", GameLogBatch { lines: std::mem::take(&mut lines) });
                continue;
            };
            match recv {
                Some(line) => {
                    if let Some(server) = presence_from_log(&line.line) {
                        set_activity_server(&app3, server, false);
                    } else if let Some(address) = crate::hosting::relay_address(&line.line) {
                        set_activity_server(&app3, Some(address), true);
                    }
                    {
                        let mut tail = tail2.lock().unwrap();
                        if tail.len() >= crate::crash::LOG_TAIL {
                            tail.pop_front();
                        }
                        tail.push_back(line.line.clone());
                    }
                    lines.push(line);
                    if lines.len() >= 128 || last_flush.elapsed() >= Duration::from_millis(120) {
                        let _ = app3.emit("game-log", GameLogBatch { lines: std::mem::take(&mut lines) });
                        last_flush = tokio::time::Instant::now();
                    }
                }
                None => {
                    if !lines.is_empty() {
                        let _ = app3.emit("game-log", GameLogBatch { lines });
                    }
                    break;
                }
            }
        }
    });

    // supervisor: wait for exit, run post-exit hook, notify UI
    {
        let app3 = app.clone();
        let pid2 = profile_id.clone();
        let env2 = env.clone();
        let root2 = dirs.root.clone();
        let mods2 = dirs.mods.clone();
        let stop = std::sync::Arc::new(tokio::sync::Notify::new());
        let on_play = state.settings.lock().unwrap().on_play.clone();
        *state.running_game.lock().await = Some(RunningGame { profile_id: profile_id.clone(), stop: stop.clone() });
        tokio::spawn(async move {
            let state = app3.state::<AppState>();
            let (status, stopped) = tokio::select! {
                s = child.wait() => (s, false),
                _ = stop.notified() => (stop_child(&mut child).await, true),
            };
            *state.running_game.lock().await = None;
            *state.activity.lock().unwrap() = None;
            let _ = app3.emit("game-activity", None::<crate::appstate::GameActivity>);
            crate::discord::refresh(&app3);
            let post = env2.clone();
            let _ = tokio::task::spawn_blocking(move || launch::run_post_exit(&post)).await;
            let played = started.elapsed().map(|d| d.as_secs()).unwrap_or(0);
            let _ = state.patch_profile(&pid2, |p| p.play_secs += played);
            let code = status.ok().and_then(|s| s.code());
            emit_state(&app3, &pid2, "exited", code);
            if on_play != "keep" {
                if let Some(w) = app3.get_webview_window("main") {
                    let _ = w.show();
                    let _ = w.unminimize();
                    let _ = w.set_focus();
                }
            }
            // an error exit the player didn't ask for: say why, once the
            // last of the output has come through
            if let Some(code) = code.filter(|c| *c != 0 && !stopped) {
                let _ = tokio::time::timeout(Duration::from_secs(3), forwarder).await;
                let log: Vec<String> = tail.lock().unwrap().iter().cloned().collect();
                let (root, mods, pid) = (root2.clone(), mods2.clone(), pid2.clone());
                let crash = tokio::task::spawn_blocking(move || crate::crash::analyze(&pid, &root, &mods, started, &log, code)).await;
                if let Ok(crash) = crash {
                    let _ = app3.emit("game-crash", crash);
                }
            }
            if let Some(snap) = settings_snapshot {
                match crate::client_settings::collect_from_instance(&state.data_dir, &root2, &snap) {
                    Ok(true) => crate::client_settings::sync(&state).await,
                    Ok(false) => {}
                    Err(e) => tracing::warn!("could not collect client settings: {e}"),
                }
            }
        });
    }

    // count the launch on the Dusk account (the first one settles a
    // referral); offline or signed out it simply isn't counted
    {
        let app3 = app.clone();
        tokio::spawn(async move {
            if let Err(e) = crate::dusk::report_launch(&app3.state::<AppState>()).await {
                tracing::debug!("launch not reported to Dusk: {e}");
            }
        });
    }

    *state.activity.lock().unwrap() = Some(crate::appstate::GameActivity {
        profile_id: profile_id.clone(),
        profile_name: profile.name.clone(),
        game_version: profile.game_version.clone(),
        server: None,
        hosting: false,
        started_at: now_millis() / 1000,
    });
    let _ = app.emit("game-activity", state.activity.lock().unwrap().clone());
    crate::discord::refresh(&app);
    emit_state(&app, &profile_id, "running", None);
    if let Some(w) = app.get_webview_window("main") {
        match state.settings.lock().unwrap().on_play.as_str() {
            "minimize" => drop(w.minimize()),
            "hide" => drop(w.hide()),
            _ => {}
        }
    }
    let _ = state.patch_profile(&profile_id, |p| p.last_played = Some(now_millis()));
    Ok(())
}

/// What a game log line says about where the player is: `Some(Some(addr))`
/// joined a server, `Some(None)` is singleplayer or back at the menus. The
/// bundled client mod logs `[DuskPresence] …`; without it (vanilla, other
/// loaders) the vanilla "Connecting to host, port" line still catches joins.
fn presence_from_log(line: &str) -> Option<Option<String>> {
    if let Some(i) = line.find("[DuskPresence] ") {
        let rest = line[i + "[DuskPresence] ".len()..].trim();
        return Some(rest.strip_prefix("server ").map(|a| a.trim().to_ascii_lowercase()).filter(|a| !a.is_empty()));
    }
    let i = line.find("Connecting to ")?;
    let (host, port) = line[i + "Connecting to ".len()..].trim().split_once(", ")?;
    let port: u16 = port.trim().parse().ok()?;
    let host = host.trim().to_ascii_lowercase();
    if host.is_empty() {
        return None;
    }
    Some(Some(if port == 25565 { host } else { format!("{host}:{port}") }))
}

fn set_activity_server(app: &AppHandle, server: Option<String>, hosting: bool) {
    let state = app.state::<AppState>();
    let changed = {
        let mut activity = state.activity.lock().unwrap();
        match activity.as_mut() {
            Some(a) if a.server != server || a.hosting != hosting => {
                a.server = server;
                a.hosting = hosting;
                true
            }
            _ => false,
        }
    };
    if changed {
        let _ = app.emit("game-activity", state.activity.lock().unwrap().clone());
        crate::discord::refresh(app);
    }
}

/// What the running game is doing right now (`None` when nothing runs).
#[tauri::command]
pub fn game_activity(state: State<'_, AppState>) -> Option<crate::appstate::GameActivity> {
    state.activity.lock().unwrap().clone()
}

fn emit_state(app: &AppHandle, profile_id: &str, state: &str, code: Option<i32>) {
    let _ = app.emit(
        "game-state",
        GameStatePayload {
            profile_id: profile_id.to_string(),
            state: state.to_string(),
            code,
        },
    );
}

#[tauri::command]
pub async fn stop_game(state: State<'_, AppState>) -> Result<(), String> {
    // the supervisor ends the process and sends `exited`, which clears the UI
    if let Some(game) = state.running_game.lock().await.as_ref() {
        game.stop.notify_one();
    }
    Ok(())
}

/// End the game for STOP: ask it to quit first, so Minecraft's shutdown hook
/// can save a singleplayer world, then force it if it's still up.
async fn stop_child(child: &mut tokio::process::Child) -> std::io::Result<std::process::ExitStatus> {
    #[cfg(unix)]
    if let Some(pid) = child.id() {
        let _ = tokio::process::Command::new("kill").args(["-TERM", &pid.to_string()]).status().await;
        if let Ok(status) = tokio::time::timeout(Duration::from_secs(8), child.wait()).await {
            return status;
        }
    }
    let _ = child.start_kill();
    child.wait().await
}

/// The game currently running, if any — what the UI syncs its PLAY / STOP
/// button to on mount and whenever it doubts the event stream.
#[tauri::command]
pub async fn game_state(state: State<'_, AppState>) -> Result<Option<GameStatePayload>, String> {
    Ok(state.running_game.lock().await.as_ref().map(|g| GameStatePayload {
        profile_id: g.profile_id.clone(),
        state: "running".into(),
        code: None,
    }))
}

/// Java for a profile: the instance's own executable, else the settings
/// override for this major version, else the provisioned runtime.
async fn java_for(
    app: &AppHandle,
    client: &reqwest::Client,
    state: &AppState,
    profile: &Profile,
    dirs: &fasterlauncher_core::profile::ProfileDirs,
    java: &fasterlauncher_core::meta::JavaVersion,
) -> Result<std::path::PathBuf, String> {
    let configured = {
        let settings = state.settings.lock().unwrap();
        profile
            .java_path
            .as_deref()
            .filter(|p| !p.trim().is_empty())
            .or_else(|| {
                settings
                    .java_paths
                    .get(&java.major_version.to_string())
                    .map(String::as_str)
                    .filter(|p| !p.trim().is_empty())
            })
            .map(std::path::PathBuf::from)
    };
    if let Some(p) = configured {
        return Ok(p);
    }
    let mut prog = ProgressEmitter::new(app.clone(), &profile.id, "java", 1, 0);
    let runtime_dir = dirs.runtimes.join(&java.component);
    fasterlauncher_core::java::provision(client, &java.component, &dirs.runtimes, |_| {})
        .await
        .map_err(|e| e.to_string())?;
    prog.bump(0);
    prog.flush(true);
    Ok(fasterlauncher_core::java::java_executable(&runtime_dir))
}

/// The resolved version of an instance's last good install, so it can
/// launch without the network.
#[derive(Serialize, Deserialize)]
struct LastInstall {
    key: String,
    version: fasterlauncher_core::meta::VersionJson,
    natives: std::path::PathBuf,
}

const LAST_INSTALL_FILE: &str = ".dusk-last-install.json";

/// What the cached install must match: a changed version or loader means a
/// different game.
fn install_key(profile: &Profile) -> String {
    format!(
        "{}|{:?}|{}",
        profile.game_version,
        profile.loader,
        profile.loader_version.as_deref().unwrap_or("")
    )
}

fn save_last_install(
    profile: &Profile,
    dirs: &fasterlauncher_core::profile::ProfileDirs,
    (version, natives): &(fasterlauncher_core::meta::VersionJson, std::path::PathBuf),
) {
    let last = LastInstall { key: install_key(profile), version: version.clone(), natives: natives.clone() };
    if let Ok(json) = serde_json::to_vec(&last) {
        let _ = std::fs::write(dirs.root.join(LAST_INSTALL_FILE), json);
    }
}

fn load_last_install(
    profile: &Profile,
    dirs: &fasterlauncher_core::profile::ProfileDirs,
) -> Option<(fasterlauncher_core::meta::VersionJson, std::path::PathBuf)> {
    let bytes = std::fs::read(dirs.root.join(LAST_INSTALL_FILE)).ok()?;
    let last: LastInstall = serde_json::from_slice(&bytes).ok()?;
    let jar = dirs.versions.join(format!("{}.jar", last.version.id));
    (last.key == install_key(profile) && jar.exists() && last.natives.exists()).then_some((last.version, last.natives))
}

/// Where the shared libraries and assets folders remember which files were
/// already checked against their sha1 (see `download::VerifiedCache`).
/// REPAIR deletes it, so the next launch hashes everything again.
pub(crate) const VERIFIED_CACHE_FILE: &str = ".dusk-verified.json";

type InstallResult = Result<
    (
        fasterlauncher_core::meta::VersionJson,
        std::path::PathBuf,
    ),
    String,
>;

async fn install_profile(
    app: &AppHandle,
    client: &reqwest::Client,
    state: &AppState,
    profile: &Profile,
    dirs: &fasterlauncher_core::profile::ProfileDirs,
) -> InstallResult {
    use fasterlauncher_core::{download, meta};

    let manifest = state.manifest(client).await.map_err(|e| e.to_string())?;
    let version = meta::fetch_version_json(client, &manifest, &profile.game_version, &dirs.versions)
        .await
        .map_err(|e| e.to_string())?;

    // Loaders: the loader's profile merged with the vanilla JSON becomes the
    // effective version (loader mainClass and arguments, vanilla java
    // runtime / client jar / asset index).
    let effective_version = match profile.loader {
        Loader::Fabric => fasterlauncher_core::fabric::install_fabric(
            client,
            &profile.game_version,
            profile.loader_version.as_deref(),
            &dirs.versions,
            &version,
        )
        .await
        .map_err(|e| e.to_string())?,
        // NeoForge's installer runs on the game's Java, so that comes first
        Loader::NeoForge => {
            let java_bin = java_for(app, client, state, profile, dirs, &version.effective_java()).await?;
            let mut prog = ProgressEmitter::new(app.clone(), &profile.id, "loader", 1, 0);
            let merged = fasterlauncher_core::neoforge::install_neoforge(
                client,
                &java_bin,
                &profile.game_version,
                profile.loader_version.as_deref(),
                &state.data_dir,
                &dirs.versions,
                &version,
            )
            .await
            .map_err(|e| e.to_string())?;
            prog.bump(0);
            prog.flush(true);
            merged
        }
        Loader::Vanilla => version,
    };

    // Libraries — both Mojang (`downloads.artifact`) and Fabric (maven `url`
    // base + coordinates) dialects resolve through resolve_artifact; without
    // it every fabric jar is silently skipped and KnotClient won't load.
    let libs: Vec<download::Download> = effective_version
        .libraries
        .iter()
        .filter(|l| meta::library_allowed(l))
        .filter_map(|l| {
            // an empty url marks an installer output (NeoForge's patched
            // client), already on disk
            let artifact = l.resolve_artifact().filter(|a| !a.url.is_empty())?;
            Some(download::Download {
                url: artifact.url.clone(),
                dest: dirs.libraries.join(&artifact.path),
                sha1: artifact.sha1.clone(),
                size: artifact.size,
            })
        })
        .collect();
    let sizes: HashMap<String, u64> = libs.iter().filter_map(|d| d.size.map(|s| (d.url.clone(), s))).collect();
    let total_bytes: u64 = sizes.values().sum();
    let sizes = Arc::new(sizes);
    let emitter = Arc::new(std::sync::Mutex::new(ProgressEmitter::new(
        app.clone(),
        &profile.id,
        "libraries",
        libs.len() as u64,
        total_bytes,
    )));
    let emitter2 = emitter.clone();
    let sizes2 = sizes.clone();
    let lib_cache = Arc::new(download::VerifiedCache::load(dirs.libraries.join(VERIFIED_CACHE_FILE)));
    let libs_done = download::download_all_cached(client, libs, 12, Some(lib_cache.clone()), move |ev| {
        if let download::ProgressEvent::FileDone { url } = ev {
            emitter2
                .lock()
                .unwrap()
                .bump(sizes2.get(&url).copied().unwrap_or(0));
        }
    })
    .await;
    lib_cache.save();
    libs_done.map_err(|e| e.to_string())?;
    emitter.lock().unwrap().flush(true);

    // Client jar
    let client_art = effective_version
        .downloads
        .client
        .clone()
        .ok_or("no client jar in version json")?;
    let client_jar_path = dirs.versions.join(format!("{}.jar", effective_version.id));
    let mut prog = ProgressEmitter::new(app.clone(), &profile.id, "client", 1, client_art.size);
    let client_done = download::download_all_cached(
        client,
        vec![download::Download {
            url: client_art.url,
            dest: client_jar_path,
            sha1: Some(client_art.sha1),
            size: Some(client_art.size),
        }],
        1,
        Some(lib_cache.clone()),
        |_| {},
    )
    .await;
    lib_cache.save();
    client_done.map_err(|e| e.to_string())?;
    prog.bump(0);
    prog.flush(true);

    // Assets + persist the asset index where the client expects it
    if let Some(idx) = &effective_version.asset_index {
        tokio::fs::create_dir_all(dirs.assets.join("indexes")).await.map_err(|e| e.to_string())?;
        let index_path = dirs.assets.join("indexes").join(format!("{}.json", idx.id));
        // the saved index when it's still the one the version names; a
        // stale one is replaced, or the game would look up assets by it
        let saved = tokio::fs::read_to_string(&index_path)
            .await
            .ok()
            .filter(|text| download::sha1_hex(text.as_bytes()) == idx.sha1);
        let index_text = match saved {
            Some(text) => text,
            None => {
                let text = client
                    .get(&idx.url)
                    .send()
                    .await
                    .map_err(|e| e.to_string())?
                    .error_for_status()
                    .map_err(|e| e.to_string())?
                    .text()
                    .await
                    .map_err(|e| e.to_string())?;
                tokio::fs::write(&index_path, &text).await.map_err(|e| e.to_string())?;
                text
            }
        };
        let index: serde_json::Value = serde_json::from_str(&index_text).map_err(|e| e.to_string())?;
        let objects = index
            .get("objects")
            .and_then(|o| o.as_object())
            .ok_or("bad asset index")?;
        let mut asset_downloads = Vec::new();
        for (_name, obj) in objects {
            let hash = obj.get("hash").and_then(|h| h.as_str()).unwrap_or_default().to_string();
            if hash.len() < 2 {
                continue;
            }
            let size = obj.get("size").and_then(|s| s.as_u64()).unwrap_or(0);
            asset_downloads.push(download::Download {
                url: format!("https://resources.download.minecraft.net/{}/{}", &hash[..2], hash),
                dest: dirs.assets.join("objects").join(&hash[..2]).join(&hash),
                sha1: Some(hash),
                size: Some(size),
            });
        }
        let total_bytes: u64 = asset_downloads.iter().filter_map(|d| d.size).sum();
        let sizes: Arc<HashMap<String, u64>> =
            Arc::new(asset_downloads.iter().filter_map(|d| d.size.map(|s| (d.url.clone(), s))).collect());
        let emitter = Arc::new(std::sync::Mutex::new(ProgressEmitter::new(
            app.clone(),
            &profile.id,
            "assets",
            asset_downloads.len() as u64,
            total_bytes,
        )));
        let emitter2 = emitter.clone();
        let sizes2 = sizes.clone();
        let asset_cache = Arc::new(download::VerifiedCache::load(dirs.assets.join(VERIFIED_CACHE_FILE)));
        let assets_done = download::download_all_cached(client, asset_downloads, 16, Some(asset_cache.clone()), move |ev| {
            if let download::ProgressEvent::FileDone { url } = ev {
                emitter2
                    .lock()
                    .unwrap()
                    .bump(sizes2.get(&url).copied().unwrap_or(0));
            }
        })
        .await;
        asset_cache.save();
        assets_done.map_err(|e| e.to_string())?;
        emitter.lock().unwrap().flush(true);
    }

    // Natives (LWJGL): download classifier jars and extract
    let natives_dir = dirs.versions.join(format!("{}-natives", effective_version.id));
    if !natives_dir.exists() || std::fs::read_dir(&natives_dir).map_or(true, |d| d.count() == 0) {
        natives::install_natives(client, &effective_version, &dirs.libraries, &natives_dir)
            .await
            .map_err(|e| e.to_string())?;
    }

    let _ = app.emit(
        "launch-progress",
        ProgressPayload {
            profile_id: profile.id.clone(),
            stage: "launching".into(),
            done: 1,
            total: 1,
            done_bytes: 0,
            total_bytes: 0,
        },
    );

    Ok((effective_version, natives_dir))
}

// ── settings ───────────────────────────────────────────────────────────────

#[tauri::command]
pub fn get_settings(state: State<AppState>) -> Settings {
    state.settings.lock().unwrap().clone()
}

/// First-launch config for a fresh instance, written only where the file
/// doesn't exist yet so the player's own choices always win. On macOS,
/// Sodium Extra renders at the display's native (Retina) resolution unless
/// told otherwise: 4x the pixels of what Prism instances usually run. The
/// file may be partial: Sodium Extra fills the rest from its defaults.
fn seed_instance_config(root: &std::path::Path) {
    if cfg!(target_os = "macos") {
        let path = root.join("config").join("sodium-extra-options.json");
        if !path.exists() {
            let _ = std::fs::create_dir_all(root.join("config"));
            let _ = std::fs::write(&path, r#"{"extra_settings":{"reduce_resolution_on_mac":true}}"#);
        }
    }
}

#[tauri::command]
pub fn set_settings(app: AppHandle, state: State<AppState>, mut settings: Settings) -> Result<Settings, String> {
    {
        let mut s = state.settings.lock().unwrap();
        // the UI doesn't know this field; don't let a round-trip rewind it
        settings.jvm_defaults_rev = settings.jvm_defaults_rev.max(s.jvm_defaults_rev);
        // a settings object read before offline play was unlocked must not
        // lock it again; renaming to something non-empty still goes through
        if settings.offline_name.trim().is_empty() {
            settings.offline_name = s.offline_name.clone();
        }
        *s = settings.clone();
    }
    state.save_settings(&settings);
    crate::discord::refresh(&app);
    Ok(settings)
}

// ── account ────────────────────────────────────────────────────────────────

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountDto {
    pub username: String,
    pub uuid: String,
    pub authenticated: bool,
    /// the arm model Mojang reports for the active skin: "classic" | "slim"
    /// ("" when unknown)
    pub skin_variant: String,
    /// an offline-play identity (no Microsoft account): launches singleplayer
    /// and offline-mode servers only
    pub offline: bool,
}

#[tauri::command]
pub async fn get_current_account(state: State<'_, AppState>) -> Result<Option<AccountDto>, String> {
    // Hydrate from the persisted session on every call: a still-fresh session
    // must count as signed in too. Falling through to the in-memory account
    // here (empty after a restart) made every launch look signed-out and
    // forced a pointless re-sign-in.
    if let Some(session) = auth_store::load_session(&state.data_dir) {
        let session = if session.needs_refresh() {
            let config = auth_flow::resolve_auth_config(&state);
            match fasterlauncher_core::auth::refresh_session(&state.client, &config, &session).await {
                Ok(fresh) => {
                    auth_store::save_session(&state.data_dir, &fresh);
                    fresh
                }
                // Keep showing the stored identity; the launch path surfaces
                // refresh failures when they actually block playing.
                Err(_) => session,
            }
        } else {
            session
        };
        let dto = account_dto(&session);
        *state.account.lock().unwrap() = Some(Account {
            username: session.username.clone(),
            uuid: session.uuid.clone(),
            authenticated: true,
        });
        return Ok(Some(dto));
    }
    if let Some(session) = offline_session(&state) {
        return Ok(Some(AccountDto {
            username: session.username,
            uuid: session.uuid,
            authenticated: false,
            skin_variant: String::new(),
            offline: true,
        }));
    }
    Ok(state
        .account
        .lock()
        .unwrap()
        .as_ref()
        .map(|a| AccountDto {
            username: a.username.clone(),
            uuid: a.uuid.clone(),
            authenticated: a.authenticated,
            skin_variant: String::new(),
            offline: false,
        }))
}

/// Interactive Microsoft login: opens the browser, waits for the loopback
/// callback, verifies game ownership, persists the session.
#[tauri::command]
pub async fn begin_login(app: AppHandle, state: State<'_, AppState>) -> Result<AccountDto, String> {
    let _login_guard = state
        .login_lock
        .try_lock()
        .map_err(|_| "a sign-in is already in progress".to_string())?;
    let session = cancellable_login(&app, &state, auth_flow::run_login(&app, &state)).await?;
    let dto = account_dto(&session);
    *state.account.lock().unwrap() = Some(Account {
        username: session.username.clone(),
        uuid: session.uuid.clone(),
        authenticated: true,
    });
    Ok(dto)
}

/// The user-chosen device-code sign-in (the alternative offered when the
/// webview sign-in window can't run).
#[tauri::command]
pub async fn begin_code_login(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<AccountDto, String> {
    let _login_guard = state
        .login_lock
        .try_lock()
        .map_err(|_| "a sign-in is already in progress".to_string())?;
    let session = cancellable_login(&app, &state, auth_flow::run_code_login(&app, &state)).await?;
    let dto = account_dto(&session);
    *state.account.lock().unwrap() = Some(Account {
        username: session.username.clone(),
        uuid: session.uuid.clone(),
        authenticated: true,
    });
    Ok(dto)
}

/// Repair login for the Xbox HTTP 400: same flow as [`begin_login`] but the
/// browser is forced to show Microsoft's permission screen (`prompt=consent`)
/// so a missing `XboxLive.signin` grant can actually be approved. A plain
/// retry is not enough there — it would silently reuse the old grants.
#[tauri::command]
pub async fn begin_reconsent_login(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<AccountDto, String> {
    let _login_guard = state
        .login_lock
        .try_lock()
        .map_err(|_| "a sign-in is already in progress".to_string())?;
    let session = cancellable_login(&app, &state, auth_flow::run_reconsent_login(&app, &state)).await?;
    let dto = account_dto(&session);
    *state.account.lock().unwrap() = Some(Account {
        username: session.username.clone(),
        uuid: session.uuid.clone(),
        authenticated: true,
    });
    Ok(dto)
}

fn account_dto(session: &Session) -> AccountDto {
    AccountDto {
        username: session.username.clone(),
        uuid: session.uuid.clone(),
        authenticated: true,
        skin_variant: session.skin_variant.to_lowercase(),
        offline: false,
    }
}

/// Run one interactive sign-in, unless the popup cancels it first. A
/// cancelled webview sign-in also closes its window.
async fn cancellable_login(
    app: &AppHandle,
    state: &AppState,
    login: impl std::future::Future<Output = Result<Session, String>>,
) -> Result<Session, String> {
    tokio::select! {
        r = login => r,
        _ = state.login_cancel.notified() => {
            if let Some(w) = app.get_webview_window("msa-signin") {
                let _ = w.close();
            }
            Err("sign-in cancelled".into())
        }
    }
}

/// Stop the sign-in in flight (the popup's other options, or ADD ACCOUNT
/// LATER). Only wakes a login that is waiting now, so it can't cancel the
/// next one.
#[tauri::command]
pub fn cancel_login(state: State<AppState>) {
    state.login_cancel.notify_waiters();
}

/// Sign out of the active account and forget it; other saved accounts stay
/// in the switcher.
#[tauri::command]
pub fn logout(state: State<AppState>) {
    if let Some(session) = auth_store::load_session(&state.data_dir) {
        auth_store::remove_stashed(&state.data_dir, &session.uuid);
    }
    auth_store::clear_session(&state.data_dir);
    crate::dusk::clear_token(&state.data_dir);
    *state.account.lock().unwrap() = Some(Account::default());
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedAccountDto {
    pub uuid: String,
    pub username: String,
    pub active: bool,
}

/// Every Microsoft account signed in on this machine, for the switcher.
#[tauri::command]
pub fn list_accounts(state: State<AppState>) -> Vec<SavedAccountDto> {
    let active = auth_store::load_session(&state.data_dir).map(|s| s.uuid.replace('-', ""));
    auth_store::list_stashed(&state.data_dir)
        .into_iter()
        .map(|s| SavedAccountDto {
            active: active.as_deref() == Some(s.uuid.replace('-', "").as_str()),
            uuid: s.uuid,
            username: s.username,
        })
        .collect()
}

/// Make a saved account the active one — no browser needed; its refresh
/// token is renewed on the next launch if it has gone stale.
#[tauri::command]
pub async fn switch_account(state: State<'_, AppState>, uuid: String) -> Result<AccountDto, String> {
    if state.running_game.lock().await.is_some() {
        return Err("Close the game before switching accounts.".into());
    }
    let session = auth_store::load_stashed(&state.data_dir, &uuid)
        .ok_or("That account isn't saved on this computer any more — sign in again.")?;
    auth_store::save_session(&state.data_dir, &session);
    crate::dusk::clear_token(&state.data_dir);
    *state.account.lock().unwrap() = Some(Account {
        username: session.username.clone(),
        uuid: session.uuid.clone(),
        authenticated: true,
    });
    Ok(account_dto(&session))
}

/// Forget a saved account that isn't the active one.
#[tauri::command]
pub fn remove_account(state: State<AppState>, uuid: String) -> Result<(), String> {
    let active = auth_store::load_session(&state.data_dir).map(|s| s.uuid.replace('-', ""));
    if active.as_deref() == Some(uuid.replace('-', "").as_str()) {
        return Err("That's the account in use — sign out instead.".into());
    }
    auth_store::remove_stashed(&state.data_dir, &uuid);
    Ok(())
}

/// Resolve the session to play with:
/// - fresh stored session → use (refreshing silently when stale)
/// - no session + login configured → hard error telling the user to sign in
/// - no session + login not configured yet (pre-Azure-approval) → clearly
///   logged demo session so the install/launch pipeline stays testable.
pub(crate) async fn ensure_play_session(state: &AppState) -> Result<Session, String> {
    if let Some(session) = auth_store::load_session(&state.data_dir) {
        if !session.needs_refresh() {
            return Ok(session);
        }
        let config = auth_flow::resolve_auth_config(state);
        return match fasterlauncher_core::auth::refresh_session(&state.client, &config, &session).await {
            Ok(fresh) => {
                auth_store::save_session(&state.data_dir, &fresh);
                Ok(fresh)
            }
            // no connection (not a rejected login): play offline as the
            // saved account. Singleplayer works; servers need a fresh login.
            Err(fasterlauncher_core::Error::Http(e)) if !e.is_status() => {
                tracing::warn!("could not refresh the session ({e}); launching offline");
                Ok(session)
            }
            Err(e) => Err(format!("session expired and refresh failed ({e}) — sign in again")),
        };
    }
    // No session: real auth is required. The demo Player session exists only
    // behind an explicit dev escape hatch so release builds can never
    // silently launch unauthenticated.
    if std::env::var("DUSK_ALLOW_DEMO_PLAY").as_deref() == Ok("1") {
        tracing::warn!("DUSK_ALLOW_DEMO_PLAY=1: launching with a demo Player session (dev only)");
        return Ok(Session {
            access_token: String::new(),
            expires_at: 0,
            uuid: "00000000-0000-0000-0000-000000000000".into(),
            username: "Player".into(),
            xuid: String::new(),
            refresh_token: String::new(),
            skin_url: String::new(),
            skin_variant: String::new(),
        });
    }
    Err("Not signed in — use Sign in with Microsoft first.".into())
}

/// The offline-play identity, once unlocked: the username from settings and
/// the UUID vanilla gives an offline player (`UUID.nameUUIDFromBytes(
/// "OfflinePlayer:" + name)`, an MD5 v3), with no token. Only when no
/// Microsoft session exists — a signed-in account always wins.
pub(crate) fn offline_session(state: &AppState) -> Option<Session> {
    if auth_store::load_session(&state.data_dir).is_some() {
        return None;
    }
    let name = state.settings.lock().unwrap().offline_name.trim().to_string();
    if name.is_empty() {
        return None;
    }
    let uuid = offline_uuid(&name);
    Some(Session {
        access_token: String::new(),
        expires_at: 0,
        uuid,
        username: name,
        xuid: String::new(),
        refresh_token: String::new(),
        skin_url: String::new(),
        skin_variant: String::new(),
    })
}

fn offline_uuid(name: &str) -> String {
    use md5::{Digest, Md5};
    let mut b: [u8; 16] = Md5::digest(format!("OfflinePlayer:{name}").as_bytes()).into();
    b[6] = (b[6] & 0x0f) | 0x30; // version 3
    b[8] = (b[8] & 0x3f) | 0x80; // IETF variant
    let h = hex::encode(b);
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

/// The session PLAY launches with: the Microsoft one, or the offline-play
/// identity when that's unlocked and nobody is signed in. Everything else
/// that needs a real account (Dusk sign-in, skins) keeps using
/// [`ensure_play_session`].
async fn launch_session(state: &AppState) -> Result<Session, String> {
    match offline_session(state) {
        Some(s) => Ok(s),
        None => ensure_play_session(state).await,
    }
}

/// A desktop notification (friend online, new message, gift). The UI
/// decides when — only while the launcher window isn't focused.
#[tauri::command]
pub fn notify(app: AppHandle, title: String, body: String) -> Result<(), String> {
    use tauri_plugin_notification::NotificationExt;
    app.notification()
        .builder()
        .title(title)
        .body(body)
        .show()
        .map_err(|e| e.to_string())
}

// ── app info ───────────────────────────────────────────────────────────────

#[tauri::command]
pub fn get_app_info(state: State<AppState>) -> AppInfoDto {
    AppInfoDto {
        launcher_version: env!("CARGO_PKG_VERSION").into(),
        os: format!("{} ({})", capitalize(std::env::consts::OS), std::env::consts::ARCH),
        data_dir: state.data_dir.display().to_string(),
        discord_available: crate::discord::available(),
    }
}

fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

#[cfg(test)]
mod presence_tests {
    use super::presence_from_log;

    #[test]
    fn reads_dusk_and_vanilla_presence_lines() {
        assert_eq!(
            presence_from_log("[12:00:01] [Render thread/INFO] (duskclient) [DuskPresence] server Play.Example.net"),
            Some(Some("play.example.net".into()))
        );
        assert_eq!(presence_from_log("[Render thread/INFO] (duskclient) [DuskPresence] singleplayer"), Some(None));
        assert_eq!(presence_from_log("[Render thread/INFO] (duskclient) [DuskPresence] menu"), Some(None));
        assert_eq!(
            presence_from_log("[Server Connector #1/INFO] (Minecraft) Connecting to mc.example.net, 25565"),
            Some(Some("mc.example.net".into()))
        );
        assert_eq!(
            presence_from_log("[Server Connector #1/INFO] Connecting to 10.0.0.2, 25570"),
            Some(Some("10.0.0.2:25570".into()))
        );
        assert_eq!(presence_from_log("[Render thread/INFO] Connecting to the database"), None);
        assert_eq!(presence_from_log("Loading 12 mods"), None);
    }
}

#[cfg(test)]
mod duplicate_tests {
    use super::{copy_tree, NOT_DUPLICATED};

    #[test]
    fn copies_the_setup_and_leaves_the_history() {
        let base = std::env::temp_dir().join(format!("dusk-dup-{}", std::process::id()));
        let (from, to) = (base.join("a"), base.join("b"));
        for dir in ["mods", "saves/World/region", "logs", "screenshots"] {
            std::fs::create_dir_all(from.join(dir)).unwrap();
        }
        std::fs::write(from.join("options.txt"), "fov:90").unwrap();
        std::fs::write(from.join("mods/sodium.jar"), "jar").unwrap();
        std::fs::write(from.join("saves/World/region/r.0.0.mca"), "mca").unwrap();
        std::fs::write(from.join("logs/latest.log"), "log").unwrap();
        // a nested folder named like a skipped one is still content
        std::fs::create_dir_all(from.join("config/logs")).unwrap();
        std::fs::write(from.join("config/logs/keep.txt"), "x").unwrap();

        copy_tree(&from, &to, NOT_DUPLICATED).unwrap();
        assert_eq!(std::fs::read_to_string(to.join("options.txt")).unwrap(), "fov:90");
        assert!(to.join("mods/sodium.jar").is_file());
        assert!(to.join("saves/World/region/r.0.0.mca").is_file());
        assert!(to.join("config/logs/keep.txt").is_file());
        assert!(!to.join("logs").exists());
        assert!(!to.join("screenshots").exists());
        let _ = std::fs::remove_dir_all(&base);
    }
}

#[cfg(test)]
mod offline_tests {
    #[test]
    fn matches_vanillas_offline_uuid() {
        // UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes(UTF_8))
        assert_eq!(super::offline_uuid("Notch"), "b50ad385-829d-3141-a216-7e7d7539ba7f");
    }
}
