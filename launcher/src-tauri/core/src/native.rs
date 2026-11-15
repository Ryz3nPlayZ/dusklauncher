//! Dusk Native: the Rust game (the Horizon fork) as an instance's loader. This
//! file holds what can be checked without a game: the build the launcher is
//! pinned to and the command lines of the game's launch contract.

use crate::auth::Session;
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

/// The release this launcher build plays. A launcher release pins one game
/// release by hash, so nothing but the launcher's own update has to be trusted.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct Pin {
    pub tag: String,
    pub minecraft: String,
    #[serde(default)]
    pub repo: String,
    /// archive name → SHA-256
    #[serde(default)]
    pub archives: BTreeMap<String, String>,
}

impl Pin {
    /// This computer's archive and its hash, when the release has one.
    pub fn archive(&self) -> Option<(&'static str, &str)> {
        let name = archive_name()?;
        self.archives.get(name).map(|sha| (name, sha.as_str()))
    }

    pub fn url(&self, archive: &str) -> String {
        format!("https://github.com/{}/releases/download/{}/{archive}", self.repo, self.tag)
    }
}

fn archive_name() -> Option<&'static str> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("macos", "aarch64") => Some("horizon-macos-arm64.zip"),
        ("macos", "x86_64") => Some("horizon-macos-x64.zip"),
        ("windows", "x86_64") => Some("horizon-windows-x64.zip"),
        ("windows", "aarch64") => Some("horizon-windows-arm64.zip"),
        ("linux", "x86_64") => Some("horizon-linux-x86_64.tar.gz"),
        _ => None,
    }
}

/// The two programs in a build folder.
pub fn programs(build: &Path) -> (PathBuf, PathBuf) {
    let exe = std::env::consts::EXE_SUFFIX;
    (build.join(format!("horizon-client{exe}")), build.join(format!("horizon-prepare{exe}")))
}

pub struct PrepareFiles<'a> {
    pub server_jar: &'a Path,
    pub version_json: &'a Path,
    pub client_jar: &'a Path,
    pub libraries: &'a Path,
    pub assets: &'a Path,
    pub java_home: &'a Path,
    pub output: &'a Path,
}

pub fn prepare_args(f: &PrepareFiles) -> Vec<String> {
    let mut args = vec!["--prepare".to_string()];
    for (flag, path) in [
        ("--server-jar", f.server_jar),
        ("--version-json", f.version_json),
        ("--client-jar", f.client_jar),
        ("--libraries-dir", f.libraries),
        ("--assets-dir", f.assets),
        ("--java-home", f.java_home),
        ("--output", f.output),
    ] {
        args.push(flag.to_string());
        args.push(path.display().to_string());
    }
    args.extend(["--complete", "--progress", "json"].map(String::from));
    args
}

pub fn check_args(output: &Path) -> Vec<String> {
    vec!["--check".into(), "--output".into(), output.display().to_string()]
}

pub struct Start<'a> {
    pub prepared: &'a Path,
    pub instance: &'a Path,
    pub resolution: (u32, u32),
    pub fullscreen: bool,
    pub server: Option<&'a str>,
    pub world: Option<&'a str>,
}

/// The game's arguments. The session goes over stdin, never on the command line.
pub fn client_args(s: &Start) -> Vec<String> {
    let instance = s.instance.display().to_string();
    let mut args = vec![
        "--resources".to_string(),
        s.prepared.display().to_string(),
        "--game-dir".to_string(),
        instance.clone(),
        "--home".to_string(),
        instance,
        "--width".to_string(),
        s.resolution.0.to_string(),
        "--height".to_string(),
        s.resolution.1.to_string(),
        "--session-stdin".to_string(),
    ];
    if s.fullscreen {
        args.push("--fullscreen".into());
    }
    if let Some(world) = s.world {
        args.push("--quickPlaySingleplayer".into());
        args.push(world.to_string());
    } else if let Some(server) = s.server {
        args.push("--quickPlayMultiplayer".into());
        args.push(server.to_string());
    }
    args
}

pub fn session_json(s: &Session) -> String {
    json!({
        "name": s.username,
        "uuid": s.uuid,
        "access_token": s.access_token,
        "user_type": "msa",
        "xuid": s.xuid,
    })
    .to_string()
}

#[derive(Debug, PartialEq)]
pub enum PrepareLine {
    Message(String),
    Fraction(f64),
    Error(String),
}

/// One line of `--progress json`; anything else on stdout is ignored.
pub fn parse_prepare_line(line: &str) -> Option<PrepareLine> {
    let v: Value = serde_json::from_str(line.trim()).ok()?;
    if let Some(e) = v.get("error").and_then(Value::as_str) {
        return Some(PrepareLine::Error(e.to_string()));
    }
    if let Some(f) = v.get("fraction").and_then(Value::as_f64) {
        return Some(PrepareLine::Fraction(f.clamp(0.0, 1.0)));
    }
    v.get("message").and_then(Value::as_str).map(|m| PrepareLine::Message(m.to_string()))
}

/// The Minecraft version from `horizon-prepare --launch-api`'s reply.
pub fn minecraft_of(launch_api: &str) -> Option<String> {
    let v: Value = serde_json::from_str(launch_api.trim()).ok()?;
    v.get("minecraft")?.as_str().map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn start(server: Option<&'static str>, world: Option<&'static str>) -> Vec<String> {
        client_args(&Start {
            prepared: Path::new("/p"),
            instance: Path::new("/i"),
            resolution: (854, 480),
            fullscreen: false,
            server,
            world,
        })
    }

    #[test]
    fn the_token_never_reaches_the_command_line() {
        let args = start(None, None);
        assert_eq!(
            args,
            ["--resources", "/p", "--game-dir", "/i", "--home", "/i", "--width", "854", "--height", "480", "--session-stdin"]
        );
    }

    #[test]
    fn a_world_wins_over_a_server() {
        let args = start(Some("play.example.net:25566"), Some("My World"));
        assert_eq!(args[args.len() - 2..], ["--quickPlaySingleplayer", "My World"]);
        let args = start(Some("play.example.net:25566"), None);
        assert_eq!(args[args.len() - 2..], ["--quickPlayMultiplayer", "play.example.net:25566"]);
    }

    #[test]
    fn session_json_has_the_fields_the_game_reads() {
        let s = Session {
            access_token: "t".into(),
            expires_at: 0,
            uuid: "u".into(),
            username: "n".into(),
            xuid: "x".into(),
            refresh_token: "keep me out".into(),
            skin_url: String::new(),
            skin_variant: String::new(),
        };
        let v: Value = serde_json::from_str(&session_json(&s)).unwrap();
        assert_eq!(v, json!({"name":"n","uuid":"u","access_token":"t","user_type":"msa","xuid":"x"}));
    }

    #[test]
    fn prepare_lines_parse() {
        assert_eq!(parse_prepare_line(r#"{"fraction":0.5}"#), Some(PrepareLine::Fraction(0.5)));
        assert_eq!(parse_prepare_line(r#"{"message":"Reading"}"#), Some(PrepareLine::Message("Reading".into())));
        assert_eq!(parse_prepare_line(r#"{"error":"no java"}"#), Some(PrepareLine::Error("no java".into())));
        assert_eq!(parse_prepare_line("plain text"), None);
    }

    #[test]
    fn an_empty_pin_has_no_archive() {
        assert!(Pin::default().archive().is_none());
    }
}
