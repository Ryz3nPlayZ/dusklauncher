pub mod auth;
pub mod download;
pub mod fabric;
pub mod java;
pub mod launch;
pub mod meta;
pub mod modrinth;
pub mod natives;
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
