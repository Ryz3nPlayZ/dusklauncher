//! Dusk cosmetics service (docs/COSMETICS.md §6, phase 3 + 5).
//!
//! One small HTTP API in front of one SQLite file:
//!
//! * **who you are** — the launcher proves a Minecraft account the same way
//!   a server does: it calls Mojang's `join` with the game's access token and
//!   a random server id, we call `hasJoined` with the same id. No Microsoft
//!   secrets ever reach this box. The answer is a bearer token.
//! * **what you own / can spend** — coins and purchases live only here. The
//!   catalog with prices is compiled in (`catalog.json`): animated capes cost
//!   750, still ones 500. Coins come from redeem codes (one use per account).
//! * **what you wear** — the launcher pushes the loadout after every equip
//!   (only owned ids are accepted); every Dusk client asks `/v1/loadout/<uuid>`
//!   for the players around it, so Dusk users see each other.
//!
//! * **who brought you** — every account gets a referral code. A new account
//!   (first seen within `REFERRAL_WINDOW`) can name the code of whoever
//!   invited it, once; both are paid on the new player's first game launch
//!   (`POST /v1/me/launched`), so a sign-in alone earns nothing. Every coin
//!   movement lands in `ledger`.
//!
//! * **cracked players** — an offline account has no Mojang session to
//!   check, so its name is held by the first device that claims it (a
//!   random key the launcher keeps); see [`offline_sign_in`]. It gets the
//!   same features, plus a skin hosted here that Dusk clients draw on it.
//!
//! Config is all env: `DUSK_DB` (sqlite path), `DUSK_BIND` (host:port),
//! `DUSK_CODES` (extra `code:coins,...` on top of the built-in ones),
//! `DUSK_DEV_AUTH=1` (enables `/v1/auth/dev`, never in production).

use axum::{
    extract::{Path, Query, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{delete, get, post, put},
    Json, Router,
};
use rand::RngCore;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

mod quests;

const CATALOG_JSON: &str = include_str!("../catalog.json");
const PRICE_ANIMATED: i64 = 750;
const PRICE_STILL: i64 = 500;
/// Built-in codes (one use per account). Extra codes come from `DUSK_CODES`.
const BUILTIN_CODES: &[(&str, i64)] = &[
    ("yourewelcome", 1000),
    ("yourewelcomeagain", 1000),
    ("wowyouregreedy", 1000),
    ("leavemealone", 1500),
    ("zemuiscool", 999_999_999),
];
const TOKEN_TTL: Duration = Duration::from_secs(90 * 24 * 3600);
/// What a referral pays, once the new player has launched the game.
const REFERRER_REWARD: i64 = 500;
const REFEREE_REWARD: i64 = 250;
/// How long after its first sign-in an account may still name a referrer.
const REFERRAL_WINDOW: Duration = Duration::from_secs(7 * 24 * 3600);
/// Paid referrals per referrer; past this the new player is still paid.
const MAX_PAID_REFERRALS: i64 = 100;
/// Referral codes: no 0/O, 1/I/L, so they survive being read aloud.
const CODE_ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const CODE_LEN: usize = 8;
/// An account is "online" if it made an authed call within this window.
/// The launcher heartbeats (`POST /v1/me/presence`) well inside it.
const ONLINE_WINDOW: i64 = 120;
/// What `presence` accepts as the game being played ("1.21.4").
const PLAYING_MAX_LEN: usize = 32;
/// Outgoing requests one account may have pending at once.
const MAX_PENDING_REQUESTS: i64 = 50;
/// One conversation page: the first fetch returns the newest this many.
const MESSAGE_PAGE: i64 = 200;
const MESSAGE_MAX_LEN: usize = 1000;
const MESSAGE_RATE_WINDOW: i64 = 10;
const MESSAGE_RATE_MAX: i64 = 20;
/// What `presence` accepts as the server address ("mc.example.net:25565").
const SERVER_MAX_LEN: usize = 64;
/// Saved outfits per account.
const MAX_OUTFITS: i64 = 20;
const OUTFIT_NAME_MAX: usize = 32;
/// Chat images (screenshots): size, daily uploads, and how long they're kept.
const IMAGE_MAX_BYTES: usize = 8 * 1024 * 1024;
const IMAGE_DAILY_MAX: i64 = 30;
const IMAGE_TTL: i64 = 90 * 24 * 3600;
/// Shared instances: a small .mrpack (Modrinth links plus configs), how many
/// one account shares a day, and how long a code works.
const PACK_MAX_BYTES: usize = 4 * 1024 * 1024;
const PACK_DAILY_MAX: i64 = 20;
const PACK_TTL: i64 = 30 * 24 * 3600;
/// The synced client settings blob (HUD layout, module options, menu prefs).
const SETTINGS_MAX_BYTES: usize = 64 * 1024;
/// A skin upload: a vanilla 64x64 (or legacy 64x32) PNG is a few KB.
const SKIN_MAX_BYTES: usize = 32 * 1024;

// ── catalog ────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct CatalogFile {
    #[serde(default)]
    capes: Vec<CatalogItem>,
    #[serde(default)]
    accessories: Vec<CatalogItem>,
}

#[derive(Deserialize, Clone)]
struct CatalogItem {
    id: u32,
    name: String,
    #[serde(default)]
    animated: bool,
    /// Overrides the animated/still default price when set.
    #[serde(default)]
    price: Option<i64>,
}

#[derive(Serialize, Clone)]
struct PricedItem {
    id: u32,
    kind: &'static str,
    name: String,
    animated: bool,
    price: i64,
}

fn load_catalog() -> BTreeMap<u32, PricedItem> {
    let file: CatalogFile = serde_json::from_str(CATALOG_JSON).expect("catalog.json is valid");
    let price = |i: &CatalogItem| i.price.unwrap_or(if i.animated { PRICE_ANIMATED } else { PRICE_STILL });
    let mut out = BTreeMap::new();
    for c in &file.capes {
        out.insert(c.id, PricedItem { id: c.id, kind: "cape", name: c.name.clone(), animated: c.animated, price: price(c) });
    }
    for a in &file.accessories {
        out.insert(a.id, PricedItem { id: a.id, kind: "accessory", name: a.name.clone(), animated: a.animated, price: price(a) });
    }
    out
}

// ── state / db ─────────────────────────────────────────────────────────────

struct App {
    db: Mutex<Connection>,
    http: reqwest::Client,
    catalog: BTreeMap<u32, PricedItem>,
    codes: BTreeMap<String, i64>,
    dev_auth: bool,
}

type Shared = Arc<App>;

fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs() as i64
}

fn open_db(path: &str) -> Connection {
    let db = Connection::open(path).expect("open sqlite");
    db.execute_batch(
        "PRAGMA journal_mode=WAL;
         PRAGMA foreign_keys=ON;
         CREATE TABLE IF NOT EXISTS accounts (
           uuid TEXT PRIMARY KEY, username TEXT NOT NULL, coins INTEGER NOT NULL DEFAULT 0,
           created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS tokens (
           hash TEXT PRIMARY KEY, uuid TEXT NOT NULL REFERENCES accounts(uuid),
           created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS owned (
           uuid TEXT NOT NULL REFERENCES accounts(uuid), item INTEGER NOT NULL,
           acquired_at INTEGER NOT NULL, PRIMARY KEY (uuid, item));
         CREATE TABLE IF NOT EXISTS loadouts (
           uuid TEXT PRIMARY KEY REFERENCES accounts(uuid), json TEXT NOT NULL, updated_at INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS redemptions (
           uuid TEXT NOT NULL REFERENCES accounts(uuid), code TEXT NOT NULL,
           redeemed_at INTEGER NOT NULL, PRIMARY KEY (uuid, code));
         CREATE TABLE IF NOT EXISTS ledger (
           id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, delta INTEGER NOT NULL,
           reason TEXT NOT NULL, at INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS referral_codes (
           uuid TEXT PRIMARY KEY REFERENCES accounts(uuid), code TEXT NOT NULL UNIQUE);
         CREATE TABLE IF NOT EXISTS referrals (
           referee TEXT PRIMARY KEY REFERENCES accounts(uuid), referrer TEXT NOT NULL REFERENCES accounts(uuid),
           claimed_at INTEGER NOT NULL, paid_at INTEGER);
         CREATE INDEX IF NOT EXISTS referrals_by_referrer ON referrals (referrer);
         CREATE TABLE IF NOT EXISTS friend_requests (
           id INTEGER PRIMARY KEY AUTOINCREMENT,
           from_uuid TEXT NOT NULL REFERENCES accounts(uuid), to_uuid TEXT NOT NULL REFERENCES accounts(uuid),
           created_at INTEGER NOT NULL, UNIQUE (from_uuid, to_uuid));
         CREATE INDEX IF NOT EXISTS friend_requests_to ON friend_requests (to_uuid);
         CREATE TABLE IF NOT EXISTS friendships (
           a TEXT NOT NULL REFERENCES accounts(uuid), b TEXT NOT NULL REFERENCES accounts(uuid),
           created_at INTEGER NOT NULL, PRIMARY KEY (a, b));
         CREATE TABLE IF NOT EXISTS messages (
           id INTEGER PRIMARY KEY AUTOINCREMENT,
           from_uuid TEXT NOT NULL REFERENCES accounts(uuid), to_uuid TEXT NOT NULL REFERENCES accounts(uuid),
           body TEXT NOT NULL, sent_at INTEGER NOT NULL);
         CREATE INDEX IF NOT EXISTS messages_pair ON messages (from_uuid, to_uuid, sent_at);
         CREATE TABLE IF NOT EXISTS message_reads (
           reader TEXT NOT NULL REFERENCES accounts(uuid), other TEXT NOT NULL REFERENCES accounts(uuid),
           last_read_id INTEGER NOT NULL, PRIMARY KEY (reader, other));
         CREATE TABLE IF NOT EXISTS blocks (
           blocker TEXT NOT NULL REFERENCES accounts(uuid), blocked TEXT NOT NULL REFERENCES accounts(uuid),
           created_at INTEGER NOT NULL, PRIMARY KEY (blocker, blocked));
         CREATE TABLE IF NOT EXISTS outfits (
           id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL REFERENCES accounts(uuid),
           name TEXT NOT NULL, json TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE (uuid, name));
         CREATE TABLE IF NOT EXISTS images (
           id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES accounts(uuid), mime TEXT NOT NULL,
           bytes BLOB NOT NULL, created_at INTEGER NOT NULL);
         CREATE INDEX IF NOT EXISTS images_by_owner ON images (owner, created_at);
         CREATE TABLE IF NOT EXISTS client_settings (
           uuid TEXT PRIMARY KEY REFERENCES accounts(uuid), json TEXT NOT NULL, updated_at INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS skins (
           uuid TEXT PRIMARY KEY REFERENCES accounts(uuid), png BLOB NOT NULL, slim INTEGER NOT NULL,
           hash TEXT NOT NULL, updated_at INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS shared_packs (
           code TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES accounts(uuid),
           bytes BLOB NOT NULL, created_at INTEGER NOT NULL);
         CREATE INDEX IF NOT EXISTS shared_packs_by_owner ON shared_packs (owner, created_at);",
    )
    .expect("schema");
    add_column(&db, "accounts", "launches", "INTEGER NOT NULL DEFAULT 0");
    add_column(&db, "accounts", "playing", "TEXT");
    add_column(&db, "accounts", "server", "TEXT");
    // privacy: NULL = visible, else when "appear offline" went on (what
    // friends see as last seen from then on)
    add_column(&db, "accounts", "hidden_since", "INTEGER");
    add_column(&db, "accounts", "share_activity", "INTEGER NOT NULL DEFAULT 1");
    add_column(&db, "accounts", "friend_requests", "TEXT NOT NULL DEFAULT 'everyone'");
    // offline (cracked) accounts: 1, and the hash of the key that holds the name
    add_column(&db, "accounts", "offline", "INTEGER NOT NULL DEFAULT 0");
    add_column(&db, "accounts", "device_key", "TEXT");
    add_column(&db, "messages", "kind", "TEXT NOT NULL DEFAULT 'text'");
    add_column(&db, "messages", "meta", "TEXT");
    quests::migrate(&db);
    db
}

/// `ALTER TABLE ... ADD COLUMN` for databases created before the column
/// existed; a no-op once it is there.
fn add_column(db: &Connection, table: &str, column: &str, decl: &str) {
    let exists: bool = db
        .query_row(
            &format!("SELECT 1 FROM pragma_table_info('{table}') WHERE name = ?1"),
            params![column],
            |_| Ok(true),
        )
        .optional()
        .expect("table info")
        .unwrap_or(false);
    if !exists {
        db.execute_batch(&format!("ALTER TABLE {table} ADD COLUMN {column} {decl}")).expect("migrate");
    }
}

// ── errors ─────────────────────────────────────────────────────────────────

#[derive(Debug)]
struct ApiError(StatusCode, String);

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({ "error": self.1 }))).into_response()
    }
}

fn bad(msg: impl Into<String>) -> ApiError {
    ApiError(StatusCode::BAD_REQUEST, msg.into())
}

fn unauthorized() -> ApiError {
    ApiError(StatusCode::UNAUTHORIZED, "sign in again".into())
}

impl From<rusqlite::Error> for ApiError {
    fn from(e: rusqlite::Error) -> Self {
        tracing::error!("sqlite: {e}");
        ApiError(StatusCode::INTERNAL_SERVER_ERROR, "database error".into())
    }
}

type ApiResult<T> = Result<Json<T>, ApiError>;

// ── auth ───────────────────────────────────────────────────────────────────

fn token_hash(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

/// `d8b1…` (no dashes, as Mojang returns it) → `d8b1xxxx-xxxx-…` lowercase.
fn dashed_uuid(raw: &str) -> Option<String> {
    let s: String = raw.chars().filter(|c| *c != '-').collect::<String>().to_lowercase();
    if s.len() != 32 || !s.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    Some(format!("{}-{}-{}-{}-{}", &s[0..8], &s[8..12], &s[12..16], &s[16..20], &s[20..32]))
}

fn issue_token(db: &Connection, uuid: &str, username: &str) -> Result<String, rusqlite::Error> {
    let t = now();
    db.execute(
        "INSERT INTO accounts (uuid, username, coins, created_at, last_seen) VALUES (?1, ?2, 0, ?3, ?3)
         ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, last_seen = excluded.last_seen",
        params![uuid, username, t],
    )?;
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    let token = hex::encode(bytes);
    db.execute(
        "INSERT INTO tokens (hash, uuid, created_at, expires_at) VALUES (?1, ?2, ?3, ?4)",
        params![token_hash(&token), uuid, t, t + TOKEN_TTL.as_secs() as i64],
    )?;
    // keep the table from growing forever
    db.execute("DELETE FROM tokens WHERE expires_at < ?1", params![t])?;
    Ok(token)
}

/// Bearer token → account uuid.
fn authed(app: &App, headers: &HeaderMap) -> Result<String, ApiError> {
    let raw = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .map(str::trim)
        .filter(|t| !t.is_empty())
        .ok_or_else(unauthorized)?;
    let db = app.db.lock().unwrap();
    let uuid: Option<String> = db
        .query_row(
            "SELECT uuid FROM tokens WHERE hash = ?1 AND expires_at > ?2",
            params![token_hash(raw), now()],
            |r| r.get(0),
        )
        .optional()?;
    let uuid = uuid.ok_or_else(unauthorized)?;
    db.execute("UPDATE accounts SET last_seen = ?1 WHERE uuid = ?2", params![now(), uuid])?;
    Ok(uuid)
}

#[derive(Deserialize)]
struct MinecraftAuth {
    username: String,
    #[serde(rename = "serverId")]
    server_id: String,
}

#[derive(Serialize)]
struct AuthOk {
    token: String,
    uuid: String,
    username: String,
}

/// The launcher already called `sessionserver.mojang.com/session/minecraft/join`
/// with this `serverId`; `hasJoined` tells us which account did.
async fn auth_minecraft(State(app): State<Shared>, Json(body): Json<MinecraftAuth>) -> ApiResult<AuthOk> {
    let name = body.username.trim();
    let sid = body.server_id.trim();
    if name.is_empty() || name.len() > 16 || sid.is_empty() || sid.len() > 64 || !sid.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
        return Err(bad("username and serverId required"));
    }
    let resp = app
        .http
        .get("https://sessionserver.mojang.com/session/minecraft/hasJoined")
        .query(&[("username", name), ("serverId", sid)])
        .send()
        .await
        .map_err(|e| ApiError(StatusCode::BAD_GATEWAY, format!("mojang unreachable: {e}")))?;
    if resp.status() != StatusCode::OK {
        return Err(ApiError(StatusCode::UNAUTHORIZED, "Mojang did not confirm this session".into()));
    }
    let profile: Value = resp
        .json()
        .await
        .map_err(|_| ApiError(StatusCode::BAD_GATEWAY, "unreadable mojang reply".into()))?;
    let uuid = profile
        .get("id")
        .and_then(Value::as_str)
        .and_then(dashed_uuid)
        .ok_or_else(|| ApiError(StatusCode::BAD_GATEWAY, "mojang reply had no id".into()))?;
    let username = profile.get("name").and_then(Value::as_str).unwrap_or(name).to_string();
    let token = issue_token(&app.db.lock().unwrap(), &uuid, &username)?;
    tracing::info!("auth ok: {username} ({uuid})");
    Ok(Json(AuthOk { token, uuid, username }))
}

#[derive(Deserialize)]
struct DevAuth {
    uuid: String,
    username: String,
}

/// Local testing only (`DUSK_DEV_AUTH=1`): mint a token for any uuid.
async fn auth_dev(State(app): State<Shared>, Json(body): Json<DevAuth>) -> ApiResult<AuthOk> {
    if !app.dev_auth {
        return Err(ApiError(StatusCode::NOT_FOUND, "not found".into()));
    }
    let uuid = dashed_uuid(&body.uuid).ok_or_else(|| bad("bad uuid"))?;
    let token = issue_token(&app.db.lock().unwrap(), &uuid, body.username.trim())?;
    Ok(Json(AuthOk { token, uuid, username: body.username }))
}

// ── offline (cracked) accounts ─────────────────────────────────────────────

/// A name as vanilla allows it: 3–16 of `A-Z a-z 0-9 _`.
fn valid_name(name: &str) -> bool {
    (3..=16).contains(&name.len()) && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// The uuid an offline-mode server gives `name` (Java's
/// `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`), so the account is the
/// same player the game and other Dusk clients see.
fn offline_uuid(name: &str) -> String {
    use md5::{Digest as _, Md5};
    let mut b: [u8; 16] = Md5::digest(format!("OfflinePlayer:{name}").as_bytes()).into();
    b[6] = (b[6] & 0x0f) | 0x30; // version 3
    b[8] = (b[8] & 0x3f) | 0x80; // IETF variant
    dashed_uuid(&hex::encode(b)).expect("32 hex digits")
}

fn is_offline(db: &Connection, uuid: &str) -> Result<bool, rusqlite::Error> {
    Ok(db
        .query_row("SELECT offline FROM accounts WHERE uuid = ?1", params![uuid], |r| r.get::<_, i64>(0))
        .optional()?
        .is_some_and(|o| o != 0))
}

#[derive(Deserialize)]
struct OfflineAuth {
    username: String,
    /// the device's secret: random, kept by the launcher, sent on every sign-in
    key: String,
}

/// Sign in an offline account. There's no Mojang session to check, so the
/// first device to claim a name holds it: later sign-ins must bring the
/// same key. A name a Minecraft (premium) account uses on Dusk can't be
/// claimed, and names are unique whatever their case.
fn offline_sign_in(db: &Connection, name: &str, key: &str) -> Result<AuthOk, ApiError> {
    if !valid_name(name) {
        return Err(bad("Offline names are 3–16 letters, digits or _."));
    }
    if !(32..=128).contains(&key.len()) || !key.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(bad("bad device key"));
    }
    let taken = || ApiError(StatusCode::CONFLICT, format!("{name} is taken on Dusk — pick another offline name."));
    let premium: bool = db
        .query_row("SELECT 1 FROM accounts WHERE offline = 0 AND username = ?1 COLLATE NOCASE", params![name], |_| Ok(true))
        .optional()?
        .unwrap_or(false);
    if premium {
        return Err(taken());
    }
    let uuid = offline_uuid(name);
    let other_case: bool = db
        .query_row(
            "SELECT 1 FROM accounts WHERE offline = 1 AND username = ?1 COLLATE NOCASE AND uuid != ?2",
            params![name, uuid],
            |_| Ok(true),
        )
        .optional()?
        .unwrap_or(false);
    if other_case {
        return Err(taken());
    }
    let hash = token_hash(key);
    let held: Option<Option<String>> = db
        .query_row("SELECT device_key FROM accounts WHERE uuid = ?1", params![uuid], |r| r.get(0))
        .optional()?;
    match held {
        Some(Some(k)) if k == hash => {}
        Some(_) => return Err(taken()),
        None => {
            let t = now();
            db.execute(
                "INSERT INTO accounts (uuid, username, coins, created_at, last_seen, offline, device_key)
                 VALUES (?1, ?2, 0, ?3, ?3, 1, ?4)",
                params![uuid, name, t, hash],
            )?;
            tracing::info!("offline account claimed: {name} ({uuid})");
        }
    }
    let token = issue_token(db, &uuid, name)?;
    Ok(AuthOk { token, uuid, username: name.to_string() })
}

async fn auth_offline(State(app): State<Shared>, Json(body): Json<OfflineAuth>) -> ApiResult<AuthOk> {
    let db = app.db.lock().unwrap();
    Ok(Json(offline_sign_in(&db, body.username.trim(), body.key.trim())?))
}

// ── skins (offline accounts) ───────────────────────────────────────────────

/// Width and height of a PNG, from its header.
fn png_size(png: &[u8]) -> Option<(u32, u32)> {
    if png.len() < 24 || &png[..8] != b"\x89PNG\r\n\x1a\n" || &png[12..16] != b"IHDR" {
        return None;
    }
    let be = |i: usize| u32::from_be_bytes([png[i], png[i + 1], png[i + 2], png[i + 3]]);
    Some((be(16), be(20)))
}

#[derive(Deserialize)]
struct SkinQuery {
    #[serde(default)]
    model: Option<String>,
}

#[derive(Serialize)]
struct SkinInfo {
    model: &'static str,
    hash: String,
}

/// An offline account's skin, which Dusk clients draw on it wherever its
/// profile carries none (offline-mode servers, LAN, singleplayer).
/// Minecraft accounts change theirs at Mojang instead.
async fn put_skin(
    State(app): State<Shared>,
    headers: HeaderMap,
    Query(q): Query<SkinQuery>,
    body: axum::body::Bytes,
) -> ApiResult<SkinInfo> {
    let uuid = authed(&app, &headers)?;
    let slim = match q.model.as_deref().unwrap_or("classic") {
        "slim" => true,
        "classic" => false,
        _ => return Err(bad("model is slim or classic")),
    };
    if body.len() > SKIN_MAX_BYTES || !matches!(png_size(&body), Some((64, 64)) | Some((64, 32))) {
        return Err(bad("A skin is a 64×64 (or 64×32) PNG."));
    }
    let db = app.db.lock().unwrap();
    if !is_offline(&db, &uuid)? {
        return Err(ApiError(StatusCode::FORBIDDEN, "Minecraft accounts change their skin at Mojang.".into()));
    }
    let hash = hex::encode(Sha256::digest(&body));
    db.execute(
        "INSERT INTO skins (uuid, png, slim, hash, updated_at) VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(uuid) DO UPDATE SET png = excluded.png, slim = excluded.slim, hash = excluded.hash,
           updated_at = excluded.updated_at",
        params![uuid, body.as_ref(), slim as i64, hash, now()],
    )?;
    Ok(Json(SkinInfo { model: if slim { "slim" } else { "classic" }, hash }))
}

async fn delete_skin(State(app): State<Shared>, headers: HeaderMap) -> Result<StatusCode, ApiError> {
    let uuid = authed(&app, &headers)?;
    app.db.lock().unwrap().execute("DELETE FROM skins WHERE uuid = ?1", params![uuid])?;
    Ok(StatusCode::NO_CONTENT)
}

/// The skin by player name, the way Ely.by's skin system answers it: the
/// PNG, with its model in `X-Skin-Model`. Only offline accounts have one,
/// and none while a Minecraft account on Dusk uses the same name. A uuid
/// works too (the launcher's friend list knows those, not names).
fn skin_by_name(db: &Connection, name: &str) -> Result<Option<(Vec<u8>, bool, String)>, rusqlite::Error> {
    if let Some(uuid) = dashed_uuid(name) {
        let username: Option<String> = db
            .query_row("SELECT username FROM accounts WHERE uuid = ?1 AND offline = 1", params![uuid], |r| r.get(0))
            .optional()?;
        return match username {
            Some(n) => skin_by_name(db, &n),
            None => Ok(None),
        };
    }
    if !valid_name(name) {
        return Ok(None);
    }
    db.query_row(
        "SELECT s.png, s.slim, s.hash FROM skins s JOIN accounts a ON a.uuid = s.uuid
         WHERE a.offline = 1 AND a.username = ?1 COLLATE NOCASE
           AND NOT EXISTS (SELECT 1 FROM accounts p WHERE p.offline = 0 AND p.username = ?1 COLLATE NOCASE)",
        params![name],
        |r| Ok((r.get(0)?, r.get::<_, i64>(1)? != 0, r.get(2)?)),
    )
    .optional()
}

async fn get_skin(State(app): State<Shared>, Path(name): Path<String>) -> Result<Response, ApiError> {
    let name = name.trim_end_matches(".png");
    let found = skin_by_name(&app.db.lock().unwrap(), name)?;
    let Some((png, slim, hash)) = found else {
        return Ok((StatusCode::NOT_FOUND, [(header::CACHE_CONTROL, "public, max-age=60")], Json(json!({ "error": "no skin" })))
            .into_response());
    };
    Ok((
        [
            (header::CONTENT_TYPE, "image/png".to_string()),
            (header::CACHE_CONTROL, "public, max-age=60".to_string()),
            (header::ETAG, format!("\"{hash}\"")),
            (header::HeaderName::from_static("x-skin-model"), if slim { "slim" } else { "classic" }.to_string()),
        ],
        png,
    )
        .into_response())
}

// ── account ────────────────────────────────────────────────────────────────

#[derive(Serialize)]
struct Me {
    uuid: String,
    username: String,
    coins: i64,
    owned: Vec<u32>,
    loadout: Map<String, Value>,
    /// a cracked account (no Microsoft sign-in behind it)
    offline: bool,
}

fn read_me(db: &Connection, uuid: &str) -> Result<Me, ApiError> {
    let (username, coins, offline): (String, i64, bool) = db
        .query_row("SELECT username, coins, offline FROM accounts WHERE uuid = ?1", params![uuid], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? != 0))
        })
        .optional()?
        .ok_or_else(unauthorized)?;
    Ok(Me { uuid: uuid.to_string(), username, coins, owned: owned_ids(db, uuid)?, loadout: read_loadout(db, uuid)?, offline })
}

fn owned_ids(db: &Connection, uuid: &str) -> Result<Vec<u32>, rusqlite::Error> {
    let mut st = db.prepare("SELECT item FROM owned WHERE uuid = ?1 ORDER BY item")?;
    let rows = st.query_map(params![uuid], |r| r.get::<_, i64>(0))?;
    Ok(rows.filter_map(Result::ok).filter_map(|n| u32::try_from(n).ok()).collect())
}

fn read_loadout(db: &Connection, uuid: &str) -> Result<Map<String, Value>, rusqlite::Error> {
    let json: Option<String> = db
        .query_row("SELECT json FROM loadouts WHERE uuid = ?1", params![uuid], |r| r.get(0))
        .optional()?;
    Ok(json
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default())
}

async fn me(State(app): State<Shared>, headers: HeaderMap) -> ApiResult<Me> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    Ok(Json(read_me(&db, &uuid)?))
}

#[derive(Deserialize)]
struct Redeem {
    code: String,
}

#[derive(Serialize)]
struct Redeemed {
    granted: i64,
    coins: i64,
}

async fn redeem(State(app): State<Shared>, headers: HeaderMap, Json(body): Json<Redeem>) -> ApiResult<Redeemed> {
    let uuid = authed(&app, &headers)?;
    let code = body.code.trim().to_lowercase();
    let Some(&amount) = app.codes.get(&code) else {
        return Err(ApiError(StatusCode::NOT_FOUND, "that code does not exist".into()));
    };
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    let inserted = tx.execute(
        "INSERT OR IGNORE INTO redemptions (uuid, code, redeemed_at) VALUES (?1, ?2, ?3)",
        params![uuid, code, now()],
    )?;
    if inserted == 0 {
        return Err(ApiError(StatusCode::CONFLICT, "you already used that code".into()));
    }
    tx.execute("UPDATE accounts SET coins = coins + ?1 WHERE uuid = ?2", params![amount, uuid])?;
    tx.execute(
        "INSERT INTO ledger (uuid, delta, reason, at) VALUES (?1, ?2, ?3, ?4)",
        params![uuid, amount, format!("redeem:{code}"), now()],
    )?;
    let coins: i64 = tx.query_row("SELECT coins FROM accounts WHERE uuid = ?1", params![uuid], |r| r.get(0))?;
    tx.commit()?;
    tracing::info!("{uuid} redeemed {code} (+{amount}) → {coins}");
    Ok(Json(Redeemed { granted: amount, coins }))
}

#[derive(Deserialize)]
struct Buy {
    id: u32,
}

#[derive(Serialize)]
struct Bought {
    coins: i64,
    owned: Vec<u32>,
}

async fn buy(State(app): State<Shared>, headers: HeaderMap, Json(body): Json<Buy>) -> ApiResult<Bought> {
    let uuid = authed(&app, &headers)?;
    let item = app.catalog.get(&body.id).ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "no such cosmetic".into()))?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    let already: bool = tx
        .query_row("SELECT 1 FROM owned WHERE uuid = ?1 AND item = ?2", params![uuid, item.id], |_| Ok(true))
        .optional()?
        .unwrap_or(false);
    if already {
        return Err(ApiError(StatusCode::CONFLICT, "you already own that".into()));
    }
    let coins: i64 = tx.query_row("SELECT coins FROM accounts WHERE uuid = ?1", params![uuid], |r| r.get(0))?;
    if coins < item.price {
        return Err(ApiError(
            StatusCode::PAYMENT_REQUIRED,
            format!("{} costs {} coins; you have {coins}", item.name, item.price),
        ));
    }
    tx.execute("UPDATE accounts SET coins = coins - ?1 WHERE uuid = ?2", params![item.price, uuid])?;
    tx.execute("INSERT INTO owned (uuid, item, acquired_at) VALUES (?1, ?2, ?3)", params![uuid, item.id, now()])?;
    tx.execute(
        "INSERT INTO ledger (uuid, delta, reason, at) VALUES (?1, ?2, ?3, ?4)",
        params![uuid, -item.price, format!("buy:{}", item.id), now()],
    )?;
    let owned = owned_ids(&tx, &uuid)?;
    tx.commit()?;
    tracing::info!("{uuid} bought {} for {}", item.id, item.price);
    Ok(Json(Bought { coins: coins - item.price, owned }))
}

/// The ids a loadout wears, whatever the slot (`cape: 7`, `accessories: [16]`).
fn loadout_ids(loadout: &Map<String, Value>) -> Result<BTreeSet<u32>, ApiError> {
    let mut out = BTreeSet::new();
    let mut push = |v: &Value| -> Result<(), ApiError> {
        let n = v.as_u64().and_then(|n| u32::try_from(n).ok()).ok_or_else(|| bad("loadout ids must be non-negative integers"))?;
        out.insert(n);
        Ok(())
    };
    for (slot, v) in loadout {
        if slot == "settings" {
            if !v.is_object() {
                return Err(bad("loadout.settings must be an object"));
            }
            continue;
        }
        match v {
            Value::Array(items) => {
                for i in items {
                    push(i)?;
                }
            }
            other => push(other)?,
        }
    }
    Ok(out)
}

async fn put_loadout(
    State(app): State<Shared>,
    headers: HeaderMap,
    Json(loadout): Json<Map<String, Value>>,
) -> ApiResult<Map<String, Value>> {
    let uuid = authed(&app, &headers)?;
    if loadout.len() > 32 {
        return Err(bad("too many slots"));
    }
    let wanted = loadout_ids(&loadout)?;
    let db = app.db.lock().unwrap();
    let have: BTreeSet<u32> = owned_ids(&db, &uuid)?.into_iter().collect();
    if let Some(missing) = wanted.difference(&have).next() {
        let name = app.catalog.get(missing).map(|i| i.name.as_str()).unwrap_or("that");
        return Err(ApiError(StatusCode::FORBIDDEN, format!("you don't own {name}")));
    }
    db.execute(
        "INSERT INTO loadouts (uuid, json, updated_at) VALUES (?1, ?2, ?3)
         ON CONFLICT(uuid) DO UPDATE SET json = excluded.json, updated_at = excluded.updated_at",
        params![uuid, Value::Object(loadout.clone()).to_string(), now()],
    )?;
    Ok(Json(loadout))
}

// ── referrals ──────────────────────────────────────────────────────────────

/// This account's referral code, minted on first ask.
fn referral_code(db: &Connection, uuid: &str) -> Result<String, rusqlite::Error> {
    let have: Option<String> = db
        .query_row("SELECT code FROM referral_codes WHERE uuid = ?1", params![uuid], |r| r.get(0))
        .optional()?;
    if let Some(code) = have {
        return Ok(code);
    }
    let mut rng = rand::thread_rng();
    loop {
        let code: String = (0..CODE_LEN)
            .map(|_| CODE_ALPHABET[(rng.next_u32() as usize) % CODE_ALPHABET.len()] as char)
            .collect();
        // a collision (31^8 codes) just draws again
        if db.execute("INSERT OR IGNORE INTO referral_codes (uuid, code) VALUES (?1, ?2)", params![uuid, code])? == 1 {
            return Ok(code);
        }
    }
}

fn grant(db: &Connection, uuid: &str, amount: i64, reason: &str) -> Result<(), rusqlite::Error> {
    db.execute("UPDATE accounts SET coins = coins + ?1 WHERE uuid = ?2", params![amount, uuid])?;
    db.execute(
        "INSERT INTO ledger (uuid, delta, reason, at) VALUES (?1, ?2, ?3, ?4)",
        params![uuid, amount, reason, now()],
    )?;
    Ok(())
}

/// Pay out `referee`'s referral if it is claimed, unpaid and the referee has
/// launched the game. Returns what the referee got.
fn settle_referral(db: &Connection, referee: &str) -> Result<i64, rusqlite::Error> {
    let pending: Option<String> = db
        .query_row(
            "SELECT r.referrer FROM referrals r JOIN accounts a ON a.uuid = r.referee
             WHERE r.referee = ?1 AND r.paid_at IS NULL AND a.launches > 0",
            params![referee],
            |r| r.get(0),
        )
        .optional()?;
    let Some(referrer) = pending else { return Ok(0) };
    let paid: i64 = db.query_row(
        "SELECT COUNT(*) FROM referrals WHERE referrer = ?1 AND paid_at IS NOT NULL",
        params![referrer],
        |r| r.get(0),
    )?;
    grant(db, referee, REFEREE_REWARD, &format!("referred-by:{referrer}"))?;
    // a cracked account is free to make, so inviting doesn't pay it
    if paid < MAX_PAID_REFERRALS && !is_offline(db, &referrer)? {
        grant(db, &referrer, REFERRER_REWARD, &format!("referral:{referee}"))?;
    }
    db.execute("UPDATE referrals SET paid_at = ?1 WHERE referee = ?2", params![now(), referee])?;
    tracing::info!("referral paid: {referrer} brought {referee}");
    Ok(REFEREE_REWARD)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Referral {
    /// share this
    code: String,
    /// who invited this account, once claimed
    referred_by: Option<String>,
    /// whether that referral has paid out yet
    referral_paid: bool,
    /// whether this account may still claim a code
    can_claim: bool,
    /// accounts that named this one's code / of those, how many paid out
    invited: i64,
    paid: i64,
    referrer_reward: i64,
    referee_reward: i64,
    coins: i64,
}

fn read_referral(db: &Connection, uuid: &str) -> Result<Referral, ApiError> {
    let code = referral_code(db, uuid)?;
    let (created_at, coins, offline): (i64, i64, bool) = db
        .query_row("SELECT created_at, coins, offline FROM accounts WHERE uuid = ?1", params![uuid], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? != 0))
        })
        .optional()?
        .ok_or_else(unauthorized)?;
    let mine: Option<(String, bool)> = db
        .query_row(
            "SELECT a.username, r.paid_at IS NOT NULL FROM referrals r JOIN accounts a ON a.uuid = r.referrer
             WHERE r.referee = ?1",
            params![uuid],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let (invited, paid): (i64, i64) = db.query_row(
        "SELECT COUNT(*), COUNT(paid_at) FROM referrals WHERE referrer = ?1",
        params![uuid],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    Ok(Referral {
        code,
        can_claim: !offline && mine.is_none() && now() - created_at <= REFERRAL_WINDOW.as_secs() as i64,
        referral_paid: mine.as_ref().is_some_and(|m| m.1),
        referred_by: mine.map(|m| m.0),
        invited,
        paid,
        referrer_reward: REFERRER_REWARD,
        referee_reward: REFEREE_REWARD,
        coins,
    })
}

async fn get_referral(State(app): State<Shared>, headers: HeaderMap) -> ApiResult<Referral> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    Ok(Json(read_referral(&db, &uuid)?))
}

#[derive(Deserialize)]
struct ClaimReferral {
    code: String,
}

/// Name who invited you. Pays out now if this account has already
/// launched the game, else on its first launch.
async fn claim_referral(
    State(app): State<Shared>,
    headers: HeaderMap,
    Json(body): Json<ClaimReferral>,
) -> ApiResult<Referral> {
    let uuid = authed(&app, &headers)?;
    let code = body.code.trim().to_uppercase();
    if code.is_empty() || code.len() > 16 {
        return Err(bad("enter a referral code"));
    }
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    let referrer: String = tx
        .query_row("SELECT uuid FROM referral_codes WHERE code = ?1", params![code], |r| r.get(0))
        .optional()?
        .ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "that referral code does not exist".into()))?;
    if referrer == uuid {
        return Err(bad("that is your own code"));
    }
    let status = read_referral(&tx, &uuid)?;
    if status.referred_by.is_some() {
        return Err(ApiError(StatusCode::CONFLICT, "you already entered a referral code".into()));
    }
    if is_offline(&tx, &uuid)? {
        return Err(ApiError(StatusCode::FORBIDDEN, "Referral codes need a Microsoft account.".into()));
    }
    if !status.can_claim {
        return Err(ApiError(StatusCode::FORBIDDEN, "referral codes are for new accounts only".into()));
    }
    // two accounts naming each other would pay twice for one friendship
    let reverse: bool = tx
        .query_row("SELECT 1 FROM referrals WHERE referee = ?1 AND referrer = ?2", params![referrer, uuid], |_| Ok(true))
        .optional()?
        .unwrap_or(false);
    if reverse {
        return Err(bad("you invited them"));
    }
    tx.execute(
        "INSERT INTO referrals (referee, referrer, claimed_at) VALUES (?1, ?2, ?3)",
        params![uuid, referrer, now()],
    )?;
    settle_referral(&tx, &uuid)?;
    let out = read_referral(&tx, &uuid)?;
    tx.commit()?;
    Ok(Json(out))
}

#[derive(Serialize)]
struct Launched {
    launches: i64,
    /// coins a referral paid this account just now (0 if none)
    granted: i64,
    coins: i64,
}

/// The launcher reports each game launch; the first one settles a
/// pending referral.
async fn launched(State(app): State<Shared>, headers: HeaderMap) -> ApiResult<Launched> {
    let uuid = authed(&app, &headers)?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    tx.execute("UPDATE accounts SET launches = launches + 1 WHERE uuid = ?1", params![uuid])?;
    let granted = settle_referral(&tx, &uuid)?;
    let (launches, coins): (i64, i64) = tx.query_row(
        "SELECT launches, coins FROM accounts WHERE uuid = ?1",
        params![uuid],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    tx.commit()?;
    Ok(Json(Launched { launches, granted, coins }))
}

// ── friends ────────────────────────────────────────────────────────────────

/// Canonical, order-independent key for a friendship row.
fn friend_pair(a: &str, b: &str) -> (String, String) {
    if a <= b { (a.to_string(), b.to_string()) } else { (b.to_string(), a.to_string()) }
}

fn are_friends(db: &Connection, a: &str, b: &str) -> Result<bool, rusqlite::Error> {
    let (x, y) = friend_pair(a, b);
    Ok(db
        .query_row("SELECT 1 FROM friendships WHERE a = ?1 AND b = ?2", params![x, y], |_| Ok(true))
        .optional()?
        .unwrap_or(false))
}

#[derive(Serialize)]
struct FriendEntry {
    uuid: String,
    username: String,
    online: bool,
    /// The game version they're in — only while online.
    #[serde(skip_serializing_if = "Option::is_none")]
    playing: Option<String>,
    /// The multiplayer server they're on — only while online and playing.
    #[serde(skip_serializing_if = "Option::is_none")]
    server: Option<String>,
    #[serde(rename = "lastSeen")]
    last_seen: i64,
    /// Messages from them this account hasn't fetched yet.
    unread: i64,
    /// a cracked account
    offline: bool,
}

/// What a friend is allowed to see of an account's presence, after its
/// privacy settings: "appear offline" freezes last seen at the moment it
/// went on; not sharing activity hides the game and the server.
struct Presence {
    online: bool,
    last_seen: i64,
    playing: Option<String>,
    server: Option<String>,
}

fn visible_presence(
    last_seen: i64,
    hidden_since: Option<i64>,
    share_activity: bool,
    playing: Option<String>,
    server: Option<String>,
) -> Presence {
    let last_seen = hidden_since.map_or(last_seen, |h| h.min(last_seen));
    let online = hidden_since.is_none() && now() - last_seen <= ONLINE_WINDOW;
    let playing = playing.filter(|_| online && share_activity);
    let server = server.filter(|_| playing.is_some());
    Presence { online, last_seen, playing, server }
}

fn read_friends(db: &Connection, uuid: &str) -> Result<Vec<FriendEntry>, ApiError> {
    let mut st = db.prepare(
        "SELECT acc.uuid, acc.username, acc.last_seen, acc.playing, acc.server, acc.hidden_since, acc.share_activity,
                (SELECT COUNT(*) FROM messages m WHERE m.from_uuid = acc.uuid AND m.to_uuid = ?1
                   AND m.id > COALESCE((SELECT last_read_id FROM message_reads WHERE reader = ?1 AND other = acc.uuid), 0)),
                acc.offline
         FROM friendships f JOIN accounts acc ON acc.uuid = CASE WHEN f.a = ?1 THEN f.b ELSE f.a END
         WHERE f.a = ?1 OR f.b = ?1",
    )?;
    let mut out = st
        .query_map(params![uuid], |r| {
            let p = visible_presence(r.get(2)?, r.get(5)?, r.get::<_, i64>(6)? != 0, r.get(3)?, r.get(4)?);
            Ok(FriendEntry {
                uuid: r.get(0)?,
                username: r.get(1)?,
                online: p.online,
                playing: p.playing,
                server: p.server,
                last_seen: p.last_seen,
                unread: r.get(7)?,
                offline: r.get::<_, i64>(8)? != 0,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    // who you can talk to right now leads, then alphabetical
    out.sort_by(|a, b| {
        b.online.cmp(&a.online).then_with(|| a.username.to_lowercase().cmp(&b.username.to_lowercase()))
    });
    Ok(out)
}

fn friend_uuids(db: &Connection, uuid: &str) -> Result<BTreeSet<String>, rusqlite::Error> {
    let mut st = db.prepare("SELECT CASE WHEN a = ?1 THEN b ELSE a END FROM friendships WHERE a = ?1 OR b = ?1")?;
    let out = st.query_map(params![uuid], |r| r.get(0))?.collect();
    out
}

async fn list_friends(State(app): State<Shared>, headers: HeaderMap) -> ApiResult<Vec<FriendEntry>> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    Ok(Json(read_friends(&db, &uuid)?))
}

#[derive(Deserialize)]
struct PresenceBody {
    /// The game version being played, or absent when the game isn't running.
    #[serde(default)]
    playing: Option<String>,
    /// The multiplayer server the game is connected to, if any.
    #[serde(default)]
    server: Option<String>,
}

#[derive(Serialize)]
struct SocialSummary {
    /// Incoming friend requests waiting on this account.
    requests: i64,
    /// Unread messages across every conversation.
    unread: i64,
    /// Friends online right now.
    online: i64,
}

/// The launcher's heartbeat. `authed` already bumps `last_seen`, which is
/// what keeps the account "online" for its friends; this also records what
/// it's playing and answers with the counts the status pill badges — one
/// small call instead of polling the full lists.
async fn presence(
    State(app): State<Shared>,
    headers: HeaderMap,
    Json(body): Json<PresenceBody>,
) -> ApiResult<SocialSummary> {
    let uuid = authed(&app, &headers)?;
    let playing = body
        .playing
        .map(|s| {
            s.chars()
                .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | ' '))
                .take(PLAYING_MAX_LEN)
                .collect::<String>()
                .trim()
                .to_string()
        })
        .filter(|s| !s.is_empty());
    let server = body.server.as_deref().and_then(clean_server).filter(|_| playing.is_some());
    let db = app.db.lock().unwrap();
    db.execute("UPDATE accounts SET playing = ?1, server = ?2 WHERE uuid = ?3", params![playing, server, uuid])?;
    let friends = read_friends(&db, &uuid)?;
    let requests: i64 =
        db.query_row("SELECT COUNT(*) FROM friend_requests WHERE to_uuid = ?1", params![uuid], |r| r.get(0))?;
    Ok(Json(SocialSummary {
        requests,
        unread: friends.iter().map(|f| f.unread).sum(),
        online: friends.iter().filter(|f| f.online).count() as i64,
    }))
}

/// A server address as typed into the multiplayer screen: host name or IP,
/// optional port. Anything else (spaces, schemes, paths) is refused.
fn clean_server(raw: &str) -> Option<String> {
    let s = raw.trim().to_lowercase();
    let ok = !s.is_empty()
        && s.len() <= SERVER_MAX_LEN
        && s.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | ':' | '[' | ']'));
    ok.then_some(s)
}

async fn remove_friend(State(app): State<Shared>, headers: HeaderMap, Path(raw): Path<String>) -> ApiResult<Vec<FriendEntry>> {
    let uuid = authed(&app, &headers)?;
    let target = dashed_uuid(&raw).ok_or_else(|| bad("bad uuid"))?;
    let db = app.db.lock().unwrap();
    let (a, b) = friend_pair(&uuid, &target);
    db.execute("DELETE FROM friendships WHERE a = ?1 AND b = ?2", params![a, b])?;
    Ok(Json(read_friends(&db, &uuid)?))
}

#[derive(Serialize)]
struct FriendRequestEntry {
    id: i64,
    uuid: String,
    username: String,
    #[serde(rename = "createdAt")]
    created_at: i64,
}

#[derive(Serialize)]
struct FriendRequests {
    incoming: Vec<FriendRequestEntry>,
    outgoing: Vec<FriendRequestEntry>,
}

fn read_requests(db: &Connection, uuid: &str) -> Result<FriendRequests, ApiError> {
    let load = |sql: &str| -> Result<Vec<FriendRequestEntry>, ApiError> {
        let mut st = db.prepare(sql)?;
        let rows = st.query_map(params![uuid], |r| {
            Ok(FriendRequestEntry { id: r.get(0)?, uuid: r.get(1)?, username: r.get(2)?, created_at: r.get(3)? })
        })?;
        Ok(rows.filter_map(Result::ok).collect())
    };
    let incoming = load(
        "SELECT fr.id, fr.from_uuid, a.username, fr.created_at FROM friend_requests fr
         JOIN accounts a ON a.uuid = fr.from_uuid WHERE fr.to_uuid = ?1 ORDER BY fr.created_at DESC",
    )?;
    let outgoing = load(
        "SELECT fr.id, fr.to_uuid, a.username, fr.created_at FROM friend_requests fr
         JOIN accounts a ON a.uuid = fr.to_uuid WHERE fr.from_uuid = ?1 ORDER BY fr.created_at DESC",
    )?;
    Ok(FriendRequests { incoming, outgoing })
}

async fn list_friend_requests(State(app): State<Shared>, headers: HeaderMap) -> ApiResult<FriendRequests> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    Ok(Json(read_requests(&db, &uuid)?))
}

#[derive(Deserialize)]
struct FriendRequestBody {
    #[serde(default)]
    uuid: Option<String>,
    #[serde(default)]
    username: Option<String>,
}

fn no_such_player() -> ApiError {
    ApiError(StatusCode::NOT_FOUND, "No Dusk player by that name — they need to sign in to Dusk Launcher once first.".into())
}

/// Look up an existing Dusk account by uuid or username — never invites
/// someone who has never signed in.
fn resolve_account(db: &Connection, body: &FriendRequestBody) -> Result<String, ApiError> {
    if let Some(raw) = body.uuid.as_deref() {
        let uuid = dashed_uuid(raw).ok_or_else(|| bad("bad uuid"))?;
        let exists: bool = db
            .query_row("SELECT 1 FROM accounts WHERE uuid = ?1", params![uuid], |_| Ok(true))
            .optional()?
            .unwrap_or(false);
        return if exists { Ok(uuid) } else { Err(no_such_player()) };
    }
    if let Some(name) = body.username.as_deref() {
        let name = name.trim();
        if name.is_empty() {
            return Err(bad("username or uuid required"));
        }
        // a renamed account keeps its old name here until it signs in again,
        // so a name can briefly match two rows — the most recently seen wins
        return db
            .query_row(
                "SELECT uuid FROM accounts WHERE username = ?1 COLLATE NOCASE ORDER BY offline, last_seen DESC LIMIT 1",
                params![name],
                |r| r.get(0),
            )
            .optional()?
            .ok_or_else(no_such_player);
    }
    Err(bad("username or uuid required"))
}

/// Accept a request on the recipient's behalf, turning it into a friendship.
fn accept_request_tx(db: &Connection, id: i64, acceptor: &str) -> Result<(), ApiError> {
    let row: Option<(String, String)> = db
        .query_row("SELECT from_uuid, to_uuid FROM friend_requests WHERE id = ?1", params![id], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?;
    let (from_uuid, to_uuid) = row.ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "That request is no longer pending.".into()))?;
    if to_uuid != acceptor {
        return Err(ApiError(StatusCode::FORBIDDEN, "not your request".into()));
    }
    let (a, b) = friend_pair(&from_uuid, &to_uuid);
    db.execute("INSERT OR IGNORE INTO friendships (a, b, created_at) VALUES (?1, ?2, ?3)", params![a, b, now()])?;
    db.execute("DELETE FROM friend_requests WHERE id = ?1", params![id])?;
    Ok(())
}

async fn send_friend_request(
    State(app): State<Shared>,
    headers: HeaderMap,
    Json(body): Json<FriendRequestBody>,
) -> ApiResult<FriendRequests> {
    let uuid = authed(&app, &headers)?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    let target = resolve_account(&tx, &body)?;
    if target == uuid {
        return Err(bad("You can't add yourself."));
    }
    if are_friends(&tx, &uuid, &target)? {
        return Err(ApiError(StatusCode::CONFLICT, "You're already friends.".into()));
    }
    if is_blocked(&tx, &uuid, &target)? {
        return Err(ApiError(StatusCode::FORBIDDEN, "You blocked this player — unblock them first.".into()));
    }
    // they already asked first — accept instead of leaving two requests in flight
    let reverse: Option<i64> = tx
        .query_row("SELECT id FROM friend_requests WHERE from_uuid = ?1 AND to_uuid = ?2", params![target, uuid], |r| r.get(0))
        .optional()?;
    if let Some(id) = reverse {
        accept_request_tx(&tx, id, &uuid)?;
    } else {
        if !accepts_request_from(&tx, &target, &uuid)? {
            return Err(ApiError(StatusCode::FORBIDDEN, "This player isn't accepting friend requests.".into()));
        }
        let pending: i64 =
            tx.query_row("SELECT COUNT(*) FROM friend_requests WHERE from_uuid = ?1", params![uuid], |r| r.get(0))?;
        if pending >= MAX_PENDING_REQUESTS {
            return Err(ApiError(
                StatusCode::TOO_MANY_REQUESTS,
                "You have too many requests waiting — cancel some first.".into(),
            ));
        }
        tx.execute(
            "INSERT OR IGNORE INTO friend_requests (from_uuid, to_uuid, created_at) VALUES (?1, ?2, ?3)",
            params![uuid, target, now()],
        )?;
    }
    let out = read_requests(&tx, &uuid)?;
    tx.commit()?;
    Ok(Json(out))
}

async fn accept_friend_request(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> ApiResult<FriendRequests> {
    let uuid = authed(&app, &headers)?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    accept_request_tx(&tx, id, &uuid)?;
    let out = read_requests(&tx, &uuid)?;
    tx.commit()?;
    Ok(Json(out))
}

/// Either side may decline: the recipient turns it down, the sender cancels it.
async fn decline_friend_request(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> ApiResult<FriendRequests> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    let row: Option<(String, String)> = db
        .query_row("SELECT from_uuid, to_uuid FROM friend_requests WHERE id = ?1", params![id], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?;
    let (from_uuid, to_uuid) = row.ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "That request is no longer pending.".into()))?;
    if from_uuid != uuid && to_uuid != uuid {
        return Err(ApiError(StatusCode::FORBIDDEN, "not your request".into()));
    }
    db.execute("DELETE FROM friend_requests WHERE id = ?1", params![id])?;
    Ok(Json(read_requests(&db, &uuid)?))
}

#[derive(Serialize)]
struct FriendProfile {
    uuid: String,
    username: String,
    online: bool,
    #[serde(rename = "lastSeen")]
    last_seen: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    playing: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    server: Option<String>,
    cape: Option<u32>,
    accessories: Vec<u32>,
    /// everything they own, so a gift can skip it
    owned: Vec<u32>,
    /// achievements they've claimed (quests.rs)
    badges: Vec<&'static str>,
    /// a cracked account
    offline: bool,
}

/// Whether `to`'s privacy settings (and block list) let `from` send a friend
/// request. A block is answered like "not accepting", so it can't be probed.
fn accepts_request_from(db: &Connection, to: &str, from: &str) -> Result<bool, ApiError> {
    if is_blocked(db, to, from)? {
        return Ok(false);
    }
    let policy: String =
        db.query_row("SELECT friend_requests FROM accounts WHERE uuid = ?1", params![to], |r| r.get(0))?;
    Ok(match policy.as_str() {
        "nobody" => false,
        "friends_of_friends" => {
            let theirs = friend_uuids(db, to)?;
            friend_uuids(db, from)?.iter().any(|f| theirs.contains(f))
        }
        _ => true,
    })
}

fn not_friends() -> ApiError {
    ApiError(StatusCode::FORBIDDEN, "You're not friends with this player.".into())
}

/// A friend's profile — own account and friends only, never a stranger's.
async fn friend_profile(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path(raw): Path<String>,
) -> ApiResult<FriendProfile> {
    let uuid = authed(&app, &headers)?;
    let target = dashed_uuid(&raw).ok_or_else(|| bad("bad uuid"))?;
    let db = app.db.lock().unwrap();
    if target != uuid && !are_friends(&db, &uuid, &target)? {
        return Err(not_friends());
    }
    let (username, p, offline): (String, Presence, bool) = db
        .query_row(
            "SELECT username, last_seen, hidden_since, share_activity, playing, server, offline FROM accounts WHERE uuid = ?1",
            params![target],
            |r| {
                let p = visible_presence(r.get(1)?, r.get(2)?, r.get::<_, i64>(3)? != 0, r.get(4)?, r.get(5)?);
                Ok((r.get(0)?, p, r.get::<_, i64>(6)? != 0))
            },
        )
        .optional()?
        .ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "no such Dusk player".into()))?;
    let lo = read_loadout(&db, &target)?;
    let cape = lo.get("cape").and_then(Value::as_u64).and_then(|n| u32::try_from(n).ok());
    let accessories = lo
        .get("accessories")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_u64).filter_map(|n| u32::try_from(n).ok()).collect())
        .unwrap_or_default();
    let owned = owned_ids(&db, &target)?;
    let badges = quests::badges(&db, &target)?;
    Ok(Json(FriendProfile {
        uuid: target,
        username,
        online: p.online,
        last_seen: p.last_seen,
        playing: p.playing,
        server: p.server,
        cape,
        accessories,
        owned,
        badges,
        offline,
    }))
}

// ── chat ───────────────────────────────────────────────────────────────────

#[derive(Serialize)]
struct MessageEntry {
    id: i64,
    #[serde(rename = "fromUuid")]
    from_uuid: String,
    #[serde(rename = "toUuid")]
    to_uuid: String,
    body: String,
    #[serde(rename = "sentAt")]
    sent_at: i64,
    /// text | invite (meta: server, version) | image (meta: image id) |
    /// gift (meta: item, name, kind)
    kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    meta: Option<Value>,
}

fn insert_message(
    db: &Connection,
    from: &str,
    to: &str,
    body: &str,
    kind: &str,
    meta: Option<&Value>,
) -> Result<MessageEntry, rusqlite::Error> {
    let t = now();
    db.execute(
        "INSERT INTO messages (from_uuid, to_uuid, body, sent_at, kind, meta) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![from, to, body, t, kind, meta.map(Value::to_string)],
    )?;
    quests::record_message(db, from, kind)?;
    Ok(MessageEntry {
        id: db.last_insert_rowid(),
        from_uuid: from.to_string(),
        to_uuid: to.to_string(),
        body: body.to_string(),
        sent_at: t,
        kind: kind.to_string(),
        meta: meta.cloned(),
    })
}

#[derive(Deserialize)]
struct MessagesQuery {
    #[serde(default, rename = "afterId")]
    after_id: i64,
}

/// A friends-only conversation, newest-last. `afterId` lets the client poll
/// for just what landed since its last fetch instead of re-reading it all.
async fn get_messages(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path(raw): Path<String>,
    Query(q): Query<MessagesQuery>,
) -> ApiResult<Vec<MessageEntry>> {
    let uuid = authed(&app, &headers)?;
    let target = dashed_uuid(&raw).ok_or_else(|| bad("bad uuid"))?;
    let db = app.db.lock().unwrap();
    if !are_friends(&db, &uuid, &target)? {
        return Err(not_friends());
    }
    Ok(Json(read_messages(&db, &uuid, &target, q.after_id)?))
}

/// `me`'s conversation with `other`, oldest-first, and marks what it returns
/// as read. The first fetch (`after_id` 0) is the newest page — a long
/// history opens on its latest lines, not its first ones; a poll after that
/// takes everything since in order, so nothing in between is skipped.
fn read_messages(db: &Connection, me: &str, other: &str, after_id: i64) -> Result<Vec<MessageEntry>, ApiError> {
    let pair = "((from_uuid = ?1 AND to_uuid = ?2) OR (from_uuid = ?2 AND to_uuid = ?1)) AND id > ?3";
    let sql = if after_id == 0 {
        format!(
            "SELECT * FROM (SELECT id, from_uuid, to_uuid, body, sent_at, kind, meta FROM messages WHERE {pair}
             ORDER BY id DESC LIMIT ?4) ORDER BY id ASC"
        )
    } else {
        format!("SELECT id, from_uuid, to_uuid, body, sent_at, kind, meta FROM messages WHERE {pair} ORDER BY id ASC LIMIT ?4")
    };
    let mut st = db.prepare(&sql)?;
    let out = st
        .query_map(params![me, other, after_id, MESSAGE_PAGE], |r| {
            let meta: Option<String> = r.get(6)?;
            Ok(MessageEntry {
                id: r.get(0)?,
                from_uuid: r.get(1)?,
                to_uuid: r.get(2)?,
                body: r.get(3)?,
                sent_at: r.get(4)?,
                kind: r.get(5)?,
                meta: meta.and_then(|m| serde_json::from_str(&m).ok()),
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    if let Some(last) = out.last() {
        db.execute(
            "INSERT INTO message_reads (reader, other, last_read_id) VALUES (?1, ?2, ?3)
             ON CONFLICT(reader, other) DO UPDATE SET last_read_id = MAX(last_read_id, excluded.last_read_id)",
            params![me, other, last.id],
        )?;
    }
    Ok(out)
}

#[derive(Deserialize)]
struct SendMessage {
    #[serde(default)]
    body: String,
    /// text (default) | invite | image
    #[serde(default)]
    kind: Option<String>,
    #[serde(default)]
    meta: Option<Value>,
}

/// Check a message the client composed; invites and images get a default
/// body so older launchers still show something readable.
fn compose_message(db: &Connection, from: &str, payload: &SendMessage) -> Result<(String, String, Option<Value>), ApiError> {
    let text = payload.body.trim().to_string();
    if text.chars().count() > MESSAGE_MAX_LEN {
        return Err(bad(format!("Messages are limited to {MESSAGE_MAX_LEN} characters.")));
    }
    let field = |k: &str| payload.meta.as_ref().and_then(|m| m.get(k)).and_then(Value::as_str);
    match payload.kind.as_deref().unwrap_or("text") {
        "text" => {
            if text.is_empty() {
                return Err(bad("Type a message first."));
            }
            Ok((text, "text".into(), None))
        }
        "invite" => {
            let server = field("server").and_then(clean_server).ok_or_else(|| bad("invite needs a server address"))?;
            let version = field("version")
                .map(|v| v.chars().filter(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_')).take(PLAYING_MAX_LEN).collect::<String>())
                .filter(|v| !v.is_empty());
            let body = if text.is_empty() { format!("Join me on {server}") } else { text };
            Ok((body, "invite".into(), Some(json!({ "server": server, "version": version }))))
        }
        "image" => {
            let id = field("image").ok_or_else(|| bad("image message needs an image id"))?;
            let mine: bool = db
                .query_row("SELECT 1 FROM images WHERE id = ?1 AND owner = ?2", params![id, from], |_| Ok(true))
                .optional()?
                .unwrap_or(false);
            if !mine {
                return Err(ApiError(StatusCode::NOT_FOUND, "That image has expired — send it again.".into()));
            }
            let body = if text.is_empty() { "Sent a screenshot".to_string() } else { text };
            Ok((body, "image".into(), Some(json!({ "image": id }))))
        }
        _ => Err(bad("unknown message kind")),
    }
}

async fn send_message(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path(raw): Path<String>,
    Json(payload): Json<SendMessage>,
) -> ApiResult<MessageEntry> {
    let uuid = authed(&app, &headers)?;
    let target = dashed_uuid(&raw).ok_or_else(|| bad("bad uuid"))?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    if !are_friends(&tx, &uuid, &target)? {
        return Err(not_friends());
    }
    let (body, kind, meta) = compose_message(&tx, &uuid, &payload)?;
    let recent: i64 = tx.query_row(
        "SELECT COUNT(*) FROM messages WHERE from_uuid = ?1 AND sent_at > ?2",
        params![uuid, now() - MESSAGE_RATE_WINDOW],
        |r| r.get(0),
    )?;
    if recent >= MESSAGE_RATE_MAX {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "You're sending messages too fast — wait a few seconds.".into()));
    }
    let msg = insert_message(&tx, &uuid, &target, &body, &kind, meta.as_ref())?;
    tx.commit()?;
    Ok(Json(msg))
}

// ── privacy ────────────────────────────────────────────────────────────────

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Privacy {
    /// friends see this account as offline, last seen when this went on
    appear_offline: bool,
    /// friends see the game version and server being played
    share_activity: bool,
    /// who may send a friend request: everyone | friends_of_friends | nobody
    friend_requests: String,
}

fn read_privacy(db: &Connection, uuid: &str) -> Result<Privacy, rusqlite::Error> {
    db.query_row(
        "SELECT hidden_since, share_activity, friend_requests FROM accounts WHERE uuid = ?1",
        params![uuid],
        |r| {
            Ok(Privacy {
                appear_offline: r.get::<_, Option<i64>>(0)?.is_some(),
                share_activity: r.get::<_, i64>(1)? != 0,
                friend_requests: r.get(2)?,
            })
        },
    )
}

async fn get_privacy(State(app): State<Shared>, headers: HeaderMap) -> ApiResult<Privacy> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    Ok(Json(read_privacy(&db, &uuid)?))
}

async fn put_privacy(State(app): State<Shared>, headers: HeaderMap, Json(body): Json<Privacy>) -> ApiResult<Privacy> {
    let uuid = authed(&app, &headers)?;
    if !matches!(body.friend_requests.as_str(), "everyone" | "friends_of_friends" | "nobody") {
        return Err(bad("friendRequests must be everyone, friends_of_friends or nobody"));
    }
    let db = app.db.lock().unwrap();
    // keep the original moment when it was already on, so toggling other
    // settings doesn't move "last seen"
    db.execute(
        "UPDATE accounts SET hidden_since = CASE WHEN ?1 THEN COALESCE(hidden_since, ?2) ELSE NULL END,
                             share_activity = ?3, friend_requests = ?4 WHERE uuid = ?5",
        params![body.appear_offline, now(), body.share_activity, body.friend_requests, uuid],
    )?;
    Ok(Json(read_privacy(&db, &uuid)?))
}

// ── blocks ─────────────────────────────────────────────────────────────────

fn is_blocked(db: &Connection, blocker: &str, blocked: &str) -> Result<bool, rusqlite::Error> {
    Ok(db
        .query_row("SELECT 1 FROM blocks WHERE blocker = ?1 AND blocked = ?2", params![blocker, blocked], |_| Ok(true))
        .optional()?
        .unwrap_or(false))
}

#[derive(Serialize)]
struct BlockedEntry {
    uuid: String,
    username: String,
}

fn read_blocks(db: &Connection, uuid: &str) -> Result<Vec<BlockedEntry>, ApiError> {
    let mut st = db.prepare(
        "SELECT b.blocked, a.username FROM blocks b JOIN accounts a ON a.uuid = b.blocked
         WHERE b.blocker = ?1 ORDER BY a.username COLLATE NOCASE",
    )?;
    let out = st
        .query_map(params![uuid], |r| Ok(BlockedEntry { uuid: r.get(0)?, username: r.get(1)? }))?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(out)
}

async fn list_blocks(State(app): State<Shared>, headers: HeaderMap) -> ApiResult<Vec<BlockedEntry>> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    Ok(Json(read_blocks(&db, &uuid)?))
}

/// Block a player: ends the friendship and any request in either direction;
/// they can't send a new one and see this account as not accepting requests.
fn block_tx(db: &Connection, uuid: &str, target: &str) -> Result<(), ApiError> {
    if target == uuid {
        return Err(bad("You can't block yourself."));
    }
    let (a, b) = friend_pair(uuid, target);
    db.execute("DELETE FROM friendships WHERE a = ?1 AND b = ?2", params![a, b])?;
    db.execute(
        "DELETE FROM friend_requests WHERE (from_uuid = ?1 AND to_uuid = ?2) OR (from_uuid = ?2 AND to_uuid = ?1)",
        params![uuid, target],
    )?;
    db.execute(
        "INSERT OR IGNORE INTO blocks (blocker, blocked, created_at) VALUES (?1, ?2, ?3)",
        params![uuid, target, now()],
    )?;
    Ok(())
}

async fn block_player(
    State(app): State<Shared>,
    headers: HeaderMap,
    Json(body): Json<FriendRequestBody>,
) -> ApiResult<Vec<BlockedEntry>> {
    let uuid = authed(&app, &headers)?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    let target = resolve_account(&tx, &body)?;
    block_tx(&tx, &uuid, &target)?;
    let out = read_blocks(&tx, &uuid)?;
    tx.commit()?;
    Ok(Json(out))
}

async fn unblock_player(State(app): State<Shared>, headers: HeaderMap, Path(raw): Path<String>) -> ApiResult<Vec<BlockedEntry>> {
    let uuid = authed(&app, &headers)?;
    let target = dashed_uuid(&raw).ok_or_else(|| bad("bad uuid"))?;
    let db = app.db.lock().unwrap();
    db.execute("DELETE FROM blocks WHERE blocker = ?1 AND blocked = ?2", params![uuid, target])?;
    Ok(Json(read_blocks(&db, &uuid)?))
}

// ── gifts ──────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct Gift {
    to: String,
    id: u32,
}

/// Buy a cosmetic for a friend: the sender pays, the friend owns it, and the
/// conversation gets a gift message so they find out.
fn gift_tx(db: &Connection, item: &PricedItem, from: &str, to: &str) -> Result<(i64, MessageEntry), ApiError> {
    if to == from {
        return Err(bad("Buy it for yourself in the store instead."));
    }
    if !are_friends(db, from, to)? {
        return Err(not_friends());
    }
    let already: bool = db
        .query_row("SELECT 1 FROM owned WHERE uuid = ?1 AND item = ?2", params![to, item.id], |_| Ok(true))
        .optional()?
        .unwrap_or(false);
    if already {
        return Err(ApiError(StatusCode::CONFLICT, format!("They already own {}.", item.name)));
    }
    let coins: i64 = db.query_row("SELECT coins FROM accounts WHERE uuid = ?1", params![from], |r| r.get(0))?;
    if coins < item.price {
        return Err(ApiError(
            StatusCode::PAYMENT_REQUIRED,
            format!("{} costs {} coins; you have {coins}", item.name, item.price),
        ));
    }
    let t = now();
    db.execute("UPDATE accounts SET coins = coins - ?1 WHERE uuid = ?2", params![item.price, from])?;
    db.execute("INSERT INTO owned (uuid, item, acquired_at) VALUES (?1, ?2, ?3)", params![to, item.id, t])?;
    db.execute(
        "INSERT INTO ledger (uuid, delta, reason, at) VALUES (?1, ?2, ?3, ?4)",
        params![from, -item.price, format!("gift:{}:{to}", item.id), t],
    )?;
    let meta = json!({ "item": item.id, "name": item.name, "kind": item.kind });
    let body = format!("Sent you {} as a gift", item.name);
    let msg = insert_message(db, from, to, &body, "gift", Some(&meta))?;
    Ok((coins - item.price, msg))
}

#[derive(Serialize)]
struct Gifted {
    coins: i64,
    message: MessageEntry,
}

async fn gift(State(app): State<Shared>, headers: HeaderMap, Json(body): Json<Gift>) -> ApiResult<Gifted> {
    let uuid = authed(&app, &headers)?;
    let to = dashed_uuid(&body.to).ok_or_else(|| bad("bad uuid"))?;
    let item = app.catalog.get(&body.id).ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "no such cosmetic".into()))?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    let (coins, message) = gift_tx(&tx, item, &uuid, &to)?;
    tx.commit()?;
    tracing::info!("{uuid} gifted {} to {to}", item.id);
    Ok(Json(Gifted { coins, message }))
}

// ── outfits ────────────────────────────────────────────────────────────────

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Outfit {
    id: i64,
    name: String,
    loadout: Value,
    created_at: i64,
}

#[derive(Deserialize)]
struct NewOutfit {
    name: String,
    loadout: Map<String, Value>,
}

fn read_outfits(db: &Connection, uuid: &str) -> Result<Vec<Outfit>, ApiError> {
    let mut st = db.prepare("SELECT id, name, json, created_at FROM outfits WHERE uuid = ?1 ORDER BY created_at, id")?;
    let out = st
        .query_map(params![uuid], |r| {
            let json: String = r.get(2)?;
            Ok(Outfit {
                id: r.get(0)?,
                name: r.get(1)?,
                loadout: serde_json::from_str(&json).unwrap_or(Value::Object(Map::new())),
                created_at: r.get(3)?,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(out)
}

async fn list_outfits(State(app): State<Shared>, headers: HeaderMap) -> ApiResult<Vec<Outfit>> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    Ok(Json(read_outfits(&db, &uuid)?))
}

/// Save a loadout under a name; saving under a name already used replaces it.
async fn save_outfit(State(app): State<Shared>, headers: HeaderMap, Json(body): Json<NewOutfit>) -> ApiResult<Vec<Outfit>> {
    let uuid = authed(&app, &headers)?;
    let name = body.name.trim();
    if name.is_empty() || name.chars().count() > OUTFIT_NAME_MAX {
        return Err(bad(format!("Outfit names are 1–{OUTFIT_NAME_MAX} characters.")));
    }
    if body.loadout.len() > 32 {
        return Err(bad("too many slots"));
    }
    // ownership is checked again when an outfit is worn (PUT /v1/me/loadout)
    loadout_ids(&body.loadout)?;
    let db = app.db.lock().unwrap();
    let exists: bool = db
        .query_row("SELECT 1 FROM outfits WHERE uuid = ?1 AND name = ?2", params![uuid, name], |_| Ok(true))
        .optional()?
        .unwrap_or(false);
    if !exists {
        let count: i64 = db.query_row("SELECT COUNT(*) FROM outfits WHERE uuid = ?1", params![uuid], |r| r.get(0))?;
        if count >= MAX_OUTFITS {
            return Err(bad(format!("You can save up to {MAX_OUTFITS} outfits — delete one first.")));
        }
    }
    db.execute(
        "INSERT INTO outfits (uuid, name, json, created_at) VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT(uuid, name) DO UPDATE SET json = excluded.json",
        params![uuid, name, Value::Object(body.loadout).to_string(), now()],
    )?;
    Ok(Json(read_outfits(&db, &uuid)?))
}

async fn delete_outfit(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<i64>) -> ApiResult<Vec<Outfit>> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    db.execute("DELETE FROM outfits WHERE id = ?1 AND uuid = ?2", params![id, uuid])?;
    Ok(Json(read_outfits(&db, &uuid)?))
}

// ── settings sync ──────────────────────────────────────────────────────────

/// The Dusk client's settings as the launcher last pushed them. The service
/// doesn't read inside it: the launcher merges key by key and pushes the
/// result; this is last-writer-wins storage with a server clock.
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ClientSettings {
    settings: Option<Map<String, Value>>,
    /// server time of the last push, 0 before the first
    #[serde(default)]
    updated_at: i64,
}

fn read_client_settings(db: &Connection, uuid: &str) -> Result<ClientSettings, rusqlite::Error> {
    let row: Option<(String, i64)> = db
        .query_row("SELECT json, updated_at FROM client_settings WHERE uuid = ?1", params![uuid], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
        .optional()?;
    Ok(match row {
        Some((json, updated_at)) => ClientSettings {
            settings: serde_json::from_str::<Value>(&json).ok().and_then(|v| v.as_object().cloned()),
            updated_at,
        },
        None => ClientSettings { settings: None, updated_at: 0 },
    })
}

async fn get_client_settings(State(app): State<Shared>, headers: HeaderMap) -> ApiResult<ClientSettings> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    Ok(Json(read_client_settings(&db, &uuid)?))
}

async fn put_client_settings(
    State(app): State<Shared>,
    headers: HeaderMap,
    Json(body): Json<ClientSettings>,
) -> ApiResult<ClientSettings> {
    let uuid = authed(&app, &headers)?;
    let settings = body.settings.ok_or_else(|| bad("settings must be an object"))?;
    let json = Value::Object(settings).to_string();
    if json.len() > SETTINGS_MAX_BYTES {
        return Err(bad("Those settings are too large to sync."));
    }
    let db = app.db.lock().unwrap();
    // strictly increasing, so two pushes in one second still order
    let prev: i64 = db
        .query_row("SELECT updated_at FROM client_settings WHERE uuid = ?1", params![uuid], |r| r.get(0))
        .optional()?
        .unwrap_or(0);
    let t = now().max(prev + 1);
    db.execute(
        "INSERT INTO client_settings (uuid, json, updated_at) VALUES (?1, ?2, ?3)
         ON CONFLICT(uuid) DO UPDATE SET json = excluded.json, updated_at = excluded.updated_at",
        params![uuid, json, t],
    )?;
    Ok(Json(read_client_settings(&db, &uuid)?))
}

// ── images ─────────────────────────────────────────────────────────────────

fn image_mime(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("image/png")
    } else if bytes.starts_with(&[0xff, 0xd8, 0xff]) {
        Some("image/jpeg")
    } else {
        None
    }
}

#[derive(Serialize)]
struct Uploaded {
    id: String,
}

/// Store a screenshot for a chat message. The raw PNG/JPEG is the body; the
/// id comes back to be sent as an `image` message.
async fn upload_image(State(app): State<Shared>, headers: HeaderMap, body: axum::body::Bytes) -> ApiResult<Uploaded> {
    let uuid = authed(&app, &headers)?;
    if body.len() > IMAGE_MAX_BYTES {
        return Err(bad("Screenshots are limited to 8 MB."));
    }
    let mime = image_mime(&body).ok_or_else(|| bad("Only PNG and JPEG images can be sent."))?;
    let db = app.db.lock().unwrap();
    let t = now();
    let today: i64 = db.query_row(
        "SELECT COUNT(*) FROM images WHERE owner = ?1 AND created_at > ?2",
        params![uuid, t - 24 * 3600],
        |r| r.get(0),
    )?;
    if today >= IMAGE_DAILY_MAX {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "You've sent a lot of screenshots today — try again tomorrow.".into()));
    }
    let mut raw = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut raw);
    let id = hex::encode(raw);
    db.execute(
        "INSERT INTO images (id, owner, mime, bytes, created_at) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![id, uuid, mime, body.as_ref(), t],
    )?;
    db.execute("DELETE FROM images WHERE created_at < ?1", params![t - IMAGE_TTL])?;
    Ok(Json(Uploaded { id }))
}

/// An image, for its uploader and whoever it was sent to in chat, until it
/// expires (expired rows are only swept when someone uploads).
async fn get_image(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> Result<Response, ApiError> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    let row: Option<(String, String, Vec<u8>)> = db
        .query_row(
            "SELECT owner, mime, bytes FROM images WHERE id = ?1 AND created_at >= ?2",
            params![id, now() - IMAGE_TTL],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .optional()?;
    let gone = || ApiError(StatusCode::NOT_FOUND, "That image has expired.".into());
    let (owner, mime, bytes) = row.ok_or_else(gone)?;
    if owner != uuid {
        let shared: bool = db
            .query_row(
                "SELECT 1 FROM messages WHERE kind = 'image' AND from_uuid = ?1 AND to_uuid = ?2
                   AND json_extract(meta, '$.image') = ?3 LIMIT 1",
                params![owner, uuid, id],
                |_| Ok(true),
            )
            .optional()?
            .unwrap_or(false);
        if !shared {
            return Err(gone());
        }
    }
    Ok(([(header::CONTENT_TYPE, mime), (header::CACHE_CONTROL, "private, max-age=86400".into())], bytes).into_response())
}

// ── shared instances ───────────────────────────────────────────────────────

/// A code as typed: any case, with or without the dash or spaces.
fn pack_code(raw: &str) -> Option<String> {
    let code: String = raw.chars().filter(|c| !matches!(c, '-' | ' ')).collect::<String>().to_ascii_uppercase();
    (code.len() == CODE_LEN && code.bytes().all(|b| CODE_ALPHABET.contains(&b))).then_some(code)
}

/// Keep a shared instance under a new code, dropping expired ones.
fn store_pack(db: &Connection, uuid: &str, bytes: &[u8]) -> Result<String, ApiError> {
    if bytes.len() > PACK_MAX_BYTES {
        return Err(bad("A shared instance is limited to 4 MB of configs."));
    }
    // a zip: the launcher reads the rest when it installs it
    if !bytes.starts_with(b"PK\x03\x04") {
        return Err(bad("That isn't a modpack."));
    }
    let t = now();
    db.execute("DELETE FROM shared_packs WHERE created_at < ?1", params![t - PACK_TTL])?;
    let today: i64 = db.query_row(
        "SELECT COUNT(*) FROM shared_packs WHERE owner = ?1 AND created_at > ?2",
        params![uuid, t - 24 * 3600],
        |r| r.get(0),
    )?;
    if today >= PACK_DAILY_MAX {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "You've shared a lot of instances today — try again tomorrow.".into()));
    }
    let mut rng = rand::thread_rng();
    loop {
        let code: String = (0..CODE_LEN)
            .map(|_| CODE_ALPHABET[(rng.next_u32() as usize) % CODE_ALPHABET.len()] as char)
            .collect();
        if db.execute(
            "INSERT OR IGNORE INTO shared_packs (code, owner, bytes, created_at) VALUES (?1, ?2, ?3, ?4)",
            params![code, uuid, bytes, t],
        )? == 1
        {
            return Ok(code);
        }
    }
}

fn load_pack(db: &Connection, raw: &str) -> Result<Vec<u8>, ApiError> {
    let gone = || ApiError(StatusCode::NOT_FOUND, "No instance has that code — it may have expired (codes last 30 days).".into());
    let code = pack_code(raw).ok_or_else(gone)?;
    db.query_row(
        "SELECT bytes FROM shared_packs WHERE code = ?1 AND created_at >= ?2",
        params![code, now() - PACK_TTL],
        |r| r.get(0),
    )
    .optional()?
    .ok_or_else(gone)
}

#[derive(Serialize)]
struct SharedPack {
    code: String,
}

/// Share an instance: the .mrpack is the body, a code comes back.
async fn share_pack(State(app): State<Shared>, headers: HeaderMap, body: axum::body::Bytes) -> ApiResult<SharedPack> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    Ok(Json(SharedPack { code: store_pack(&db, &uuid, &body)? }))
}

/// A shared instance's .mrpack, for anyone signed in to Dusk with its code.
async fn get_pack(State(app): State<Shared>, headers: HeaderMap, Path(code): Path<String>) -> Result<Response, ApiError> {
    authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    let bytes = load_pack(&db, &code)?;
    Ok(([(header::CONTENT_TYPE, "application/x-modrinth-modpack+zip")], bytes).into_response())
}

// ── public ─────────────────────────────────────────────────────────────────

#[derive(Serialize)]
struct PublicLoadout {
    uuid: String,
    cape: Option<u32>,
    accessories: Vec<u32>,
    /// Always true: the account exists, so Dusk clients badge its name tag.
    /// Says nothing about whether it's online; presence stays friends-only.
    dusk: bool,
}

/// What a Dusk client draws on another Dusk player. Only slots the mod knows
/// today; `settings` and unknown slots stay private. Any account answers,
/// with or without cosmetics, so its name tag gets the Dusk badge.
async fn public_loadout(State(app): State<Shared>, Path(raw): Path<String>) -> Result<Response, ApiError> {
    let uuid = dashed_uuid(&raw).ok_or_else(|| bad("bad uuid"))?;
    let db = app.db.lock().unwrap();
    let exists: bool = db
        .query_row("SELECT 1 FROM accounts WHERE uuid = ?1", params![uuid], |_| Ok(true))
        .optional()?
        .unwrap_or(false);
    if !exists {
        return Ok((StatusCode::NOT_FOUND, Json(json!({ "error": "no account" }))).into_response());
    }
    let lo = read_loadout(&db, &uuid)?;
    let cape = lo.get("cape").and_then(Value::as_u64).and_then(|n| u32::try_from(n).ok());
    let accessories = lo
        .get("accessories")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_u64).filter_map(|n| u32::try_from(n).ok()).collect())
        .unwrap_or_default();
    Ok(([(header::CACHE_CONTROL, "public, max-age=60")], Json(PublicLoadout { uuid, cape, accessories, dusk: true })).into_response())
}

async fn catalog(State(app): State<Shared>) -> Json<Value> {
    Json(json!({ "items": app.catalog.values().cloned().collect::<Vec<_>>() }))
}

async fn health() -> &'static str {
    "ok"
}

// ── main ───────────────────────────────────────────────────────────────────

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let db_path = std::env::var("DUSK_DB").unwrap_or_else(|_| "dusk.db".into());
    let bind = std::env::var("DUSK_BIND").unwrap_or_else(|_| "0.0.0.0:8787".into());
    let mut codes: BTreeMap<String, i64> = BUILTIN_CODES.iter().map(|(c, n)| (c.to_string(), *n)).collect();
    if let Ok(extra) = std::env::var("DUSK_CODES") {
        for pair in extra.split(',').map(str::trim).filter(|s| !s.is_empty()) {
            if let Some((code, n)) = pair.split_once(':') {
                if let Ok(n) = n.trim().parse::<i64>() {
                    codes.insert(code.trim().to_lowercase(), n);
                }
            }
        }
    }
    let app = Arc::new(App {
        db: Mutex::new(open_db(&db_path)),
        http: reqwest::Client::builder()
            .user_agent("DuskServer/0.1")
            .timeout(Duration::from_secs(10))
            .build()
            .expect("http client"),
        catalog: load_catalog(),
        codes,
        dev_auth: std::env::var("DUSK_DEV_AUTH").as_deref() == Ok("1"),
    });
    tracing::info!("catalog: {} items, {} code(s), db {db_path}", app.catalog.len(), app.codes.len());
    if app.dev_auth {
        tracing::warn!("DUSK_DEV_AUTH=1: /v1/auth/dev is open");
    }

    let router = Router::new()
        .route("/health", get(health))
        .route("/v1/catalog", get(catalog))
        .route("/v1/auth/minecraft", post(auth_minecraft))
        .route("/v1/auth/dev", post(auth_dev))
        .route("/v1/auth/offline", post(auth_offline))
        .route(
            "/v1/me/skin",
            put(put_skin).delete(delete_skin).layer(axum::extract::DefaultBodyLimit::max(SKIN_MAX_BYTES + 1024)),
        )
        .route("/v1/skins/{name}", get(get_skin))
        .route("/v1/me", get(me))
        .route("/v1/me/redeem", post(redeem))
        .route("/v1/me/buy", post(buy))
        .route("/v1/me/loadout", put(put_loadout))
        .route("/v1/me/referral", get(get_referral).post(claim_referral))
        .route("/v1/me/launched", post(launched))
        .route("/v1/me/presence", post(presence))
        .route("/v1/loadout/{uuid}", get(public_loadout))
        .route("/v1/friends", get(list_friends))
        .route("/v1/friends/{uuid}", delete(remove_friend))
        .route("/v1/friends/requests", get(list_friend_requests).post(send_friend_request))
        .route("/v1/friends/requests/{id}/accept", post(accept_friend_request))
        .route("/v1/friends/requests/{id}/decline", post(decline_friend_request))
        .route("/v1/profile/{uuid}", get(friend_profile))
        .route("/v1/messages/{uuid}", get(get_messages).post(send_message))
        .route("/v1/me/privacy", get(get_privacy).put(put_privacy))
        .route(
            "/v1/me/settings",
            get(get_client_settings)
                .put(put_client_settings)
                .layer(axum::extract::DefaultBodyLimit::max(SETTINGS_MAX_BYTES + 1024)),
        )
        .route("/v1/me/gift", post(gift))
        .route("/v1/me/outfits", get(list_outfits).post(save_outfit))
        .route("/v1/me/quests", get(quests::get_quests))
        .route("/v1/me/quests/claim", post(quests::claim))
        .route("/v1/me/play", post(quests::play))
        .route("/v1/me/outfits/{id}", delete(delete_outfit))
        .route("/v1/blocks", get(list_blocks).post(block_player))
        .route("/v1/blocks/{uuid}", delete(unblock_player))
        .route(
            "/v1/images",
            // the one route that takes more than a small JSON body
            post(upload_image).layer(axum::extract::DefaultBodyLimit::max(IMAGE_MAX_BYTES + 1024)),
        )
        .route("/v1/images/{id}", get(get_image))
        .route(
            "/v1/packs",
            post(share_pack).layer(axum::extract::DefaultBodyLimit::max(PACK_MAX_BYTES + 1024)),
        )
        .route("/v1/packs/{code}", get(get_pack))
        .layer(axum::extract::DefaultBodyLimit::max(16 * 1024))
        .with_state(app);

    let listener = tokio::net::TcpListener::bind(&bind).await.expect("bind");
    tracing::info!("listening on {bind}");
    axum::serve(listener, router).await.expect("serve");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uuid_normalisation() {
        assert_eq!(
            dashed_uuid("BA4161C03A42496C8AE07D13372F3371").as_deref(),
            Some("ba4161c0-3a42-496c-8ae0-7d13372f3371")
        );
        assert!(dashed_uuid("ba4161c0-3a42-496c-8ae0-7d13372f3371").is_some());
        assert!(dashed_uuid("nope").is_none());
    }

    #[test]
    fn prices_follow_animation() {
        let cat = load_catalog();
        assert_eq!(cat[&5].price, PRICE_ANIMATED);
        assert_eq!(cat[&8].price, PRICE_STILL);
        assert_eq!(cat[&16].kind, "accessory");
    }

    #[test]
    fn loadout_ids_reads_every_slot() {
        let lo: Map<String, Value> = serde_json::from_str(r#"{"cape":5,"accessories":[16,9],"settings":{}}"#).unwrap();
        assert_eq!(loadout_ids(&lo).unwrap().into_iter().collect::<Vec<_>>(), vec![5, 9, 16]);
        let bad: Map<String, Value> = serde_json::from_str(r#"{"cape":"x"}"#).unwrap();
        assert!(loadout_ids(&bad).is_err());
    }

    #[test]
    fn wallet_round_trip() {
        let db = open_db(":memory:");
        let token = issue_token(&db, "ba4161c0-3a42-496c-8ae0-7d13372f3371", "James").unwrap();
        let uuid: String = db
            .query_row("SELECT uuid FROM tokens WHERE hash = ?1", params![token_hash(&token)], |r| r.get(0))
            .unwrap();
        assert_eq!(uuid, "ba4161c0-3a42-496c-8ae0-7d13372f3371");
        let me = read_me(&db, &uuid).unwrap();
        assert_eq!(me.coins, 0);
        assert!(me.owned.is_empty());
    }

    const A: &str = "aaaaaaaa-0000-0000-0000-000000000001";
    const B: &str = "bbbbbbbb-0000-0000-0000-000000000002";

    fn coins(db: &Connection, uuid: &str) -> i64 {
        db.query_row("SELECT coins FROM accounts WHERE uuid = ?1", params![uuid], |r| r.get(0)).unwrap()
    }

    #[test]
    fn referral_pays_on_first_launch() {
        let db = open_db(":memory:");
        issue_token(&db, A, "Inviter").unwrap();
        issue_token(&db, B, "Friend").unwrap();
        let code = referral_code(&db, A).unwrap();
        assert_eq!(code, referral_code(&db, A).unwrap(), "a code is minted once");
        assert_eq!(code.len(), CODE_LEN);

        db.execute("INSERT INTO referrals (referee, referrer, claimed_at) VALUES (?1, ?2, ?3)", params![B, A, now()]).unwrap();
        assert_eq!(settle_referral(&db, B).unwrap(), 0, "no launch yet, no payout");
        assert_eq!(coins(&db, A), 0);

        db.execute("UPDATE accounts SET launches = 1 WHERE uuid = ?1", params![B]).unwrap();
        assert_eq!(settle_referral(&db, B).unwrap(), REFEREE_REWARD);
        assert_eq!(coins(&db, A), REFERRER_REWARD);
        assert_eq!(coins(&db, B), REFEREE_REWARD);
        assert_eq!(settle_referral(&db, B).unwrap(), 0, "pays once");
        assert_eq!(coins(&db, A), REFERRER_REWARD);

        let a = read_referral(&db, A).unwrap();
        assert_eq!((a.invited, a.paid), (1, 1));
        let b = read_referral(&db, B).unwrap();
        assert_eq!(b.referred_by.as_deref(), Some("Inviter"));
        assert!(b.referral_paid && !b.can_claim);
    }

    #[test]
    fn offline_uuid_matches_java() {
        assert_eq!(offline_uuid("Notch"), "b50ad385-829d-3141-a216-7e7d7539ba7f");
    }

    #[test]
    fn offline_names_are_held_by_their_key() {
        let db = open_db(":memory:");
        let key = "ab".repeat(32);
        let first = offline_sign_in(&db, "Steve_1", &key).unwrap();
        assert_eq!(first.uuid, offline_uuid("Steve_1"));
        assert!(is_offline(&db, &first.uuid).unwrap());
        assert!(offline_sign_in(&db, "Steve_1", &key).is_ok(), "same device signs in again");
        assert!(offline_sign_in(&db, "Steve_1", &"cd".repeat(32)).is_err(), "another device can't");
        assert!(offline_sign_in(&db, "steve_1", &"cd".repeat(32)).is_err(), "nor the name in another case");
        assert!(offline_sign_in(&db, "no", &key).is_err());
        assert!(offline_sign_in(&db, "bad name", &key).is_err());
        assert!(offline_sign_in(&db, "Other", "short").is_err());

        issue_token(&db, A, "Premium").unwrap();
        assert!(offline_sign_in(&db, "premium", &key).is_err(), "a Minecraft account's name");
        assert!(!read_referral(&db, &first.uuid).unwrap().can_claim);
        assert!(read_me(&db, &first.uuid).unwrap().offline);
    }

    #[test]
    fn skins_are_found_by_name() {
        let db = open_db(":memory:");
        let me = offline_sign_in(&db, "Alex", &"ab".repeat(32)).unwrap();
        let mut png = b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR".to_vec();
        png.extend_from_slice(&64u32.to_be_bytes());
        png.extend_from_slice(&64u32.to_be_bytes());
        assert_eq!(png_size(&png), Some((64, 64)));
        assert_eq!(png_size(b"GIF89a"), None);
        db.execute(
            "INSERT INTO skins (uuid, png, slim, hash, updated_at) VALUES (?1, ?2, 1, 'h', 0)",
            params![me.uuid, png],
        )
        .unwrap();
        let (got, slim, _) = skin_by_name(&db, "alex").unwrap().unwrap();
        assert_eq!((got, slim), (png.clone(), true));
        assert!(skin_by_name(&db, &me.uuid).unwrap().is_some(), "by uuid too");
        // a Minecraft account by that name outranks it
        issue_token(&db, A, "Alex").unwrap();
        assert!(skin_by_name(&db, "Alex").unwrap().is_none());
    }

    #[test]
    fn offline_referrers_are_not_paid() {
        let db = open_db(":memory:");
        let inviter = offline_sign_in(&db, "Cracked", &"ab".repeat(32)).unwrap().uuid;
        issue_token(&db, B, "Friend").unwrap();
        db.execute("INSERT INTO referrals (referee, referrer, claimed_at) VALUES (?1, ?2, ?3)", params![B, inviter, now()]).unwrap();
        db.execute("UPDATE accounts SET launches = 1 WHERE uuid = ?1", params![B]).unwrap();
        assert_eq!(settle_referral(&db, B).unwrap(), REFEREE_REWARD);
        assert_eq!(coins(&db, &inviter), 0);
    }

    #[test]
    fn old_accounts_cannot_claim() {
        let db = open_db(":memory:");
        issue_token(&db, A, "Old").unwrap();
        db.execute("UPDATE accounts SET created_at = 0 WHERE uuid = ?1", params![A]).unwrap();
        assert!(!read_referral(&db, A).unwrap().can_claim);
    }

    #[test]
    fn friend_request_round_trip() {
        let db = open_db(":memory:");
        issue_token(&db, A, "Inviter").unwrap();
        issue_token(&db, B, "Friend").unwrap();
        assert!(!are_friends(&db, A, B).unwrap());

        let body = FriendRequestBody { uuid: Some(B.to_string()), username: None };
        let target = resolve_account(&db, &body).unwrap();
        assert_eq!(target, B);
        db.execute("INSERT INTO friend_requests (from_uuid, to_uuid, created_at) VALUES (?1, ?2, ?3)", params![A, B, now()]).unwrap();

        let reqs = read_requests(&db, B).unwrap();
        assert_eq!(reqs.incoming.len(), 1);
        assert_eq!(reqs.incoming[0].uuid, A);

        accept_request_tx(&db, reqs.incoming[0].id, B).unwrap();
        assert!(are_friends(&db, A, B).unwrap());
        assert!(read_requests(&db, B).unwrap().incoming.is_empty());
    }

    #[test]
    fn non_friend_cannot_message() {
        let db = open_db(":memory:");
        issue_token(&db, A, "Inviter").unwrap();
        issue_token(&db, B, "Friend").unwrap();
        assert!(!are_friends(&db, A, B).unwrap());
        // the handler itself checks `are_friends` before insert; here we just
        // confirm the helper the handler relies on reports the right thing
        db.execute("INSERT INTO messages (from_uuid, to_uuid, body, sent_at) VALUES (?1, ?2, ?3, ?4)", params![A, B, "hi", now()]).unwrap();
        let (a, b) = friend_pair(A, B);
        db.execute("INSERT INTO friendships (a, b, created_at) VALUES (?1, ?2, ?3)", params![a, b, now()]).unwrap();
        assert!(are_friends(&db, A, B).unwrap());
        assert!(are_friends(&db, B, A).unwrap());
    }

    fn befriend(db: &Connection, x: &str, y: &str) {
        let (a, b) = friend_pair(x, y);
        db.execute("INSERT INTO friendships (a, b, created_at) VALUES (?1, ?2, ?3)", params![a, b, now()]).unwrap();
    }

    fn say(db: &Connection, from: &str, to: &str, body: &str) {
        db.execute(
            "INSERT INTO messages (from_uuid, to_uuid, body, sent_at) VALUES (?1, ?2, ?3, ?4)",
            params![from, to, body, now()],
        )
        .unwrap();
    }

    #[test]
    fn unread_clears_once_fetched() {
        let db = open_db(":memory:");
        issue_token(&db, A, "Inviter").unwrap();
        issue_token(&db, B, "Friend").unwrap();
        befriend(&db, A, B);
        say(&db, B, A, "hey");
        say(&db, B, A, "you there?");
        say(&db, A, B, "yep");
        assert_eq!(read_friends(&db, A).unwrap()[0].unread, 2);
        assert_eq!(read_friends(&db, B).unwrap()[0].unread, 1);

        let got = read_messages(&db, A, B, 0).unwrap();
        assert_eq!(got.len(), 3);
        assert_eq!(read_friends(&db, A).unwrap()[0].unread, 0, "fetching marks read");
        assert_eq!(read_friends(&db, B).unwrap()[0].unread, 1, "only for the reader");

        say(&db, B, A, "new");
        assert_eq!(read_friends(&db, A).unwrap()[0].unread, 1);
        // an empty poll leaves the mark where it was
        assert!(read_messages(&db, A, B, i64::MAX).unwrap().is_empty());
        assert_eq!(read_friends(&db, A).unwrap()[0].unread, 1);
    }

    #[test]
    fn first_page_is_the_newest() {
        let db = open_db(":memory:");
        issue_token(&db, A, "Inviter").unwrap();
        issue_token(&db, B, "Friend").unwrap();
        befriend(&db, A, B);
        for i in 0..MESSAGE_PAGE + 5 {
            say(&db, A, B, &format!("m{i}"));
        }
        let first = read_messages(&db, B, A, 0).unwrap();
        assert_eq!(first.len() as i64, MESSAGE_PAGE);
        assert_eq!(first.last().unwrap().body, format!("m{}", MESSAGE_PAGE + 4), "ends on the latest");
        assert!(first.windows(2).all(|w| w[0].id < w[1].id), "oldest-first");
        // polling after an older id walks forward in order
        let after = read_messages(&db, B, A, first[0].id - 3).unwrap();
        assert_eq!(after[0].id, first[0].id - 2);
    }

    #[test]
    fn friends_list_puts_online_first() {
        let db = open_db(":memory:");
        issue_token(&db, A, "Me").unwrap();
        issue_token(&db, B, "Zed").unwrap();
        issue_token(&db, C, "Amy").unwrap();
        befriend(&db, A, B);
        befriend(&db, A, C);
        db.execute("UPDATE accounts SET last_seen = 0, playing = '1.21.4' WHERE uuid = ?1", params![C]).unwrap();
        db.execute("UPDATE accounts SET playing = '1.21.4' WHERE uuid = ?1", params![B]).unwrap();
        let list = read_friends(&db, A).unwrap();
        assert_eq!(list.iter().map(|f| f.username.as_str()).collect::<Vec<_>>(), ["Zed", "Amy"]);
        assert_eq!(list[0].playing.as_deref(), Some("1.21.4"));
        assert_eq!(list[1].playing, None, "an offline friend isn't playing anything");
    }

    #[test]
    fn friend_pair_is_order_independent() {
        assert_eq!(friend_pair(A, B), friend_pair(B, A));
    }

    #[test]
    fn launches_column_migrates() {
        let db = Connection::open_in_memory().unwrap();
        db.execute_batch(
            "CREATE TABLE accounts (uuid TEXT PRIMARY KEY, username TEXT NOT NULL, coins INTEGER NOT NULL DEFAULT 0,
             created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL);",
        )
        .unwrap();
        add_column(&db, "accounts", "launches", "INTEGER NOT NULL DEFAULT 0");
        add_column(&db, "accounts", "launches", "INTEGER NOT NULL DEFAULT 0");
        db.query_row("SELECT launches FROM accounts", [], |_| Ok(())).optional().unwrap();
    }

    const C: &str = "cccccccc-0000-0000-0000-000000000003";

    fn three() -> Connection {
        let db = open_db(":memory:");
        issue_token(&db, A, "Alice").unwrap();
        issue_token(&db, B, "Bob").unwrap();
        issue_token(&db, C, "Carol").unwrap();
        db
    }

    #[test]
    fn appear_offline_hides_presence_and_freezes_last_seen() {
        let db = three();
        befriend(&db, A, B);
        db.execute("UPDATE accounts SET playing = '1.21.4', server = 'mc.example.net' WHERE uuid = ?1", params![B]).unwrap();
        let seen = &read_friends(&db, A).unwrap()[0];
        assert!(seen.online);
        assert_eq!(seen.server.as_deref(), Some("mc.example.net"));

        db.execute("UPDATE accounts SET share_activity = 0 WHERE uuid = ?1", params![B]).unwrap();
        let seen = &read_friends(&db, A).unwrap()[0];
        assert!(seen.online && seen.playing.is_none() && seen.server.is_none());

        db.execute("UPDATE accounts SET hidden_since = 100, share_activity = 1 WHERE uuid = ?1", params![B]).unwrap();
        let seen = &read_friends(&db, A).unwrap()[0];
        assert!(!seen.online && seen.playing.is_none());
        assert_eq!(seen.last_seen, 100);
    }

    #[test]
    fn friend_request_policy() {
        let db = three();
        assert!(accepts_request_from(&db, B, A).unwrap());
        db.execute("UPDATE accounts SET friend_requests = 'nobody' WHERE uuid = ?1", params![B]).unwrap();
        assert!(!accepts_request_from(&db, B, A).unwrap());
        db.execute("UPDATE accounts SET friend_requests = 'friends_of_friends' WHERE uuid = ?1", params![B]).unwrap();
        assert!(!accepts_request_from(&db, B, A).unwrap());
        befriend(&db, A, C);
        befriend(&db, B, C);
        assert!(accepts_request_from(&db, B, A).unwrap());
    }

    #[test]
    fn blocking_ends_friendship_and_requests() {
        let db = three();
        befriend(&db, A, B);
        db.execute("INSERT INTO friend_requests (from_uuid, to_uuid, created_at) VALUES (?1, ?2, 0)", params![B, A]).unwrap();
        block_tx(&db, A, B).unwrap();
        assert!(!are_friends(&db, A, B).unwrap());
        assert!(read_requests(&db, A).unwrap().incoming.is_empty());
        // the blocked side just sees someone not accepting requests
        assert!(!accepts_request_from(&db, A, B).unwrap());
        assert_eq!(read_blocks(&db, A).unwrap()[0].username, "Bob");
        assert!(block_tx(&db, A, A).is_err());
    }

    #[test]
    fn gift_moves_coins_and_ownership() {
        let db = three();
        let cat = load_catalog();
        let item = &cat[&8];
        assert!(gift_tx(&db, item, A, B).is_err(), "strangers can't gift");
        befriend(&db, A, B);
        assert!(gift_tx(&db, item, A, B).is_err(), "no coins");
        grant(&db, A, 1000, "test").unwrap();
        let (coins, msg) = gift_tx(&db, item, A, B).unwrap();
        assert_eq!(coins, 1000 - item.price);
        assert_eq!(msg.kind, "gift");
        assert_eq!(owned_ids(&db, B).unwrap(), vec![8]);
        assert!(owned_ids(&db, A).unwrap().is_empty());
        assert!(gift_tx(&db, item, A, B).is_err(), "already owned");
        let got = read_messages(&db, B, A, 0).unwrap();
        assert_eq!(got[0].meta.as_ref().unwrap()["item"], 8);
    }

    #[test]
    fn invite_and_image_messages_validate() {
        let db = three();
        let invite = SendMessage {
            body: String::new(),
            kind: Some("invite".into()),
            meta: Some(json!({ "server": "Play.Example.net:25565", "version": "1.21.4" })),
        };
        let (body, kind, meta) = compose_message(&db, A, &invite).unwrap();
        assert_eq!(kind, "invite");
        assert_eq!(body, "Join me on play.example.net:25565");
        assert_eq!(meta.unwrap()["version"], "1.21.4");
        let bad_invite = SendMessage { body: String::new(), kind: Some("invite".into()), meta: Some(json!({ "server": "http://x y" })) };
        assert!(compose_message(&db, A, &bad_invite).is_err());

        let image = SendMessage { body: String::new(), kind: Some("image".into()), meta: Some(json!({ "image": "abc" })) };
        assert!(compose_message(&db, A, &image).is_err(), "not uploaded");
        db.execute("INSERT INTO images (id, owner, mime, bytes, created_at) VALUES ('abc', ?1, 'image/png', x'00', 0)", params![A]).unwrap();
        assert!(compose_message(&db, B, &image).is_err(), "someone else's image");
        let (_, kind, meta) = compose_message(&db, A, &image).unwrap();
        insert_message(&db, A, B, "Sent a screenshot", &kind, meta.as_ref()).unwrap();
        let shared: bool = db
            .query_row(
                "SELECT 1 FROM messages WHERE kind = 'image' AND from_uuid = ?1 AND to_uuid = ?2 AND json_extract(meta, '$.image') = ?3",
                params![A, B, "abc"],
                |_| Ok(true),
            )
            .unwrap();
        assert!(shared);
    }

    #[test]
    fn server_addresses_are_cleaned() {
        assert_eq!(clean_server(" MC.Hypixel.net ").as_deref(), Some("mc.hypixel.net"));
        assert_eq!(clean_server("[::1]:25565").as_deref(), Some("[::1]:25565"));
        assert!(clean_server("a b").is_none());
        assert!(clean_server("x/../y").is_none());
        assert!(clean_server("").is_none());
    }

    #[test]
    fn client_settings_round_trip() {
        let db = three();
        let empty = read_client_settings(&db, A).unwrap();
        assert!(empty.settings.is_none());
        assert_eq!(empty.updated_at, 0);
        db.execute(
            "INSERT INTO client_settings (uuid, json, updated_at) VALUES (?1, ?2, 5)",
            params![A, r#"{"hud":{"fps":{"enabled":true}}}"#],
        )
        .unwrap();
        let got = read_client_settings(&db, A).unwrap();
        assert_eq!(got.updated_at, 5);
        assert_eq!(got.settings.unwrap()["hud"]["fps"]["enabled"], true);
        assert!(read_client_settings(&db, B).unwrap().settings.is_none(), "per account");
    }

    #[test]
    fn a_shared_instance_comes_back_by_its_code_however_its_typed() {
        let db = three();
        let pack = b"PK\x03\x04 the rest".to_vec();
        let code = store_pack(&db, A, &pack).unwrap();
        assert_eq!(code.len(), CODE_LEN);
        assert_eq!(load_pack(&db, &code).unwrap(), pack);
        let typed = format!("{}-{}", &code[..4], &code[4..]).to_lowercase();
        assert_eq!(load_pack(&db, &typed).unwrap(), pack);
        assert_eq!(load_pack(&db, "AAAA-AAAA").unwrap_err().0, StatusCode::NOT_FOUND);
        assert_eq!(load_pack(&db, "../../x").unwrap_err().0, StatusCode::NOT_FOUND);
        assert!(store_pack(&db, A, b"not a zip").is_err());
        // expired codes stop working
        db.execute("UPDATE shared_packs SET created_at = 0", []).unwrap();
        assert_eq!(load_pack(&db, &code).unwrap_err().0, StatusCode::NOT_FOUND);
    }

    #[test]
    fn image_sniffing() {
        assert_eq!(image_mime(b"\x89PNG\r\n\x1a\nrest"), Some("image/png"));
        assert_eq!(image_mime(&[0xff, 0xd8, 0xff, 0xe0]), Some("image/jpeg"));
        assert_eq!(image_mime(b"GIF89a"), None);
    }
}
