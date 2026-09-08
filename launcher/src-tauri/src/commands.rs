use crate::appstate::{Account, AppState};
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
    pub jvm_args: Vec<String>,
    pub resolution: (u32, u32),
    pub server: Option<String>,
    pub mod_count: usize,
    /// deterministic seed for the procedural card banner
    pub art: u32,
}

pub fn dto(p: &Profile) -> ProfileDto {
    ProfileDto {
        id: p.id.clone(),
        name: p.name.clone(),
        game_version: p.game_version.clone(),
        loader: p.loader.as_str().to_string(),
        loader_version: p.loader_version.clone(),
        created_at: p.created_at,
        last_played: p.last_played,
        jvm_args: p.jvm_args.clone(),
        resolution: p.resolution,
        server: p.server.clone(),
        mod_count: p.mod_filenames.len(),
        art: art_seed(&p.id),
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
    state.profiles.lock().unwrap().profiles.iter().map(dto).collect()
}

#[tauri::command]
pub fn create_profile(
    state: State<AppState>,
    name: String,
    game_version: String,
    loader: String,
    server: Option<String>,
) -> ProfileDto {
    let jvm_args = {
        let settings = state.settings.lock().unwrap();
        let parsed: Vec<String> = settings
            .default_jvm_args
            .split_whitespace()
            .map(str::to_string)
            .collect();
        if parsed.is_empty() { default_jvm_args() } else { parsed }
    };
    let id = format!("p{}", now_millis());
    let profile = Profile {
        id: id.clone(),
        name,
        game_version,
        loader: Loader::parse(&loader),
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
    };
    let mut store = state.profiles.lock().unwrap();
    store.profiles.push(profile);
    state.save_profiles(&store);
    dto(store.profiles.last().unwrap())
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
            if let Some(v) = &patch.game_version {
                p.game_version = v.clone();
            }
            if let Some(l) = &patch.loader {
                p.loader = Loader::parse(l);
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
        })
        .map(|p| dto(&p))
        .ok_or_else(|| "profile not found".to_string())
}

#[tauri::command]
pub fn delete_profile(state: State<AppState>, id: String) -> Result<(), String> {
    let mut store = state.profiles.lock().unwrap();
    store.profiles.retain(|p| p.id != id);
    state.save_profiles(&store);
    Ok(())
}

/// One world = one subdir of saves/ carrying a level.dat. Name, last
/// modification time (level.dat when present, else the dir), and total size.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldDto {
    pub name: String,
    pub modified: u64,
    pub size: u64,
}

fn millis(t: std::time::SystemTime) -> u64 {
    t.duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn dir_size(dir: &std::path::Path) -> u64 {
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

#[tauri::command]
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
        if !path.is_dir() || path.join("level.dat").exists() == false {
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_string();
        let stamp = std::fs::metadata(path.join("level.dat"))
            .and_then(|m| m.modified())
            .or_else(|_| entry.metadata().and_then(|m| m.modified()))
            .map(millis)
            .unwrap_or(0);
        out.push(WorldDto { name, modified: stamp, size: dir_size(&path) });
    }
    out.sort_by(|a, b| b.modified.cmp(&a.modified));
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
    let allowed = ["", "mods", "resourcepacks", "shaderpacks", "saves", "logs", "screenshots"];
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

// ── launch ─────────────────────────────────────────────────────────────────

/// Install (if needed) and launch a profile. Resolves once the game process
/// spawns; afterwards `game-log` / `game-state` events stream to the UI.
#[tauri::command]
pub async fn install_and_launch(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
) -> Result<(), String> {
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
    emit_state(&app, &profile_id, "starting", None);

    let session = ensure_play_session(&state).await?;

    let client = state.client.clone();
    let dirs = profile.dirs(&state.data_dir);
    let app2 = app.clone();

    let (version, natives_dir) =
        install_profile(&app2, &client, &state, &profile, &dirs).await?;

    // Java: settings override per major version, else provisioned runtime
    let java = version.effective_java();
    let java_bin = {
        let settings = state.settings.lock().unwrap();
        settings
            .java_paths
            .get(&java.major_version.to_string())
            .filter(|p| !p.trim().is_empty())
            .map(std::path::PathBuf::from)
    };
    let java_bin = match java_bin {
        Some(p) => p,
        None => {
            let mut prog = ProgressEmitter::new(app.clone(), &profile_id, "java", 1, 0);
            let runtime_dir = dirs.runtimes.join(&java.component);
            fasterlauncher_core::java::provision(&client, &java.component, &dirs.runtimes, |_| {})
                .await
                .map_err(|e| e.to_string())?;
            prog.bump(0);
            prog.flush(true);
            fasterlauncher_core::java::java_executable(&runtime_dir)
        }
    };

    let env = {
        let settings = state.settings.lock().unwrap();
        LaunchEnv {
            env_vars: settings.env_pairs(),
            prelaunch_hook: Some(settings.prelaunch_hook.clone()).filter(|s| !s.trim().is_empty()),
            wrapper_hook: Some(settings.wrapper_hook.clone()).filter(|s| !s.trim().is_empty()),
            post_exit_hook: Some(settings.post_exit_hook.clone()).filter(|s| !s.trim().is_empty()),
            hook_cwd: Some(dirs.root.clone()),
        }
    };

    let spec = launch::build_launch_spec(&java_bin, &version, &profile, &dirs, &natives_dir, &session, &env);
    let mut child = launch::launch(&spec, &env).await.map_err(|e| e.to_string())?;

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
    tokio::spawn(async move {
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
        tokio::spawn(async move {
            let state = app3.state::<AppState>();
            let mut guard = state.running_game.lock().await;
            let status = match guard.as_mut() {
                Some(child) => child.wait().await,
                None => return,
            };
            *guard = None;
            drop(guard);
            launch::run_post_exit(&env2);
            let code = status.ok().and_then(|s| s.code());
            emit_state(&app3, &pid2, "exited", code);
        });
        *state.running_game.lock().await = Some(child);
    }

    emit_state(&app, &profile_id, "running", None);
    let _ = state.patch_profile(&profile_id, |p| p.last_played = Some(now_millis()));
    Ok(())
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
    let mut guard = state.running_game.lock().await;
    if let Some(child) = guard.as_mut() {
        child.kill().await.map_err(|e| e.to_string())?;
    }
    *guard = None;
    Ok(())
}

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

    // Fabric: install the loader profile merged with the vanilla JSON; the
    // merged profile becomes the effective version (fabric mainClass and
    // arguments, vanilla java runtime / client jar / asset index).
    let effective_version = if profile.loader == Loader::Fabric {
        fasterlauncher_core::fabric::install_fabric(
            client,
            &profile.game_version,
            profile.loader_version.as_deref(),
            &dirs.versions,
            &version,
        )
        .await
        .map_err(|e| e.to_string())?
    } else {
        version
    };

    // Libraries
    let libs: Vec<download::Download> = effective_version
        .libraries
        .iter()
        .filter(|l| meta::library_allowed(l))
        .filter_map(|l| {
            let artifact = l.downloads.as_ref()?.artifact.as_ref()?;
            Some(download::Download {
                url: artifact.url.clone(),
                dest: dirs.libraries.join(&artifact.path),
                sha1: Some(artifact.sha1.clone()),
                size: Some(artifact.size),
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
    download::download_all(client, libs, 12, move |ev| {
        if let download::ProgressEvent::FileDone { url } = ev {
            emitter2
                .lock()
                .unwrap()
                .bump(sizes2.get(&url).copied().unwrap_or(0));
        }
    })
    .await
    .map_err(|e| e.to_string())?;
    emitter.lock().unwrap().flush(true);

    // Client jar
    let client_art = effective_version
        .downloads
        .client
        .clone()
        .ok_or("no client jar in version json")?;
    let client_jar_path = dirs.versions.join(format!("{}.jar", effective_version.id));
    let mut prog = ProgressEmitter::new(app.clone(), &profile.id, "client", 1, client_art.size);
    download::download_one(
        client,
        &download::Download {
            url: client_art.url,
            dest: client_jar_path,
            sha1: Some(client_art.sha1),
            size: Some(client_art.size),
        },
    )
    .await
    .map_err(|e| e.to_string())?;
    prog.bump(0);
    prog.flush(true);

    // Assets + persist the asset index where the client expects it
    if let Some(idx) = &effective_version.asset_index {
        tokio::fs::create_dir_all(dirs.assets.join("indexes")).await.map_err(|e| e.to_string())?;
        let index_path = dirs.assets.join("indexes").join(format!("{}.json", idx.id));
        let index_text = client
            .get(&idx.url)
            .send()
            .await
            .map_err(|e| e.to_string())?
            .error_for_status()
            .map_err(|e| e.to_string())?
            .text()
            .await
            .map_err(|e| e.to_string())?;
        if !index_path.exists() {
            tokio::fs::write(&index_path, &index_text).await.map_err(|e| e.to_string())?;
        }
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
        download::download_all(client, asset_downloads, 16, move |ev| {
            if let download::ProgressEvent::FileDone { url } = ev {
                emitter2
                    .lock()
                    .unwrap()
                    .bump(sizes2.get(&url).copied().unwrap_or(0));
            }
        })
        .await
        .map_err(|e| e.to_string())?;
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

#[tauri::command]
pub fn set_settings(state: State<AppState>, settings: Settings) -> Result<Settings, String> {
    {
        let mut s = state.settings.lock().unwrap();
        *s = settings.clone();
    }
    state.save_settings(&settings);
    Ok(settings)
}

// ── account ────────────────────────────────────────────────────────────────

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountDto {
    pub username: String,
    pub uuid: String,
    pub authenticated: bool,
}

#[tauri::command]
pub async fn get_current_account(state: State<'_, AppState>) -> Result<Option<AccountDto>, String> {
    // Opportunistic silent refresh so the UI never shows a stale identity.
    if let Some(session) = auth_store::load_session(&state.data_dir) {
        if session.needs_refresh() {
            let config = auth_flow::resolve_auth_config(&state);
            if let Ok(fresh) =
                fasterlauncher_core::auth::refresh_session(&state.client, &config, &session).await
            {
                auth_store::save_session(&state.data_dir, &fresh);
                let dto = account_dto(&fresh);
                *state.account.lock().unwrap() = Some(Account {
                    username: fresh.username.clone(),
                    uuid: fresh.uuid.clone(),
                    authenticated: true,
                });
                return Ok(Some(dto));
            }
        }
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
    let session = auth_flow::run_login(&app, &state).await?;
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
    let session = auth_flow::run_reconsent_login(&app, &state).await?;
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
    }
}

#[tauri::command]
pub fn logout(state: State<AppState>) {
    auth_store::clear_session(&state.data_dir);
    *state.account.lock().unwrap() = Some(Account::default());
}

/// Resolve the session to play with:
/// - fresh stored session → use (refreshing silently when stale)
/// - no session + login configured → hard error telling the user to sign in
/// - no session + login not configured yet (pre-Azure-approval) → clearly
///   logged demo session so the install/launch pipeline stays testable.
async fn ensure_play_session(state: &AppState) -> Result<Session, String> {
    if let Some(session) = auth_store::load_session(&state.data_dir) {
        if !session.needs_refresh() {
            return Ok(session);
        }
        let config = auth_flow::resolve_auth_config(state);
        return fasterlauncher_core::auth::refresh_session(&state.client, &config, &session)
            .await
            .map(|fresh| {
                auth_store::save_session(&state.data_dir, &fresh);
                fresh
            })
            .map_err(|e| format!("session expired and refresh failed ({e}) — sign in again"));
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

// ── app info ───────────────────────────────────────────────────────────────

#[tauri::command]
pub fn get_app_info(state: State<AppState>) -> AppInfoDto {
    AppInfoDto {
        launcher_version: env!("CARGO_PKG_VERSION").into(),
        os: format!("{} ({})", capitalize(std::env::consts::OS), std::env::consts::ARCH),
        data_dir: state.data_dir.display().to_string(),
    }
}

fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}
