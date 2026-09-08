//! Global launcher settings, persisted to `<data>/settings.json`.
//! Field names are camelCase on the wire to match the TS `SettingsDto`.

use serde::{Deserialize, Serialize};

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
    /// Custom background override (image or video file path). Empty = animated scene.
    #[serde(default)]
    pub custom_background: String,
}

fn default_resolution() -> u32 {
    1280
}

fn default_auth_mode() -> String {
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
            default_jvm_args: "-Xms2G -Xmx4G -XX:+UseZGC -XX:+AlwaysPreTouch".into(),
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

    /// Parse the env_vars text block into KEY=VALUE pairs.
    pub fn env_pairs(&self) -> Vec<(String, String)> {
        self.env_vars
            .lines()
            .filter_map(|line| {
                let line = line.trim();
                if line.is_empty() {
                    return None;
                }
                let (k, v) = line.split_once('=')?;
                Some((k.trim().to_string(), v.trim().to_string()))
            })
            .collect()
    }
}
