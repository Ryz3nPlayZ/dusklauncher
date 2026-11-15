//! Starts a Dusk Native instance: gets the pinned game build, prepares it from
//! the owner's vanilla files, and hands back a launch spec for the usual
//! supervisor. The launch contract is in `fasterlauncher_core::native`.

use crate::appstate::AppState;
use crate::commands::ProgressEmitter;
use fasterlauncher_core::auth::Session;
use fasterlauncher_core::launch::{LaunchEnv, LaunchSpec};
use fasterlauncher_core::meta::VersionJson;
use fasterlauncher_core::native::{self, Pin, PrepareFiles, PrepareLine, Start};
use fasterlauncher_core::profile::{Profile, ProfileDirs};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use tauri::AppHandle;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

const PIN: &str = include_str!("../native-build.json");

pub(crate) fn pin() -> Pin {
    serde_json::from_str(PIN).expect("native-build.json is valid")
}

/// The Minecraft version Dusk Native plays, and whether this computer can get it.
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeInfo {
    minecraft: String,
    available: bool,
}

#[tauri::command]
pub fn native_game_info() -> NativeInfo {
    let pin = pin();
    let available = std::env::var_os("DUSK_NATIVE_BUILD").is_some() || (!pin.tag.is_empty() && pin.archive().is_some());
    NativeInfo { minecraft: pin.minecraft, available }
}

#[allow(clippy::too_many_arguments)]
pub(crate) async fn launch_spec(
    app: &AppHandle,
    state: &AppState,
    profile: &Profile,
    dirs: &ProfileDirs,
    version: &VersionJson,
    java_bin: &Path,
    session: &Session,
    env: &LaunchEnv,
) -> Result<LaunchSpec, String> {
    let (build, build_id) = build_folder(app, state, profile).await?;
    let (client, prepare) = native::programs(&build);
    let output = state.data_dir.join("native").join("prepared").join(&build_id);
    if !prepared(&prepare, &output).await {
        prepare_game(app, state, profile, dirs, version, java_bin, &prepare, &output).await?;
    }
    let mut game_env = env.env_vars.clone();
    // 1.21.11 and older servers, which the game keeps behind a switch upstream
    game_env.push(("HORIZON_OLDER_SERVERS".into(), "1".into()));
    // The launcher shows Discord presence itself; the game's would post Horizon's application.
    game_env.push(("HORIZON_NO_DISCORD".into(), "1".into()));
    Ok(LaunchSpec {
        java_bin: client,
        jvm_args: Vec::new(),
        main_class: String::new(),
        game_args: native::client_args(&Start {
            prepared: &output,
            instance: &dirs.root,
            resolution: profile.resolution,
            fullscreen: profile.fullscreen == Some(true),
            server: profile.server.as_deref(),
            world: env.world.as_deref(),
        }),
        cwd: dirs.root.clone(),
        env: game_env,
        stdin: Some(native::session_json(session)),
    })
}

/// The build's folder and a name for what's prepared from it. `DUSK_NATIVE_BUILD`
/// points at a local build, for working on the game.
async fn build_folder(app: &AppHandle, state: &AppState, profile: &Profile) -> Result<(PathBuf, String), String> {
    if let Some(dev) = std::env::var_os("DUSK_NATIVE_BUILD") {
        return Ok((PathBuf::from(dev), "dev".into()));
    }
    let pin = pin();
    let (name, sha) = pin
        .archive()
        .filter(|_| !pin.tag.is_empty())
        .ok_or_else(|| "This launcher has no Dusk Native game for this computer yet.".to_string())?;
    let target = state.data_dir.join("native").join("builds").join(&pin.tag);
    if !native::programs(&target).0.exists() {
        install_build(app, state, profile, &pin, name, sha, &target).await?;
    }
    Ok((target, pin.tag))
}

async fn install_build(
    app: &AppHandle,
    state: &AppState,
    profile: &Profile,
    pin: &Pin,
    name: &str,
    sha: &str,
    target: &Path,
) -> Result<(), String> {
    let parent = target.parent().ok_or("no folder for game builds")?;
    let work = parent.join(format!("{}.part", pin.tag));
    let _ = std::fs::remove_dir_all(&work);
    std::fs::create_dir_all(&work).map_err(|e| e.to_string())?;
    let archive = work.join(name);
    download_checked(app, state, profile, &pin.url(name), &archive, sha).await?;

    let (from, to) = (archive.clone(), work.join("unpacked"));
    tokio::task::spawn_blocking(move || unpack(&from, &to))
        .await
        .map_err(|e| e.to_string())??;
    // the archive holds one folder, the build
    let top = std::fs::read_dir(work.join("unpacked"))
        .map_err(|e| e.to_string())?
        .flatten()
        .map(|e| e.path())
        .find(|p| native::programs(p).0.exists())
        .ok_or("The Dusk Native download doesn't hold the game.")?;
    let _ = std::fs::remove_dir_all(target);
    std::fs::rename(&top, target).map_err(|e| e.to_string())?;
    let _ = std::fs::remove_dir_all(&work);
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("xattr").args(["-dr", "com.apple.quarantine"]).arg(target).status();
    Ok(())
}

async fn download_checked(
    app: &AppHandle,
    state: &AppState,
    profile: &Profile,
    url: &str,
    to: &Path,
    sha: &str,
) -> Result<(), String> {
    let mut response = state.client.get(url).send().await.and_then(|r| r.error_for_status()).map_err(|e| e.to_string())?;
    let mut progress = ProgressEmitter::new(app.clone(), &profile.id, "getting the game", 1, response.content_length().unwrap_or(0));
    let mut file = tokio::fs::File::create(to).await.map_err(|e| e.to_string())?;
    let mut hash = Sha256::new();
    let mut done = 0u64;
    while let Some(chunk) = response.chunk().await.map_err(|e| e.to_string())? {
        hash.update(&chunk);
        file.write_all(&chunk).await.map_err(|e| e.to_string())?;
        done += chunk.len() as u64;
        progress.set_bytes(done);
    }
    file.flush().await.map_err(|e| e.to_string())?;
    progress.set(1);
    progress.flush(true);
    if hex::encode(hash.finalize()) != sha.to_ascii_lowercase() {
        return Err("The Dusk Native download didn't match what this launcher expects, so it wasn't used.".into());
    }
    Ok(())
}

fn unpack(archive: &Path, to: &Path) -> Result<(), String> {
    std::fs::create_dir_all(to).map_err(|e| e.to_string())?;
    if archive.extension().is_some_and(|e| e == "zip") {
        let file = std::fs::File::open(archive).map_err(|e| e.to_string())?;
        zip::ZipArchive::new(file).and_then(|mut z| z.extract(to)).map_err(|e| e.to_string())
    } else {
        let status = std::process::Command::new("tar").arg("-xzf").arg(archive).arg("-C").arg(to).status();
        match status {
            Ok(s) if s.success() => Ok(()),
            Ok(s) => Err(format!("tar failed: {s}")),
            Err(e) => Err(e.to_string()),
        }
    }
}

/// Whether what's prepared still fits this build.
async fn prepared(prepare: &Path, output: &Path) -> bool {
    if !output.exists() {
        return false;
    }
    let status = quiet(prepare).args(native::check_args(output)).status().await;
    status.is_ok_and(|s| s.success())
}

#[allow(clippy::too_many_arguments)]
async fn prepare_game(
    app: &AppHandle,
    state: &AppState,
    profile: &Profile,
    dirs: &ProfileDirs,
    version: &VersionJson,
    java_bin: &Path,
    prepare: &Path,
    output: &Path,
) -> Result<(), String> {
    use fasterlauncher_core::download::{self, Download};

    let server = version.downloads.server.as_ref().ok_or_else(|| format!("Minecraft {} has no server download.", version.id))?;
    let server_jar = state.data_dir.join("native").join("server").join(format!("{}.jar", version.id));
    let dl = Download { url: server.url.clone(), dest: server_jar.clone(), sha1: Some(server.sha1.clone()), size: Some(server.size) };
    download::download_one(&state.client, &dl).await.map_err(|e| e.to_string())?;

    // java is <home>/bin/java
    let java_home = java_bin.parent().and_then(Path::parent).ok_or("no Java folder")?;
    let version_json = dirs.versions.join(format!("{}.json", version.id));
    let client_jar = dirs.versions.join(format!("{}.jar", version.id));
    let args = native::prepare_args(&PrepareFiles {
        server_jar: &server_jar,
        version_json: &version_json,
        client_jar: &client_jar,
        libraries: &dirs.libraries,
        assets: &dirs.assets,
        java_home,
        output,
    });

    let mut child = quiet(prepare)
        .args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .map_err(|e| format!("Couldn't start the game's preparing step: {e}"))?;
    let stdout = child.stdout.take().ok_or("no output from the preparing step")?;
    let mut lines = BufReader::new(stdout).lines();
    let mut progress = ProgressEmitter::new(app.clone(), &profile.id, "preparing the game", 1000, 0);
    let mut failure = None;
    while let Some(line) = lines.next_line().await.map_err(|e| e.to_string())? {
        match native::parse_prepare_line(&line) {
            Some(PrepareLine::Fraction(f)) => progress.set((f * 1000.0) as u64),
            Some(PrepareLine::Message(m)) => tracing::info!("preparing Dusk Native: {m}"),
            Some(PrepareLine::Error(e)) => failure = Some(e),
            None => {}
        }
    }
    progress.flush(true);
    let status = child.wait().await.map_err(|e| e.to_string())?;
    match failure {
        Some(e) => Err(format!("Preparing Dusk Native failed: {e}")),
        None if !status.success() => Err(format!("Preparing Dusk Native failed ({status}).")),
        None => Ok(()),
    }
}

fn quiet(program: &Path) -> tokio::process::Command {
    let mut command = tokio::process::Command::new(program);
    command.kill_on_drop(true);
    #[cfg(windows)]
    command.creation_flags(0x0800_0000); // no console window
    command
}
