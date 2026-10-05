//! The Java installs already on this machine, for the instance JAVA picker:
//! the runtimes this launcher downloaded, the official launcher's, and the
//! usual JDK folders per OS. Each one's version comes from the `release`
//! file every JDK/JRE ships beside `bin/` — nothing is run.

use crate::appstate::AppState;
use serde::Serialize;
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use tauri::State;

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct JavaInstall {
    /// the java executable
    pub path: String,
    /// as the `release` file gives it, e.g. "21.0.2" or "1.8.0_392"
    pub version: String,
    pub major: u32,
    pub vendor: Option<String>,
    /// one of the runtimes this launcher downloads by itself
    pub bundled: bool,
}

const EXE: &str = if cfg!(target_os = "windows") { "java.exe" } else { "java" };

/// `KEY="value"` lines of a JDK's `release` file → (version, vendor).
fn read_release(text: &str) -> Option<(String, Option<String>)> {
    let field = |key: &str| {
        text.lines()
            .find_map(|l| l.strip_prefix(key)?.strip_prefix('='))
            .map(|v| v.trim().trim_matches('"').to_string())
            .filter(|v| !v.is_empty())
    };
    Some((field("JAVA_VERSION")?, field("IMPLEMENTOR")))
}

/// "1.8.0_392" → 8, "21.0.2" → 21, "25" → 25
fn major_of(version: &str) -> Option<u32> {
    let mut parts = version.split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

/// A Java home (the folder holding `bin/` and `release`) → its install.
fn probe_home(home: &Path, bundled: bool) -> Option<JavaInstall> {
    let exe = home.join("bin").join(EXE);
    if !exe.is_file() {
        return None;
    }
    let (version, vendor) = read_release(&std::fs::read_to_string(home.join("release")).ok()?)?;
    Some(JavaInstall {
        path: exe.to_string_lossy().into_owned(),
        major: major_of(&version)?,
        version,
        vendor,
        bundled,
    })
}

/// The homes a folder can hold: itself, a macOS bundle's `Contents/Home`,
/// or the layouts the Mojang runtimes use.
fn homes_in(dir: &Path) -> Vec<PathBuf> {
    vec![
        dir.to_path_buf(),
        dir.join("Contents").join("Home"),
        dir.join("jre.bundle").join("Contents").join("Home"),
        dir.join("libexec").join("openjdk.jdk").join("Contents").join("Home"),
    ]
}

fn children(dir: &Path) -> Vec<PathBuf> {
    std::fs::read_dir(dir)
        .map(|rd| rd.flatten().map(|e| e.path()).filter(|p| p.is_dir()).collect())
        .unwrap_or_default()
}

/// Folders whose children are Java installs.
fn system_parents() -> Vec<PathBuf> {
    let home = dirs::home_dir().unwrap_or_default();
    let mut out = vec![home.join(".jdks"), home.join(".sdkman").join("candidates").join("java")];
    if cfg!(target_os = "macos") {
        out.extend([
            PathBuf::from("/Library/Java/JavaVirtualMachines"),
            home.join("Library").join("Java").join("JavaVirtualMachines"),
            PathBuf::from("/opt/homebrew/opt"),
            PathBuf::from("/usr/local/opt"),
        ]);
    } else if cfg!(target_os = "windows") {
        for root in ["ProgramFiles", "ProgramFiles(x86)"].iter().filter_map(std::env::var_os) {
            let root = PathBuf::from(root);
            for vendor in ["Java", "Eclipse Adoptium", "Microsoft", "Zulu", "BellSoft", "Amazon Corretto", "Semeru"] {
                out.push(root.join(vendor));
            }
        }
    } else {
        out.extend(["/usr/lib/jvm", "/usr/lib64/jvm", "/usr/java", "/opt"].map(PathBuf::from));
    }
    out
}

/// The official launcher's runtimes: `runtime/<component>/<platform>/<component>`.
fn official_runtimes() -> Vec<PathBuf> {
    let Some(mc) = (if cfg!(target_os = "macos") {
        dirs::data_dir().map(|d| d.join("minecraft"))
    } else if cfg!(target_os = "windows") {
        dirs::data_dir().map(|d| d.join(".minecraft"))
    } else {
        dirs::home_dir().map(|d| d.join(".minecraft"))
    }) else {
        return Vec::new();
    };
    children(&mc.join("runtime"))
        .iter()
        .flat_map(|component| children(component))
        .flat_map(|platform| children(&platform))
        .collect()
}

fn scan(runtimes: &Path) -> Vec<JavaInstall> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    let mut add = |dir: &Path, bundled: bool| {
        for home in homes_in(dir) {
            if let Some(found) = probe_home(&home, bundled) {
                let key = std::fs::canonicalize(&found.path).unwrap_or_else(|_| PathBuf::from(&found.path));
                if seen.insert(key) {
                    out.push(found);
                }
                return;
            }
        }
    };
    for dir in children(runtimes) {
        add(&dir, true);
    }
    for dir in official_runtimes() {
        add(&dir, false);
    }
    if let Some(home) = std::env::var_os("JAVA_HOME") {
        add(Path::new(&home), false);
    }
    for parent in system_parents() {
        for dir in children(&parent) {
            add(&dir, false);
        }
    }
    out.sort_by(|a, b| b.major.cmp(&a.major).then(b.bundled.cmp(&a.bundled)));
    out
}

#[tauri::command]
pub async fn list_javas(state: State<'_, AppState>) -> Result<Vec<JavaInstall>, String> {
    let runtimes = state.data_dir.join("runtimes");
    tokio::task::spawn_blocking(move || scan(&runtimes)).await.map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_and_vendors() {
        let text = "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_RUNTIME_VERSION=\"21.0.2+13\"\nJAVA_VERSION=\"21.0.2\"\n";
        assert_eq!(read_release(text), Some(("21.0.2".into(), Some("Eclipse Adoptium".into()))));
        assert_eq!(read_release("JAVA_VERSION=\"1.8.0_392\""), Some(("1.8.0_392".into(), None)));
        assert_eq!(read_release("IMPLEMENTOR=\"x\""), None);
        assert_eq!(major_of("1.8.0_392"), Some(8));
        assert_eq!(major_of("21.0.2"), Some(21));
        assert_eq!(major_of("25"), Some(25));
        assert_eq!(major_of("17-ea"), Some(17));
    }

    #[test]
    fn finds_a_mac_bundle_and_a_plain_home() {
        let tmp = std::env::temp_dir().join(format!("dusk-javas-{}", std::process::id()));
        let mac = tmp.join("runtimes").join("java-runtime-delta").join("jre.bundle").join("Contents").join("Home");
        let plain = tmp.join("runtimes").join("jre-legacy");
        for (home, v) in [(&mac, "21.0.7"), (&plain, "1.8.0_51")] {
            std::fs::create_dir_all(home.join("bin")).unwrap();
            std::fs::write(home.join("bin").join(EXE), b"").unwrap();
            std::fs::write(home.join("release"), format!("JAVA_VERSION=\"{v}\"\n")).unwrap();
        }
        let found = scan(&tmp.join("runtimes"));
        let ours: Vec<_> = found.iter().filter(|j| j.bundled).map(|j| j.major).collect();
        assert_eq!(ours, [21, 8]);
        let _ = std::fs::remove_dir_all(&tmp);
    }
}
