//! Desktop shortcuts that start one instance straight away, like Prism's
//! "Create shortcut": the shortcut runs the launcher with `--launch <id>`.
//! A cold start picks the id up through [`take_launch_request`] once the
//! window is up; with the launcher already open, the single-instance plugin
//! hands the second start's arguments to [`second_start`] and that copy exits.

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use tauri::{AppHandle, Emitter, Manager, State};

use crate::AppState;

/// The `--launch` this process was started with, until the window takes it.
#[derive(Default)]
pub struct PendingLaunch(Mutex<Option<String>>);

impl PendingLaunch {
    pub fn from_args() -> Self {
        Self(Mutex::new(launch_arg(std::env::args())))
    }
}

/// `--launch <id>` or `--launch=<id>` among the arguments.
fn launch_arg(args: impl IntoIterator<Item = String>) -> Option<String> {
    let mut args = args.into_iter();
    while let Some(a) = args.next() {
        let id = if a == "--launch" {
            args.next()
        } else {
            a.strip_prefix("--launch=").map(str::to_string)
        };
        if let Some(id) = id.filter(|id| !id.is_empty()) {
            return Some(id);
        }
    }
    None
}

/// The window asks once it is listening: the instance a shortcut started us for.
#[tauri::command]
pub fn take_launch_request(pending: State<PendingLaunch>) -> Option<String> {
    pending.0.lock().unwrap().take()
}

/// Another start while we're open: bring the window forward, and if it came
/// from a shortcut, start that instance here.
pub fn second_start(app: &AppHandle, argv: Vec<String>) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
    }
    if let Some(id) = launch_arg(argv) {
        let _ = app.emit("launch-request", id);
    }
}

/// DESKTOP SHORTCUT in the instance editor: puts a shortcut on the desktop that
/// starts this instance. Returns where it went.
#[tauri::command]
pub fn create_shortcut(state: State<AppState>, profile_id: String) -> Result<String, String> {
    let name = state
        .profiles
        .lock()
        .unwrap()
        .profiles
        .iter()
        .find(|p| p.id == profile_id)
        .map(|p| p.name.clone())
        .ok_or("profile not found")?;
    let desktop = dirs::desktop_dir().filter(|d| d.is_dir()).ok_or("No desktop folder found.")?;
    let exe = launcher_exe()?;
    let path = write_shortcut(&desktop, &file_stem(&name), &name, &exe, &profile_id, &state.data_dir)?;
    Ok(path.display().to_string())
}

/// What the shortcut should start: the AppImage itself rather than its
/// short-lived mount, else this executable.
fn launcher_exe() -> Result<PathBuf, String> {
    #[cfg(target_os = "linux")]
    if let Some(img) = std::env::var_os("APPIMAGE") {
        return Ok(PathBuf::from(img));
    }
    std::env::current_exe().map_err(|e| format!("couldn't find the launcher: {e}"))
}

/// The instance name, safe as a file name everywhere.
fn file_stem(name: &str) -> String {
    let s: String = name
        .chars()
        .map(|c| if c.is_control() || r#"/\:*?"<>|"#.contains(c) { '-' } else { c })
        .collect();
    let s = s.trim().trim_matches('.').trim();
    if s.is_empty() { "Dusk instance".into() } else { s.chars().take(80).collect() }
}

/// `dir/stem.ext`, or `dir/stem (2).ext` and on when that's taken.
fn free_path(dir: &Path, stem: &str, ext: &str) -> PathBuf {
    (1..)
        .map(|n| {
            let s = if n == 1 { stem.to_string() } else { format!("{stem} ({n})") };
            dir.join(format!("{s}.{ext}"))
        })
        .find(|p| !p.exists())
        .unwrap()
}

/// macOS: a small app bundle whose script opens the launcher with `--launch`.
/// `open -n` starts a fresh copy even when one is running, so the
/// single-instance hand-off sees the arguments.
#[cfg(target_os = "macos")]
fn write_shortcut(dir: &Path, stem: &str, name: &str, exe: &Path, id: &str, _data: &Path) -> Result<PathBuf, String> {
    use std::os::unix::fs::PermissionsExt;
    let app = free_path(dir, stem, "app");
    let contents = app.join("Contents");
    let macos = contents.join("MacOS");
    std::fs::create_dir_all(&macos).map_err(|e| e.to_string())?;
    let bundle = exe.ancestors().find(|p| p.extension().is_some_and(|e| e == "app"));
    let script = match bundle {
        Some(b) => format!("#!/bin/sh\nexec open -n -a {} --args --launch {}\n", sh_quote(&b.to_string_lossy()), sh_quote(id)),
        None => format!("#!/bin/sh\nexec {} --launch {}\n", sh_quote(&exe.to_string_lossy()), sh_quote(id)),
    };
    let run = macos.join("launch");
    let write = || -> std::io::Result<()> {
        std::fs::write(&run, script)?;
        std::fs::set_permissions(&run, std::fs::Permissions::from_mode(0o755))?;
        let mut icon = String::new();
        if let Some(src) = bundle.map(|b| b.join("Contents/Resources/icon.icns")).filter(|p| p.is_file()) {
            let res = contents.join("Resources");
            std::fs::create_dir_all(&res)?;
            std::fs::copy(src, res.join("icon.icns"))?;
            icon = "<key>CFBundleIconFile</key><string>icon</string>".into();
        }
        std::fs::write(
            contents.join("Info.plist"),
            format!(
                r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleName</key><string>{name}</string>
<key>CFBundleExecutable</key><string>launch</string>
<key>CFBundleIdentifier</key><string>app.tryzwork.dusklauncher.shortcut.{id}</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>LSUIElement</key><true/>
{icon}
</dict></plist>
"#,
                name = xml_escape(name),
                id = xml_escape(id),
            ),
        )
    };
    if let Err(e) = write() {
        let _ = std::fs::remove_dir_all(&app);
        return Err(e.to_string());
    }
    Ok(app)
}

/// Windows: a .lnk made through the shell's own COM object, with the
/// launcher's icon. Values go in through the environment, never the script.
#[cfg(target_os = "windows")]
fn write_shortcut(dir: &Path, stem: &str, name: &str, exe: &Path, id: &str, _data: &Path) -> Result<PathBuf, String> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    let lnk = free_path(dir, stem, "lnk");
    let script = "$s = (New-Object -ComObject WScript.Shell).CreateShortcut($env:DUSK_LNK); \
        $s.TargetPath = $env:DUSK_EXE; $s.Arguments = $env:DUSK_ARGS; \
        $s.WorkingDirectory = Split-Path $env:DUSK_EXE; $s.IconLocation = \"$env:DUSK_EXE,0\"; \
        $s.Description = $env:DUSK_DESC; $s.Save()";
    let out = std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .env("DUSK_LNK", &lnk)
        .env("DUSK_EXE", exe)
        .env("DUSK_ARGS", format!("--launch {id}"))
        .env("DUSK_DESC", format!("Play {name} in DuskLauncher"))
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| format!("couldn't make the shortcut: {e}"))?;
    if !out.status.success() || !lnk.is_file() {
        return Err(format!("couldn't make the shortcut: {}", String::from_utf8_lossy(&out.stderr).trim()));
    }
    Ok(lnk)
}

/// Linux: a .desktop entry, wearing the launcher's icon from the data folder
/// (an AppImage installs none). Most desktops ask once before running one.
#[cfg(all(unix, not(target_os = "macos")))]
fn write_shortcut(dir: &Path, stem: &str, name: &str, exe: &Path, id: &str, data: &Path) -> Result<PathBuf, String> {
    use std::os::unix::fs::PermissionsExt;
    let icon = data.join("shortcut-icon.png");
    if !icon.is_file() {
        let _ = std::fs::write(&icon, include_bytes!("../icons/128x128.png"));
    }
    let file = free_path(dir, stem, "desktop");
    let exec = format!("{} --launch {}", desktop_quote(&exe.to_string_lossy()), desktop_quote(id));
    let one_line = |s: &str| s.replace(['\n', '\r'], " ");
    let body = format!(
        "[Desktop Entry]\nType=Application\nName={}\nComment=Play {} in DuskLauncher\nExec={}\nIcon={}\nTerminal=false\nCategories=Game;\n",
        one_line(name),
        one_line(name),
        exec,
        icon.display()
    );
    std::fs::write(&file, body).map_err(|e| e.to_string())?;
    let _ = std::fs::set_permissions(&file, std::fs::Permissions::from_mode(0o755));
    Ok(file)
}

#[cfg(target_os = "macos")]
fn sh_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r#"'\''"#))
}

#[cfg(target_os = "macos")]
fn xml_escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
}

/// An Exec= argument: double-quoted, with the characters the spec reserves
/// escaped, and `%` doubled so it isn't read as a field code.
#[cfg(all(unix, not(target_os = "macos")))]
fn desktop_quote(s: &str) -> String {
    let mut out = String::from("\"");
    for c in s.chars() {
        match c {
            '"' | '`' | '$' | '\\' => {
                out.push_str("\\\\");
                out.push(c);
            }
            '%' => out.push_str("%%"),
            _ => out.push(c),
        }
    }
    out.push('"');
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_launch_argument() {
        let args = |v: &[&str]| v.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        assert_eq!(launch_arg(args(&["dusk", "--launch", "p123"])), Some("p123".into()));
        assert_eq!(launch_arg(args(&["dusk", "--launch=p9"])), Some("p9".into()));
        assert_eq!(launch_arg(args(&["dusk", "--launch"])), None);
        assert_eq!(launch_arg(args(&["dusk"])), None);
    }

    #[cfg(unix)]
    #[test]
    fn writes_a_shortcut_that_launches_the_instance() {
        let dir = std::env::temp_dir().join(format!("dusk-shortcut-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let exe = Path::new("/opt/Dusk Launcher/dusk");
        let first = write_shortcut(&dir, "My \"pack\"", "My \"pack\"", exe, "p42", &dir).unwrap();
        let second = write_shortcut(&dir, "My \"pack\"", "My \"pack\"", exe, "p42", &dir).unwrap();
        assert_ne!(first, second, "a second shortcut doesn't overwrite the first");
        #[cfg(target_os = "macos")]
        let script = std::fs::read_to_string(first.join("Contents/MacOS/launch")).unwrap();
        #[cfg(not(target_os = "macos"))]
        let script = std::fs::read_to_string(&first).unwrap();
        assert!(script.contains("--launch") && script.contains("p42") && script.contains("/opt/Dusk Launcher/dusk"));
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn names_are_safe_files() {
        assert_eq!(file_stem("Fabric 1.21.11"), "Fabric 1.21.11");
        assert_eq!(file_stem("a/b:c?"), "a-b-c-");
        assert_eq!(file_stem(" .. "), "Dusk instance");
    }
}
