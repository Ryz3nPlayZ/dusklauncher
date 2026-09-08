//! End-to-end boot test: real Mojang downloads, real JVM spawn, real game boot.
//!
//! This is the only test that proves the launcher can actually play the
//! game — unit tests cover arg assembly, this covers the assembled whole.
//! It mirrors `install_profile` in `src-tauri/src/commands.rs`; if that
//! orchestration changes, mirror the change here.
//!
//! Ignored by default (downloads ~1 GB, opens a real game window, needs
//! network). Run it:
//!
//! ```sh
//! cargo test -p fasterlauncher-core --test boot -- --ignored --nocapture
//! ```
//!
//! Env: `DUSK_TEST_VERSION` (default `1.21`), `DUSK_BOOT_ROOT`
//! (default `<tmp>/dusk-boottest`). Downloads are checksum-cached, so
//! re-runs only fetch what is missing.

use fasterlauncher_core::{auth, download, java, launch, meta, natives, profile};
use std::path::PathBuf;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, BufReader};

/// Log lines that prove progressive boot stages.
const USER_MARKER: &str = "Setting user:";
const BOOT_MARKER: &str = "Sound engine started";
/// Substrings that mean the JVM or game died before booting.
const DEATH_MARKERS: &[&str] = &[
    "Unrecognized option",
    "Unrecognized VM option",
    "Could not create the Java Virtual Machine",
    "Could not find or load main class",
    "A fatal error has been detected",
    "Minecraft Crash Report",
];

fn root() -> PathBuf {
    std::env::var("DUSK_BOOT_ROOT")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::temp_dir().join("dusk-boottest"))
}

async fn pump<R: tokio::io::AsyncRead + Unpin>(
    reader: BufReader<R>,
    tx: tokio::sync::mpsc::Sender<String>,
) {
    let mut reader = reader;
    let mut line = String::new();
    loop {
        line.clear();
        match reader.read_line(&mut line).await {
            Ok(0) | Err(_) => break,
            Ok(_) => {
                if tx.send(line.trim_end().to_string()).await.is_err() {
                    break;
                }
            }
        }
    }
}

#[tokio::test]
#[ignore]
async fn boot_to_menu() {
    let version_id = std::env::var("DUSK_TEST_VERSION").unwrap_or_else(|_| "1.21".into());
    let data_dir = root();
    println!("boot root: {}", data_dir.display());

    let client = reqwest::Client::builder()
        .user_agent("DuskLauncher/0.2.0")
        .build()
        .unwrap();

    let prof = profile::Profile {
        id: "boottest".into(),
        name: "boottest".into(),
        game_version: version_id.clone(),
        loader: profile::Loader::Vanilla,
        loader_version: None,
        jvm_args: profile::default_jvm_args(),
        resolution: (1280, 720),
        mod_filenames: vec![],
        server: None,
        created_at: 0,
        last_played: None,
    };
    let dirs = prof.dirs(&data_dir);

    // ── version ──
    let manifest = meta::VersionManifest::fetch(&client).await.unwrap();
    let version = meta::fetch_version_json(&client, &manifest, &version_id, &dirs.versions)
        .await
        .unwrap();
    println!("version {} java {:?}", version.id, version.java_version);

    // ── libraries ──
    let libs: Vec<download::Download> = version
        .libraries
        .iter()
        .filter(|l| meta::library_allowed(l))
        .filter_map(|l| {
            let artifact = l.downloads.as_ref()?.artifact.as_ref()?;
            Some(download::Download {
                url: artifact.url.clone(),
                dest: dirs.libraries.join(&artifact.path),
                sha1: Some(artifact.sha1.clone()),
                size: Some(artifact.size),
            })
        })
        .collect();
    println!("libraries: {}", libs.len());
    download::download_all(&client, libs, 12, |_| {}).await.unwrap();

    // ── client jar ──
    let client_art = version.downloads.client.clone().unwrap();
    download::download_one(
        &client,
        &download::Download {
            url: client_art.url,
            dest: dirs.versions.join(format!("{}.jar", version.id)),
            sha1: Some(client_art.sha1),
            size: Some(client_art.size),
        },
    )
    .await
    .unwrap();

    // ── assets ──
    if let Some(idx) = &version.asset_index {
        tokio::fs::create_dir_all(dirs.assets.join("indexes")).await.unwrap();
        let index_path = dirs.assets.join("indexes").join(format!("{}.json", idx.id));
        if !index_path.exists() {
            let text = client.get(&idx.url).send().await.unwrap().text().await.unwrap();
            tokio::fs::write(&index_path, &text).await.unwrap();
        }
        let index: serde_json::Value =
            serde_json::from_str(&tokio::fs::read_to_string(&index_path).await.unwrap()).unwrap();
        let mut asset_downloads = Vec::new();
        for obj in index["objects"].as_object().unwrap().values() {
            let hash = obj["hash"].as_str().unwrap_or_default().to_string();
            if hash.len() < 2 {
                continue;
            }
            asset_downloads.push(download::Download {
                url: format!("https://resources.download.minecraft.net/{}/{}", &hash[..2], hash),
                dest: dirs.assets.join("objects").join(&hash[..2]).join(&hash),
                sha1: Some(hash.clone()),
                size: Some(obj["size"].as_u64().unwrap_or(0)),
            });
        }
        println!("assets: {}", asset_downloads.len());
        download::download_all(&client, asset_downloads, 16, |_| {}).await.unwrap();
    }

    // ── natives (legacy classifiers; modern versions need none) ──
    let natives_dir = dirs.versions.join(format!("{}-natives", version.id));
    let n = natives::install_natives(&client, &version, &dirs.libraries, &natives_dir)
        .await
        .unwrap();
    println!("natives jars extracted: {n}");

    // ── java ──
    java::provision(&client, &version.effective_java().component, &dirs.runtimes, |_| {})
        .await
        .unwrap();
    let java_bin = java::java_executable(&java::runtime_dir(&dirs.runtimes, &version.effective_java().component));
    println!("java: {}", java_bin.display());
    assert!(java_bin.exists(), "provisioned java binary missing");

    // ── spec + spawn ──
    let session = auth::Session {
        access_token: String::new(),
        expires_at: u64::MAX,
        uuid: "00000000-0000-0000-0000-000000000000".into(),
        username: "Player".into(),
        xuid: String::new(),
        refresh_token: String::new(),
    };
    let spec = launch::build_launch_spec(
        &java_bin,
        &version,
        &prof,
        &dirs,
        &natives_dir,
        &session,
        &launch::LaunchEnv::default(),
    );
    println!("cmd: {} {}", java_bin.display(), spec.jvm_args.join(" "));
    let mut child = launch::launch(&spec, &launch::LaunchEnv::default()).await.unwrap();

    // ── watch the log for boot or death ──
    let (tx, mut rx) = tokio::sync::mpsc::channel::<String>(512);
    if let Some(out) = child.stdout.take() {
        let tx = tx.clone();
        tokio::spawn(async move { pump(BufReader::new(out), tx).await });
    }
    if let Some(err) = child.stderr.take() {
        let tx = tx.clone();
        tokio::spawn(async move { pump(BufReader::new(err), tx).await });
    }
    drop(tx);

    let mut saw_user = false;
    let booted = tokio::time::timeout(Duration::from_secs(360), async {
        while let Some(line) = rx.recv().await {
            println!("[game] {line}");
            if line.contains(USER_MARKER) {
                saw_user = true;
            }
            if line.contains(BOOT_MARKER) {
                return true;
            }
            if DEATH_MARKERS.iter().any(|m| line.contains(m)) {
                panic!("game died during boot: {line}");
            }
        }
        false
    })
    .await
    .unwrap_or(false);

    // Reap either way: success means the menu is up — kill it, the test
    // proved what it came to prove.
    let _ = child.kill().await;
    let _ = child.wait().await;

    assert!(saw_user, "game never logged '{USER_MARKER}' — auth args broken");
    assert!(booted, "game did not reach '{BOOT_MARKER}' within 6 minutes");
    println!("BOOT OK: {version_id} reached the menu");
}
