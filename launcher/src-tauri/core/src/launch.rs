//! Build the JVM command line and spawn the game.
//!
//! Command-line layout (the part every launcher must get right):
//!   java <jvm args> <mainClass> <game args>
//! The version JSON splits `arguments.jvm` / `arguments.game`; they are
//! expanded separately and never interleaved.

use crate::meta;
use crate::profile::{Profile, ProfileDirs};
use crate::{Error, Result};
use serde_json::Value;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;

/// Where (relative to the game directory) 1.20+ writes its Quick Play log:
/// a one-entry JSON list naming the world or server of the last session.
pub const QUICK_PLAY_LOG: &str = "quickPlay/java/dusklauncher.json";

/// Launch settings that come from the global launcher settings store.
#[derive(Debug, Clone, Default)]
pub struct LaunchEnv {
    /// KEY=VALUE lines
    pub env_vars: Vec<(String, String)>,
    /// run before the game starts (shell command)
    pub prelaunch_hook: Option<String>,
    /// a command the game starts through (`gamemoderun`, `prime-run`):
    /// `wrapper [its args] <java> <jvm args> …`
    pub wrapper_hook: Option<String>,
    /// run after the game exits (shell command)
    pub post_exit_hook: Option<String>,
    /// working directory for hooks
    pub hook_cwd: Option<PathBuf>,
    /// a `saves/` folder name: open that world straight from the launcher
    pub world: Option<String>,
}

#[derive(Debug, Clone)]
pub struct LaunchSpec {
    pub java_bin: PathBuf,
    pub jvm_args: Vec<String>,
    pub main_class: String,
    pub game_args: Vec<String>,
    pub cwd: PathBuf,
    pub env: Vec<(String, String)>,
}

/// Expand one of the rule-based argument lists (jvm or game) from a version JSON.
/// `features` maps feature names (has_custom_resolution, etc.) to on/off.
pub fn expand_arguments(
    entries: &[Value],
    values: &HashMap<String, String>,
    features: &HashMap<String, bool>,
) -> Vec<String> {
    let mut out = Vec::new();
    for entry in entries {
        match entry {
            Value::String(s) => out.push(substitute(s, values)),
            Value::Object(obj) => {
                let Some(rules) = obj.get("rules").and_then(|r| r.as_array()) else {
                    continue;
                };
                if !rules_allow(rules, features) {
                    continue;
                }
                match obj.get("value") {
                    Some(Value::String(s)) => out.push(substitute(s, values)),
                    Some(Value::Array(a)) => {
                        for v in a {
                            if let Some(s) = v.as_str() {
                                out.push(substitute(s, values));
                            }
                        }
                    }
                    _ => {}
                }
            }
            _ => {}
        }
    }
    out
}

fn rules_allow(rules: &[Value], features: &HashMap<String, bool>) -> bool {
    let mut allowed = false;
    for rule in rules {
        let os_ok = rule
            .get("os")
            .and_then(|o| o.get("name"))
            .and_then(|n| n.as_str())
            .is_none_or(|n| n == meta::os_name());
        if !os_ok {
            continue;
        }
        let feat_ok = match rule.get("features") {
            Some(f) => f.as_object().is_none_or(|m| {
                m.iter().all(|(k, v)| features.get(k).copied().unwrap_or(false) == v.as_bool().unwrap_or(false))
            }),
            None => true,
        };
        if feat_ok {
            allowed = rule.get("action").and_then(|a| a.as_str()) == Some("allow");
        }
    }
    allowed
}

/// JVM args pre-1.13 versions need. Modern JSONs ship these as rule-gated
/// `arguments.jvm`; old ones have no `arguments` at all, so the launcher
/// supplies the canonical set (same as MultiMC/Prism's legacy path).
const LEGACY_JVM_ARGS: &[&str] = &[
    "-Djava.library.path=${natives_directory}",
    "-Djna.tmpdir=${natives_directory}",
    "-Dorg.lwjgl.librarypath=${natives_directory}",
    "-Dorg.lwjgl.util.librarypath=${natives_directory}",
    "-cp",
    "${classpath}",
];

/// Game args from a pre-1.13 `minecraftArguments` template: whitespace-split,
/// then placeholder-substituted. (No rules/feature gating exists in this
/// format, which is why it can be this simple.)
pub fn legacy_game_args(
    template: &str,
    values: &HashMap<String, String>,
) -> Vec<String> {
    template
        .split_whitespace()
        .map(|s| substitute(s, values))
        .collect()
}

/// Drop profile JVM flags the resolved Java cannot parse. An unrecognized
/// -XX option is fatal at JVM startup, so the launcher's PvP-tuned defaults
/// (ZGC) must not reach a Java 8 runtime provisioned for old versions.
fn strip_unsupported_flags(args: Vec<String>, java_major: u32) -> Vec<String> {
    args.into_iter()
        .filter(|a| {
            let name = a.trim_start_matches("-XX:+").trim_start_matches("-XX:-");
            match name {
                "UseZGC" => java_major >= 15,
                // generational ZGC: opt-in on 21-23, the only mode (and the
                // flag obsolete) from 24, unknown before 21
                "ZGenerational" => (21..24).contains(&java_major),
                "UseCompactObjectHeaders" => java_major >= 24,
                _ => true,
            }
        })
        .collect()
}

fn substitute(s: &str, values: &HashMap<String, String>) -> String {
    let mut result = s.to_string();
    for (k, v) in values {
        result = result.replace(&format!("${{{k}}}"), v);
    }
    result
}

/// Collect classpath entries: all allowed library artifacts + the client jar.
/// Covers both Mojang (`downloads.artifact`) and Fabric (maven `url`)
/// library dialects.
pub fn build_classpath(
    version: &meta::VersionJson,
    libraries_root: &Path,
    client_jar: &Path,
) -> Vec<PathBuf> {
    let mut entries = Vec::new();
    for lib in &version.libraries {
        if !meta::library_allowed(lib) {
            continue;
        }
        if let Some(artifact) = lib.resolve_artifact() {
            entries.push(libraries_root.join(&artifact.path));
        }
    }
    entries.push(client_jar.to_path_buf());
    entries
}

/// Build the full launch specification for a profile.
#[allow(clippy::too_many_arguments)]
pub fn build_launch_spec(
    java_bin: &Path,
    version: &meta::VersionJson,
    profile: &Profile,
    dirs: &ProfileDirs,
    natives_dir: &Path,
    session: &crate::auth::Session,
    env: &LaunchEnv,
) -> LaunchSpec {
    let client_jar = dirs.versions.join(format!("{}.jar", version.id));
    let classpath = build_classpath(version, &dirs.libraries, &client_jar);
    let classpath_str = std::env::join_paths(classpath)
        .map(|p| p.display().to_string())
        .unwrap_or_default();

    let mut values = HashMap::new();
    values.insert("natives_directory".into(), natives_dir.display().to_string());
    values.insert("launcher_name".into(), "DuskLauncher".into());
    values.insert("launcher_version".into(), env!("CARGO_PKG_VERSION").into());
    values.insert("classpath".into(), classpath_str);
    values.insert("auth_player_name".into(), session.username.clone());
    values.insert("auth_uuid".into(), session.uuid.clone());
    // an offline session has no token: pass a placeholder (an empty
    // `--accessToken` value trips some versions' argument parser) and say so
    // in the user type
    let offline = session.access_token.is_empty();
    values.insert(
        "auth_access_token".into(),
        if offline { "0".into() } else { session.access_token.clone() },
    );
    values.insert("auth_xuid".into(), session.xuid.clone());
    values.insert("clientid".into(), String::new());
    values.insert("user_type".into(), if offline { "legacy" } else { "msa" }.into());
    values.insert("version_name".into(), version.id.clone());
    values.insert(
        "version_type".into(),
        if version.kind.is_empty() {
            "release".into()
        } else {
            version.kind.clone()
        },
    );
    values.insert("resolution_width".into(), profile.resolution.0.to_string());
    values.insert("resolution_height".into(), profile.resolution.1.to_string());
    values.insert(
        "assets_index_name".into(),
        version.asset_index.as_ref().map(|a| a.id.clone()).unwrap_or_default(),
    );
    values.insert("game_directory".into(), dirs.root.display().to_string());
    values.insert("assets_root".into(), dirs.assets.display().to_string());
    values.insert("user_properties".into(), "{}".into());
    // NeoForge builds its module path (`-p`) from these
    values.insert("library_directory".into(), dirs.libraries.display().to_string());
    values.insert("classpath_separator".into(), if cfg!(windows) { ";" } else { ":" }.into());

    let mut features = HashMap::new();
    features.insert("is_demo_user".to_string(), false);
    features.insert("has_custom_resolution".to_string(), true);
    features.insert("has_quick_plays_support".to_string(), false);
    // 1.20 swapped `--server`/`--port` for Quick Play and the game silently
    // ignores the old options, so versions that list the Quick Play entries
    // join (or open a world) through those
    let quick_play = version
        .arguments
        .as_ref()
        .is_some_and(|a| a.game.iter().any(|e| e.to_string().contains("quickPlayMultiplayer")));
    if quick_play {
        // the game rewrites this with the world or server each session
        // lands in; the launcher folds it into its "recent" list
        features.insert("has_quick_plays_support".to_string(), true);
        values.insert("quickPlayPath".into(), QUICK_PLAY_LOG.into());
        if let Some(server) = &profile.server {
            features.insert("is_quick_play_multiplayer".to_string(), true);
            values.insert("quickPlayMultiplayer".into(), server.clone());
        } else if let Some(world) = &env.world {
            features.insert("is_quick_play_singleplayer".to_string(), true);
            values.insert("quickPlaySingleplayer".into(), world.clone());
        }
    }

    let empty = Vec::new();
    let (jvm_entries, game_entries) = match &version.arguments {
        Some(args) => (&args.jvm, &args.game),
        None => (&empty, &empty),
    };
    let java = version.effective_java();

    let mut jvm_args: Vec<String> = Vec::new();
    jvm_args.extend(strip_unsupported_flags(profile.jvm_args.clone(), java.major_version));
    jvm_args.extend(crate::profile::modern_jvm_extras(java.major_version));
    jvm_args.extend(expand_arguments(jvm_entries, &values, &features));
    if version.arguments.is_none() {
        // Old JSON: no argument arrays at all — supply the legacy JVM set and
        // let the flag stripper above keep profile defaults Java-8-safe.
        for a in LEGACY_JVM_ARGS {
            jvm_args.push(substitute(a, &values));
        }
    }

    let mut game_args = if version.arguments.is_none() {
        version
            .minecraft_arguments
            .as_deref()
            .map(|t| legacy_game_args(t, &values))
            .unwrap_or_default()
    } else {
        expand_arguments(game_entries, &values, &features)
    };
    // Ancient versions predate the rule-gated resolution entries: fall back
    // to the profile resolution so the game still gets a window size. These
    // must stay on the game side of mainClass — the JVM rejects unknown
    // --width/--height options and refuses to start.
    if !game_args.iter().any(|a| a == "--width") {
        game_args.push("--width".into());
        game_args.push(profile.resolution.0.to_string());
        game_args.push("--height".into());
        game_args.push(profile.resolution.1.to_string());
    }
    if !quick_play {
        if let Some(server) = &profile.server {
            let (host, port) = split_server(server);
            game_args.push("--server".into());
            game_args.push(host.to_string());
            if let Some(port) = port {
                game_args.push("--port".into());
                game_args.push(port.to_string());
            }
        }
    }

    LaunchSpec {
        java_bin: java_bin.to_path_buf(),
        jvm_args,
        main_class: version.main_class.clone(),
        game_args,
        cwd: dirs.root.clone(),
        env: env.env_vars.clone(),
    }
}

/// `host:port` → (host, port) for the pre-1.20 `--server`/`--port` pair.
/// A bare IPv6 address has colons but no port; `[v6]:port` does.
fn split_server(addr: &str) -> (&str, Option<u16>) {
    if let Some(rest) = addr.strip_prefix('[') {
        if let Some((host, tail)) = rest.split_once(']') {
            return (host, tail.strip_prefix(':').and_then(|p| p.parse().ok()));
        }
    }
    match addr.rsplit_once(':') {
        Some((host, port)) if !host.contains(':') => match port.parse() {
            Ok(p) => (host, Some(p)),
            Err(_) => (addr, None),
        },
        _ => (addr, None),
    }
}

/// Windows' CREATE_NO_WINDOW: start a console program without a console window.
#[cfg(windows)]
pub(crate) const CREATE_NO_WINDOW: u32 = 0x0800_0000;

fn run_hook(cmd: &str, cwd: &Path) {
    if cmd.trim().is_empty() {
        return;
    }
    let mut formed = if cfg!(target_os = "windows") {
        let mut c = std::process::Command::new("cmd");
        c.args(["/C", cmd]);
        c
    } else {
        let mut c = std::process::Command::new("sh");
        c.args(["-c", cmd]);
        c
    };
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        formed.creation_flags(CREATE_NO_WINDOW);
    }
    let status = formed.current_dir(cwd).status();
    tracing::info!(?cmd, ?status, "hook finished");
}

/// Spawn the game from a launch spec. Applies env vars and hooks.
/// The returned child's stdout/stderr are piped — the caller must read them
/// (otherwise a full pipe can block the game).
pub async fn launch(spec: &LaunchSpec, env: &LaunchEnv) -> Result<tokio::process::Child> {
    let hook_cwd = env.hook_cwd.clone().unwrap_or_else(|| spec.cwd.clone());

    if let Some(pre) = env.prelaunch_hook.clone().filter(|p| !p.trim().is_empty()) {
        // a hook can take a while; keep it off the async workers
        let _ = tokio::task::spawn_blocking(move || run_hook(&pre, &hook_cwd)).await;
    }

    let (java_bin, cmd_args) = command_line(spec, env);

    tracing::info!(java = ?java_bin, "launching: {:?} {}", java_bin, cmd_args.join(" "));

    let mut command = tokio::process::Command::new(&java_bin);
    command
        .args(&cmd_args)
        .current_dir(&spec.cwd)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    for (k, v) in &spec.env {
        command.env(k, v);
    }
    // java.exe is a console program: without this Windows opens a console
    // window next to the game
    #[cfg(windows)]
    command.creation_flags(CREATE_NO_WINDOW);

    let child = command.spawn().map_err(|e| Error::Other(format!("spawn {}: {e}", java_bin.display())))?;
    Ok(child)
}

/// The program to start and its arguments: java and the game's arguments,
/// behind the wrapper command when one is set.
fn command_line(spec: &LaunchSpec, env: &LaunchEnv) -> (PathBuf, Vec<String>) {
    let mut program = spec.java_bin.clone();
    let mut args: Vec<String> = Vec::new();
    if let Some(wrapper) = env.wrapper_hook.as_deref().filter(|w| !w.trim().is_empty()) {
        let mut parts = wrapper.split_whitespace();
        if let Some(bin) = parts.next() {
            program = PathBuf::from(bin);
            args.extend(parts.map(str::to_string));
            args.push(spec.java_bin.to_string_lossy().into_owned());
        }
    }
    args.extend(spec.jvm_args.iter().cloned());
    args.push(spec.main_class.clone());
    args.extend(spec.game_args.iter().cloned());
    (program, args)
}

/// Run the post-exit hook (called by the supervisor after the game exits).
pub fn run_post_exit(env: &LaunchEnv) {
    if let Some(post) = &env.post_exit_hook {
        let cwd = env.hook_cwd.clone().unwrap_or_else(|| PathBuf::from("."));
        run_hook(post, &cwd);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::Session;
    use crate::profile::Loader;

    #[test]
    fn wrapper_runs_java_with_the_game() {
        let spec = LaunchSpec {
            java_bin: PathBuf::from("/jre/bin/java"),
            jvm_args: vec!["-Xmx4G".into()],
            main_class: "Main".into(),
            game_args: vec!["--demo".into()],
            cwd: PathBuf::from("."),
            env: vec![],
        };
        let (bin, args) = command_line(&spec, &LaunchEnv::default());
        assert_eq!(bin, PathBuf::from("/jre/bin/java"));
        assert_eq!(args, ["-Xmx4G", "Main", "--demo"]);
        let env = LaunchEnv { wrapper_hook: Some("prime-run --flag".into()), ..LaunchEnv::default() };
        let (bin, args) = command_line(&spec, &env);
        assert_eq!(bin, PathBuf::from("prime-run"));
        assert_eq!(args, ["--flag", "/jre/bin/java", "-Xmx4G", "Main", "--demo"]);
    }

    fn test_version() -> meta::VersionJson {
        let json = r#"{
            "id": "test", "type": "release", "mainClass": "net.minecraft.client.main.Main",
            "javaVersion": {"component": "java-runtime-delta", "majorVersion": 21},
            "arguments": {
                "jvm": ["-Djava.library.path=${natives_directory}", "-cp", "${classpath}"],
                "game": ["--username", "${auth_player_name}", "--version", "${version_name}"]
            },
            "libraries": [],
            "downloads": {"client": null}
        }"#;
        serde_json::from_str(json).unwrap()
    }

    fn test_profile() -> Profile {
        Profile {
            id: "p1".into(),
            name: "T".into(),
            game_version: "test".into(),
            loader: Loader::Vanilla,
            loader_version: None,
            jvm_args: vec!["-Xmx4G".into()],
            resolution: (1280, 720),
            mod_filenames: vec![],
            server: Some("play.example.net".into()),
            created_at: 0,
            last_played: None,
            play_secs: 0,
            memory_mb: None,
            java_path: None,
        }
    }

    #[test]
    fn jvm_args_precede_main_class_and_game_args_follow() {
        let version = test_version();
        let profile = test_profile();
        let dirs = profile.dirs(Path::new("/data"));
        let session = Session {
            access_token: String::new(),
            expires_at: 0,
            uuid: "u".into(),
            username: "Player".into(),
            xuid: "xuid".into(),
            refresh_token: String::new(),
            skin_url: String::new(),
            skin_variant: String::new(),
        };
        let spec = build_launch_spec(
            Path::new("/java"),
            &version,
            &profile,
            &dirs,
            Path::new("/natives"),
            &session,
            &LaunchEnv::default(),
        );
        let main_at = spec.jvm_args.iter().position(|a| a == "net.minecraft.client.main.Main");
        assert!(main_at.is_none(), "mainClass is stored separately from jvm args");

        // full order: jvm args, then mainClass, then game args
        let mut full = spec.jvm_args.clone();
        full.push(spec.main_class.clone());
        full.extend(spec.game_args.clone());

        let jvm_pos = full.iter().position(|a| a == "-Xmx4G").unwrap();
        let natives_pos = full.iter().position(|a| a.starts_with("-Djava.library.path")).unwrap();
        let main_pos = full.iter().position(|a| a == "net.minecraft.client.main.Main").unwrap();
        let user_pos = full.iter().position(|a| a == "--username").unwrap();
        let server_pos = full.iter().position(|a| a == "--server").unwrap();
        assert!(jvm_pos < main_pos && natives_pos < main_pos, "jvm args must precede mainClass");
        assert!(main_pos < user_pos, "game args must follow mainClass");
        assert!(user_pos < server_pos, "server arg is appended last");
        assert!(full.contains(&"--width".to_string()), "resolution is always present");
    }

    #[test]
    fn modern_placeholders_expand_and_resolution_stays_on_game_side() {
        // mirrors the real 1.21 entries that broke launching: rule-gated
        // resolution with ${resolution_width}/${resolution_height} plus
        // ${version_type} in the game args.
        let json = r#"{
            "id": "1.21", "type": "release", "mainClass": "net.minecraft.client.main.Main",
            "javaVersion": {"component": "java-runtime-delta", "majorVersion": 21},
            "arguments": {
                "jvm": ["-Djava.library.path=${natives_directory}", "-cp", "${classpath}"],
                "game": ["--versionType", "${version_type}",
                    {"rules": [{"action": "allow", "features": {"has_custom_resolution": true}}],
                     "value": ["--width", "${resolution_width}", "--height", "${resolution_height}"]}]
            },
            "libraries": [],
            "downloads": {"client": null}
        }"#;
        let version: meta::VersionJson = serde_json::from_str(json).unwrap();
        let profile = test_profile();
        let dirs = profile.dirs(Path::new("/data"));
        let session = Session {
            access_token: String::new(),
            expires_at: 0,
            uuid: "u".into(),
            username: "Player".into(),
            xuid: String::new(),
            refresh_token: String::new(),
            skin_url: String::new(),
            skin_variant: String::new(),
        };
        let spec = build_launch_spec(
            Path::new("/java"),
            &version,
            &profile,
            &dirs,
            Path::new("/natives"),
            &session,
            &LaunchEnv::default(),
        );
        for arg in spec.jvm_args.iter().chain(spec.game_args.iter()) {
            assert!(!arg.contains("${"), "unexpanded placeholder: {arg}");
        }
        // the JVM dies on unknown --width/--height: they must never precede mainClass
        assert!(
            !spec.jvm_args.iter().any(|a| a == "--width" || a == "--height"),
            "resolution leaked into jvm args: {:?}",
            spec.jvm_args
        );
        let width_pos = spec.game_args.iter().position(|a| a == "--width").unwrap();
        assert_eq!(spec.game_args[width_pos + 1], "1280");
        let vt = spec.game_args.iter().position(|a| a == "--versionType").unwrap();
        assert_eq!(spec.game_args[vt + 1], "release");
    }

    #[test]
    fn jvm_flags_follow_the_version_java_major() {
        let mut version = test_version(); // majorVersion 21 in the fixture
        let profile = test_profile();
        let dirs = profile.dirs(Path::new("/data"));
        let session = Session {
            access_token: String::new(),
            expires_at: 0,
            uuid: "u".into(),
            username: "Player".into(),
            xuid: String::new(),
            refresh_token: String::new(),
            skin_url: String::new(),
            skin_variant: String::new(),
        };
        // stale profile carrying the pre-fix unconditional default
        let mut stale = profile.clone();
        stale.jvm_args.push("-XX:+UseCompactObjectHeaders".into());
        let spec = build_launch_spec(
            Path::new("/java"),
            &version,
            &stale,
            &dirs,
            Path::new("/natives"),
            &session,
            &LaunchEnv::default(),
        );
        assert!(
            !spec.jvm_args.iter().any(|a| a.contains("CompactObjectHeaders")),
            "fatal flag must be stripped on Java 21"
        );
        version.java_version.as_mut().unwrap().major_version = 25;
        let spec = build_launch_spec(
            Path::new("/java"),
            &version,
            &profile,
            &dirs,
            Path::new("/natives"),
            &session,
            &LaunchEnv::default(),
        );
        assert!(
            spec.jvm_args.iter().any(|a| a == "-XX:+UseCompactObjectHeaders"),
            "modern flag must be present on Java 25"
        );
    }

    #[test]
    fn env_var_lines_parse() {
        let env = crate::launch::LaunchEnv {
            env_vars: vec![("A".into(), "1".into()), ("B".into(), "x=y".into())],
            ..Default::default()
        };
        assert_eq!(env.env_vars.len(), 2);
    }

    /// Pre-1.13 versions (the PvP classics — 1.8.9): no `arguments` block, a
    /// `minecraftArguments` template instead, and no `javaVersion`. The spec
    /// must expand the template, supply the legacy JVM args, and strip the
    /// ZGC default that a Java 8 runtime would die on.
    #[test]
    fn legacy_version_builds_a_working_command_line() {
        let json = r#"{
            "id": "1.8.9", "type": "release", "mainClass": "net.minecraft.client.main.Main",
            "minecraftArguments": "--username ${auth_player_name} --version ${version_name} --assetsDir ${assets_root} --assetIndex ${assets_index_name} --uuid ${auth_uuid} --accessToken ${auth_access_token} --userType ${user_type}",
            "libraries": [],
            "downloads": {"client": null}
        }"#;
        let version: meta::VersionJson = serde_json::from_str(json).unwrap();
        let mut profile = test_profile();
        profile.jvm_args = vec!["-XX:+UseZGC".into(), "-XX:+AlwaysPreTouch".into()];
        let dirs = profile.dirs(Path::new("/data"));
        let session = Session {
            access_token: "tok".into(),
            expires_at: 0,
            uuid: "u".into(),
            username: "Player".into(),
            xuid: String::new(),
            refresh_token: String::new(),
            skin_url: String::new(),
            skin_variant: String::new(),
        };
        let spec = build_launch_spec(
            Path::new("/java"),
            &version,
            &profile,
            &dirs,
            Path::new("/natives"),
            &session,
            &LaunchEnv::default(),
        );

        // legacy game args expanded from the template
        let user = spec.game_args.iter().position(|a| a == "--username").unwrap();
        assert_eq!(spec.game_args[user + 1], "Player");
        let assets = spec.game_args.iter().position(|a| a == "--assetIndex").unwrap();
        assert_eq!(spec.game_args[assets + 1], ""); // no assetIndex in the JSON
        // legacy JVM args present
        assert!(spec.jvm_args.iter().any(|a| a.starts_with("-Djava.library.path")));
        assert!(spec.jvm_args.iter().any(|a| a == "-cp"));
        // Java 8: ZGC must be stripped, AlwaysPreTouch survives
        assert!(!spec.jvm_args.iter().any(|a| a.contains("UseZGC")), "ZGC is fatal on Java 8");
        assert!(spec.jvm_args.iter().any(|a| a == "-XX:+AlwaysPreTouch"));
        for arg in spec.jvm_args.iter().chain(spec.game_args.iter()) {
            assert!(!arg.contains("${"), "unexpanded placeholder: {arg}");
        }
    }

    #[test]
    fn generational_zgc_is_gated_per_java() {
        let args = || vec!["-XX:+UseZGC".to_string(), "-XX:+ZGenerational".to_string()];
        assert_eq!(strip_unsupported_flags(args(), 8), Vec::<String>::new());
        assert_eq!(strip_unsupported_flags(args(), 17), vec!["-XX:+UseZGC"]);
        assert_eq!(strip_unsupported_flags(args(), 21), args());
        assert_eq!(strip_unsupported_flags(args(), 25), vec!["-XX:+UseZGC"]);
    }

    #[test]
    fn zgc_survives_on_modern_java() {
        let mut version = test_version(); // Java 21
        let mut profile = test_profile();
        profile.jvm_args = vec!["-XX:+UseZGC".into()];
        let dirs = profile.dirs(Path::new("/data"));
        let session = Session {
            access_token: String::new(),
            expires_at: 0,
            uuid: "u".into(),
            username: "Player".into(),
            xuid: String::new(),
            refresh_token: String::new(),
            skin_url: String::new(),
            skin_variant: String::new(),
        };
        let spec = build_launch_spec(
            Path::new("/java"),
            &version,
            &profile,
            &dirs,
            Path::new("/natives"),
            &session,
            &LaunchEnv::default(),
        );
        assert!(spec.jvm_args.iter().any(|a| a == "-XX:+UseZGC"));

        version.java_version = None; // legacy: Java 8 fallback
        let spec = build_launch_spec(
            Path::new("/java"),
            &version,
            &profile,
            &dirs,
            Path::new("/natives"),
            &session,
            &LaunchEnv::default(),
        );
        assert!(!spec.jvm_args.iter().any(|a| a == "-XX:+UseZGC"));
    }

    fn quick_play_spec(server: Option<&str>, world: Option<&str>) -> LaunchSpec {
        // the real 1.20+ entries: each Quick Play target behind its own feature
        let json = r#"{
            "id": "1.21.11", "type": "release", "mainClass": "net.minecraft.client.main.Main",
            "arguments": {
                "jvm": [],
                "game": ["--username", "${auth_player_name}",
                    {"rules": [{"action": "allow", "features": {"has_quick_plays_support": true}}],
                     "value": ["--quickPlayPath", "${quickPlayPath}"]},
                    {"rules": [{"action": "allow", "features": {"is_quick_play_singleplayer": true}}],
                     "value": ["--quickPlaySingleplayer", "${quickPlaySingleplayer}"]},
                    {"rules": [{"action": "allow", "features": {"is_quick_play_multiplayer": true}}],
                     "value": ["--quickPlayMultiplayer", "${quickPlayMultiplayer}"]}]
            },
            "libraries": [],
            "downloads": {"client": null}
        }"#;
        let version: meta::VersionJson = serde_json::from_str(json).unwrap();
        let mut profile = test_profile();
        profile.server = server.map(str::to_string);
        let dirs = profile.dirs(Path::new("/data"));
        let session = Session {
            access_token: String::new(),
            expires_at: 0,
            uuid: "u".into(),
            username: "Player".into(),
            xuid: String::new(),
            refresh_token: String::new(),
            skin_url: String::new(),
            skin_variant: String::new(),
        };
        let env = LaunchEnv { world: world.map(str::to_string), ..LaunchEnv::default() };
        build_launch_spec(Path::new("/java"), &version, &profile, &dirs, Path::new("/natives"), &session, &env)
    }

    #[test]
    fn modern_versions_join_through_quick_play() {
        let spec = quick_play_spec(Some("play.example.net:25570"), None);
        let at = spec.game_args.iter().position(|a| a == "--quickPlayMultiplayer").unwrap();
        assert_eq!(spec.game_args[at + 1], "play.example.net:25570");
        assert!(!spec.game_args.iter().any(|a| a == "--server" || a.contains("${")));
        assert!(!spec.game_args.iter().any(|a| a == "--quickPlaySingleplayer"));

        let spec = quick_play_spec(None, Some("New World"));
        let at = spec.game_args.iter().position(|a| a == "--quickPlaySingleplayer").unwrap();
        assert_eq!(spec.game_args[at + 1], "New World");
        assert!(!spec.game_args.iter().any(|a| a == "--quickPlayMultiplayer"));

        let spec = quick_play_spec(None, None);
        let at = spec.game_args.iter().position(|a| a == "--quickPlayPath").unwrap();
        assert_eq!(spec.game_args[at + 1], QUICK_PLAY_LOG);
        assert!(!spec.game_args.iter().any(|a| a.starts_with("--quickPlay") && a != "--quickPlayPath"));
    }

    #[test]
    fn old_versions_split_the_port_off() {
        assert_eq!(split_server("mc.example.net:25570"), ("mc.example.net", Some(25570)));
        assert_eq!(split_server("mc.example.net"), ("mc.example.net", None));
        assert_eq!(split_server("[::1]:25566"), ("::1", Some(25566)));
        assert_eq!(split_server("::1"), ("::1", None));
    }
}
