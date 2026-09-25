//! User profiles: a named combination of MC version, loader, mods, and JVM args.

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// Default JVM args: G1, the collector Mojang's own launcher and Prism run
/// the game on. (Non-generational ZGC on Java 21 plus AlwaysPreTouch cost
/// throughput and a heap-sized page touch at startup for little gain.)
/// Safe for every Java Mojang ships (8+): version-gated flags live in
/// [`modern_jvm_extras`], not here.
pub fn default_jvm_args() -> Vec<String> {
    vec![
        "-Xms2G".into(),
        "-Xmx4G".into(),
        "-XX:+UnlockExperimentalVMOptions".into(),
        "-XX:+UseG1GC".into(),
    ]
}

/// Swap the launcher's old ZGC defaults for G1 in args that still carry
/// them untouched (heap sizes aside). Returns whether anything changed.
pub fn migrate_legacy_gc(args: &mut Vec<String>) -> bool {
    let old = |a: &String| a == "-XX:+UseZGC" || a == "-XX:+AlwaysPreTouch";
    let only_defaults = args.iter().all(|a| {
        old(a) || a.starts_with("-Xms") || a.starts_with("-Xmx") || a == "-XX:+UnlockExperimentalVMOptions"
    });
    if !only_defaults || !args.iter().any(|a| a == "-XX:+UseZGC") {
        return false;
    }
    args.retain(|a| !old(a));
    args.push("-XX:+UseG1GC".into());
    true
}

/// Extra JVM flags that only exist on newer Java releases, keyed by the
/// version JSON's `javaVersion.majorVersion`. Applied at spec build time so
/// one default profile works from 1.16-era Java 17 to current Java 25+.
pub fn modern_jvm_extras(java_major: u32) -> Vec<String> {
    // Compact Object Headers: experimental since 24 (hence the
    // UnlockExperimentalVMOptions above), production in 26. Unknown — and
    // fatal — on 17/21, which is why this must never be unconditional.
    if java_major >= 24 {
        vec!["-XX:+UseCompactObjectHeaders".into()]
    } else {
        vec![]
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Profile {
    pub id: String,
    pub name: String,
    /// e.g. "26.2" or "1.21.11"
    pub game_version: String,
    pub loader: Loader,
    /// pinned loader version (Fabric); None = latest at install time
    #[serde(default)]
    pub loader_version: Option<String>,
    #[serde(default = "default_jvm_args")]
    pub jvm_args: Vec<String>,
    pub resolution: (u32, u32),
    #[serde(default)]
    pub mod_filenames: Vec<String>,
    #[serde(default)]
    pub server: Option<String>,
    pub created_at: u64,
    /// unix millis of the last successful launch
    #[serde(default)]
    pub last_played: Option<u64>,
    /// JVM heap for this instance in MB; None = the launcher-wide setting.
    /// Applied at launch in place of any -Xmx/-Xms in `jvm_args`.
    #[serde(default)]
    pub memory_mb: Option<u32>,
    /// java executable for this instance; None = the launcher-wide override
    /// for the version's Java major, else the provisioned runtime
    #[serde(default)]
    pub java_path: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Loader {
    Vanilla,
    Fabric,
}

impl Loader {
    pub fn as_str(&self) -> &'static str {
        match self {
            Loader::Vanilla => "vanilla",
            Loader::Fabric => "fabric",
        }
    }

    pub fn parse(s: &str) -> Loader {
        if s.eq_ignore_ascii_case("fabric") {
            Loader::Fabric
        } else {
            Loader::Vanilla
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProfileStore {
    pub profiles: Vec<Profile>,
}

impl ProfileStore {
    pub fn load(path: &PathBuf) -> std::io::Result<Self> {
        if path.exists() {
            Ok(serde_json::from_slice(&std::fs::read(path)?)?)
        } else {
            Ok(Self { profiles: Vec::new() })
        }
    }

    pub fn save(&self, path: &PathBuf) -> std::io::Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(path, serde_json::to_vec_pretty(self)?)?;
        Ok(())
    }
}

impl Profile {
    /// Where this profile's version json / mods live.
    pub fn dirs(&self, data_dir: &std::path::Path) -> ProfileDirs {
        let root = data_dir.join("profiles").join(&self.id);
        ProfileDirs {
            root: root.clone(),
            versions: root.join("versions"),
            mods: root.join("mods"),
            resourcepacks: root.join("resourcepacks"),
            shaderpacks: root.join("shaderpacks"),
            assets: data_dir.join("assets"),
            libraries: data_dir.join("libraries"),
            runtimes: data_dir.join("runtimes"),
        }
    }
}

#[derive(Debug, Clone)]
pub struct ProfileDirs {
    pub root: PathBuf,
    pub versions: PathBuf,
    pub mods: PathBuf,
    pub resourcepacks: PathBuf,
    pub shaderpacks: PathBuf,
    /// shared across profiles
    pub assets: PathBuf,
    /// shared across profiles
    pub libraries: PathBuf,
    /// shared across profiles
    pub runtimes: PathBuf,
}
