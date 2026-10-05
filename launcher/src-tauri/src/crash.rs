//! Why the game just crashed, in plain words. When a game exits with an
//! error the supervisor hands its last log lines here; the newest crash
//! report (or JVM fatal-error log) written since launch is read too, and a
//! few well-known causes are recognised: mods that don't fit together, a
//! mod whose mixins fail, running out of memory, the wrong Java, graphics
//! driver trouble. Anything else still gets the report's own description.

use crate::appstate::AppState;
use serde::Serialize;
use std::path::{Path, PathBuf};
use std::time::SystemTime;
use tauri::State;

/// Lines of game output the supervisor keeps for this.
pub const LOG_TAIL: usize = 600;
const MAX_REPORT_BYTES: u64 = 512 * 1024;

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct CrashDto {
    pub profile_id: String,
    pub code: i32,
    /// One line: what went wrong
    pub title: String,
    /// What to do about it, one item per line
    pub advice: Vec<String>,
    /// The crash report or fatal-error log, relative to the instance folder
    pub report: Option<String>,
    /// The report's head or the end of the log, for COPY DETAILS
    pub details: String,
}

/// Build the explanation for a game that exited with `code`.
pub fn analyze(profile_id: &str, root: &Path, mods: &Path, started: SystemTime, log: &[String], code: i32) -> CrashDto {
    let report = newest_since(&root.join("crash-reports"), started, |n| n.ends_with(".txt"))
        .map(|p| ("crash-reports", p))
        .or_else(|| newest_since(root, started, |n| n.starts_with("hs_err_pid") && n.ends_with(".log")).map(|p| ("", p)));
    let report_text = report.as_ref().and_then(|(_, p)| read_capped(p)).unwrap_or_default();
    let log_text = log.join("\n");
    let all = format!("{log_text}\n{report_text}");

    let (title, advice) = explain(&all, &report_text, mods);
    let details = if report_text.is_empty() {
        log.iter().rev().take(80).rev().cloned().collect::<Vec<_>>().join("\n")
    } else {
        report_text.lines().take(120).collect::<Vec<_>>().join("\n")
    };
    CrashDto {
        profile_id: profile_id.to_string(),
        code,
        title,
        advice,
        report: report.map(|(dir, p)| {
            let name = p.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            if dir.is_empty() { name } else { format!("{dir}/{name}") }
        }),
        details,
    }
}

fn explain(all: &str, report: &str, mods: &Path) -> (String, Vec<String>) {
    // Fabric's dependency check: it already words its fix plainly
    if all.contains("Some of your mods are incompatible") || all.contains("Incompatible mods found") || all.contains("incompatible mod set") {
        let mut advice = bullet_lines(all, "A potential solution has been determined", 6);
        if advice.is_empty() {
            advice = bullet_lines(all, "More details:", 6);
        }
        if advice.is_empty() {
            advice.push("A mod needs another mod (or a different version of one) that isn't installed. Check the log for which.".into());
        }
        return ("Some mods don't work together".into(), advice);
    }
    if all.contains("Could not reserve enough space for")
        || all.contains("Invalid maximum heap size")
        || all.contains("Initial heap size set to a larger value than the maximum")
    {
        return (
            "Java couldn't get the memory it was given".into(),
            vec!["Lower MEMORY in Settings → JAVA, or in the instance's SETTINGS tab.".into()],
        );
    }
    if all.contains("java.lang.OutOfMemoryError") {
        return (
            "The game ran out of memory".into(),
            vec![
                "Give it more: MEMORY in the instance's SETTINGS tab (4096 MB suits most modded instances).".into(),
                "Big shader packs and high render distance need more than vanilla.".into(),
            ],
        );
    }
    if all.contains("UnsupportedClassVersionError") || all.contains("compiled by a more recent version of the Java Runtime") {
        return (
            "A mod needs a newer Java".into(),
            vec![
                "Leave JAVA empty in the instance's SETTINGS tab so the launcher picks the right one.".into(),
                "If it's already empty, a mod in this instance is built for a newer Minecraft; remove it.".into(),
            ],
        );
    }
    if let Some(id) = mixin_mod(all) {
        let name = mod_name(mods, &id).unwrap_or(id);
        return (
            format!("{name} failed to load"),
            vec![
                format!("{name} doesn't work with this Minecraft version or with another mod here."),
                format!("Update {name} in CONTENT, or turn it off and launch again."),
            ],
        );
    }
    if let Some(id) = quoted_after(all, "due to errors, provided by '") {
        let name = mod_name(mods, &id).unwrap_or(id);
        return (
            format!("{name} crashed while starting"),
            vec![
                format!("Update {name} in CONTENT, or turn it off and launch again."),
                "If it needs another mod, the log names it a few lines further down.".into(),
            ],
        );
    }
    if ["GLFW error 65542", "GLFW error 65543", "does not appear to support OpenGL", "Pixel format not accelerated", "Failed to create the GLFW window"]
        .iter()
        .any(|s| all.contains(s))
    {
        return (
            "The graphics driver couldn't open the game window".into(),
            vec![
                "Update your graphics driver from NVIDIA, AMD or Intel's site.".into(),
                "On a laptop, make sure Java runs on the dedicated graphics card.".into(),
            ],
        );
    }
    if all.contains("EXCEPTION_ACCESS_VIOLATION") || all.contains("A fatal error has been detected by the Java Runtime Environment") || all.contains("SIGSEGV") {
        return (
            "Java crashed inside native code".into(),
            vec![
                "This is usually the graphics driver or a rendering mod. Update your graphics driver.".into(),
                "If you use shaders, turn them off and try again.".into(),
            ],
        );
    }
    if let Some(desc) = field(report, "Description:") {
        let cause = report
            .lines()
            .skip_while(|l| !l.starts_with("Description:"))
            .skip(1)
            .map(str::trim)
            .find(|l| !l.is_empty())
            .map(str::to_string);
        let mut advice = Vec::new();
        if let Some(cause) = cause {
            advice.push(cause);
        }
        advice.push("If it happens again, turn off the mods you added most recently.".into());
        return (format!("The game crashed: {desc}"), advice);
    }
    (
        "The game closed unexpectedly".into(),
        vec!["The LOG tab of the instance shows the last thing it printed.".into()],
    )
}

/// The `- ...` lines that follow the line containing `marker`, up to `max`.
fn bullet_lines(text: &str, marker: &str, max: usize) -> Vec<String> {
    let lower = marker.to_ascii_lowercase();
    let mut lines = text.lines().skip_while(|l| !l.to_ascii_lowercase().contains(&lower)).skip(1);
    let mut out = Vec::new();
    for line in lines.by_ref() {
        let t = strip_log_prefix(line).trim();
        let Some(item) = t.strip_prefix("- ") else {
            if out.is_empty() && t.is_empty() {
                continue;
            }
            break;
        };
        if !out.iter().any(|o: &String| o == item) {
            out.push(item.to_string());
        }
        if out.len() >= max {
            break;
        }
    }
    out
}

/// `[12:34:56] [main/ERROR]: text` → `text`
fn strip_log_prefix(line: &str) -> &str {
    if line.starts_with('[') {
        if let Some(i) = line.find("]: ") {
            return &line[i + 3..];
        }
    }
    line
}

/// The mod whose mixin broke: `Mixin [x.mixins.json:Foo] from mod sodium`
/// or `... from mod sodium failed injection check`.
fn mixin_mod(text: &str) -> Option<String> {
    let mixin_failed = ["MixinApplyError", "InvalidInjectionException", "MixinTransformerError", "Critical injection failure", "InjectionError"]
        .iter()
        .any(|s| text.contains(s));
    if !mixin_failed {
        return None;
    }
    text.lines().find_map(|line| {
        let i = line.find(" from mod ")?;
        let id: String = line[i + " from mod ".len()..]
            .chars()
            .take_while(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
            .collect();
        Some(id).filter(|id| !id.is_empty() && id != "minecraft" && id != "java")
    })
}

/// The display name of the enabled mod jar in `mods` whose fabric.mod.json id is `id`.
fn mod_name(mods: &Path, id: &str) -> Option<String> {
    for entry in std::fs::read_dir(mods).ok()?.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("jar") {
            continue;
        }
        let Some(meta) = crate::mods::fabric_meta(&path) else { continue };
        if meta.get("id").and_then(|v| v.as_str()) == Some(id) {
            return meta.get("name").and_then(|v| v.as_str()).map(str::to_string);
        }
    }
    None
}

/// The text between `prefix` and the next `'`: `provided by 'sodium' at` → `sodium`.
fn quoted_after(text: &str, prefix: &str) -> Option<String> {
    let i = text.find(prefix)? + prefix.len();
    let id = &text[i..][..text[i..].find('\'')?];
    Some(id.to_string()).filter(|id| !id.is_empty() && id.len() <= 64)
}

fn field(text: &str, key: &str) -> Option<String> {
    text.lines()
        .find_map(|l| l.strip_prefix(key))
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

/// The newest file in `dir` matching `want` that was written at or after `since`.
fn newest_since(dir: &Path, since: SystemTime, want: impl Fn(&str) -> bool) -> Option<PathBuf> {
    // file times can be coarse; a report written in the launch second still counts
    let since = since.checked_sub(std::time::Duration::from_secs(2)).unwrap_or(since);
    std::fs::read_dir(dir)
        .ok()?
        .flatten()
        .filter(|e| want(&e.file_name().to_string_lossy()))
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .filter(|(t, _)| *t >= since)
        .max_by_key(|(t, _)| *t)
        .map(|(_, p)| p)
}

fn read_capped(path: &Path) -> Option<String> {
    use std::io::Read;
    let mut buf = Vec::new();
    std::fs::File::open(path).ok()?.take(MAX_REPORT_BYTES).read_to_end(&mut buf).ok()?;
    Some(String::from_utf8_lossy(&buf).into_owned())
}

/// SHOW REPORT on the crash dialog: reveal the report `analyze` named.
#[tauri::command]
pub fn reveal_crash_report(state: State<AppState>, profile_id: String, report: String) -> Result<(), String> {
    let (dir, name) = report.rsplit_once('/').unwrap_or(("", report.as_str()));
    let plain = !name.is_empty() && !name.contains(['\\', '/']) && name != ".." && name != ".";
    if !plain || !(dir.is_empty() || dir == "crash-reports") {
        return Err("unknown report".into());
    }
    let root = {
        let store = state.profiles.lock().unwrap();
        let profile = store.profiles.iter().find(|p| p.id == profile_id).ok_or("profile not found")?;
        profile.dirs(&state.data_dir).root
    };
    let path = if dir.is_empty() { root.join(name) } else { root.join(dir).join(name) };
    if !path.is_file() {
        return Err("That report is gone.".into());
    }
    tauri_plugin_opener::reveal_item_in_dir(path).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn explain_text(text: &str) -> (String, Vec<String>) {
        explain(text, "", Path::new("/nonexistent"))
    }

    #[test]
    fn fabric_dependency_solution() {
        let log = "[12:00:01] [main/ERROR]: Incompatible mods found!\n\
            net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!\n\
            A potential solution has been determined, this may resolve your problem:\n\
            \t - Install fabric-api, any version.\n\
            \t - Replace 'Iris' (iris) 1.6.4 with any version that is compatible with 1.21.11.\n\
            More details:\n\
            \t - Mod 'Sodium Extra' (sodium-extra) requires any version of fabric-api, which is missing!";
        let (title, advice) = explain_text(log);
        assert_eq!(title, "Some mods don't work together");
        assert_eq!(advice[0], "Install fabric-api, any version.");
        assert_eq!(advice.len(), 2);
    }

    #[test]
    fn out_of_memory() {
        let (title, _) = explain_text("Exception in thread \"Render thread\" java.lang.OutOfMemoryError: Java heap space");
        assert_eq!(title, "The game ran out of memory");
    }

    #[test]
    fn mixin_names_the_mod() {
        let log = "org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Critical injection failure: \
            @Inject annotation on render could not find any targets matching 'x' in Foo. \
            [PREINJECT Applicator Phase -> fancy.mixins.json:FooMixin from mod fancymod -> Apply Injections]";
        let (title, _) = explain_text(log);
        assert_eq!(title, "fancymod failed to load");
    }

    #[test]
    fn crash_report_description() {
        let report = "---- Minecraft Crash Report ----\n// Oops.\n\nTime: today\nDescription: Rendering overlay\n\n\
            java.lang.NullPointerException: Cannot invoke \"Foo.bar()\"\n\tat a.b.C.d(C.java:1)";
        let (title, advice) = explain(report, report, Path::new("/nonexistent"));
        assert_eq!(title, "The game crashed: Rendering overlay");
        assert!(advice[0].starts_with("java.lang.NullPointerException"));
    }

    #[test]
    fn entrypoint_names_the_mod() {
        let log = "java.lang.RuntimeException: Could not execute entrypoint stage 'client' due to errors, provided by 'coolmod' at 'a.B'!";
        assert_eq!(explain_text(log).0, "coolmod crashed while starting");
    }

    #[test]
    fn unknown_exit() {
        let (title, _) = explain_text("[12:00:00] [main/INFO]: hello");
        assert_eq!(title, "The game closed unexpectedly");
    }
}
