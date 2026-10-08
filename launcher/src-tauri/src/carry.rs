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
    let Some(into) = into else { return from.to_string() };
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
        // nothing there yet: theirs as it is
        assert_eq!(merge_options(theirs, None), theirs);
    }
}
