pub mod auth;
pub mod download;
pub mod fabric;
pub mod java;
pub mod launch;
pub mod meta;
pub mod modrinth;
pub mod natives;
pub mod pool;
pub mod neoforge;
pub mod profile;

use thiserror::Error;

#[derive(Debug, Error)]
pub enum Error {
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("sha1 mismatch for {path}: expected {expected}, got {actual}")]
    Checksum { path: String, expected: String, actual: String },
    #[error("auth error: {0}")]
    Auth(String),
    #[error("version {0} not found in manifest")]
    VersionNotFound(String),
    #[error("{0}")]
    Other(String),
}

pub type Result<T> = std::result::Result<T, Error>;

/// Write `bytes` to `path` through a temporary file beside it, so a crash or
/// a full disk mid-write leaves the old file instead of half of the new one.
pub fn write_atomic(path: &std::path::Path, bytes: &[u8]) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let mut tmp = path.as_os_str().to_owned();
    tmp.push(".tmp");
    let tmp = std::path::PathBuf::from(tmp);
    std::fs::write(&tmp, bytes)?;
    std::fs::rename(&tmp, path)
}

#[cfg(test)]
mod tests {
    #[test]
    fn write_atomic_replaces_the_file_and_leaves_no_temp() {
        let dir = std::env::temp_dir().join(format!("fl-atomic-{}", std::process::id()));
        let path = dir.join("nested").join("profiles.json");
        super::write_atomic(&path, b"one").unwrap();
        super::write_atomic(&path, b"two").unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), b"two");
        assert!(!dir.join("nested").join("profiles.json.tmp").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
