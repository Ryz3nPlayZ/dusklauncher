//! Parties: a small group of friends who play together (`/v1/party/...`).
//!
//! A party lives in memory only; a restart ends every party, which the
//! clients notice and shrug off. Members keep one WebSocket open
//! (`/v1/party/ws`) while they're signed in. Over it they tell the party
//! where they are (the locator), drop pings in the world, and run Play
//! together; we push party changes, invites and join requests down it.
//!
//! Everything goes only to people in the same party, and a party invite only
//! goes to a friend who hasn't blocked (or been blocked by) the sender.
//! Hosted worlds are the host's own game reachable through e4mc: when a host
//! invites the party (or lets someone in) we hand each player the address.

use crate::{authed, now, ApiError, Shared};
use axum::{
    body::Bytes,
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        Path, State,
    },
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    Json,
};
use futures_util::{SinkExt, StreamExt};
use rand::RngCore;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet, VecDeque};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::mpsc;

pub const MAX_MEMBERS: usize = 8;
/// Seconds a party invite stays open.
const INVITE_TTL: i64 = 5 * 60;
/// Seconds a request to join a hosted world waits for the host.
const REQUEST_TTL: i64 = 60;
/// A party nobody has been connected to for this long is gone.
const EMPTY_TTL: i64 = 15 * 60;
/// A leader who has been away this long hands the party to someone who's here.
const LEADER_AWAY: i64 = 3 * 60;
/// Pings allowed per [`PING_WINDOW_MS`]; the client limits itself well below this.
const PING_BURST: usize = 8;
const PING_WINDOW_MS: i64 = 4_000;
/// Invites one player may send per minute.
const INVITE_RATE: usize = 12;
const QUEUE: usize = 256;
/// The biggest member state we keep; real ones are a few hundred bytes.
const MAX_STATE: usize = 4096;

// ── errors ─────────────────────────────────────────────────────────────────

/// `{"error": code, "message": text}`; the client keys off the code.
#[derive(Debug, PartialEq)]
pub struct PartyError(pub StatusCode, pub &'static str, pub String);

impl IntoResponse for PartyError {
    fn into_response(self) -> Response {
        (self.0, Json(json!({ "error": self.1, "message": self.2 }))).into_response()
    }
}

impl From<ApiError> for PartyError {
    fn from(e: ApiError) -> Self {
        let code = if e.0 == StatusCode::UNAUTHORIZED { "unauthorized" } else { "internal" };
        PartyError(e.0, code, e.1)
    }
}

impl From<rusqlite::Error> for PartyError {
    fn from(e: rusqlite::Error) -> Self {
        ApiError::from(e).into()
    }
}

fn fail(status: StatusCode, code: &'static str, msg: &str) -> PartyError {
    PartyError(status, code, msg.to_string())
}

fn not_in_party() -> PartyError {
    fail(StatusCode::NOT_FOUND, "not_in_party", "You're not in a party")
}

fn not_leader() -> PartyError {
    fail(StatusCode::FORBIDDEN, "not_leader", "Only the party leader can do that")
}

type PResult<T> = Result<T, PartyError>;

// ── time and ids ───────────────────────────────────────────────────────────

/// Unix seconds → `2026-11-26T10:00:00Z`.
pub fn iso(t: i64) -> String {
    let (days, secs) = (t.div_euclid(86_400), t.rem_euclid(86_400));
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", secs / 3600, secs % 3600 / 60, secs % 60)
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64
}

fn new_id() -> String {
    let mut b = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut b);
    hex::encode(b)
}

/// The client's uuids may come with or without dashes, in any case.
fn norm(raw: &str) -> Option<String> {
    crate::dashed_uuid(raw)
}

/// Text from a client, trimmed to `max` characters with control characters dropped.
fn clean(v: Option<&Value>, max: usize) -> Option<String> {
    let s: String = v?.as_str()?.chars().filter(|c| !c.is_control()).take(max).collect();
    let s = s.trim().to_string();
    (!s.is_empty()).then_some(s)
}

// ── state ──────────────────────────────────────────────────────────────────

struct Member {
    player: String,
    joined_at: i64,
    state: Option<Value>,
    hard_muted: bool,
    /// When they last dropped off; `None` while connected.
    away_since: Option<i64>,
}

struct Party {
    id: String,
    leader: String,
    created_at: i64,
    voice_mode: String,
    invite_policy: String,
    members: Vec<Member>,
    /// When the last connected member left.
    empty_since: Option<i64>,
}

impl Party {
    fn member(&self, uuid: &str) -> Option<&Member> {
        self.members.iter().find(|m| m.player == uuid)
    }

    fn member_mut(&mut self, uuid: &str) -> Option<&mut Member> {
        self.members.iter_mut().find(|m| m.player == uuid)
    }
}

#[derive(Clone, Copy, PartialEq, Debug)]
enum InviteStatus {
    Pending,
    Accepted,
    Declined,
    Cancelled,
    Expired,
}

impl InviteStatus {
    fn name(self) -> &'static str {
        match self {
            InviteStatus::Pending => "pending",
            InviteStatus::Accepted => "accepted",
            InviteStatus::Declined => "declined",
            InviteStatus::Cancelled => "cancelled",
            InviteStatus::Expired => "expired",
        }
    }
}

struct Invite {
    id: String,
    party_id: String,
    sender: String,
    recipient: String,
    created_at: i64,
    expires_at: i64,
}

struct JoinRequest {
    id: String,
    session_id: String,
    host: String,
    player: String,
    expires_at: i64,
}

/// A hosted world someone in a party opened, and where it can be reached.
struct Session {
    host: String,
    server: String,
    version: Option<String>,
}

struct Play {
    party_id: String,
    leader: String,
    session_id: Option<String>,
}

struct Conn {
    id: u64,
    tx: mpsc::Sender<Message>,
    pings: VecDeque<i64>,
}

#[derive(Default)]
pub struct Hub {
    conns: HashMap<String, Conn>,
    parties: HashMap<String, Party>,
    /// uuid → the party they're in.
    member_of: HashMap<String, String>,
    invites: HashMap<String, Invite>,
    /// uuid → when they last sent invites, for the rate limit.
    invite_times: HashMap<String, VecDeque<i64>>,
    requests: HashMap<String, JoinRequest>,
    sessions: HashMap<String, Session>,
    plays: HashMap<String, Play>,
    next_conn: u64,
    next_session_invite: i32,
}

/// What a REST call needs to know about the caller from the database.
pub struct Social {
    pub friends: HashSet<String>,
    /// Blocked by them, or blocking them.
    pub blocked: HashSet<String>,
}

impl Hub {
    // ── sockets ────────────────────────────────────────────────────────────

    fn push(&self, uuid: &str, v: Value) {
        if let Some(c) = self.conns.get(uuid) {
            // a full queue means a stuck client; it'll refresh when it catches up
            let _ = c.tx.try_send(Message::Text(v.to_string().into()));
        }
    }

    fn push_party(&self, party: &Party, v: &Value, except: Option<&str>) {
        for m in &party.members {
            if Some(m.player.as_str()) != except {
                self.push(&m.player, v.clone());
            }
        }
    }

    fn online(&self, uuid: &str) -> bool {
        self.conns.contains_key(uuid)
    }

    pub fn join(&mut self, uuid: &str, tx: mpsc::Sender<Message>, t: i64) -> u64 {
        self.next_conn += 1;
        let id = self.next_conn;
        // a second socket for the same account replaces the first
        self.conns.insert(uuid.to_string(), Conn { id, tx, pings: VecDeque::new() });
        if let Some(pid) = self.member_of.get(uuid).cloned() {
            if let Some(p) = self.parties.get_mut(&pid) {
                p.empty_since = None;
                if let Some(m) = p.member_mut(uuid) {
                    m.away_since = None;
                }
            }
            self.broadcast_party(&pid);
        }
        let _ = t;
        id
    }

    pub fn owns(&self, uuid: &str, id: u64) -> bool {
        self.conns.get(uuid).is_some_and(|c| c.id == id)
    }

    pub fn part(&mut self, uuid: &str, id: u64, t: i64) {
        if !self.owns(uuid, id) {
            return;
        }
        self.conns.remove(uuid);
        let Some(pid) = self.member_of.get(uuid).cloned() else { return };
        let anyone = {
            let Some(p) = self.parties.get_mut(&pid) else { return };
            if let Some(m) = p.member_mut(uuid) {
                m.away_since = Some(t);
            }
            p.members.iter().any(|m| self.conns.contains_key(&m.player))
        };
        if let Some(p) = self.parties.get_mut(&pid) {
            if !anyone {
                p.empty_since = Some(t);
            }
        }
        self.broadcast_party(&pid);
    }

    // ── views ──────────────────────────────────────────────────────────────

    fn party_json(&self, p: &Party) -> Value {
        let members: Vec<Value> = p
            .members
            .iter()
            .map(|m| {
                json!({
                    "player": m.player,
                    "joined_at": iso(m.joined_at),
                    "online": self.online(&m.player),
                    "voice_hard_muted": m.hard_muted,
                    "state": m.state,
                })
            })
            .collect();
        json!({
            "id": p.id,
            "leader": p.leader,
            "created_at": iso(p.created_at),
            "settings": { "voice_mode": p.voice_mode, "invite_policy": p.invite_policy },
            "voice": { "transport": "p2p", "rtc_available": false },
            "members": members,
        })
    }

    fn invite_json(&self, i: &Invite, status: InviteStatus) -> Value {
        let size = self.parties.get(&i.party_id).map_or(1, |p| p.members.len());
        json!({
            "id": i.id,
            "party_id": i.party_id,
            "sender": i.sender,
            "recipient": i.recipient,
            "status": status.name(),
            "created_at": iso(i.created_at),
            "expires_at": iso(i.expires_at),
            "party_size": size,
        })
    }

    fn broadcast_party(&self, pid: &str) {
        if let Some(p) = self.parties.get(pid) {
            let v = json!({ "type": "PartyUpdated", "party": self.party_json(p) });
            self.push_party(p, &v, None);
        }
    }

    fn party_of(&self, uuid: &str) -> PResult<&Party> {
        self.member_of.get(uuid).and_then(|pid| self.parties.get(pid)).ok_or_else(not_in_party)
    }

    pub fn current(&self, uuid: &str) -> Option<Value> {
        self.party_of(uuid).ok().map(|p| self.party_json(p))
    }

    // ── membership ─────────────────────────────────────────────────────────

    fn create_party(&mut self, uuid: &str, t: i64, voice_mode: Option<String>, invite_policy: Option<String>) -> String {
        let id = new_id();
        let party = Party {
            id: id.clone(),
            leader: uuid.to_string(),
            created_at: t,
            voice_mode: voice_mode.unwrap_or_else(|| "party".into()),
            invite_policy: invite_policy.unwrap_or_else(|| "leader".into()),
            members: vec![Member { player: uuid.to_string(), joined_at: t, state: None, hard_muted: false, away_since: None }],
            empty_since: None,
        };
        self.parties.insert(id.clone(), party);
        self.member_of.insert(uuid.to_string(), id.clone());
        id
    }

    pub fn create(&mut self, uuid: &str, body: &Value, t: i64) -> PResult<Value> {
        if self.member_of.contains_key(uuid) {
            return Err(fail(StatusCode::CONFLICT, "already_in_party", "You're already in a party"));
        }
        let (voice, policy) = settings(body)?;
        let pid = self.create_party(uuid, t, voice, policy);
        self.broadcast_party(&pid);
        Ok(self.party_json(&self.parties[&pid]))
    }

    pub fn update_settings(&mut self, uuid: &str, body: &Value) -> PResult<Value> {
        let pid = self.party_of(uuid)?.id.clone();
        let (voice, policy) = settings(body)?;
        let p = self.parties.get_mut(&pid).unwrap();
        if p.leader != uuid {
            return Err(not_leader());
        }
        if let Some(v) = voice {
            p.voice_mode = v;
        }
        if let Some(v) = policy {
            p.invite_policy = v;
        }
        self.broadcast_party(&pid);
        Ok(self.party_json(&self.parties[&pid]))
    }

    /// Take `uuid` out of their party, handing it on or ending it as needed.
    fn remove_member(&mut self, uuid: &str, reason: &str) {
        let Some(pid) = self.member_of.remove(uuid) else { return };
        self.push(uuid, json!({ "type": "PartyLeft", "party_id": pid, "reason": reason }));
        let Some(p) = self.parties.get_mut(&pid) else { return };
        p.members.retain(|m| m.player != uuid);
        if p.members.is_empty() {
            self.end_party(&pid, "disbanded");
            return;
        }
        if p.leader == uuid {
            // the longest-standing member who's here, else the longest-standing one
            let conns = &self.conns;
            let next = p
                .members
                .iter()
                .find(|m| conns.contains_key(&m.player))
                .or(p.members.first())
                .map(|m| m.player.clone())
                .unwrap();
            p.leader = next;
            self.end_plays(&pid, "leader_left");
        }
        self.drop_requests_for(uuid);
        self.broadcast_party(&pid);
    }

    fn end_party(&mut self, pid: &str, reason: &str) {
        let Some(p) = self.parties.remove(pid) else { return };
        for m in &p.members {
            self.member_of.remove(&m.player);
            self.push(&m.player, json!({ "type": "PartyLeft", "party_id": pid, "reason": reason }));
        }
        let open: Vec<String> = self.invites.values().filter(|i| i.party_id == pid).map(|i| i.id.clone()).collect();
        for id in open {
            self.close_invite(&id, InviteStatus::Cancelled);
        }
        self.plays.retain(|_, play| play.party_id != pid);
    }

    fn end_plays(&mut self, pid: &str, reason: &str) {
        let ended: Vec<String> = self.plays.iter().filter(|(_, p)| p.party_id == pid).map(|(id, _)| id.clone()).collect();
        for id in ended {
            self.plays.remove(&id);
            if let Some(p) = self.parties.get(pid) {
                self.push_party(p, &json!({ "type": "PlayTogetherEnded", "id": id, "reason": reason }), None);
            }
        }
    }

    pub fn leave(&mut self, uuid: &str) -> PResult<()> {
        self.party_of(uuid)?;
        self.remove_member(uuid, "left");
        Ok(())
    }

    pub fn disband(&mut self, uuid: &str) -> PResult<()> {
        let p = self.party_of(uuid)?;
        if p.leader != uuid {
            return Err(not_leader());
        }
        let pid = p.id.clone();
        self.end_party(&pid, "disbanded");
        Ok(())
    }

    pub fn transfer(&mut self, uuid: &str, target: &str) -> PResult<Value> {
        let p = self.party_of(uuid)?;
        if p.leader != uuid {
            return Err(not_leader());
        }
        if p.member(target).is_none() {
            return Err(fail(StatusCode::NOT_FOUND, "member_not_found", "They're not in your party"));
        }
        let pid = p.id.clone();
        self.parties.get_mut(&pid).unwrap().leader = target.to_string();
        self.end_plays(&pid, "leader_left");
        self.broadcast_party(&pid);
        Ok(self.party_json(&self.parties[&pid]))
    }

    pub fn kick(&mut self, uuid: &str, target: &str) -> PResult<()> {
        let p = self.party_of(uuid)?;
        if p.leader != uuid {
            return Err(not_leader());
        }
        if target == uuid {
            return Err(fail(StatusCode::BAD_REQUEST, "cannot_kick_self", "You can't kick yourself; leave instead"));
        }
        if p.member(target).is_none() {
            return Err(fail(StatusCode::NOT_FOUND, "member_not_found", "They're not in your party"));
        }
        self.remove_member(target, "kicked");
        Ok(())
    }

    pub fn hard_mute(&mut self, uuid: &str, target: &str, muted: bool) -> PResult<()> {
        let p = self.party_of(uuid)?;
        if p.leader != uuid {
            return Err(not_leader());
        }
        let pid = p.id.clone();
        let m = self.parties.get_mut(&pid).unwrap().member_mut(target);
        let m = m.ok_or_else(|| fail(StatusCode::NOT_FOUND, "member_not_found", "They're not in your party"))?;
        m.hard_muted = muted;
        self.broadcast_party(&pid);
        Ok(())
    }

    // ── invites ────────────────────────────────────────────────────────────

    pub fn invite(&mut self, uuid: &str, target: &str, social: &Social, t: i64) -> PResult<Value> {
        if target == uuid {
            return Err(fail(StatusCode::BAD_REQUEST, "invalid_player", "You can't invite yourself"));
        }
        if social.blocked.contains(target) {
            return Err(fail(StatusCode::FORBIDDEN, "blocked", "You can't invite them"));
        }
        if !social.friends.contains(target) {
            return Err(fail(StatusCode::FORBIDDEN, "not_friends", "You can only invite friends"));
        }
        if let Ok(p) = self.party_of(uuid) {
            if p.leader != uuid && p.invite_policy != "members" {
                return Err(fail(StatusCode::FORBIDDEN, "not_allowed_to_invite", "Only the party leader can invite"));
            }
            if p.member(target).is_some() {
                return Err(fail(StatusCode::CONFLICT, "already_member", "They're already in your party"));
            }
            if p.members.len() >= MAX_MEMBERS {
                return Err(fail(StatusCode::CONFLICT, "party_full", "Your party is full"));
            }
            if self.invites.values().any(|i| i.party_id == p.id && i.recipient == target) {
                return Err(fail(StatusCode::CONFLICT, "already_invited", "They already have an invite"));
            }
        }
        let times = self.invite_times.entry(uuid.to_string()).or_default();
        while times.front().is_some_and(|&at| at <= t - 60) {
            times.pop_front();
        }
        if times.len() >= INVITE_RATE {
            return Err(fail(StatusCode::TOO_MANY_REQUESTS, "rate_limited", "You're sending invites too fast"));
        }
        times.push_back(t);
        // inviting with no party starts one
        let pid = match self.member_of.get(uuid) {
            Some(pid) => pid.clone(),
            None => {
                let pid = self.create_party(uuid, t, None, None);
                self.broadcast_party(&pid);
                pid
            }
        };
        let invite = Invite {
            id: new_id(),
            party_id: pid,
            sender: uuid.to_string(),
            recipient: target.to_string(),
            created_at: t,
            expires_at: t + INVITE_TTL,
        };
        let v = self.invite_json(&invite, InviteStatus::Pending);
        self.push(target, json!({ "type": "PartyInviteReceived", "invite": v }));
        self.invites.insert(invite.id.clone(), invite);
        Ok(v)
    }

    pub fn incoming(&self, uuid: &str) -> Vec<Value> {
        self.invites.values().filter(|i| i.recipient == uuid).map(|i| self.invite_json(i, InviteStatus::Pending)).collect()
    }

    pub fn outgoing(&self, uuid: &str) -> Vec<Value> {
        self.invites.values().filter(|i| i.sender == uuid).map(|i| self.invite_json(i, InviteStatus::Pending)).collect()
    }

    /// Close an invite and tell both ends.
    fn close_invite(&mut self, id: &str, status: InviteStatus) {
        let Some(i) = self.invites.remove(id) else { return };
        let v = json!({ "type": "PartyInviteUpdated", "invite_id": i.id, "status": status.name() });
        self.push(&i.sender, v.clone());
        self.push(&i.recipient, v);
    }

    fn invite_for(&self, id: &str, t: i64) -> PResult<&Invite> {
        let i = self.invites.get(id).ok_or_else(|| fail(StatusCode::NOT_FOUND, "invite_not_found", "That invite is gone"))?;
        if i.expires_at <= t {
            return Err(fail(StatusCode::GONE, "invite_expired", "That invite expired"));
        }
        Ok(i)
    }

    pub fn accept(&mut self, uuid: &str, id: &str, leave_current: bool, blocked: &HashSet<String>, t: i64) -> PResult<Value> {
        let i = self.invite_for(id, t)?;
        if i.recipient != uuid {
            return Err(fail(StatusCode::NOT_FOUND, "invite_not_found", "That invite is gone"));
        }
        let pid = i.party_id.clone();
        let Some(p) = self.parties.get(&pid) else {
            self.close_invite(id, InviteStatus::Expired);
            return Err(fail(StatusCode::GONE, "invite_expired", "That party is gone"));
        };
        if p.members.len() >= MAX_MEMBERS {
            return Err(fail(StatusCode::CONFLICT, "party_full", "That party is full"));
        }
        if p.members.iter().any(|m| blocked.contains(&m.player)) {
            return Err(fail(StatusCode::FORBIDDEN, "blocked", "You can't join that party"));
        }
        if let Some(mine) = self.member_of.get(uuid).cloned() {
            if mine == pid {
                self.close_invite(id, InviteStatus::Accepted);
                return Ok(self.party_json(&self.parties[&pid]));
            }
            if !leave_current {
                return Err(fail(StatusCode::CONFLICT, "already_in_party", "Leave your party first"));
            }
            self.remove_member(uuid, "left");
        }
        let away = (!self.online(uuid)).then_some(t);
        let p = self.parties.get_mut(&pid).unwrap();
        p.members.push(Member { player: uuid.to_string(), joined_at: t, state: None, hard_muted: false, away_since: away });
        if away.is_none() {
            p.empty_since = None;
        }
        self.member_of.insert(uuid.to_string(), pid.clone());
        self.close_invite(id, InviteStatus::Accepted);
        // anything else they'd been sent is moot now they're in a party
        self.broadcast_party(&pid);
        Ok(self.party_json(&self.parties[&pid]))
    }

    pub fn decline(&mut self, uuid: &str, id: &str) -> PResult<()> {
        match self.invites.get(id) {
            Some(i) if i.recipient == uuid => {
                self.close_invite(id, InviteStatus::Declined);
                Ok(())
            }
            _ => Err(fail(StatusCode::NOT_FOUND, "invite_not_found", "That invite is gone")),
        }
    }

    pub fn cancel(&mut self, uuid: &str, id: &str) -> PResult<()> {
        let allowed = self.invites.get(id).is_some_and(|i| {
            i.sender == uuid || self.parties.get(&i.party_id).is_some_and(|p| p.leader == uuid)
        });
        if !allowed {
            return Err(fail(StatusCode::NOT_FOUND, "invite_not_found", "That invite is gone"));
        }
        self.close_invite(id, InviteStatus::Cancelled);
        Ok(())
    }

    // ── hosted worlds ──────────────────────────────────────────────────────

    fn session_invite(&mut self, session_id: &str, host: &str, player: &str, t: i64) -> Value {
        self.next_session_invite += 1;
        // well clear of message ids, which the client uses for invites sent as messages
        let id = 1_900_000_000 + self.next_session_invite;
        let s = &self.sessions[session_id];
        let v = json!({
            "id": id,
            "session_id": session_id,
            "sender": host,
            "created_at": iso(t),
            "status": "pending",
            "minecraft_version": s.version,
            "server": s.server,
        });
        self.push(
            player,
            json!({
                "type": "SessionInviteReceived",
                "invite_id": id,
                "session_id": session_id,
                "sender": host,
                "server": s.server,
                "minecraft_version": s.version,
            }),
        );
        v
    }

    fn register_session(&mut self, uuid: &str, session_id: &str, body: &Value) -> PResult<()> {
        let server = clean(body.get("server"), 255);
        let version = clean(body.get("version"), 32);
        match (server, self.sessions.get_mut(session_id)) {
            (_, Some(s)) if s.host != uuid => Err(fail(StatusCode::FORBIDDEN, "not_host", "That isn't your world")),
            (Some(server), Some(s)) => {
                s.server = server;
                if version.is_some() {
                    s.version = version;
                }
                Ok(())
            }
            (None, Some(_)) => Ok(()),
            (Some(server), None) => {
                self.sessions.insert(session_id.to_string(), Session { host: uuid.to_string(), server, version });
                Ok(())
            }
            (None, None) => Err(fail(StatusCode::NOT_FOUND, "session_not_found", "Your world isn't open yet")),
        }
    }

    /// The host asks for the whole party to be let in.
    pub fn invite_session(&mut self, uuid: &str, session_id: &str, body: &Value, t: i64) -> PResult<Vec<Value>> {
        let p = self.party_of(uuid)?;
        let pid = p.id.clone();
        let others: Vec<String> = p.members.iter().map(|m| m.player.clone()).filter(|m| m != uuid).collect();
        self.register_session(uuid, session_id, body)?;
        // a Play together waiting on this world learns which one it is first
        let plays: Vec<String> = self
            .plays
            .iter()
            .filter(|(_, play)| play.party_id == pid && play.leader == uuid && play.session_id.is_none())
            .map(|(id, _)| id.clone())
            .collect();
        for id in plays {
            self.plays.get_mut(&id).unwrap().session_id = Some(session_id.to_string());
            let v = json!({ "type": "PlayTogetherUpdated", "id": id, "player": uuid, "response": "joining", "session_id": session_id });
            self.push_party(&self.parties[&pid], &v, None);
        }
        Ok(others.iter().map(|o| self.session_invite(session_id, uuid, o, t)).collect())
    }

    /// Where a member says they are, if it's the session.
    fn hosting(&self, host: &str, session_id: &str) -> bool {
        let key = format!("session:{session_id}");
        self.party_of(host)
            .ok()
            .and_then(|p| p.member(host))
            .and_then(|m| m.state.as_ref())
            .and_then(|s| s.pointer("/location/key"))
            .and_then(Value::as_str)
            == Some(key.as_str())
    }

    pub fn request_join(&mut self, uuid: &str, session_id: &str, blocked: &HashSet<String>, t: i64) -> PResult<Value> {
        let p = self.party_of(uuid)?;
        let host = match self.sessions.get(session_id) {
            Some(s) => s.host.clone(),
            None => p
                .members
                .iter()
                .find(|m| self.hosting(&m.player, session_id))
                .map(|m| m.player.clone())
                .ok_or_else(|| fail(StatusCode::NOT_FOUND, "session_not_found", "That world is closed"))?,
        };
        if host == uuid {
            return Err(fail(StatusCode::BAD_REQUEST, "own_session", "That's your own world"));
        }
        if p.member(&host).is_none() {
            return Err(fail(StatusCode::FORBIDDEN, "host_not_in_party", "The host isn't in your party"));
        }
        if blocked.contains(&host) {
            return Err(fail(StatusCode::FORBIDDEN, "blocked", "You can't join that world"));
        }
        if !self.online(&host) {
            return Err(fail(StatusCode::CONFLICT, "host_offline", "The host is offline"));
        }
        if self.requests.values().any(|r| r.session_id == session_id && r.player == uuid) {
            return Err(fail(StatusCode::CONFLICT, "join_request_pending", "You already asked to join"));
        }
        let r = JoinRequest {
            id: new_id(),
            session_id: session_id.to_string(),
            host: host.clone(),
            player: uuid.to_string(),
            expires_at: t + REQUEST_TTL,
        };
        let v = json!({ "id": r.id, "session_id": r.session_id, "host": r.host, "player": r.player, "expires_at": iso(r.expires_at) });
        self.push(
            &host,
            json!({ "type": "HostedJoinRequested", "request_id": r.id, "session_id": r.session_id, "player": uuid, "expires_at": iso(r.expires_at) }),
        );
        self.push(
            uuid,
            json!({ "type": "HostedJoinRequestSent", "request_id": r.id, "session_id": r.session_id, "host": host, "expires_at": iso(r.expires_at) }),
        );
        self.requests.insert(r.id.clone(), r);
        Ok(v)
    }

    fn resolve(&mut self, id: &str, result: &str, invite_id: Option<i32>) {
        let Some(r) = self.requests.remove(id) else { return };
        let v = json!({ "type": "HostedJoinResolved", "request_id": r.id, "session_id": r.session_id, "result": result, "invite_id": invite_id });
        self.push(&r.player, v.clone());
        self.push(&r.host, v);
    }

    pub fn answer_join(&mut self, uuid: &str, id: &str, allow: bool, body: &Value, t: i64) -> PResult<()> {
        let r = self
            .requests
            .get(id)
            .filter(|r| r.host == uuid)
            .ok_or_else(|| fail(StatusCode::NOT_FOUND, "join_request_not_found", "That request is gone"))?;
        if !allow {
            self.resolve(id, "denied", None);
            return Ok(());
        }
        let (session_id, player) = (r.session_id.clone(), r.player.clone());
        self.register_session(uuid, &session_id, body)?;
        // the invite goes first so the player has it when they hear they were let in
        let invite = self.session_invite(&session_id, uuid, &player, t);
        let invite_id = invite["id"].as_i64().map(|i| i as i32);
        self.resolve(id, "allowed", invite_id);
        Ok(())
    }

    fn drop_requests_for(&mut self, uuid: &str) {
        let gone: Vec<String> = self.requests.values().filter(|r| r.player == uuid || r.host == uuid).map(|r| r.id.clone()).collect();
        for id in gone {
            self.resolve(&id, "cancelled", None);
        }
    }

    pub fn close_session(&mut self, uuid: &str, session_id: &str) {
        if self.sessions.get(session_id).is_some_and(|s| s.host == uuid) {
            self.sessions.remove(session_id);
            let gone: Vec<String> = self.requests.values().filter(|r| r.session_id == session_id).map(|r| r.id.clone()).collect();
            for id in gone {
                self.resolve(&id, "cancelled", None);
            }
        }
    }

    // ── socket messages ────────────────────────────────────────────────────

    fn error(&self, uuid: &str, code: &str, message: &str) {
        self.push(uuid, json!({ "type": "Error", "error_code": code, "message": message }));
    }

    pub fn handle_text(&mut self, uuid: &str, text: &str, t: i64) {
        let Ok(v) = serde_json::from_str::<Value>(text) else { return };
        match v.get("type").and_then(Value::as_str) {
            Some("SetPartyState") => self.set_state(uuid, &v),
            Some("SendPing") => self.ping(uuid, &v),
            Some("ClearPing") => self.clear_ping(uuid, &v),
            Some("ReplyPing") => self.reply_ping(uuid, &v),
            Some("StartPlayTogether") => self.start_play(uuid, &v, t),
            Some("CancelPlayTogether") => self.cancel_play(uuid, &v),
            Some("PlayTogetherResponse") => self.play_response(uuid, &v),
            _ => {}
        }
    }

    fn set_state(&mut self, uuid: &str, v: &Value) {
        let Some(state) = v.get("state").filter(|s| s.is_object()) else { return };
        if state.to_string().len() > MAX_STATE {
            return;
        }
        let Some(pid) = self.member_of.get(uuid).cloned() else { return };
        let Some(m) = self.parties.get_mut(&pid).and_then(|p| p.member_mut(uuid)) else { return };
        if m.state.as_ref() == Some(state) {
            return;
        }
        m.state = Some(state.clone());
        let out = json!({ "type": "PartyMemberStateChanged", "party_id": pid, "player": uuid, "state": state });
        self.push_party(&self.parties[&pid], &out, Some(uuid));
    }

    fn ping(&mut self, uuid: &str, v: &Value) {
        let Some(pid) = self.member_of.get(uuid).cloned() else {
            return self.error(uuid, "not_in_party", "You're not in a party");
        };
        let coord = |k: &str| v.get(k).and_then(Value::as_f64).filter(|c| c.is_finite() && c.abs() < 3.0e7);
        let (Some(id), Some(server), Some(dim), Some(x), Some(y), Some(z), Some(kind)) = (
            clean(v.get("id"), 64),
            clean(v.get("server"), 128),
            clean(v.get("dimension"), 128),
            coord("x"),
            coord("y"),
            coord("z"),
            clean(v.get("kind"), 16),
        ) else {
            return self.error(uuid, "invalid_ping", "That ping didn't make sense");
        };
        let t = now_ms();
        let Some(conn) = self.conns.get_mut(uuid) else { return };
        while conn.pings.front().is_some_and(|&at| at <= t - PING_WINDOW_MS) {
            conn.pings.pop_front();
        }
        if conn.pings.len() >= PING_BURST {
            return self.error(uuid, "rate_limited", "Slow down with the pings");
        }
        conn.pings.push_back(t);
        let target = v.get("target").filter(|t| t.is_object() && t.to_string().len() <= 512).cloned();
        let out = json!({
            "type": "PartyPing",
            "from": uuid,
            "sent_at": t,
            "id": id,
            "server": server,
            "dimension": dim,
            "x": x, "y": y, "z": z,
            "kind": kind,
            "target": target,
            "label": clean(v.get("label"), 48),
        });
        self.push_party(&self.parties[&pid], &out, Some(uuid));
    }

    fn clear_ping(&mut self, uuid: &str, v: &Value) {
        let (Some(pid), Some(id)) = (self.member_of.get(uuid), clean(v.get("id"), 64)) else { return };
        let out = json!({ "type": "PartyPingCleared", "id": id, "from": uuid });
        self.push_party(&self.parties[pid], &out, Some(uuid));
    }

    fn reply_ping(&mut self, uuid: &str, v: &Value) {
        let Some(pid) = self.member_of.get(uuid) else {
            return self.error(uuid, "not_in_party", "You're not in a party");
        };
        let (Some(id), Some(owner), Some(reply)) = (clean(v.get("id"), 64), v.get("owner").and_then(Value::as_str).and_then(norm), clean(v.get("reply"), 16))
        else {
            return self.error(uuid, "invalid_ping", "That reply didn't make sense");
        };
        let p = &self.parties[pid];
        if p.member(&owner).is_none() {
            return;
        }
        let out = json!({ "type": "PartyPingReply", "id": id, "owner": owner, "from": uuid, "reply": reply, "sent_at": now_ms() });
        self.push_party(p, &out, Some(uuid));
    }

    fn start_play(&mut self, uuid: &str, v: &Value, t: i64) {
        let Ok(p) = self.party_of(uuid) else {
            return self.error(uuid, "not_in_party", "You're not in a party");
        };
        if p.leader != uuid {
            return self.error(uuid, "not_leader", "Only the party leader can start Play together");
        }
        let Some(dest) = v.get("destination").filter(|d| d.is_object() && d.to_string().len() <= 16 * 1024) else {
            return self.error(uuid, "invalid_destination", "Pick somewhere to play");
        };
        let pid = p.id.clone();
        // one at a time; a new one replaces the last
        let old: Vec<String> = self.plays.iter().filter(|(_, play)| play.party_id == pid).map(|(id, _)| id.clone()).collect();
        for id in old {
            self.plays.remove(&id);
            self.push_party(&self.parties[&pid], &json!({ "type": "PlayTogetherEnded", "id": id, "reason": "replaced" }), None);
        }
        let id = new_id();
        self.plays.insert(id.clone(), Play { party_id: pid.clone(), leader: uuid.to_string(), session_id: None });
        let out = json!({
            "type": "PlayTogetherStarted",
            "id": id,
            "party_id": pid,
            "leader": uuid,
            "destination": dest,
            "starts_at": iso(t + 3),
        });
        self.push_party(&self.parties[&pid], &out, None);
    }

    fn cancel_play(&mut self, uuid: &str, v: &Value) {
        let Some(id) = v.get("id").and_then(Value::as_str) else { return };
        let Some(play) = self.plays.get(id).filter(|p| p.leader == uuid) else { return };
        let pid = play.party_id.clone();
        self.plays.remove(id);
        if let Some(p) = self.parties.get(&pid) {
            self.push_party(p, &json!({ "type": "PlayTogetherEnded", "id": id, "reason": "cancelled" }), Some(uuid));
        }
    }

    fn play_response(&mut self, uuid: &str, v: &Value) {
        let (Some(id), Some(response)) = (v.get("id").and_then(Value::as_str), clean(v.get("response"), 16)) else { return };
        let Some(play) = self.plays.get(id) else { return };
        if self.member_of.get(uuid) != Some(&play.party_id) {
            return;
        }
        let out = json!({
            "type": "PlayTogetherUpdated",
            "id": id,
            "player": uuid,
            "response": response,
            "reason": clean(v.get("reason"), 16),
            "session_id": play.session_id,
        });
        self.push_party(&self.parties[&play.party_id], &out, None);
    }

    // ── housekeeping ───────────────────────────────────────────────────────

    pub fn prune(&mut self, t: i64) {
        let expired: Vec<String> = self.invites.values().filter(|i| i.expires_at <= t).map(|i| i.id.clone()).collect();
        for id in expired {
            self.close_invite(&id, InviteStatus::Expired);
        }
        let expired: Vec<String> = self.requests.values().filter(|r| r.expires_at <= t).map(|r| r.id.clone()).collect();
        for id in expired {
            self.resolve(&id, "expired", None);
        }
        let empty: Vec<String> =
            self.parties.values().filter(|p| p.empty_since.is_some_and(|at| t - at > EMPTY_TTL)).map(|p| p.id.clone()).collect();
        for pid in empty {
            self.end_party(&pid, "expired");
        }
        // a leader who wandered off hands over to someone who's here
        let handover: Vec<(String, String)> = self
            .parties
            .values()
            .filter_map(|p| {
                let leader = p.member(&p.leader)?;
                if !leader.away_since.is_some_and(|at| t - at > LEADER_AWAY) {
                    return None;
                }
                let next = p.members.iter().find(|m| self.conns.contains_key(&m.player))?;
                Some((p.id.clone(), next.player.clone()))
            })
            .collect();
        for (pid, next) in handover {
            self.parties.get_mut(&pid).unwrap().leader = next;
            self.end_plays(&pid, "leader_left");
            self.broadcast_party(&pid);
        }
        // sessions whose host left the party
        let gone: Vec<String> = self
            .sessions
            .iter()
            .filter(|(_, s)| !self.member_of.contains_key(&s.host) && !self.conns.contains_key(&s.host))
            .map(|(id, _)| id.clone())
            .collect();
        for id in gone {
            self.sessions.remove(&id);
        }
        self.invite_times.retain(|_, times| times.back().is_some_and(|&at| at > t - 60));
    }
}

/// `voice_mode` / `invite_policy` from a request body, each optional.
fn settings(body: &Value) -> PResult<(Option<String>, Option<String>)> {
    let pick = |k: &str, allowed: &[&str]| -> PResult<Option<String>> {
        match body.get(k) {
            None | Some(Value::Null) => Ok(None),
            Some(v) => match v.as_str() {
                Some(s) if allowed.contains(&s) => Ok(Some(s.to_string())),
                _ => Err(fail(StatusCode::BAD_REQUEST, "invalid_settings", "Unknown party setting")),
            },
        }
    };
    Ok((pick("voice_mode", &["party", "proximity"])?, pick("invite_policy", &["leader", "members"])?))
}

// ── HTTP ───────────────────────────────────────────────────────────────────

fn body_json(b: &Bytes) -> Value {
    if b.is_empty() {
        return json!({});
    }
    serde_json::from_slice(b).unwrap_or_else(|_| json!({}))
}

fn player(raw: &str) -> PResult<String> {
    norm(raw).ok_or_else(|| fail(StatusCode::BAD_REQUEST, "invalid_player", "That isn't a player"))
}

fn social(app: &Shared, uuid: &str) -> PResult<Social> {
    let (_, friends, blocked) = crate::voice::social(&app.db.lock().unwrap(), uuid)?;
    Ok(Social { friends, blocked })
}

pub async fn current(State(app): State<Shared>, headers: HeaderMap) -> Result<Response, PartyError> {
    let uuid = authed(&app, &headers)?;
    let party = app.party.lock().unwrap().current(&uuid);
    Ok(match party {
        Some(p) => Json(p).into_response(),
        None => StatusCode::NO_CONTENT.into_response(),
    })
}

pub async fn create(State(app): State<Shared>, headers: HeaderMap, body: Bytes) -> PResult<Json<Value>> {
    let uuid = authed(&app, &headers)?;
    let v = app.party.lock().unwrap().create(&uuid, &body_json(&body), now())?;
    Ok(Json(v))
}

pub async fn disband(State(app): State<Shared>, headers: HeaderMap) -> PResult<StatusCode> {
    let uuid = authed(&app, &headers)?;
    app.party.lock().unwrap().disband(&uuid)?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn update_settings(State(app): State<Shared>, headers: HeaderMap, body: Bytes) -> PResult<Json<Value>> {
    let uuid = authed(&app, &headers)?;
    let v = app.party.lock().unwrap().update_settings(&uuid, &body_json(&body))?;
    Ok(Json(v))
}

pub async fn leave(State(app): State<Shared>, headers: HeaderMap) -> PResult<StatusCode> {
    let uuid = authed(&app, &headers)?;
    app.party.lock().unwrap().leave(&uuid)?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn transfer(State(app): State<Shared>, headers: HeaderMap, body: Bytes) -> PResult<Json<Value>> {
    let uuid = authed(&app, &headers)?;
    let target = player(body_json(&body).get("player").and_then(Value::as_str).unwrap_or(""))?;
    let v = app.party.lock().unwrap().transfer(&uuid, &target)?;
    Ok(Json(v))
}

pub async fn kick(State(app): State<Shared>, headers: HeaderMap, Path(raw): Path<String>) -> PResult<StatusCode> {
    let uuid = authed(&app, &headers)?;
    let target = player(&raw)?;
    app.party.lock().unwrap().kick(&uuid, &target)?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn hard_mute(State(app): State<Shared>, headers: HeaderMap, Path(raw): Path<String>, body: Bytes) -> PResult<StatusCode> {
    let uuid = authed(&app, &headers)?;
    let target = player(&raw)?;
    let muted = body_json(&body).get("hard_muted").and_then(Value::as_bool).unwrap_or(false);
    app.party.lock().unwrap().hard_mute(&uuid, &target, muted)?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn invite(State(app): State<Shared>, headers: HeaderMap, body: Bytes) -> PResult<Json<Value>> {
    let uuid = authed(&app, &headers)?;
    let target = player(body_json(&body).get("player").and_then(Value::as_str).unwrap_or(""))?;
    let social = social(&app, &uuid)?;
    let v = app.party.lock().unwrap().invite(&uuid, &target, &social, now())?;
    Ok(Json(v))
}

pub async fn incoming(State(app): State<Shared>, headers: HeaderMap) -> PResult<Json<Vec<Value>>> {
    let uuid = authed(&app, &headers)?;
    Ok(Json(app.party.lock().unwrap().incoming(&uuid)))
}

pub async fn outgoing(State(app): State<Shared>, headers: HeaderMap) -> PResult<Json<Vec<Value>>> {
    let uuid = authed(&app, &headers)?;
    Ok(Json(app.party.lock().unwrap().outgoing(&uuid)))
}

pub async fn accept(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>, body: Bytes) -> PResult<Json<Value>> {
    let uuid = authed(&app, &headers)?;
    let leave = body_json(&body).get("leave_current").and_then(Value::as_bool).unwrap_or(false);
    let social = social(&app, &uuid)?;
    let v = app.party.lock().unwrap().accept(&uuid, &id, leave, &social.blocked, now())?;
    Ok(Json(v))
}

pub async fn decline(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> PResult<StatusCode> {
    let uuid = authed(&app, &headers)?;
    app.party.lock().unwrap().decline(&uuid, &id)?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn cancel(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> PResult<StatusCode> {
    let uuid = authed(&app, &headers)?;
    app.party.lock().unwrap().cancel(&uuid, &id)?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn invite_session(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>, body: Bytes) -> PResult<Json<Vec<Value>>> {
    let uuid = authed(&app, &headers)?;
    let id = clean(Some(&Value::String(id)), 64).ok_or_else(|| fail(StatusCode::BAD_REQUEST, "session_not_found", "Bad session"))?;
    let v = app.party.lock().unwrap().invite_session(&uuid, &id, &body_json(&body), now())?;
    Ok(Json(v))
}

pub async fn close_session(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> PResult<StatusCode> {
    let uuid = authed(&app, &headers)?;
    app.party.lock().unwrap().close_session(&uuid, &id);
    Ok(StatusCode::NO_CONTENT)
}

pub async fn request_join(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> PResult<Json<Value>> {
    let uuid = authed(&app, &headers)?;
    let social = social(&app, &uuid)?;
    let v = app.party.lock().unwrap().request_join(&uuid, &id, &social.blocked, now())?;
    Ok(Json(v))
}

pub async fn allow_join(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>, body: Bytes) -> PResult<StatusCode> {
    let uuid = authed(&app, &headers)?;
    app.party.lock().unwrap().answer_join(&uuid, &id, true, &body_json(&body), now())?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn deny_join(State(app): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> PResult<StatusCode> {
    let uuid = authed(&app, &headers)?;
    app.party.lock().unwrap().answer_join(&uuid, &id, false, &json!({}), now())?;
    Ok(StatusCode::NO_CONTENT)
}

/// Voice runs on Dusk's own voice chat, not through parties.
pub async fn no_voice() -> PartyError {
    fail(StatusCode::NOT_FOUND, "voice_unavailable", "Party voice runs through Dusk voice chat")
}

// ── socket ─────────────────────────────────────────────────────────────────

pub async fn party_ws(State(app): State<Shared>, headers: HeaderMap, ws: WebSocketUpgrade) -> Response {
    let uuid = match authed(&app, &headers) {
        Ok(u) => u,
        Err(e) => return e.into_response(),
    };
    ws.max_message_size(32 * 1024).on_upgrade(move |socket| run(app, uuid, socket))
}

async fn run(app: Shared, uuid: String, socket: WebSocket) {
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::channel::<Message>(QUEUE);
    let id = app.party.lock().unwrap().join(&uuid, tx, now());
    let writer = tokio::spawn(async move {
        while let Some(m) = rx.recv().await {
            if sink.send(m).await.is_err() {
                break;
            }
        }
        let _ = sink.close().await;
    });
    let mut tick = tokio::time::interval(Duration::from_secs(5));
    loop {
        tokio::select! {
            m = stream.next() => {
                let Some(Ok(m)) = m else { break };
                let mut hub = app.party.lock().unwrap();
                if !hub.owns(&uuid, id) {
                    break;
                }
                match m {
                    Message::Text(t) => hub.handle_text(&uuid, t.as_str(), now()),
                    Message::Close(_) => break,
                    _ => {}
                }
            }
            _ = tick.tick() => {
                let mut hub = app.party.lock().unwrap();
                hub.prune(now());
                if !hub.owns(&uuid, id) {
                    break;
                }
            }
        }
    }
    app.party.lock().unwrap().part(&uuid, id, now());
    let _ = writer.await;
}

#[cfg(test)]
mod tests {
    use super::*;

    const A: &str = "aaaaaaaa-0000-0000-0000-000000000001";
    const B: &str = "bbbbbbbb-0000-0000-0000-000000000002";
    const C: &str = "cccccccc-0000-0000-0000-000000000003";

    fn friends(of: &[&str]) -> Social {
        Social { friends: of.iter().map(|s| s.to_string()).collect(), blocked: HashSet::new() }
    }

    fn connect(hub: &mut Hub, uuid: &str) -> mpsc::Receiver<Message> {
        let (tx, rx) = mpsc::channel(QUEUE);
        hub.join(uuid, tx, 0);
        rx
    }

    fn drain(rx: &mut mpsc::Receiver<Message>) -> Vec<Value> {
        let mut out = vec![];
        while let Ok(Message::Text(t)) = rx.try_recv() {
            out.push(serde_json::from_str(t.as_str()).unwrap());
        }
        out
    }

    fn types(v: &[Value]) -> Vec<&str> {
        v.iter().map(|m| m["type"].as_str().unwrap()).collect()
    }

    /// A leads a party with B in it.
    fn pair(hub: &mut Hub) -> String {
        let inv = hub.invite(A, B, &friends(&[B]), 10).unwrap();
        hub.accept(B, inv["id"].as_str().unwrap(), false, &HashSet::new(), 11).unwrap()["id"].as_str().unwrap().to_string()
    }

    #[test]
    fn iso_dates() {
        assert_eq!(iso(0), "1970-01-01T00:00:00Z");
        assert_eq!(iso(1_795_000_000), "2026-11-18T11:06:40Z");
        assert_eq!(iso(951_782_400), "2000-02-29T00:00:00Z");
    }

    #[test]
    fn invite_needs_a_friend() {
        let mut hub = Hub::default();
        let e = hub.invite(A, B, &friends(&[]), 0).unwrap_err();
        assert_eq!(e.1, "not_friends");
        let mut s = friends(&[B]);
        s.blocked.insert(B.into());
        assert_eq!(hub.invite(A, B, &s, 0).unwrap_err().1, "blocked");
        assert!(hub.current(A).is_none());
    }

    #[test]
    fn invite_starts_a_party_and_accept_joins_it() {
        let mut hub = Hub::default();
        let mut ra = connect(&mut hub, A);
        let mut rb = connect(&mut hub, B);
        let inv = hub.invite(A, B, &friends(&[B]), 10).unwrap();
        assert_eq!(inv["status"], "pending");
        assert_eq!(types(&drain(&mut rb)), ["PartyInviteReceived"]);
        assert_eq!(hub.incoming(B).len(), 1);
        assert_eq!(hub.invite(A, B, &friends(&[B]), 10).unwrap_err().1, "already_invited");
        let party = hub.accept(B, inv["id"].as_str().unwrap(), false, &HashSet::new(), 11).unwrap();
        assert_eq!(party["leader"], A);
        assert_eq!(party["members"].as_array().unwrap().len(), 2);
        assert_eq!(party["members"][1]["online"], true);
        let a = drain(&mut ra);
        assert!(types(&a).contains(&"PartyInviteUpdated"));
        assert_eq!(*types(&a).last().unwrap(), "PartyUpdated");
        assert!(hub.incoming(B).is_empty());
    }

    #[test]
    fn accept_while_in_another_party_needs_leave_current() {
        let mut hub = Hub::default();
        pair(&mut hub);
        let inv = hub.invite(C, B, &friends(&[B]), 20).unwrap();
        let id = inv["id"].as_str().unwrap();
        assert_eq!(hub.accept(B, id, false, &HashSet::new(), 21).unwrap_err().1, "already_in_party");
        let p = hub.accept(B, id, true, &HashSet::new(), 21).unwrap();
        assert_eq!(p["leader"], C);
        assert_eq!(hub.current(A).unwrap()["members"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn leader_leaving_hands_over_and_last_one_ends_it() {
        let mut hub = Hub::default();
        let _rb = connect(&mut hub, B);
        pair(&mut hub);
        hub.leave(A).unwrap();
        assert_eq!(hub.current(B).unwrap()["leader"], B);
        hub.leave(B).unwrap();
        assert!(hub.parties.is_empty() && hub.member_of.is_empty());
    }

    #[test]
    fn only_the_leader_kicks() {
        let mut hub = Hub::default();
        pair(&mut hub);
        assert_eq!(hub.kick(B, A).unwrap_err().1, "not_leader");
        assert_eq!(hub.kick(A, A).unwrap_err().1, "cannot_kick_self");
        hub.kick(A, B).unwrap();
        assert!(hub.current(B).is_none());
    }

    #[test]
    fn state_and_pings_reach_the_rest_of_the_party_only() {
        let mut hub = Hub::default();
        let mut ra = connect(&mut hub, A);
        let mut rb = connect(&mut hub, B);
        let mut rc = connect(&mut hub, C);
        pair(&mut hub);
        drain(&mut ra);
        drain(&mut rb);
        let state = json!({"type":"SetPartyState","state":{"location":{"kind":"server","key":"abc","display":null}}});
        hub.handle_text(A, &state.to_string(), 30);
        assert_eq!(types(&drain(&mut rb)), ["PartyMemberStateChanged"]);
        assert!(drain(&mut ra).is_empty());
        let ping = json!({"type":"SendPing","id":"p1","server":"k","dimension":"minecraft:overworld","x":1.0,"y":64.0,"z":-3.5,"kind":"go"});
        hub.handle_text(A, &ping.to_string(), 31);
        let got = drain(&mut rb);
        assert_eq!(types(&got), ["PartyPing"]);
        assert_eq!(got[0]["from"], A);
        assert_eq!(got[0]["z"], -3.5);
        assert!(drain(&mut rc).is_empty());
        hub.handle_text(B, &json!({"type":"ReplyPing","id":"p1","owner":A,"reply":"coming"}).to_string(), 32);
        assert_eq!(types(&drain(&mut ra)), ["PartyPingReply"]);
        hub.handle_text(A, &json!({"type":"ClearPing","id":"p1"}).to_string(), 33);
        assert_eq!(types(&drain(&mut rb)), ["PartyPingCleared"]);
        // someone outside a party is told so
        hub.handle_text(C, &ping.to_string(), 34);
        assert_eq!(drain(&mut rc)[0]["error_code"], "not_in_party");
    }

    #[test]
    fn pings_are_rate_limited() {
        let mut hub = Hub::default();
        let mut ra = connect(&mut hub, A);
        let _rb = connect(&mut hub, B);
        pair(&mut hub);
        drain(&mut ra);
        let ping = json!({"type":"SendPing","id":"p","server":"k","dimension":"d","x":0,"y":0,"z":0,"kind":"go"}).to_string();
        for _ in 0..PING_BURST + 1 {
            hub.handle_text(A, &ping, 0);
        }
        assert_eq!(drain(&mut ra)[0]["error_code"], "rate_limited");
    }

    #[test]
    fn hosted_join_request_round_trip() {
        let mut hub = Hub::default();
        let mut ra = connect(&mut hub, A);
        let mut rb = connect(&mut hub, B);
        pair(&mut hub);
        let state = json!({"type":"SetPartyState","state":{"location":{"kind":"hosted_world","key":"session:s1"}}});
        hub.handle_text(A, &state.to_string(), 40);
        drain(&mut ra);
        drain(&mut rb);
        let req = hub.request_join(B, "s1", &HashSet::new(), 41).unwrap();
        assert_eq!(req["host"], A);
        assert_eq!(types(&drain(&mut ra)), ["HostedJoinRequested"]);
        assert_eq!(types(&drain(&mut rb)), ["HostedJoinRequestSent"]);
        assert_eq!(hub.request_join(B, "s1", &HashSet::new(), 41).unwrap_err().1, "join_request_pending");
        let rid = req["id"].as_str().unwrap();
        assert_eq!(hub.answer_join(B, rid, true, &json!({}), 42).unwrap_err().1, "join_request_not_found");
        // allowing needs the world's address
        assert_eq!(hub.answer_join(A, rid, true, &json!({}), 42).unwrap_err().1, "session_not_found");
        hub.answer_join(A, rid, true, &json!({"server":"abc.e4mc.link","version":"1.21.11"}), 42).unwrap();
        let got = drain(&mut rb);
        assert_eq!(types(&got), ["SessionInviteReceived", "HostedJoinResolved"]);
        assert_eq!(got[0]["server"], "abc.e4mc.link");
        assert_eq!(got[1]["result"], "allowed");
        assert_eq!(got[1]["invite_id"], got[0]["invite_id"]);
    }

    #[test]
    fn session_invite_reaches_every_other_member_and_links_play_together() {
        let mut hub = Hub::default();
        let mut ra = connect(&mut hub, A);
        let mut rb = connect(&mut hub, B);
        pair(&mut hub);
        drain(&mut rb);
        hub.handle_text(A, &json!({"type":"StartPlayTogether","destination":{"kind":"world","name":"Base"}}).to_string(), 50);
        let started = drain(&mut rb);
        assert_eq!(types(&started), ["PlayTogetherStarted"]);
        drain(&mut ra);
        let out = hub.invite_session(A, "s2", &json!({"server":"x.e4mc.link"}), 51).unwrap();
        assert_eq!(out.len(), 1);
        let got = drain(&mut rb);
        assert_eq!(types(&got), ["PlayTogetherUpdated", "SessionInviteReceived"]);
        assert_eq!(got[0]["session_id"], "s2");
        assert_eq!(hub.invite_session(B, "s2", &json!({"server":"y"}), 52).unwrap_err().1, "not_host");
    }

    #[test]
    fn invites_and_requests_expire() {
        let mut hub = Hub::default();
        let mut ra = connect(&mut hub, A);
        let inv = hub.invite(A, B, &friends(&[B]), 0).unwrap();
        drain(&mut ra);
        hub.prune(INVITE_TTL + 1);
        let got = drain(&mut ra);
        assert_eq!(got[0]["type"], "PartyInviteUpdated");
        assert_eq!(got[0]["status"], "expired");
        assert_eq!(hub.accept(B, inv["id"].as_str().unwrap(), false, &HashSet::new(), INVITE_TTL + 2).unwrap_err().1, "invite_not_found");
    }

    #[test]
    fn an_empty_party_ends_and_an_away_leader_hands_over() {
        let mut hub = Hub::default();
        let ra = connect(&mut hub, A);
        let rb = connect(&mut hub, B);
        pair(&mut hub);
        let a_id = hub.conns[A].id;
        hub.part(A, a_id, 100);
        hub.prune(100 + LEADER_AWAY + 1);
        assert_eq!(hub.current(B).unwrap()["leader"], B);
        let b_id = hub.conns[B].id;
        hub.part(B, b_id, 500);
        hub.prune(500 + EMPTY_TTL + 1);
        assert!(hub.parties.is_empty());
        drop((ra, rb));
    }
}
