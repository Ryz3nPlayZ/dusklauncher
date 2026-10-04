mod appstate;
mod auth_flow;
mod auth_store;
mod client_settings;
mod commands;
mod cosmetics;
mod discord;
mod dusk;
mod friends;
mod modpacks;
mod mods;
mod recordings;
mod release_art;
mod screenshots;
mod settings;
mod skins;
mod wallpapers;

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
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_notification::init())
        .manage(AppState::init())
        .invoke_handler(tauri::generate_handler![
            // profiles
            commands::list_profiles,
            commands::create_profile,
            commands::update_profile,
            commands::delete_profile,
            commands::duplicate_profile,
            commands::list_worlds,
            commands::show_in_folder,
            commands::open_data_dir,
            modpacks::import_mrpack,
            modpacks::export_instance,
            modpacks::install_bundled_pack,
            // versions
            commands::list_versions,
            commands::fabric_loader_version,
            // launch
            commands::install_and_launch,
            commands::stop_game,
            commands::game_state,
            commands::game_activity,
            // settings
            commands::get_settings,
            commands::set_settings,
            // wallpapers
            wallpapers::list_wallpapers,
            wallpapers::import_wallpaper,
            wallpapers::remove_wallpaper,
            // account
            commands::begin_login,
            commands::begin_code_login,
            commands::cancel_login,
            commands::begin_reconsent_login,
            commands::logout,
            commands::get_current_account,
            commands::list_accounts,
            commands::switch_account,
            commands::remove_account,
            // app
            commands::get_app_info,
            commands::notify,
            // screenshots
            screenshots::list_screenshots,
            screenshots::set_screenshot_favorite,
            screenshots::delete_screenshot,
            screenshots::reveal_screenshot,
            // clips and replays
            release_art::release_art,
            recordings::list_recordings,
            recordings::recording_thumb,
            recordings::delete_recording,
            recordings::reveal_recording,
            // modpacks
            modpacks::search_modpacks,
            modpacks::search_projects,
            modpacks::get_modpack_project,
            modpacks::install_modpack,
            modpacks::list_modpack_versions,
            modpacks::install_modpack_version,
            modpacks::modrinth_tags,
            // mods + instance content (mods / resource packs / shaders)
            mods::list_profile_mods,
            mods::list_profile_content,
            mods::lookup_profile_content,
            mods::check_content_updates,
            mods::update_profile_content,
            mods::remove_profile_mod,
            mods::remove_profile_content,
            mods::set_mod_enabled,
            mods::set_content_enabled,
            mods::import_local_content,
            mods::import_content_paths,
            mods::search_mods,
            mods::search_content,
            mods::install_mod_to_profile,
            mods::install_content_to_profile,
            mods::install_content_version_to_profile,
            mods::install_bundled_client_mod,
            mods::install_performance_mods,
            mods::install_dusk_essentials,
            // cosmetics
            cosmetics::list_cosmetics,
            cosmetics::read_cosmetic_texture,
            cosmetics::read_cosmetic_model,
            cosmetics::get_loadout,
            cosmetics::set_loadout,
            cosmetics::get_inventory,
            // dusk service (wallet / store)
            dusk::get_store,
            dusk::get_wallet,
            dusk::redeem_code,
            dusk::get_referral,
            dusk::claim_referral,
            dusk::buy_cosmetic,
            // friends / chat
            friends::social_heartbeat,
            friends::list_friends,
            friends::remove_friend,
            friends::list_friend_requests,
            friends::send_friend_request,
            friends::accept_friend_request,
            friends::decline_friend_request,
            friends::get_friend_profile,
            friends::get_messages,
            friends::send_message,
            friends::get_public_skin,
            friends::send_invite,
            friends::send_screenshot,
            friends::get_chat_image,
            friends::get_privacy,
            friends::set_privacy,
            friends::list_blocked,
            friends::block_player,
            friends::unblock_player,
            friends::gift_cosmetic,
            friends::get_quests,
            friends::claim_quest,
            friends::list_outfits,
            friends::save_outfit,
            friends::delete_outfit,
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
            skins::list_account_capes,
            skins::set_account_cape,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
