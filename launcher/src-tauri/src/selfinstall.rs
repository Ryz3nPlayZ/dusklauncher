//! Linux installs made by `scripts/install-linux.sh`: the .deb's program
//! unpacked into `~/.local/share/dusklauncher`. The updater plugin can't
//! update those (no AppImage to swap, and the .deb route runs
//! `pkexec dpkg -i`), so UPDATE runs the script again instead; it swaps the
//! folder, and the restart picks up the new binary at the same path.

/// Where the script is published; the launcher fetches it fresh each update.
#[cfg(target_os = "linux")]
const SCRIPT: &str = "https://raw.githubusercontent.com/Ryz3nPlayZ/dusklauncher/main/launcher/scripts/install-linux.sh";

/// Whether this launcher was installed by the script (it leaves
/// `installed-by-script` beside `bin/`).
#[tauri::command]
pub fn script_installed() -> bool {
    #[cfg(target_os = "linux")]
    {
        let Ok(exe) = std::env::current_exe() else { return false };
        exe.parent()
            .and_then(|bin| bin.parent())
            .is_some_and(|dir| dir.join("installed-by-script").is_file())
    }
    #[cfg(not(target_os = "linux"))]
    false
}

/// Downloads and runs the install script, which replaces this install with
/// the latest release. The caller restarts the launcher afterwards.
#[tauri::command]
pub async fn script_update() -> Result<(), String> {
    #[cfg(target_os = "linux")]
    {
        tauri::async_runtime::spawn_blocking(|| {
            // fetched to a file first: `curl | sh` would hide a failed download
            let run = format!(
                r#"f=$(mktemp) && curl -fsSL '{SCRIPT}' -o "$f" && sh "$f"; s=$?; rm -f "$f"; exit $s"#
            );
            let out = std::process::Command::new("sh")
                .arg("-c")
                .arg(run)
                .output()
                .map_err(|e| format!("couldn't run the updater: {e}"))?;
            if out.status.success() {
                return Ok(());
            }
            let err = String::from_utf8_lossy(&out.stderr);
            let last = err.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("the install script failed");
            Err(last.trim().to_string())
        })
        .await
        .map_err(|e| e.to_string())?
    }
    #[cfg(not(target_os = "linux"))]
    Err("only Linux installs made by the install script update this way".into())
}
