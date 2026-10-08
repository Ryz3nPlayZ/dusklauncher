//! Global launcher settings, persisted to `<data>/settings.json`.
//! Field names are camelCase on the wire to match the TS `SettingsDto`.

use serde::{Deserialize, Serialize};

/// Bumped whenever the launcher's default JVM args change in a way existing
/// installs should pick up (see `AppState::init`). 1: ZGC -> G1.
/// 2: Mojang's G1 tuning flags.
pub const JVM_DEFAULTS_REV: u32 = 2;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub theme: String, // "nether" | "overworld"
    pub volume: f64,   // 0..1
    pub muted: bool,
    pub reduce_motion: bool,
    pub fps_cap: u32, // 30 | 60 | 0 (0 = static)
    pub selected_profile_id: Option<String>,
    pub memory_mb: u32,
    pub default_jvm_args: String,
    /// which launcher JVM-default migration this file has seen
    #[serde(default)]
    pub jvm_defaults_rev: u32,
    #[serde(default)]
    pub java_paths: std::collections::HashMap<String, String>,
    #[serde(default)]
    pub env_vars: String,
    #[serde(default)]
    pub prelaunch_hook: String,
    #[serde(default)]
    pub wrapper_hook: String,
    #[serde(default)]
    pub post_exit_hook: String,
    #[serde(default = "default_resolution")]
    pub width: u32,
    #[serde(default = "default_resolution")]
    pub height: u32,
    /// Azure native-app client_id override. Empty = use the ID shipped with the app.
    #[serde(default)]
    pub auth_client_id: String,
    /// Which Microsoft identity signs in: "official" (default; the official
    /// Minecraft launcher's Xbox title ID on login.live.com — no Azure app or
    /// approval needed) or "azure" (our own registration, which requires
    /// Microsoft's AppID approval). Switching methods invalidates the stored
    /// session's refresh token, so the next sign-in must be interactive.
    #[serde(default = "default_auth_mode")]
    pub auth_mode: String,
    /// Wallpaper override: a file name inside `<data>/wallpapers/` (see
    /// wallpapers.rs). Empty = the built-in animated scene.
    #[serde(default)]
    pub custom_background: String,
    /// Show what's being played on the Discord profile (Rich Presence).
    #[serde(default = "yes")]
    pub discord_rpc: bool,
    /// Desktop notifications for friends coming online / new messages /
    /// friend requests and gifts.
    #[serde(default = "yes")]
    pub notify_friends_online: bool,
    #[serde(default = "yes")]
    pub notify_messages: bool,
    /// Chat and screenshot times as 24-hour clock instead of 12-hour.
    #[serde(default)]
    pub clock_24h: bool,
    /// Ask before opening a link someone sent in chat.
    #[serde(default = "yes")]
    pub warn_on_links: bool,
    /// Carry the Dusk client's HUD layout, module options and menu prefs
    /// across instances and machines (client_settings.rs).
    #[serde(default = "yes")]
    pub sync_client_settings: bool,
    /// Offline play's username. Empty = locked: it only gets a value from the
    /// redeem code that unlocks offline play (dusk::redeem_code), and then
    /// lets PLAY launch with no Microsoft account (commands::launch_session).
    #[serde(default)]
    pub offline_name: String,
    /// What the launcher window does while the game runs: "keep",
    /// "minimize", or "hide" (brought back when the game closes).
    #[serde(default = "default_on_play")]
    pub on_play: String,
    /// When the game closes, each world it saved is zipped into the
    /// instance's `backups/auto/`, keeping this many per world. 0 = off.
    #[serde(default = "default_auto_backups")]
    pub auto_backups: u32,
}

fn default_auto_backups() -> u32 {
    5
}

fn default_on_play() -> String {
    "keep".into()
}

fn yes() -> bool {
    true
}

fn default_resolution() -> u32 {
    1280
}

fn default_auth_mode() -> String {
    // Official title + device code: works with zero external dependencies.
    // "azure" (browser + loopback, no codes) stays opt-in until our client
    // ID lands on Mojang Enforcement's Xbox allow list — verified blocked
    // (opaque XBL 400) as of 2026-09-21, re-test with
    // `cargo run -p fasterlauncher-core --example azure_live`.
    "official".into()
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            theme: "overworld".into(),
            volume: 0.6,
            muted: false,
            reduce_motion: false,
            fps_cap: 30,
            selected_profile_id: None,
            memory_mb: 4096,
            default_jvm_args: fasterlauncher_core::profile::default_jvm_args().join(" "),
            jvm_defaults_rev: JVM_DEFAULTS_REV,
            java_paths: Default::default(),
            env_vars: String::new(),
            prelaunch_hook: String::new(),
            wrapper_hook: String::new(),
            post_exit_hook: String::new(),
            width: 1280,
            height: 720,
            auth_client_id: String::new(),
            auth_mode: default_auth_mode(),
            custom_background: String::new(),
            discord_rpc: true,
            notify_friends_online: true,
            notify_messages: true,
            clock_24h: false,
            warn_on_links: true,
            sync_client_settings: true,
            offline_name: String::new(),
            on_play: default_on_play(),
            auto_backups: default_auto_backups(),
        }
    }
}

impl Settings {
    pub fn load(path: &std::path::Path) -> Self {
        std::fs::read(path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, path: &std::path::Path) -> std::io::Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(path, serde_json::to_vec_pretty(self)?)?;
        Ok(())
    }

    /// The env_vars field as KEY=VALUE pairs; see [`parse_env`].
    pub fn env_pairs(&self) -> Vec<(String, String)> {
        parse_env(&self.env_vars)
    }

    /// The launch environment for `p`: the launcher's variables with the
    /// instance's over them, and each hook the instance's when it has one.
    pub fn launch_hooks(&self, p: &fasterlauncher_core::profile::Profile) -> (Vec<(String, String)>, [Option<String>; 3]) {
        let mut env = self.env_pairs();
        for (k, v) in parse_env(&p.hooks.env_vars) {
            env.retain(|(have, _)| *have != k);
            env.push((k, v));
        }
        let pick = |own: &str, global: &str| {
            [own, global].into_iter().find(|s| !s.trim().is_empty()).map(|s| s.trim().to_string())
        };
        let h = &p.hooks;
        (
            env,
            [
                pick(&h.prelaunch_hook, &self.prelaunch_hook),
                pick(&h.wrapper_hook, &self.wrapper_hook),
                pick(&h.post_exit_hook, &self.post_exit_hook),
            ],
        )
    }
}

/// KEY=VALUE pairs: one per line, or several on a line split by `;`. A `;`
/// stays inside a value (a PATH list) unless what follows it starts a new
/// `KEY=`.
pub fn parse_env(text: &str) -> Vec<(String, String)> {
    let starts_pair = |s: &str| {
        s.trim_start().split_once('=').is_some_and(|(k, _)| {
            let k = k.trim();
            !k.is_empty() && k.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
        })
    };
    let mut pairs: Vec<(String, String)> = Vec::new();
    for line in text.lines() {
        let mut open = false;
        for part in line.split(';') {
            if starts_pair(part) {
                let (k, v) = part.split_once('=').unwrap();
                pairs.push((k.trim().to_string(), v.trim().to_string()));
                open = true;
            } else if open && !part.trim().is_empty() {
                let v = &mut pairs.last_mut().unwrap().1;
                v.push(';');
                v.push_str(part.trim());
            }
        }
    }
    pairs
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn env_pairs_split_on_lines_and_semicolons() {
        let s = Settings { env_vars: "A=1; B = x=y\nPATH=/a;/b;C=3\n\njunk".into(), ..Settings::default() };
        assert_eq!(
            s.env_pairs(),
            vec![
                ("A".into(), "1".into()),
                ("B".into(), "x=y".into()),
                ("PATH".into(), "/a;/b".into()),
                ("C".into(), "3".into()),
            ]
        );
    }

    #[test]
    fn an_instances_hooks_and_environment_go_over_the_launchers() {
        let s = Settings {
            env_vars: "A=1; B=2".into(),
            prelaunch_hook: "echo global".into(),
            wrapper_hook: "gamemoderun".into(),
            ..Settings::default()
        };
        let mut p: fasterlauncher_core::profile::Profile = serde_json::from_value(serde_json::json!({
            "id": "p1", "name": "x", "game_version": "1.21.11", "loader": "Fabric", "loader_version": null,
            "created_at": 0, "last_played": null, "jvm_args": [], "resolution": [854, 480], "mod_filenames": [], "server": null
        }))
        .unwrap();
        assert_eq!(s.launch_hooks(&p).1, [Some("echo global".into()), Some("gamemoderun".into()), None]);
        p.hooks.env_vars = "B=3\nC=4".into();
        p.hooks.wrapper_hook = "prime-run".into();
        p.hooks.post_exit_hook = "  ".into();
        let (env, hooks) = s.launch_hooks(&p);
        assert_eq!(env, vec![("A".into(), "1".into()), ("B".into(), "3".into()), ("C".into(), "4".into())]);
        assert_eq!(hooks, [Some("echo global".into()), Some("prime-run".into()), None]);
    }
}
