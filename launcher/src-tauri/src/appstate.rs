use crate::settings::Settings;
use fasterlauncher_core::meta::VersionManifest;
use fasterlauncher_core::profile::{Profile, ProfileStore};
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Instant;

/// Signed-in account derived from the stored Microsoft session.
/// `None` means never signed in; `authenticated: false` is the pre-auth demo.
#[derive(Debug, Clone)]
pub struct Account {
    pub username: String,
    pub uuid: String,
    pub authenticated: bool,
}

impl Default for Account {
    fn default() -> Self {
        Self {
            username: "Player".into(),
            uuid: "00000000-0000-0000-0000-000000000000".into(),
            authenticated: false,
        }
    }
}

pub struct AppState {
    pub data_dir: PathBuf,
    pub profiles: Mutex<ProfileStore>,
    pub settings: Mutex<Settings>,
    pub account: Mutex<Option<Account>>,
    /// handle to the running game, so it can be stopped from the UI
    pub running_game: tokio::sync::Mutex<Option<tokio::process::Child>>,
    /// held across install+spawn so double-clicking PLAY NOW can't launch twice
    pub launch_lock: tokio::sync::Mutex<()>,
    /// held across the interactive login flow so only one browser flow runs
    pub login_lock: tokio::sync::Mutex<()>,
    /// cached version manifest (10 min TTL)
    pub manifest: tokio::sync::RwLock<Option<(Instant, VersionManifest)>>,
    pub client: reqwest::Client,
}

impl AppState {
    pub fn init() -> Self {
        let data_dir = dirs::data_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("FasterLauncher");
        let profiles_path = data_dir.join("profiles.json");
        let profiles =
            ProfileStore::load(&profiles_path).unwrap_or(ProfileStore { profiles: Vec::new() });
        let settings = Settings::load(&data_dir.join("settings.json"));
        Self {
            data_dir,
            profiles: Mutex::new(profiles),
            settings: Mutex::new(settings),
            account: Mutex::new(Some(Account::default())),
            running_game: tokio::sync::Mutex::new(None),
            launch_lock: tokio::sync::Mutex::new(()),
            login_lock: tokio::sync::Mutex::new(()),
            manifest: tokio::sync::RwLock::new(None),
            client: reqwest::Client::builder()
                // Modrinth requires a descriptive UA; set it once here so no
                // future endpoint can forget it and eat a 403 block page.
                .user_agent(concat!(
                    "DuskLauncher/",
                    env!("CARGO_PKG_VERSION"),
                    " (github.com/dusklauncher)"
                ))
                .timeout(std::time::Duration::from_secs(30))
                .build()
                .unwrap_or_else(|_| reqwest::Client::new()),
        }
    }

    pub fn save_profiles(&self, store: &ProfileStore) {
        let _ = store.save(&self.data_dir.join("profiles.json"));
    }

    pub fn save_settings(&self, settings: &Settings) {
        let _ = settings.save(&self.data_dir.join("settings.json"));
    }

    pub async fn manifest(&self, client: &reqwest::Client) -> Result<VersionManifest, String> {
        {
            let cache = self.manifest.read().await;
            if let Some((at, m)) = cache.as_ref() {
                if at.elapsed().as_secs() < 600 {
                    return Ok(m.clone());
                }
            }
        }
        let m = VersionManifest::fetch(client).await.map_err(|e| e.to_string())?;
        *self.manifest.write().await = Some((Instant::now(), m.clone()));
        Ok(m)
    }

    pub fn patch_profile(&self, id: &str, patch: impl FnOnce(&mut Profile)) -> Option<Profile> {
        let mut store = self.profiles.lock().unwrap();
        let p = store.profiles.iter_mut().find(|p| p.id == id)?;
        patch(p);
        let updated = p.clone();
        self.save_profiles(&store);
        Some(updated)
    }
}
