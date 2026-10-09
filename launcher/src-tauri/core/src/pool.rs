//! Mod, resource pack and shader pack files kept once for every instance,
//! named by their sha1. Ten instances that all run Sodium and Fabric API
//! hold hard links to one copy each instead of downloading and storing
//! them ten times. Only files with a known hash and an archive extension
//! go in: configs and other files a game rewrites in place never do, and a
//! jar is only ever replaced by renaming over it, which leaves the pool's
//! copy as it was.

use crate::download::Download;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

/// How long a pooled file no instance links to stays for a later install.
const UNUSED_KEEP: Duration = Duration::from_secs(30 * 24 * 60 * 60);

fn pooled(pool: &Path, dl: &Download) -> Option<PathBuf> {
    let sha1 = dl.sha1.as_deref()?;
    if sha1.len() != 40 || !sha1.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let ext = dl.dest.extension()?.to_str()?.to_ascii_lowercase();
    let dir = dl.dest.parent()?.file_name()?.to_str()?;
    let shared = matches!(dir, "mods" | "resourcepacks" | "shaderpacks") && matches!(ext.as_str(), "jar" | "zip");
    shared.then(|| pool.join(sha1.to_ascii_lowercase()))
}

/// Link pooled copies into place for downloads whose file is missing. They
/// stay in the list: the download pass still checks each one against its
/// hash and fetches it again if the pooled copy turned out wrong.
pub fn fill_from(pool: &Path, downloads: &[Download]) -> usize {
    let mut linked = 0;
    for dl in downloads {
        let Some(src) = pooled(pool, dl) else { continue };
        if dl.dest.exists() || !src.is_file() {
            continue;
        }
        if let Some(parent) = dl.dest.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if std::fs::hard_link(&src, &dl.dest).is_ok() || std::fs::copy(&src, &dl.dest).is_ok() {
            linked += 1;
        }
    }
    linked
}

/// Pool the files a finished download pass left in place, then drop pooled
/// files nothing has used for a while. A pool on another disk from the
/// instance is skipped rather than filled with copies.
pub fn add_from(pool: &Path, downloads: &[Download]) {
    if std::fs::create_dir_all(pool).is_err() {
        return;
    }
    for dl in downloads {
        let Some(dst) = pooled(pool, dl) else { continue };
        if same_file(&dl.dest, &dst) {
            continue;
        }
        // linked under a temporary name and renamed, so a pooled copy that
        // failed its check is replaced by the good one whole
        let tmp = dst.with_extension("tmp");
        let _ = std::fs::remove_file(&tmp);
        if std::fs::hard_link(&dl.dest, &tmp).is_ok() && std::fs::rename(&tmp, &dst).is_err() {
            let _ = std::fs::remove_file(&tmp);
        }
    }
    prune(pool, SystemTime::now());
}

#[cfg(unix)]
fn same_file(a: &Path, b: &Path) -> bool {
    use std::os::unix::fs::MetadataExt;
    match (std::fs::metadata(a), std::fs::metadata(b)) {
        (Ok(a), Ok(b)) => a.dev() == b.dev() && a.ino() == b.ino(),
        _ => false,
    }
}

#[cfg(not(unix))]
fn same_file(_: &Path, _: &Path) -> bool {
    false
}

/// Whether some instance still links to this pooled file. Where the link
/// count can't be read, every file counts as unused once it's old: removing
/// a pool link never touches an instance's own.
#[cfg(unix)]
fn in_use(meta: &std::fs::Metadata) -> bool {
    use std::os::unix::fs::MetadataExt;
    meta.nlink() > 1
}

#[cfg(not(unix))]
fn in_use(_: &std::fs::Metadata) -> bool {
    false
}

fn prune(pool: &Path, now: SystemTime) {
    let Ok(rd) = std::fs::read_dir(pool) else { return };
    for e in rd.flatten() {
        let Ok(meta) = e.metadata() else { continue };
        let old = meta.modified().ok().and_then(|m| now.duration_since(m).ok()).is_some_and(|age| age > UNUSED_KEEP);
        if meta.is_file() && old && !in_use(&meta) {
            let _ = std::fs::remove_file(e.path());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dl(dest: PathBuf, sha1: &str) -> Download {
        Download { url: String::new(), dest, sha1: Some(sha1.into()), size: None }
    }

    #[test]
    fn a_second_instance_takes_a_pooled_jar_instead_of_fetching_it() {
        let root = std::env::temp_dir().join(format!("dusk-pool-fill-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let pool = root.join("pool");
        let sha = "0123456789abcdef0123456789abcdef01234567";
        let a = dl(root.join("a/mods/sodium.jar"), sha);
        std::fs::create_dir_all(a.dest.parent().unwrap()).unwrap();
        std::fs::write(&a.dest, b"jar").unwrap();
        // configs are never shared, whatever their hash
        let cfg = dl(root.join("a/config/sodium.json"), "1111111111111111111111111111111111111111");
        std::fs::create_dir_all(cfg.dest.parent().unwrap()).unwrap();
        std::fs::write(&cfg.dest, b"{}").unwrap();
        add_from(&pool, &[a.clone(), cfg]);
        assert!(pool.join(sha).is_file());
        assert_eq!(std::fs::read_dir(&pool).unwrap().count(), 1);

        let b = dl(root.join("b/mods/sodium.jar"), sha);
        assert_eq!(fill_from(&pool, std::slice::from_ref(&b)), 1);
        assert_eq!(std::fs::read(&b.dest).unwrap(), b"jar");
        // already in place: nothing to link
        assert_eq!(fill_from(&pool, &[b]), 0);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[cfg(unix)]
    #[test]
    fn a_pooled_file_goes_once_nothing_has_used_it_for_a_month() {
        let root = std::env::temp_dir().join(format!("dusk-pool-prune-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let pool = root.join("pool");
        let sha = "0123456789abcdef0123456789abcdef01234567";
        let a = dl(root.join("a/mods/x.jar"), sha);
        std::fs::create_dir_all(a.dest.parent().unwrap()).unwrap();
        std::fs::write(&a.dest, b"jar").unwrap();
        add_from(&pool, std::slice::from_ref(&a));
        let later = SystemTime::now() + UNUSED_KEEP + Duration::from_secs(60);
        prune(&pool, later);
        assert!(pool.join(sha).is_file(), "still linked from an instance");
        std::fs::remove_file(&a.dest).unwrap();
        prune(&pool, SystemTime::now());
        assert!(pool.join(sha).is_file(), "unused, but recently");
        prune(&pool, later);
        assert!(!pool.join(sha).exists());
        let _ = std::fs::remove_dir_all(&root);
    }
}
