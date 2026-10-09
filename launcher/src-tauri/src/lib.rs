mod appstate;
mod auth_flow;
mod auth_store;
mod carry;
mod client_settings;
mod commands;
mod cosmetics;
mod crash;
mod discord;
mod dusk;
mod friends;
mod hosting;
mod icons;
mod importer;
mod deps;
mod javas;
mod logs;
mod modpacks;
mod mods;
mod packmods;
mod recordings;
mod quickplay;
mod release_art;
mod screenshots;
mod selfinstall;
mod servers;
mod settings;
mod shortcuts;
mod skins;
mod wallpapers;
mod worlds;

pub use appstate::AppState;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // WebKitGTK's DMA-BUF renderer fails to get an EGL display on many Linux
    // setups (Arch, NVIDIA, some Wayland compositors) and the window stays
    // blank grey. Fall back to its shared-memory path unless the user chose.
    #[cfg(target_os = "linux")]
    if std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none() {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }

    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();

    tauri::Builder::default()
        // first, so a second start hands over before anything else spins up
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            shortcuts::second_start(app, argv)
        }))
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_notification::init())
        .manage(AppState::init())
        .manage(shortcuts::PendingLaunch::from_args())
        .invoke_handler(tauri::generate_handler![
            // profiles
            commands::list_profiles,
            commands::create_profile,
            commands::update_profile,
            commands::delete_profile,
            commands::duplicate_profile,
            commands::repair_profile,
            importer::scan_external_instances,
            importer::import_external_instance,
            shortcuts::create_shortcut,
            icons::set_profile_icon,
            icons::clear_profile_icon,
            shortcuts::take_launch_request,
            commands::list_worlds,
            commands::show_in_folder,
            crash::reveal_crash_report,
            commands::open_data_dir,
            modpacks::import_mrpack,
            modpacks::update_modpack,
            modpacks::export_instance,
            modpacks::share_instance,
            modpacks::import_shared_instance,
            modpacks::install_bundled_pack,
            // versions
            commands::list_versions,
            commands::fabric_loader_version,
            commands::fabric_loader_versions,
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
            javas::list_javas,
            deps::mod_problems,
            selfinstall::script_installed,
            selfinstall::script_update,
            logs::read_latest_log,
            logs::upload_log,
            logs::list_logs,
            logs::read_log,
            servers::list_servers,
            servers::ping_server,
            servers::add_server,
            servers::remove_server,
            servers::edit_server,
            carry::copy_instance_settings,
            worlds::backup_world,
            worlds::export_world,
            worlds::list_world_backups,
            worlds::restore_world_backup,
            worlds::delete_world,
            worlds::rename_world,
            worlds::duplicate_world,
            worlds::import_world,
            worlds::import_world_paths,
            worlds::list_datapacks,
            worlds::set_datapack_enabled,
            worlds::install_datapack,
            worlds::remove_datapack,
            worlds::add_datapacks,
            worlds::add_datapack_paths,
            quickplay::recent_plays,
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
            mods::list_profile_content,
            mods::lookup_profile_content,
            mods::check_content_updates,
            mods::update_profile_content,
            mods::remove_profile_content,
            mods::set_content_enabled,
            mods::import_local_content,
            mods::import_content_paths,
            mods::search_content,
            mods::install_content_to_profile,
            mods::install_content_version_to_profile,
            mods::install_performance_mods,
            packmods::pack_mods,
            packmods::install_pack_mods,
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
            skins::set_skin_model,
            skins::reset_skin,
            skins::get_account_skin,
            skins::list_account_capes,
            skins::set_account_cape,
        ])
        .build(tauri::generate_context!())
        .expect("error while running tauri application")
        .run(|app, event| {
            // the dock icon brings back a window hidden while the game runs
            #[cfg(target_os = "macos")]
            if let tauri::RunEvent::Reopen { .. } = event {
                if let Some(w) = tauri::Manager::get_webview_window(app, "main") {
                    let _ = w.show();
                    let _ = w.unminimize();
                    let _ = w.set_focus();
                }
            }
            #[cfg(not(target_os = "macos"))]
            let _ = (app, event);
        });
}
