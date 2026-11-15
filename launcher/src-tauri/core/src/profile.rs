//! User profiles: a named combination of MC version, loader, mods, and JVM args.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// Default JVM args: G1, the collector Mojang's own launcher and Prism run
/// the game on. (Non-generational ZGC on Java 21 plus AlwaysPreTouch cost
/// throughput and a heap-sized page touch at startup for little gain.)
/// Safe for every Java Mojang ships (8+): version-gated flags live in
/// [`modern_jvm_extras`], not here.
pub fn default_jvm_args() -> Vec<String> {
    let mut args = vec!["-Xms2G".into(), "-Xmx4G".into()];
    args.extend(G1_TUNING.iter().map(|a| a.to_string()));
    args
}

/// The G1 set Mojang's launcher passes: a bigger young generation and
/// reserve plus a 50 ms pause target, so the per-frame garbage the game
/// makes is swept in short young collections instead of long mixed ones.
/// `G1NewSizePercent` is experimental, hence the unlock flag first.
const G1_TUNING: &[&str] = &[
    "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseG1GC",
    "-XX:G1NewSizePercent=20",
    "-XX:G1ReservePercent=20",
    "-XX:MaxGCPauseMillis=50",
    "-XX:G1HeapRegionSize=32M",
];

/// Give args that are still the launcher's plain G1 defaults (heap sizes
/// aside) the [`G1_TUNING`] set. Returns whether anything changed.
pub fn migrate_g1_tuning(args: &mut Vec<String>) -> bool {
    let only_defaults = args.iter().all(|a| {
        a.starts_with("-Xms") || a.starts_with("-Xmx") || a == "-XX:+UnlockExperimentalVMOptions" || a == "-XX:+UseG1GC"
    });
    if !only_defaults || !args.iter().any(|a| a == "-XX:+UseG1GC") {
        return false;
    }
    args.retain(|a| a.starts_with("-Xms") || a.starts_with("-Xmx"));
    args.extend(G1_TUNING.iter().map(|a| a.to_string()));
    true
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
    /// seconds the game has run for this instance, all launches together
    #[serde(default)]
    pub play_secs: u64,
    /// JVM heap for this instance in MB; None = the launcher-wide setting.
    /// Applied at launch in place of any -Xmx/-Xms in `jvm_args`.
    #[serde(default)]
    pub memory_mb: Option<u32>,
    /// java executable for this instance; None = the launcher-wide override
    /// for the version's Java major, else the provisioned runtime
    #[serde(default)]
    pub java_path: Option<String>,
    /// a folder of the user's own on the INSTANCES page; None = ungrouped
    #[serde(default)]
    pub group: Option<String>,
    /// the instance's own picture, a file in its folder (`dusk-icon-…`);
    /// None = the stock banner
    #[serde(default)]
    pub icon: Option<String>,
    /// the Modrinth pack it was installed from, so it can move to another of
    /// the pack's versions; None = not from a pack Modrinth knows
    #[serde(default)]
    pub pack: Option<PackLink>,
    #[serde(default)]
    pub hooks: InstanceHooks,
    /// open the game fullscreen (true) or in a window (false); None = as
    /// the game last left it
    #[serde(default)]
    pub fullscreen: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PackLink {
    pub project_id: String,
    pub version_id: String,
    pub version_number: String,
    /// what the pack put in the instance — its files and overrides, relative
    /// and `/`-separated — so a version change takes out what it no longer
    /// ships and nothing the player added
    #[serde(default)]
    pub files: Vec<String>,
}

/// An instance's own launch hooks and environment, like Prism's per-instance
/// custom commands. An empty hook uses the launcher's; the environment adds
/// to the launcher's, a key set here winning.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct InstanceHooks {
    /// KEY=VALUE pairs, one per line or `;`-separated
    pub env_vars: String,
    pub prelaunch_hook: String,
    pub wrapper_hook: String,
    pub post_exit_hook: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Loader {
    Vanilla,
    Fabric,
    NeoForge,
    /// 1.8.9 only, see `forge.rs`
    Forge,
    /// the Rust game (Dusk Native), not a Java loader
    Native,
}

impl Loader {
    pub fn as_str(&self) -> &'static str {
        match self {
            Loader::Vanilla => "vanilla",
            Loader::Fabric => "fabric",
            Loader::NeoForge => "neoforge",
            Loader::Forge => "forge",
            Loader::Native => "native",
        }
    }

    /// Whether jars in `mods/` mean anything to this loader.
    pub fn takes_mods(&self) -> bool {
        matches!(self, Loader::Fabric | Loader::NeoForge | Loader::Forge)
    }

    pub fn parse(s: &str) -> Loader {
        if s.eq_ignore_ascii_case("fabric") {
            Loader::Fabric
        } else if s.eq_ignore_ascii_case("neoforge") {
            Loader::NeoForge
        } else if s.eq_ignore_ascii_case("forge") {
            Loader::Forge
        } else if s.eq_ignore_ascii_case("native") {
            Loader::Native
        } else {
            Loader::Vanilla
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ProfileStore {
    pub profiles: Vec<Profile>,
    /// Entries this build can't read (a loader it doesn't know, say). They
    /// stay out of the list but are written back untouched on save, so one
    /// unfamiliar instance never costs the others.
    #[serde(skip)]
    pub unreadable: Vec<serde_json::Value>,
}

#[derive(Deserialize)]
struct RawStore {
    profiles: Vec<serde_json::Value>,
}

impl ProfileStore {
    pub fn load(path: &Path) -> std::io::Result<Self> {
        if !path.exists() {
            return Ok(Self::default());
        }
        let raw: RawStore = serde_json::from_slice(&std::fs::read(path)?)?;
        let mut store = Self::default();
        for entry in raw.profiles {
            match serde_json::from_value::<Profile>(entry.clone()) {
                Ok(p) => store.profiles.push(p),
                Err(_) => store.unreadable.push(entry),
            }
        }
        Ok(store)
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        let mut all: Vec<serde_json::Value> = Vec::with_capacity(self.profiles.len() + self.unreadable.len());
        for p in &self.profiles {
            all.push(serde_json::to_value(p)?);
        }
        all.extend(self.unreadable.iter().cloned());
        crate::write_atomic(path, &serde_json::to_vec_pretty(&serde_json::json!({ "profiles": all }))?)
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

#[cfg(test)]
mod jvm_default_tests {
    use super::*;

    fn args(s: &str) -> Vec<String> {
        s.split_whitespace().map(str::to_string).collect()
    }

    #[test]
    fn plain_g1_defaults_gain_tuning() {
        let mut a = args("-Xms1G -Xmx6G -XX:+UseG1GC");
        assert!(migrate_g1_tuning(&mut a));
        assert_eq!(a[..2], args("-Xms1G -Xmx6G")[..]);
        assert_eq!(a[2..], default_jvm_args()[2..]);
        assert!(!migrate_g1_tuning(&mut a), "already tuned");
    }

    #[test]
    fn legacy_zgc_lands_on_tuned_g1() {
        let mut a = args("-Xms2G -Xmx4G -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:+AlwaysPreTouch");
        assert!(migrate_legacy_gc(&mut a) | migrate_g1_tuning(&mut a));
        assert_eq!(a, default_jvm_args());
    }

    #[test]
    fn hand_tuned_args_are_left_alone() {
        let mut a = args("-Xmx4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled");
        assert!(!migrate_g1_tuning(&mut a));
        assert_eq!(a, args("-Xmx4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled"));
    }

    #[test]
    fn an_unknown_loader_costs_only_its_own_entry() {
        let dir = std::env::temp_dir().join(format!("fl-store-{}", std::process::id()));
        let path = dir.join("profiles.json");
        std::fs::create_dir_all(&dir).unwrap();
        let known = r#"{"id":"a","name":"main","game_version":"1.21.11","loader":"Fabric","resolution":[1280,720],"created_at":1}"#;
        let future = r#"{"id":"b","name":"later","game_version":"9.9","loader":"SomethingNew","resolution":[1280,720],"created_at":2}"#;
        std::fs::write(&path, format!(r#"{{"profiles":[{known},{future}]}}"#)).unwrap();

        let store = ProfileStore::load(&path).unwrap();
        assert_eq!(store.profiles.len(), 1);
        assert_eq!(store.profiles[0].id, "a");
        assert_eq!(store.unreadable.len(), 1);

        store.save(&path).unwrap();
        let back: serde_json::Value = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
        let ids: Vec<&str> = back["profiles"].as_array().unwrap().iter().map(|p| p["id"].as_str().unwrap()).collect();
        assert_eq!(ids, ["a", "b"]);
        assert_eq!(back["profiles"][1]["loader"], "SomethingNew");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
