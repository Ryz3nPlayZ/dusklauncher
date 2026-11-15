//! Bring one instance's setup into another: its options (video, sound,
//! controls and keybinds — all `options.txt`) and its multiplayer list.

use crate::appstate::AppState;
use tauri::State;

/// Keys that belong to the instance, not the player: its resource packs are
/// its own files.
const INSTANCE_KEYS: &[&str] = &["resourcePacks", "incompatibleResourcePacks"];

fn key(line: &str) -> Option<&str> {
    line.split_once(':').map(|(k, _)| k)
}

fn data_version(text: &str) -> Option<i64> {
    text.lines().find_map(|l| l.strip_prefix("version:")?.trim().parse().ok())
}

/// `into`'s options with `from`'s values over them. `into`'s own resource
/// packs and its other keys (a mod's, a newer game's) stay. The data version
/// is the older of the two, so the game upgrades whatever was written for
/// an older one — keybinds included — instead of misreading it.
fn merge_options(from: &str, into: Option<&str>) -> String {
    // nothing there yet: theirs, still without their packs
    let into = into.unwrap_or("");
    let theirs: Vec<(&str, &str)> = from.lines().filter_map(|l| Some((key(l)?, l))).collect();
    let version = match (data_version(from), data_version(into)) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (a, b) => a.or(b),
    };
    let mut out: Vec<String> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for line in into.lines() {
        let Some(k) = key(line) else {
            out.push(line.to_string());
            continue;
        };
        seen.insert(k);
        if k == "version" {
            out.push(version.map_or_else(|| line.to_string(), |v| format!("version:{v}")));
        } else if INSTANCE_KEYS.contains(&k) {
            out.push(line.to_string());
        } else {
            out.push(theirs.iter().find(|(tk, _)| *tk == k).map_or(line, |(_, l)| l).to_string());
        }
    }
    for (k, line) in &theirs {
        if !seen.contains(k) && !INSTANCE_KEYS.contains(k) {
            seen.insert(k);
            out.push(if *k == "version" { version.map_or_else(|| line.to_string(), |v| format!("version:{v}")) } else { line.to_string() });
        }
    }
    let mut text = out.join("\n");
    text.push('\n');
    text
}

/// What a newer game's options keep the same way 1.8.9 does.
const SAME_ON_1_8_9: &[&str] = &[
    "bobView", "chatColors", "chatHeightFocused", "chatHeightUnfocused", "chatLinks", "chatLinksPrompt", "chatOpacity",
    "chatScale", "chatVisibility", "chatWidth", "enableVsync", "entityShadows", "fov", "fullscreen", "gamma", "guiScale",
    "invertYMouse", "maxFps", "mipmapLevels", "mouseSensitivity", "particles", "pauseOnLostFocus", "renderClouds",
    "renderDistance",
];

/// The key binds 1.8.9 has.
const KEYS_ON_1_8_9: &[&str] = &[
    "attack", "use", "forward", "left", "back", "right", "jump", "sneak", "sprint", "drop", "inventory", "chat",
    "playerlist", "pickItem", "command", "screenshot", "togglePerspective", "smoothCamera", "spectatorOutlines",
    "fullscreen", "hotbar.1", "hotbar.2", "hotbar.3", "hotbar.4", "hotbar.5", "hotbar.6", "hotbar.7", "hotbar.8",
    "hotbar.9",
];

const OPTIFINE_ZOOM: &str = "key_of.key.zoom";

/// A key by its name in a newer game's options, as LWJGL 2's code for it.
fn lwjgl2_key(name: &str) -> Option<i32> {
    if let Ok(code) = name.parse() {
        return Some(code); // already 1.8.9's
    }
    if let Some(mouse) = name.strip_prefix("key.mouse.") {
        return match mouse {
            "left" => Some(-100),
            "right" => Some(-99),
            "middle" => Some(-98),
            n => n.parse::<i32>().ok().filter(|n| *n >= 1).map(|n| n - 101),
        };
    }
    let key = name.strip_prefix("key.keyboard.")?;
    if let Some(digit) = key.parse::<i32>().ok().filter(|_| key.len() == 1) {
        return Some(if digit == 0 { 11 } else { digit + 1 });
    }
    if let Some(n) = key.strip_prefix('f').and_then(|n| n.parse::<i32>().ok()) {
        return match n {
            1..=10 => Some(58 + n),
            11 | 12 => Some(76 + n),
            _ => None,
        };
    }
    if let Some(n) = key.strip_prefix("keypad.").and_then(|n| n.parse::<usize>().ok()) {
        return [82, 79, 80, 81, 75, 76, 77, 71, 72, 73].get(n).copied();
    }
    Some(match key {
        "unknown" => 0,
        "q" => 16, "w" => 17, "e" => 18, "r" => 19, "t" => 20, "y" => 21, "u" => 22, "i" => 23, "o" => 24, "p" => 25,
        "a" => 30, "s" => 31, "d" => 32, "f" => 33, "g" => 34, "h" => 35, "j" => 36, "k" => 37, "l" => 38,
        "z" => 44, "x" => 45, "c" => 46, "v" => 47, "b" => 48, "n" => 49, "m" => 50,
        "minus" => 12, "equal" => 13, "left.bracket" => 26, "right.bracket" => 27, "semicolon" => 39,
        "apostrophe" => 40, "grave.accent" => 41, "backslash" => 43, "comma" => 51, "period" => 52, "slash" => 53,
        "escape" => 1, "backspace" => 14, "tab" => 15, "enter" => 28, "space" => 57, "caps.lock" => 58,
        "left.shift" => 42, "right.shift" => 54, "left.control" => 29, "right.control" => 157,
        "left.alt" => 56, "right.alt" => 184, "left.win" => 219, "right.win" => 220,
        "up" => 200, "down" => 208, "left" => 203, "right" => 205,
        "insert" => 210, "delete" => 211, "home" => 199, "end" => 207, "page.up" => 201, "page.down" => 209,
        "scroll.lock" => 70, "pause" => 197, "num.lock" => 69,
        "keypad.add" => 78, "keypad.subtract" => 74, "keypad.multiply" => 55, "keypad.divide" => 181,
        "keypad.decimal" => 83, "keypad.enter" => 156,
        _ => return None,
    })
}

/// A newer game's options as 1.8.9 reads them. Whatever 1.8.9 has no place
/// for stays out, and the game fills in its defaults around the rest. Resource
/// packs stay out too: they're the instance's own files.
fn options_for_1_8_9(from: &str) -> String {
    let mut out = vec!["fboEnable:true".to_string(), "useVbo:true".to_string()];
    for line in from.lines() {
        let Some((k, v)) = line.split_once(':') else { continue };
        let v = v.trim_matches('"');
        if SAME_ON_1_8_9.contains(&k) || k.starts_with("soundCategory_") || k.starts_with("modelPart_") {
            out.push(format!("{k}:{v}"));
        } else if let Some(bind) = k.strip_prefix("key_key.") {
            if KEYS_ON_1_8_9.contains(&bind) {
                out.extend(lwjgl2_key(v).map(|code| format!("{k}:{code}")));
            }
        } else {
            match (k, v) {
                ("ao", "true") => out.push("ao:2".into()),
                ("ao", "false") => out.push("ao:0".into()),
                ("ao", _) => out.push(format!("ao:{v}")),
                // 1.21.11 split the graphics modes; leaves stand for them
                ("cutoutLeaves" | "fancyGraphics", _) => out.push(format!("fancyGraphics:{v}")),
                ("graphicsMode", _) => out.push(format!("fancyGraphics:{}", v != "0")),
                ("key_zoomify.key.zoom", _) => out.extend(lwjgl2_key(v).map(|code| format!("{OPTIFINE_ZOOM}:{code}"))),
                ("lang", _) => out.push(match v.split_once('_') {
                    Some((language, region)) => format!("lang:{language}_{}", region.to_uppercase()),
                    None => format!("lang:{v}"),
                }),
                _ => {}
            }
        }
    }
    // OptiFine zooms on C unless told otherwise, which would take C from a bind
    let zoom_set = out.iter().any(|line| line.starts_with(OPTIFINE_ZOOM));
    if !zoom_set && out.iter().any(|line| line.starts_with("key_") && line.ends_with(":46")) {
        out.push(format!("{OPTIFINE_ZOOM}:0"));
    }
    let mut text = out.join("\n");
    text.push('\n');
    text
}

/// Start a 1.8.9 instance from another instance's options, if it has any.
pub(crate) fn copy_options_to_1_8_9(from: &std::path::Path, to: &std::path::Path) -> Result<(), String> {
    let theirs = match std::fs::read_to_string(from.join("options.txt")) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(e.to_string()),
    };
    std::fs::create_dir_all(to).map_err(|e| e.to_string())?;
    let tmp = to.join("options.txt.tmp");
    std::fs::write(&tmp, options_for_1_8_9(&theirs))
        .and_then(|_| std::fs::rename(&tmp, to.join("options.txt")))
        .map_err(|e| e.to_string())
}

/// Copy another instance's options and keybinds (`options`) and the servers
/// on its list this one doesn't have (`servers`). Resolves to what happened.
#[tauri::command]
pub async fn copy_instance_settings(
    state: State<'_, AppState>,
    profile_id: String,
    from_id: String,
    options: bool,
    servers: bool,
) -> Result<String, String> {
    if profile_id == from_id {
        return Err("Pick another instance to copy from.".into());
    }
    let to = crate::servers::profile_root(&state, &profile_id)?;
    let from = crate::servers::profile_root(&state, &from_id)?;
    // the game writes options.txt as it closes, over anything put there now
    crate::worlds::ensure_closed(&state, &profile_id).await?;
    let mut done = Vec::new();
    if options {
        match std::fs::read_to_string(from.join("options.txt")) {
            Ok(theirs) => {
                let path = to.join("options.txt");
                let ours = std::fs::read_to_string(&path).ok();
                std::fs::create_dir_all(&to).map_err(|e| e.to_string())?;
                let tmp = to.join("options.txt.tmp");
                std::fs::write(&tmp, merge_options(&theirs, ours.as_deref()))
                    .and_then(|_| std::fs::rename(&tmp, &path))
                    .map_err(|e| format!("Couldn't save the options: {e}"))?;
                done.push("options and keybinds copied".to_string());
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                done.push("no options there yet (it hasn't been played)".to_string());
            }
            Err(e) => return Err(e.to_string()),
        }
    }
    if servers {
        let added = crate::servers::copy_servers(&state, &profile_id, &from).await?;
        done.push(match added {
            0 => "no servers to add".to_string(),
            1 => "1 server added".to_string(),
            n => format!("{n} servers added"),
        });
    }
    let mut summary = done.join(", ");
    if let Some(first) = summary.get(..1) {
        summary = first.to_uppercase() + &summary[1..];
    }
    Ok(summary)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_new_game_s_options_read_right_on_1_8_9() {
        let text = options_for_1_8_9(
            "version:4671\nfov:1.0\nmouseSensitivity:0.5035211267605634\nao:true\nrenderClouds:\"false\"\n\
             graphicsPreset:\"custom\"\ncutoutLeaves:false\nmaxFps:120\nenableVsync:false\nlang:en_us\n\
             resourcePacks:[\"vanilla\"]\ntoggleSprint:true\nsoundCategory_master:0.5\nmodelPart_cape:true\n",
        );
        let lines: Vec<&str> = text.lines().collect();
        for line in [
            "fboEnable:true", "useVbo:true", "fov:1.0", "mouseSensitivity:0.5035211267605634", "ao:2",
            "renderClouds:false", "fancyGraphics:false", "maxFps:120", "enableVsync:false", "lang:en_US",
            "soundCategory_master:0.5", "modelPart_cape:true",
        ] {
            assert!(lines.contains(&line), "missing {line} in {text}");
        }
        assert!(!text.contains("version") && !text.contains("resourcePacks") && !text.contains("toggleSprint"));
    }

    #[test]
    fn keys_turn_into_lwjgl_2_codes() {
        let text = options_for_1_8_9(
            "key_key.attack:key.mouse.left\nkey_key.use:key.mouse.right\nkey_key.pickItem:key.mouse.middle\n\
             key_key.drop:key.mouse.4\nkey_key.forward:key.keyboard.e\nkey_key.sneak:key.keyboard.left.shift\n\
             key_key.sprint:key.keyboard.left.control\nkey_key.hotbar.1:key.keyboard.a\nkey_key.hotbar.2:key.keyboard.2\n\
             key_key.jump:key.keyboard.space\nkey_key.fullscreen:key.keyboard.f11\nkey_key.screenshot:key.keyboard.f2\n\
             key_key.smoothCamera:key.keyboard.unknown\nkey_key.swapOffhand:key.keyboard.j\nkey_key.chat:key.keyboard.world.1\n",
        );
        let want = "fboEnable:true\nuseVbo:true\nkey_key.attack:-100\nkey_key.use:-99\nkey_key.pickItem:-98\n\
                    key_key.drop:-97\nkey_key.forward:18\nkey_key.sneak:42\nkey_key.sprint:29\nkey_key.hotbar.1:30\n\
                    key_key.hotbar.2:3\nkey_key.jump:57\nkey_key.fullscreen:87\nkey_key.screenshot:60\n\
                    key_key.smoothCamera:0\n";
        assert_eq!(text, want);
    }

    #[test]
    fn optifine_zoom_stays_off_a_bind_on_c() {
        let on_c = options_for_1_8_9("key_key.hotbar.8:key.keyboard.c\n");
        assert!(on_c.lines().any(|l| l == "key_of.key.zoom:0"), "{on_c}");

        let moved = options_for_1_8_9("key_key.hotbar.8:key.keyboard.c\nkey_zoomify.key.zoom:key.keyboard.b\n");
        assert!(moved.lines().any(|l| l == "key_of.key.zoom:48"), "{moved}");
        assert!(!moved.contains("key_of.key.zoom:0"));

        assert!(!options_for_1_8_9("key_key.hotbar.8:key.keyboard.x\n").contains("zoom"));
    }

    #[test]
    fn digits_and_keypad_keep_their_numbers_apart() {
        assert_eq!(lwjgl2_key("key.keyboard.0"), Some(11));
        assert_eq!(lwjgl2_key("key.keyboard.9"), Some(10));
        assert_eq!(lwjgl2_key("key.keyboard.keypad.0"), Some(82));
        assert_eq!(lwjgl2_key("key.keyboard.keypad.9"), Some(73));
        assert_eq!(lwjgl2_key("key.keyboard.keypad.enter"), Some(156));
        assert_eq!(lwjgl2_key("key.keyboard.f12"), Some(88));
        assert_eq!(lwjgl2_key("key.keyboard.f13"), None);
        assert_eq!(lwjgl2_key("29"), Some(29));
    }

    #[test]
    fn options_come_over_but_the_packs_and_the_older_version_stay() {
        let theirs = "version:4671\nfov:1.0\nkey_key.jump:key.keyboard.v\nresourcePacks:[\"file/Theirs.zip\"]\nguiScale:2\n";
        let ours = "version:3955\nfov:0.0\nresourcePacks:[\"vanilla\",\"file/Ours.zip\"]\nsodium_thing:true\n";
        assert_eq!(
            merge_options(theirs, Some(ours)),
            "version:3955\nfov:1.0\nresourcePacks:[\"vanilla\",\"file/Ours.zip\"]\nsodium_thing:true\nkey_key.jump:key.keyboard.v\nguiScale:2\n"
        );
        // a newer target keeps the older data version, so the game upgrades what came over
        let ours = "version:4700\nfov:0.0\n";
        assert!(merge_options(theirs, Some(ours)).starts_with("version:4671\n"));
        // nothing there yet: theirs, but not their resource packs
        assert_eq!(
            merge_options(theirs, None),
            "version:4671\nfov:1.0\nkey_key.jump:key.keyboard.v\nguiScale:2\n"
        );
    }
}
