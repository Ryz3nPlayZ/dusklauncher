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
//! Config is all env: `DUSK_DB` (sqlite path), `DUSK_BIND` (host:port),
//! `DUSK_CODES` (extra `code:coins,...` on top of the built-in ones),
//! `DUSK_DEV_AUTH=1` (enables `/v1/auth/dev`, never in production).

use axum::{
    extract::{Path, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post, put},
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
    let price = |i: &CatalogItem| if i.animated { PRICE_ANIMATED } else { PRICE_STILL };
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
         CREATE INDEX IF NOT EXISTS referrals_by_referrer ON referrals (referrer);",
    )
    .expect("schema");
    add_column(&db, "accounts", "launches", "INTEGER NOT NULL DEFAULT 0");
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

// ── account ────────────────────────────────────────────────────────────────

#[derive(Serialize)]
struct Me {
    uuid: String,
    username: String,
    coins: i64,
    owned: Vec<u32>,
    loadout: Map<String, Value>,
}

fn read_me(db: &Connection, uuid: &str) -> Result<Me, ApiError> {
    let (username, coins): (String, i64) = db
        .query_row("SELECT username, coins FROM accounts WHERE uuid = ?1", params![uuid], |r| Ok((r.get(0)?, r.get(1)?)))
        .optional()?
        .ok_or_else(unauthorized)?;
    Ok(Me { uuid: uuid.to_string(), username, coins, owned: owned_ids(db, uuid)?, loadout: read_loadout(db, uuid)? })
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
    if paid < MAX_PAID_REFERRALS {
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
    let (created_at, coins): (i64, i64) = db
        .query_row("SELECT created_at, coins FROM accounts WHERE uuid = ?1", params![uuid], |r| Ok((r.get(0)?, r.get(1)?)))
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
        can_claim: mine.is_none() && now() - created_at <= REFERRAL_WINDOW.as_secs() as i64,
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

// ── public ─────────────────────────────────────────────────────────────────

#[derive(Serialize)]
struct PublicLoadout {
    uuid: String,
    cape: Option<u32>,
    accessories: Vec<u32>,
}

/// What a Dusk client draws on another Dusk player. Only slots the mod knows
/// today; `settings` and unknown slots stay private.
async fn public_loadout(State(app): State<Shared>, Path(raw): Path<String>) -> Result<Response, ApiError> {
    let uuid = dashed_uuid(&raw).ok_or_else(|| bad("bad uuid"))?;
    let db = app.db.lock().unwrap();
    let exists: bool = db
        .query_row("SELECT 1 FROM loadouts WHERE uuid = ?1", params![uuid], |_| Ok(true))
        .optional()?
        .unwrap_or(false);
    if !exists {
        return Ok((StatusCode::NOT_FOUND, Json(json!({ "error": "no loadout" }))).into_response());
    }
    let lo = read_loadout(&db, &uuid)?;
    let cape = lo.get("cape").and_then(Value::as_u64).and_then(|n| u32::try_from(n).ok());
    let accessories = lo
        .get("accessories")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_u64).filter_map(|n| u32::try_from(n).ok()).collect())
        .unwrap_or_default();
    Ok(([(header::CACHE_CONTROL, "public, max-age=60")], Json(PublicLoadout { uuid, cape, accessories })).into_response())
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
        .route("/v1/me", get(me))
        .route("/v1/me/redeem", post(redeem))
        .route("/v1/me/buy", post(buy))
        .route("/v1/me/loadout", put(put_loadout))
        .route("/v1/me/referral", get(get_referral).post(claim_referral))
        .route("/v1/me/launched", post(launched))
        .route("/v1/loadout/{uuid}", get(public_loadout))
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
        assert_eq!(dashed_uuid("ba4161c0-3a42-496c-8ae0-7d13372f3371").is_some(), true);
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
    fn old_accounts_cannot_claim() {
        let db = open_db(":memory:");
        issue_token(&db, A, "Old").unwrap();
        db.execute("UPDATE accounts SET created_at = 0 WHERE uuid = ?1", params![A]).unwrap();
        assert!(!read_referral(&db, A).unwrap().can_claim);
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
}
