mod appstate;
mod auth_flow;
mod auth_store;
mod commands;
mod modpacks;
mod mods;
mod settings;
mod skins;

pub use appstate::AppState;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState::init())
        .invoke_handler(tauri::generate_handler![
            // profiles
            commands::list_profiles,
            commands::create_profile,
            commands::update_profile,
            commands::delete_profile,
            commands::list_worlds,
            commands::show_in_folder,
            // versions
            commands::list_versions,
            // launch
            commands::install_and_launch,
            commands::stop_game,
            // settings
            commands::get_settings,
            commands::set_settings,
            // account
            commands::begin_login,
            commands::begin_reconsent_login,
            commands::logout,
            commands::get_current_account,
            // app
            commands::get_app_info,
            // modpacks
            modpacks::search_modpacks,
            modpacks::get_modpack_project,
            modpacks::install_modpack,
            modpacks::list_modpack_versions,
            modpacks::install_modpack_version,
            // mods + instance content (mods / resource packs / shaders)
            mods::list_profile_mods,
            mods::list_profile_content,
            mods::remove_profile_mod,
            mods::remove_profile_content,
            mods::set_mod_enabled,
            mods::set_content_enabled,
            mods::import_local_mod,
            mods::search_mods,
            mods::search_content,
            mods::install_mod_to_profile,
            mods::install_content_to_profile,
            mods::install_bundled_client_mod,
            // skins
            skins::list_skins,
            skins::import_skin,
            skins::rename_skin,
            skins::delete_skin,
            skins::set_selected_skin,
            skins::read_skin,
            skins::upload_skin,
            skins::reset_skin,
            skins::get_account_skin,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
