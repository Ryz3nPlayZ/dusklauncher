//! Voice between Dusk clients (`GET /v1/voice`, a WebSocket).
//!
//! Each client keeps one socket open while voice chat is on. It tells us
//! where it is (a hash of the server address, never the address, plus the
//! dimension and position) and who may hear it, and sends 20 ms Opus frames.
//! We pass each frame on to the people allowed to hear it:
//!
//! * **nearby** — Dusk players on the same server within [`RANGE`] blocks.
//!   Each side picks friends only, everyone, or off; the stricter side wins,
//!   and a block either way means neither hears the other.
//! * **calls** — rooms of up to [`ROOM_MAX`] people. You can only ring a
//!   friend, and only one who takes calls.
//!
//! Text frames are JSON control messages; binary frames are voice. Up:
//! `flags u8 (1 nearby, 2 call, 4 whisper) · seq u64 · opus`. Down:
//! `kind u8 (1 nearby, 2 call) · sender uuid (16 bytes) · seq u64 · opus`.
//! Empty opus marks the end of a stretch of talking. Nothing is stored.

use crate::{authed, dashed_uuid, now, Shared};
use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    http::HeaderMap,
    response::{IntoResponse, Response},
};
use futures_util::{SinkExt, StreamExt};
use rand::RngCore;
use rusqlite::{params, Connection};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::time::Duration;
use tokio::sync::mpsc;

/// How far nearby voice carries, in blocks; the client fades over the same.
pub const RANGE: f64 = 48.0;
/// How far a whisper carries.
const WHISPER_RANGE: f64 = 16.0;
const ROOM_MAX: usize = 8;
/// Seconds a ring waits to be answered.
const RING_TTL: i64 = 45;
/// The biggest voice frame we pass on (an Opus packet is at most 1275 bytes).
const MAX_FRAME: usize = 1400;
/// How often an open socket re-reads its friends and blocks.
const SOCIAL_TTL: i64 = 30;
/// Frames queued per listener before we start dropping; voice is better late-dropped than late.
const QUEUE: usize = 64;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mode {
    Friends,
    Everyone,
    Off,
}

impl Mode {
    fn parse(v: Option<&Value>) -> Mode {
        match v.and_then(Value::as_str) {
            Some("everyone") => Mode::Everyone,
            Some("off") => Mode::Off,
            _ => Mode::Friends,
        }
    }
}

#[derive(Clone, PartialEq, Debug)]
struct Spot {
    server: String,
    dim: String,
    x: f64,
    y: f64,
    z: f64,
}

impl Spot {
    fn within(&self, o: &Spot, range: f64) -> bool {
        if self.server != o.server || self.dim != o.dim {
            return false;
        }
        let (dx, dy, dz) = (self.x - o.x, self.y - o.y, self.z - o.z);
        dx * dx + dy * dy + dz * dz <= range * range
    }
}

struct Conn {
    id: u64,
    name: String,
    tx: mpsc::Sender<Message>,
    at: Option<Spot>,
    nearby: Mode,
    calls: Mode,
    room: Option<String>,
    near: usize,
    friends: HashSet<String>,
    /// Blocked by them, or blocking them.
    blocked: HashSet<String>,
}

struct Room {
    members: Vec<String>,
    /// uuid → when they were rung.
    invites: HashMap<String, i64>,
}

#[derive(Default)]
pub struct Hub {
    conns: HashMap<String, Conn>,
    rooms: HashMap<String, Room>,
    next: u64,
}

fn send(c: &Conn, m: Message) {
    // a full queue means a slow listener; it loses this frame rather than lagging
    let _ = c.tx.try_send(m);
}

fn text(c: &Conn, v: Value) {
    send(c, Message::Text(v.to_string().into()));
}

fn uuid_bytes(uuid: &str) -> [u8; 16] {
    let mut out = [0u8; 16];
    if let Ok(b) = hex::decode(uuid.replace('-', "")) {
        if b.len() == 16 {
            out.copy_from_slice(&b);
        }
    }
    out
}

/// Whether `a` and `b` may hear each other nearby, as both of them allow.
fn nearby_allowed(a_id: &str, a: &Conn, b_id: &str, b: &Conn) -> bool {
    if a.nearby == Mode::Off || b.nearby == Mode::Off {
        return false;
    }
    if a.blocked.contains(b_id) || b.blocked.contains(a_id) {
        return false;
    }
    if a.nearby == Mode::Friends || b.nearby == Mode::Friends {
        return a.friends.contains(b_id) && b.friends.contains(a_id);
    }
    true
}

impl Hub {
    /// A new socket for `uuid`; one that was already open is dropped.
    fn join(&mut self, uuid: &str, name: String, tx: mpsc::Sender<Message>, friends: HashSet<String>, blocked: HashSet<String>) -> u64 {
        self.next += 1;
        let id = self.next;
        let old = self.conns.remove(uuid);
        let room = old.as_ref().and_then(|o| o.room.clone());
        self.conns.insert(
            uuid.to_string(),
            Conn { id, name, tx, at: None, nearby: Mode::Friends, calls: Mode::Friends, room: room.clone(), near: 0, friends, blocked },
        );
        // a reconnect keeps its call
        if let Some(r) = room {
            self.room_state(&r);
        }
        id
    }

    fn owns(&self, uuid: &str, id: u64) -> bool {
        self.conns.get(uuid).is_some_and(|c| c.id == id)
    }

    /// The socket closed: out of its call, and out of everyone's range.
    fn part(&mut self, uuid: &str, id: u64) {
        if !self.owns(uuid, id) {
            return;
        }
        self.leave_room(uuid);
        let c = self.conns.remove(uuid);
        if let Some(server) = c.and_then(|c| c.at).map(|s| s.server) {
            self.recount(&server);
        }
    }

    fn set_social(&mut self, uuid: &str, friends: HashSet<String>, blocked: HashSet<String>) {
        let server = match self.conns.get_mut(uuid) {
            Some(c) => {
                c.friends = friends;
                c.blocked = blocked;
                c.at.as_ref().map(|s| s.server.clone())
            }
            None => return,
        };
        if let Some(s) = server {
            self.recount(&s);
        }
    }

    /// Tells everyone on `server` how many Dusk players can hear them, when that changes.
    fn recount(&mut self, server: &str) {
        let ids: Vec<String> = self
            .conns
            .iter()
            .filter(|(_, c)| c.at.as_ref().is_some_and(|s| s.server == server))
            .map(|(k, _)| k.clone())
            .collect();
        for id in ids {
            let n = self.audience(&id, RANGE).len();
            let c = self.conns.get_mut(&id).unwrap();
            if c.near != n {
                c.near = n;
                text(c, json!({"t": "near", "n": n}));
            }
        }
    }

    /// Who can hear `uuid` nearby within `range`.
    fn audience(&self, uuid: &str, range: f64) -> Vec<String> {
        let Some(me) = self.conns.get(uuid) else { return vec![] };
        let Some(at) = &me.at else { return vec![] };
        self.conns
            .iter()
            .filter(|(k, c)| {
                k.as_str() != uuid
                    && c.at.as_ref().is_some_and(|s| s.within(at, range))
                    && nearby_allowed(uuid, me, k, c)
            })
            .map(|(k, _)| k.clone())
            .collect()
    }

    fn handle_text(&mut self, uuid: &str, raw: &str, t: i64) {
        let Ok(v) = serde_json::from_str::<Value>(raw) else { return };
        match v.get("t").and_then(Value::as_str) {
            Some("where") => {
                let spot = match (v.get("server").and_then(Value::as_str), v.get("dim").and_then(Value::as_str)) {
                    (Some(server), Some(dim)) if server.len() <= 64 && dim.len() <= 128 => Some(Spot {
                        server: server.to_string(),
                        dim: dim.to_string(),
                        x: v.get("x").and_then(Value::as_f64).unwrap_or(0.0),
                        y: v.get("y").and_then(Value::as_f64).unwrap_or(0.0),
                        z: v.get("z").and_then(Value::as_f64).unwrap_or(0.0),
                    }),
                    _ => None,
                };
                let Some(c) = self.conns.get_mut(uuid) else { return };
                let before = c.at.as_ref().map(|s| s.server.clone());
                c.at = spot.clone();
                if spot.is_none() && c.near != 0 {
                    c.near = 0;
                    text(c, json!({"t": "near", "n": 0}));
                }
                if let Some(b) = before.filter(|b| spot.as_ref().map(|s| &s.server) != Some(b)) {
                    self.recount(&b);
                }
                if let Some(s) = spot {
                    self.recount(&s.server);
                }
            }
            Some("mode") => {
                let Some(c) = self.conns.get_mut(uuid) else { return };
                c.nearby = Mode::parse(v.get("nearby"));
                c.calls = match Mode::parse(v.get("calls")) {
                    Mode::Off => Mode::Off,
                    _ => Mode::Friends,
                };
                if let Some(s) = c.at.as_ref().map(|s| s.server.clone()) {
                    self.recount(&s);
                }
            }
            Some("call") => {
                if let Some(to) = v.get("to").and_then(Value::as_str).and_then(dashed_uuid) {
                    self.call(uuid, &to, t);
                }
            }
            Some("accept") => {
                if let Some(room) = v.get("room").and_then(Value::as_str) {
                    self.accept(uuid, room, t);
                }
            }
            Some("decline") => {
                if let Some(room) = v.get("room").and_then(Value::as_str) {
                    self.decline(uuid, room);
                }
            }
            Some("leave") => self.leave_room(uuid),
            _ => {}
        }
    }

    fn error(&self, uuid: &str, msg: &str) {
        if let Some(c) = self.conns.get(uuid) {
            text(c, json!({"t": "error", "msg": msg}));
        }
    }

    fn call(&mut self, from: &str, to: &str, t: i64) {
        if from == to {
            return;
        }
        let Some(me) = self.conns.get(from) else { return };
        let ok = me.friends.contains(to)
            && !me.blocked.contains(to)
            && self.conns.get(to).is_some_and(|c| c.calls != Mode::Off && c.friends.contains(from) && !c.blocked.contains(from));
        if !ok {
            return self.error(from, "They aren't taking calls right now.");
        }
        let room_id = match me.room.clone() {
            Some(r) => r,
            None => {
                let mut b = [0u8; 8];
                rand::thread_rng().fill_bytes(&mut b);
                let r = hex::encode(b);
                self.rooms.insert(r.clone(), Room { members: vec![from.to_string()], invites: HashMap::new() });
                self.conns.get_mut(from).unwrap().room = Some(r.clone());
                self.room_state(&r);
                r
            }
        };
        let room = self.rooms.get_mut(&room_id).unwrap();
        if room.members.iter().any(|m| m == to) {
            return self.error(from, "They're already in the call.");
        }
        if room.members.len() + room.invites.len() >= ROOM_MAX {
            return self.error(from, "The call is full.");
        }
        // everyone already in it must be someone they haven't blocked
        let target = self.conns.get(to).unwrap();
        if room.members.iter().any(|m| target.blocked.contains(m)) {
            return self.error(from, "They aren't taking calls right now.");
        }
        room.invites.insert(to.to_string(), t);
        let name = self.conns[from].name.clone();
        text(target, json!({"t": "ring", "room": room_id, "from": from, "name": name}));
    }

    fn accept(&mut self, uuid: &str, room_id: &str, t: i64) {
        let invited = self
            .rooms
            .get(room_id)
            .and_then(|r| r.invites.get(uuid))
            .is_some_and(|at| t - at <= RING_TTL);
        if !invited {
            return self.error(uuid, "That call has ended.");
        }
        if self.conns.get(uuid).and_then(|c| c.room.as_deref()) != Some(room_id) {
            self.leave_room(uuid);
        }
        // leaving the old room may not touch this one, but check it still stands
        let Some(room) = self.rooms.get_mut(room_id) else { return self.error(uuid, "That call has ended.") };
        room.invites.remove(uuid);
        if !room.members.iter().any(|m| m == uuid) {
            room.members.push(uuid.to_string());
        }
        if let Some(c) = self.conns.get_mut(uuid) {
            c.room = Some(room_id.to_string());
        }
        self.room_state(room_id);
    }

    fn decline(&mut self, uuid: &str, room_id: &str) {
        let Some(room) = self.rooms.get_mut(room_id) else { return };
        if room.invites.remove(uuid).is_none() {
            return;
        }
        let name = self.conns.get(uuid).map(|c| c.name.clone()).unwrap_or_default();
        for m in &room.members {
            if let Some(c) = self.conns.get(m) {
                text(c, json!({"t": "declined", "name": name}));
            }
        }
        self.settle(room_id);
    }

    fn leave_room(&mut self, uuid: &str) {
        let Some(room_id) = self.conns.get_mut(uuid).and_then(|c| c.room.take()) else { return };
        if let Some(c) = self.conns.get(uuid) {
            text(c, json!({"t": "room", "room": null}));
        }
        if let Some(room) = self.rooms.get_mut(&room_id) {
            room.members.retain(|m| m != uuid);
        }
        self.settle(&room_id);
        self.room_state(&room_id);
    }

    /// Ends a call nobody else is in or coming to.
    fn settle(&mut self, room_id: &str) {
        let Some(room) = self.rooms.get(room_id) else { return };
        if room.members.len() > 1 || (room.members.len() == 1 && !room.invites.is_empty()) {
            return;
        }
        let room = self.rooms.remove(room_id).unwrap();
        for m in room.members {
            if let Some(c) = self.conns.get_mut(&m) {
                c.room = None;
                text(c, json!({"t": "room", "room": null}));
            }
        }
    }

    /// Tells everyone in the call who is in it.
    fn room_state(&self, room_id: &str) {
        let Some(room) = self.rooms.get(room_id) else { return };
        let members: Vec<Value> = room
            .members
            .iter()
            .map(|m| json!({"uuid": m, "name": self.conns.get(m).map(|c| c.name.as_str()).unwrap_or("?")}))
            .collect();
        for m in &room.members {
            if let Some(c) = self.conns.get(m) {
                text(c, json!({"t": "room", "room": room_id, "members": members}));
            }
        }
    }

    /// Drops rings nobody answered, and calls they leave empty.
    fn prune(&mut self, t: i64) {
        let mut empty = vec![];
        for (id, room) in self.rooms.iter_mut() {
            room.invites.retain(|_, at| t - *at <= RING_TTL);
            if room.members.len() <= 1 && room.invites.is_empty() {
                empty.push(id.clone());
            }
        }
        for id in empty {
            self.settle(&id);
        }
    }

    /// One voice frame from `uuid`, passed on to whoever may hear it.
    fn handle_voice(&self, uuid: &str, data: &[u8]) {
        if data.len() < 9 || data.len() > MAX_FRAME {
            return;
        }
        let Some(me) = self.conns.get(uuid) else { return };
        let flags = data[0];
        let mut out = Vec::with_capacity(data.len() + 16);
        out.push(0);
        out.extend_from_slice(&uuid_bytes(uuid));
        out.extend_from_slice(&data[1..]);
        if flags & 1 != 0 {
            out[0] = 1;
            let range = if flags & 4 != 0 { WHISPER_RANGE } else { RANGE };
            let frame = axum::body::Bytes::copy_from_slice(&out);
            for k in self.audience(uuid, range) {
                send(&self.conns[&k], Message::Binary(frame.clone()));
            }
        }
        if flags & 2 != 0 {
            if let Some(room) = me.room.as_ref().and_then(|r| self.rooms.get(r)) {
                out[0] = 2;
                let frame = axum::body::Bytes::copy_from_slice(&out);
                for m in room.members.iter().filter(|m| m.as_str() != uuid) {
                    if let Some(c) = self.conns.get(m) {
                        send(c, Message::Binary(frame.clone()));
                    }
                }
            }
        }
    }
}

/// Name, friends and blocks (either way) for `uuid`.
fn social(db: &Connection, uuid: &str) -> Result<(String, HashSet<String>, HashSet<String>), rusqlite::Error> {
    let name: String = db.query_row("SELECT username FROM accounts WHERE uuid = ?1", params![uuid], |r| r.get(0))?;
    let mut friends = HashSet::new();
    let mut q = db.prepare("SELECT CASE WHEN a = ?1 THEN b ELSE a END FROM friendships WHERE a = ?1 OR b = ?1")?;
    for f in q.query_map(params![uuid], |r| r.get::<_, String>(0))? {
        friends.insert(f?);
    }
    let mut blocked = HashSet::new();
    let mut q = db.prepare("SELECT blocked FROM blocks WHERE blocker = ?1 UNION SELECT blocker FROM blocks WHERE blocked = ?1")?;
    for b in q.query_map(params![uuid], |r| r.get::<_, String>(0))? {
        blocked.insert(b?);
    }
    Ok((name, friends, blocked))
}

pub async fn voice_ws(State(app): State<Shared>, headers: HeaderMap, ws: WebSocketUpgrade) -> Response {
    let uuid = match authed(&app, &headers) {
        Ok(u) => u,
        Err(e) => return e.into_response(),
    };
    let found = social(&app.db.lock().unwrap(), &uuid);
    let Ok((name, friends, blocked)) = found else {
        return axum::http::StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    ws.max_message_size(16 * 1024).on_upgrade(move |socket| run(app, uuid, name, friends, blocked, socket))
}

async fn run(app: Shared, uuid: String, name: String, friends: HashSet<String>, blocked: HashSet<String>, socket: WebSocket) {
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::channel::<Message>(QUEUE);
    let id = app.voice.lock().unwrap().join(&uuid, name, tx, friends, blocked);
    // the hub drops our sender when we go (or another socket replaces us), which ends this
    let writer = tokio::spawn(async move {
        while let Some(m) = rx.recv().await {
            if sink.send(m).await.is_err() {
                break;
            }
        }
        let _ = sink.close().await;
    });
    let mut tick = tokio::time::interval(Duration::from_secs(5));
    let mut social_at = now();
    loop {
        tokio::select! {
            m = stream.next() => {
                let Some(Ok(m)) = m else { break };
                let mut hub = app.voice.lock().unwrap();
                if !hub.owns(&uuid, id) {
                    break;
                }
                match m {
                    Message::Text(t) => hub.handle_text(&uuid, t.as_str(), now()),
                    Message::Binary(b) => hub.handle_voice(&uuid, &b),
                    Message::Close(_) => break,
                    _ => {}
                }
            }
            _ = tick.tick() => {
                if now() - social_at > SOCIAL_TTL {
                    social_at = now();
                    let found = social(&app.db.lock().unwrap(), &uuid);
                    if let Ok((_, f, b)) = found {
                        app.voice.lock().unwrap().set_social(&uuid, f, b);
                    }
                }
                let mut hub = app.voice.lock().unwrap();
                hub.prune(now());
                if !hub.owns(&uuid, id) {
                    break;
                }
            }
        }
    }
    app.voice.lock().unwrap().part(&uuid, id);
    let _ = writer.await;
}

#[cfg(test)]
mod tests {
    use super::*;

    const A: &str = "aaaaaaaa-0000-0000-0000-000000000001";
    const B: &str = "bbbbbbbb-0000-0000-0000-000000000002";
    const C: &str = "cccccccc-0000-0000-0000-000000000003";

    fn set(v: &[&str]) -> HashSet<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    fn add(hub: &mut Hub, uuid: &str, name: &str, friends: &[&str], blocked: &[&str]) -> mpsc::Receiver<Message> {
        let (tx, rx) = mpsc::channel(QUEUE);
        hub.join(uuid, name.into(), tx, set(friends), set(blocked));
        rx
    }

    fn drain(rx: &mut mpsc::Receiver<Message>) -> (Vec<Value>, Vec<Vec<u8>>) {
        let (mut texts, mut bins) = (vec![], vec![]);
        while let Ok(m) = rx.try_recv() {
            match m {
                Message::Text(t) => texts.push(serde_json::from_str(t.as_str()).unwrap()),
                Message::Binary(b) => bins.push(b.to_vec()),
                _ => {}
            }
        }
        (texts, bins)
    }

    fn at(hub: &mut Hub, uuid: &str, x: f64) {
        hub.handle_text(uuid, &json!({"t": "where", "server": "s1", "dim": "overworld", "x": x, "y": 64, "z": 0}).to_string(), 0);
    }

    fn frame(flags: u8) -> Vec<u8> {
        let mut f = vec![flags];
        f.extend_from_slice(&7u64.to_be_bytes());
        f.extend_from_slice(b"opus");
        f
    }

    #[test]
    fn nearby_friends_hear_each_other_in_range() {
        let mut hub = Hub::default();
        let mut a = add(&mut hub, A, "Ann", &[B], &[]);
        let mut b = add(&mut hub, B, "Ben", &[A], &[]);
        at(&mut hub, A, 0.0);
        at(&mut hub, B, 10.0);
        assert!(drain(&mut a).0.contains(&json!({"t": "near", "n": 1})));
        drain(&mut b);
        hub.handle_voice(A, &frame(1));
        let (_, bins) = drain(&mut b);
        assert_eq!(bins.len(), 1);
        assert_eq!(bins[0][0], 1);
        assert_eq!(&bins[0][1..17], &uuid_bytes(A));
        assert_eq!(&bins[0][17..25], &7u64.to_be_bytes());
        assert_eq!(&bins[0][25..], b"opus");
        // out of range, then a whisper from 20 blocks
        at(&mut hub, B, 60.0);
        hub.handle_voice(A, &frame(1));
        assert!(drain(&mut b).1.is_empty());
        at(&mut hub, B, 20.0);
        hub.handle_voice(A, &frame(1 | 4));
        assert!(drain(&mut b).1.is_empty());
    }

    #[test]
    fn strangers_need_everyone_on_both_sides() {
        let mut hub = Hub::default();
        let _a = add(&mut hub, A, "Ann", &[], &[]);
        let mut c = add(&mut hub, C, "Cat", &[], &[]);
        at(&mut hub, A, 0.0);
        at(&mut hub, C, 5.0);
        hub.handle_voice(A, &frame(1));
        assert!(drain(&mut c).1.is_empty());
        hub.handle_text(A, r#"{"t":"mode","nearby":"everyone","calls":"friends"}"#, 0);
        hub.handle_voice(A, &frame(1));
        assert!(drain(&mut c).1.is_empty());
        hub.handle_text(C, r#"{"t":"mode","nearby":"everyone","calls":"friends"}"#, 0);
        hub.handle_voice(A, &frame(1));
        assert_eq!(drain(&mut c).1.len(), 1);
        // a block either way silences it
        hub.set_social(C, set(&[]), set(&[A]));
        hub.handle_voice(A, &frame(1));
        assert!(drain(&mut c).1.is_empty());
    }

    #[test]
    fn calls_ring_friends_only_and_end_when_alone() {
        let mut hub = Hub::default();
        let mut a = add(&mut hub, A, "Ann", &[B], &[]);
        let mut b = add(&mut hub, B, "Ben", &[A], &[]);
        let mut c = add(&mut hub, C, "Cat", &[], &[]);
        hub.handle_text(A, &json!({"t": "call", "to": C.replace('-', "")}).to_string(), 0);
        assert_eq!(drain(&mut a).0.last().unwrap()["t"], "error");
        assert!(drain(&mut c).0.is_empty());

        hub.handle_text(A, &json!({"t": "call", "to": B.replace('-', "")}).to_string(), 0);
        let ring = drain(&mut b).0.pop().unwrap();
        assert_eq!(ring["t"], "ring");
        assert_eq!(ring["name"], "Ann");
        let room = ring["room"].as_str().unwrap().to_string();
        hub.handle_text(B, &json!({"t": "accept", "room": room}).to_string(), 1);
        let state = drain(&mut a).0.pop().unwrap();
        assert_eq!(state["members"].as_array().unwrap().len(), 2);
        drain(&mut b);

        hub.handle_voice(B, &frame(2));
        let (_, bins) = drain(&mut a);
        assert_eq!(bins.len(), 1);
        assert_eq!(bins[0][0], 2);
        assert!(drain(&mut c).1.is_empty());

        hub.handle_text(B, r#"{"t":"leave"}"#, 2);
        assert!(drain(&mut a).0.contains(&json!({"t": "room", "room": null})));
        assert!(hub.rooms.is_empty());
    }

    #[test]
    fn unanswered_rings_lapse() {
        let mut hub = Hub::default();
        let mut a = add(&mut hub, A, "Ann", &[B], &[]);
        let mut b = add(&mut hub, B, "Ben", &[A], &[]);
        hub.handle_text(A, &json!({"t": "call", "to": B}).to_string(), 0);
        let room = drain(&mut b).0.pop().unwrap()["room"].as_str().unwrap().to_string();
        hub.prune(RING_TTL + 1);
        assert!(hub.rooms.is_empty());
        assert!(drain(&mut a).0.contains(&json!({"t": "room", "room": null})));
        hub.handle_text(B, &json!({"t": "accept", "room": room}).to_string(), RING_TTL + 2);
        assert_eq!(drain(&mut b).0.pop().unwrap()["t"], "error");
    }

    #[test]
    fn calls_off_turns_rings_away() {
        let mut hub = Hub::default();
        let mut a = add(&mut hub, A, "Ann", &[B], &[]);
        let mut b = add(&mut hub, B, "Ben", &[A], &[]);
        hub.handle_text(B, r#"{"t":"mode","nearby":"off","calls":"off"}"#, 0);
        hub.handle_text(A, &json!({"t": "call", "to": B}).to_string(), 0);
        assert_eq!(drain(&mut a).0.last().unwrap()["t"], "error");
        assert!(drain(&mut b).0.is_empty());
    }
}
