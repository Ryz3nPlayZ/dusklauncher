//! Group chats: a named conversation among friends.
//!
//! Whoever creates a group picks its first members from their own friends;
//! after that any member can bring in one of *their* friends. A member leaves
//! on their own, the owner can remove anyone, and when the owner leaves the
//! longest-standing member takes over. The last one out deletes the group.
//!
//! Messages work like direct messages: `afterId` polls for what's new, reading
//! marks it read, and the same length cap and rate limit apply. A sender can
//! edit or delete their own lines; the owner can delete anyone's.

use crate::{are_friends, authed, bad, dashed_uuid, is_blocked, now, quests, ApiError, ApiResult, Shared};
use crate::{MESSAGE_MAX_LEN, MESSAGE_PAGE, MESSAGE_RATE_MAX, MESSAGE_RATE_WINDOW};
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

const NAME_MAX_LEN: usize = 32;
/// Members a group can hold, owner included.
const MEMBERS_MAX: i64 = 20;
/// Groups one account can be in.
const GROUPS_MAX: i64 = 50;

pub fn migrate(db: &Connection) {
    db.execute_batch(
        "CREATE TABLE IF NOT EXISTS chat_groups (
           id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
           owner TEXT NOT NULL REFERENCES accounts(uuid), created_at INTEGER NOT NULL);
         CREATE TABLE IF NOT EXISTS chat_group_members (
           group_id INTEGER NOT NULL REFERENCES chat_groups(id) ON DELETE CASCADE,
           uuid TEXT NOT NULL REFERENCES accounts(uuid), joined_at INTEGER NOT NULL,
           last_read INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (group_id, uuid));
         CREATE INDEX IF NOT EXISTS chat_group_members_uuid ON chat_group_members(uuid);
         CREATE TABLE IF NOT EXISTS chat_group_messages (
           id INTEGER PRIMARY KEY AUTOINCREMENT,
           group_id INTEGER NOT NULL REFERENCES chat_groups(id) ON DELETE CASCADE,
           from_uuid TEXT NOT NULL REFERENCES accounts(uuid), body TEXT NOT NULL,
           sent_at INTEGER NOT NULL, edited_at INTEGER);
         CREATE INDEX IF NOT EXISTS chat_group_messages_group ON chat_group_messages(group_id, id);
         CREATE INDEX IF NOT EXISTS chat_group_messages_from ON chat_group_messages(from_uuid, sent_at);",
    )
    .expect("migrate groups");
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Member {
    uuid: String,
    username: String,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct LastMessage {
    id: i64,
    body: String,
    from_uuid: String,
    sent_at: i64,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct GroupEntry {
    id: i64,
    name: String,
    owner: String,
    members: Vec<Member>,
    last_message: Option<LastMessage>,
    /// messages from others since this account last read the group
    unread: i64,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct GroupMessage {
    id: i64,
    group_id: i64,
    from_uuid: String,
    body: String,
    sent_at: i64,
    edited_at: Option<i64>,
}

fn not_found() -> ApiError {
    ApiError(StatusCode::NOT_FOUND, "That group doesn't exist, or you're not in it.".into())
}

fn forbidden(msg: &str) -> ApiError {
    ApiError(StatusCode::FORBIDDEN, msg.into())
}

fn is_member(db: &Connection, group: i64, uuid: &str) -> Result<bool, rusqlite::Error> {
    Ok(db
        .query_row("SELECT 1 FROM chat_group_members WHERE group_id = ?1 AND uuid = ?2", params![group, uuid], |_| Ok(true))
        .optional()?
        .unwrap_or(false))
}

fn owner_of(db: &Connection, group: i64) -> Result<Option<String>, rusqlite::Error> {
    db.query_row("SELECT owner FROM chat_groups WHERE id = ?1", params![group], |r| r.get(0)).optional()
}

/// The group's owner, once `me` is known to be in it.
fn require_member(db: &Connection, group: i64, me: &str) -> Result<String, ApiError> {
    if !is_member(db, group, me)? {
        return Err(not_found());
    }
    owner_of(db, group)?.ok_or_else(not_found)
}

fn clean_name(raw: &str) -> Result<String, ApiError> {
    let name: String = raw.trim().chars().filter(|c| !c.is_control()).collect();
    if name.is_empty() {
        return Err(bad("Give the group a name."));
    }
    if name.chars().count() > NAME_MAX_LEN {
        return Err(bad(format!("Group names are limited to {NAME_MAX_LEN} characters.")));
    }
    Ok(name)
}

fn clean_body(raw: &str) -> Result<String, ApiError> {
    let body = raw.trim().to_string();
    if body.is_empty() {
        return Err(bad("Type a message first."));
    }
    if body.chars().count() > MESSAGE_MAX_LEN {
        return Err(bad(format!("Messages are limited to {MESSAGE_MAX_LEN} characters.")));
    }
    Ok(body)
}

fn group_count(db: &Connection, uuid: &str) -> Result<i64, rusqlite::Error> {
    db.query_row("SELECT COUNT(*) FROM chat_group_members WHERE uuid = ?1", params![uuid], |r| r.get(0))
}

/// `who` can join because `by` (a member) is friends with them and neither blocks the other.
fn check_invitee(db: &Connection, by: &str, who: &str) -> Result<(), ApiError> {
    if !are_friends(db, by, who)? {
        return Err(forbidden("You can only add your friends."));
    }
    if is_blocked(db, by, who)? || is_blocked(db, who, by)? {
        return Err(forbidden("You can't add this player."));
    }
    if group_count(db, who)? >= GROUPS_MAX {
        return Err(bad("That player is in too many groups."));
    }
    Ok(())
}

fn add_member(db: &Connection, group: i64, uuid: &str) -> Result<(), ApiError> {
    let count: i64 = db.query_row("SELECT COUNT(*) FROM chat_group_members WHERE group_id = ?1", params![group], |r| r.get(0))?;
    if count >= MEMBERS_MAX {
        return Err(bad(format!("Groups hold up to {MEMBERS_MAX} players.")));
    }
    // a new member starts with nothing unread: the history isn't news to them
    let latest: i64 =
        db.query_row("SELECT COALESCE(MAX(id), 0) FROM chat_group_messages WHERE group_id = ?1", params![group], |r| r.get(0))?;
    db.execute(
        "INSERT OR IGNORE INTO chat_group_members (group_id, uuid, joined_at, last_read) VALUES (?1, ?2, ?3, ?4)",
        params![group, uuid, now(), latest],
    )?;
    Ok(())
}

fn entry(db: &Connection, group: i64, me: &str) -> Result<GroupEntry, ApiError> {
    let (name, owner): (String, String) =
        db.query_row("SELECT name, owner FROM chat_groups WHERE id = ?1", params![group], |r| Ok((r.get(0)?, r.get(1)?)))?;
    let mut st = db.prepare(
        "SELECT m.uuid, COALESCE(a.username, '') FROM chat_group_members m LEFT JOIN accounts a ON a.uuid = m.uuid
         WHERE m.group_id = ?1 ORDER BY m.joined_at, m.uuid",
    )?;
    let members = st
        .query_map(params![group], |r| Ok(Member { uuid: r.get(0)?, username: r.get(1)? }))?
        .collect::<Result<Vec<_>, _>>()?;
    let last_message = db
        .query_row(
            "SELECT id, body, from_uuid, sent_at FROM chat_group_messages WHERE group_id = ?1 ORDER BY id DESC LIMIT 1",
            params![group],
            |r| Ok(LastMessage { id: r.get(0)?, body: r.get(1)?, from_uuid: r.get(2)?, sent_at: r.get(3)? }),
        )
        .optional()?;
    let unread: i64 = db.query_row(
        "SELECT COUNT(*) FROM chat_group_messages g, chat_group_members m
         WHERE g.group_id = ?1 AND m.group_id = ?1 AND m.uuid = ?2 AND g.id > m.last_read AND g.from_uuid != ?2",
        params![group, me],
        |r| r.get(0),
    )?;
    Ok(GroupEntry { id: group, name, owner, members, last_message, unread })
}

fn list(db: &Connection, me: &str) -> Result<Vec<GroupEntry>, ApiError> {
    let ids = db
        .prepare("SELECT group_id FROM chat_group_members WHERE uuid = ?1")?
        .query_map(params![me], |r| r.get::<_, i64>(0))?
        .collect::<Result<Vec<_>, _>>()?;
    let mut out = ids.into_iter().map(|id| entry(db, id, me)).collect::<Result<Vec<_>, _>>()?;
    out.sort_by_key(|g| std::cmp::Reverse(g.last_message.as_ref().map_or(0, |m| m.sent_at)));
    Ok(out)
}

/// `who` leaves `group`. The owner's seat passes to whoever joined first;
/// with nobody left the group goes.
fn remove_member(db: &Connection, group: i64, who: &str) -> Result<(), rusqlite::Error> {
    db.execute("DELETE FROM chat_group_members WHERE group_id = ?1 AND uuid = ?2", params![group, who])?;
    let heir: Option<String> = db
        .query_row(
            "SELECT uuid FROM chat_group_members WHERE group_id = ?1 ORDER BY joined_at, uuid LIMIT 1",
            params![group],
            |r| r.get(0),
        )
        .optional()?;
    match heir {
        None => {
            db.execute("DELETE FROM chat_group_messages WHERE group_id = ?1", params![group])?;
            db.execute("DELETE FROM chat_groups WHERE id = ?1", params![group])?;
        }
        Some(heir) => {
            db.execute("UPDATE chat_groups SET owner = ?2 WHERE id = ?1 AND owner = ?3", params![group, heir, who])?;
        }
    }
    Ok(())
}

// ── handlers ───────────────────────────────────────────────────────────────

pub async fn list_groups(State(app): State<Shared>, headers: HeaderMap) -> ApiResult<Vec<GroupEntry>> {
    let me = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    Ok(Json(list(&db, &me)?))
}

#[derive(Deserialize)]
pub struct CreateBody {
    name: String,
    #[serde(default)]
    members: Vec<String>,
}

pub async fn create(State(app): State<Shared>, headers: HeaderMap, Json(body): Json<CreateBody>) -> ApiResult<GroupEntry> {
    let me = authed(&app, &headers)?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    let out = create_tx(&tx, &me, &body)?;
    tx.commit()?;
    Ok(Json(out))
}

fn create_tx(db: &Connection, me: &str, body: &CreateBody) -> Result<GroupEntry, ApiError> {
    let name = clean_name(&body.name)?;
    if group_count(db, me)? >= GROUPS_MAX {
        return Err(bad(format!("You can be in up to {GROUPS_MAX} groups.")));
    }
    let mut members = Vec::new();
    for raw in &body.members {
        let uuid = dashed_uuid(raw).ok_or_else(|| bad("bad uuid"))?;
        if uuid != me && !members.contains(&uuid) {
            check_invitee(db, me, &uuid)?;
            members.push(uuid);
        }
    }
    if members.len() as i64 >= MEMBERS_MAX {
        return Err(bad(format!("Groups hold up to {MEMBERS_MAX} players.")));
    }
    let t = now();
    db.execute("INSERT INTO chat_groups (name, owner, created_at) VALUES (?1, ?2, ?3)", params![name, me, t])?;
    let id = db.last_insert_rowid();
    db.execute("INSERT INTO chat_group_members (group_id, uuid, joined_at) VALUES (?1, ?2, ?3)", params![id, me, t])?;
    for uuid in &members {
        add_member(db, id, uuid)?;
    }
    entry(db, id, me)
}

#[derive(Deserialize)]
pub struct RenameBody {
    name: String,
}

pub async fn rename(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path(group): Path<i64>,
    Json(body): Json<RenameBody>,
) -> ApiResult<GroupEntry> {
    let me = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    require_member(&db, group, &me)?;
    db.execute("UPDATE chat_groups SET name = ?2 WHERE id = ?1", params![group, clean_name(&body.name)?])?;
    Ok(Json(entry(&db, group, &me)?))
}

#[derive(Deserialize)]
pub struct AddBody {
    uuid: String,
}

pub async fn invite(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path(group): Path<i64>,
    Json(body): Json<AddBody>,
) -> ApiResult<GroupEntry> {
    let me = authed(&app, &headers)?;
    let who = dashed_uuid(&body.uuid).ok_or_else(|| bad("bad uuid"))?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    require_member(&tx, group, &me)?;
    if !is_member(&tx, group, &who)? {
        check_invitee(&tx, &me, &who)?;
        add_member(&tx, group, &who)?;
    }
    let out = entry(&tx, group, &me)?;
    tx.commit()?;
    Ok(Json(out))
}

/// Leave (your own uuid) or, as the owner, remove someone.
pub async fn remove(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path((group, raw)): Path<(i64, String)>,
) -> ApiResult<Value> {
    let me = authed(&app, &headers)?;
    let who = dashed_uuid(&raw).ok_or_else(|| bad("bad uuid"))?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    let owner = require_member(&tx, group, &me)?;
    if who != me && owner != me {
        return Err(forbidden("Only the group's owner can remove players."));
    }
    remove_member(&tx, group, &who)?;
    tx.commit()?;
    Ok(Json(json!({ "ok": true })))
}

#[derive(Deserialize)]
pub struct MessagesQuery {
    #[serde(default, rename = "afterId")]
    after_id: i64,
    /// older history: the page before this id
    #[serde(default, rename = "beforeId")]
    before_id: i64,
}

/// Oldest-first. With neither cursor, the newest page; `afterId` polls for
/// what's new, `beforeId` scrolls back. Anything returned counts as read.
pub async fn messages(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path(group): Path<i64>,
    Query(q): Query<MessagesQuery>,
) -> ApiResult<Vec<GroupMessage>> {
    let me = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    require_member(&db, group, &me)?;
    Ok(Json(read(&db, group, &me, q.after_id, q.before_id)?))
}

fn read(db: &Connection, group: i64, me: &str, after_id: i64, before_id: i64) -> Result<Vec<GroupMessage>, ApiError> {
    let cols = "id, group_id, from_uuid, body, sent_at, edited_at FROM chat_group_messages WHERE group_id = ?1";
    let sql = if after_id > 0 {
        format!("SELECT {cols} AND id > ?2 ORDER BY id ASC LIMIT ?3")
    } else {
        let bound = if before_id > 0 { "AND id < ?2" } else { "AND ?2 = ?2" };
        format!("SELECT * FROM (SELECT {cols} {bound} ORDER BY id DESC LIMIT ?3) ORDER BY id ASC")
    };
    let cursor = if after_id > 0 { after_id } else { before_id };
    let out = db
        .prepare(&sql)?
        .query_map(params![group, cursor, MESSAGE_PAGE], |r| {
            Ok(GroupMessage {
                id: r.get(0)?,
                group_id: r.get(1)?,
                from_uuid: r.get(2)?,
                body: r.get(3)?,
                sent_at: r.get(4)?,
                edited_at: r.get(5)?,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    if let Some(last) = out.last() {
        mark_read(db, group, me, last.id)?;
    }
    Ok(out)
}

fn mark_read(db: &Connection, group: i64, me: &str, id: i64) -> Result<(), rusqlite::Error> {
    db.execute(
        "UPDATE chat_group_members SET last_read = MAX(last_read, ?3) WHERE group_id = ?1 AND uuid = ?2",
        params![group, me, id],
    )?;
    Ok(())
}

#[derive(Deserialize)]
pub struct SendBody {
    body: String,
}

pub async fn send(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path(group): Path<i64>,
    Json(payload): Json<SendBody>,
) -> ApiResult<GroupMessage> {
    let me = authed(&app, &headers)?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    let out = send_tx(&tx, group, &me, &payload.body, now())?;
    tx.commit()?;
    Ok(Json(out))
}

fn send_tx(db: &Connection, group: i64, me: &str, raw: &str, t: i64) -> Result<GroupMessage, ApiError> {
    require_member(db, group, me)?;
    let body = clean_body(raw)?;
    // direct and group messages share one budget
    let recent: i64 = db.query_row(
        "SELECT (SELECT COUNT(*) FROM messages WHERE from_uuid = ?1 AND sent_at > ?2)
              + (SELECT COUNT(*) FROM chat_group_messages WHERE from_uuid = ?1 AND sent_at > ?2)",
        params![me, t - MESSAGE_RATE_WINDOW],
        |r| r.get(0),
    )?;
    if recent >= MESSAGE_RATE_MAX {
        return Err(ApiError(StatusCode::TOO_MANY_REQUESTS, "You're sending messages too fast — wait a few seconds.".into()));
    }
    db.execute(
        "INSERT INTO chat_group_messages (group_id, from_uuid, body, sent_at) VALUES (?1, ?2, ?3, ?4)",
        params![group, me, body, t],
    )?;
    let id = db.last_insert_rowid();
    mark_read(db, group, me, id)?;
    quests::record_message(db, me, "text")?;
    Ok(GroupMessage { id, group_id: group, from_uuid: me.to_string(), body, sent_at: t, edited_at: None })
}

pub async fn edit(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path((group, id)): Path<(i64, i64)>,
    Json(payload): Json<SendBody>,
) -> ApiResult<GroupMessage> {
    let me = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    require_member(&db, group, &me)?;
    let body = clean_body(&payload.body)?;
    let t = now();
    let changed = db.execute(
        "UPDATE chat_group_messages SET body = ?4, edited_at = ?5 WHERE id = ?1 AND group_id = ?2 AND from_uuid = ?3",
        params![id, group, me, body, t],
    )?;
    if changed == 0 {
        return Err(forbidden("You can only edit your own messages."));
    }
    let sent_at: i64 = db.query_row("SELECT sent_at FROM chat_group_messages WHERE id = ?1", params![id], |r| r.get(0))?;
    Ok(Json(GroupMessage { id, group_id: group, from_uuid: me, body, sent_at, edited_at: Some(t) }))
}

pub async fn delete_message(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path((group, id)): Path<(i64, i64)>,
) -> ApiResult<Value> {
    let me = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    let owner = require_member(&db, group, &me)?;
    let changed = db.execute(
        "DELETE FROM chat_group_messages WHERE id = ?1 AND group_id = ?2 AND (from_uuid = ?3 OR ?4)",
        params![id, group, me, owner == me],
    )?;
    if changed == 0 {
        return Err(forbidden("You can only delete your own messages."));
    }
    Ok(Json(json!({ "ok": true })))
}

#[derive(Deserialize)]
pub struct ReadBody {
    #[serde(rename = "lastId")]
    last_id: i64,
}

pub async fn read_up_to(
    State(app): State<Shared>,
    headers: HeaderMap,
    Path(group): Path<i64>,
    Json(body): Json<ReadBody>,
) -> ApiResult<Value> {
    let me = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    require_member(&db, group, &me)?;
    mark_read(&db, group, &me, body.last_id)?;
    Ok(Json(json!({ "ok": true })))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{friend_pair, issue_token, open_db};

    const A: &str = "ba4161c0-3a42-496c-8ae0-7d13372f3371";
    const B: &str = "0f0e0d0c-0b0a-0908-0706-050403020100";
    const C: &str = "11111111-2222-3333-4444-555555555555";

    fn setup() -> Connection {
        let db = open_db(":memory:");
        for (uuid, name) in [(A, "alice"), (B, "bob"), (C, "carol")] {
            issue_token(&db, uuid, name).unwrap();
        }
        for (x, y) in [(A, B), (B, C)] {
            let (a, b) = friend_pair(x, y);
            db.execute("INSERT INTO friendships (a, b, created_at) VALUES (?1, ?2, 0)", params![a, b]).unwrap();
        }
        db
    }

    fn make(db: &Connection, owner: &str, members: &[&str]) -> Result<GroupEntry, ApiError> {
        create_tx(db, owner, &CreateBody { name: " squad ".into(), members: members.iter().map(|s| s.to_string()).collect() })
    }

    #[test]
    fn members_come_from_friends() {
        let db = setup();
        assert_eq!(make(&db, A, &[C]).unwrap_err().0, StatusCode::FORBIDDEN);
        let g = make(&db, A, &[B]).unwrap();
        assert_eq!(g.name, "squad");
        assert_eq!(g.members.len(), 2);
        // A can't add C (not A's friend), but B can
        assert!(check_invitee(&db, A, C).is_err());
        check_invitee(&db, B, C).unwrap();
        add_member(&db, g.id, C).unwrap();
        assert_eq!(entry(&db, g.id, A).unwrap().members.len(), 3);
    }

    #[test]
    fn unread_counts_others_and_reading_clears_it() {
        let db = setup();
        let g = make(&db, A, &[B]).unwrap();
        send_tx(&db, g.id, A, "hi", 1_000).unwrap();
        send_tx(&db, g.id, A, "there", 1_001).unwrap();
        assert_eq!(entry(&db, g.id, A).unwrap().unread, 0);
        assert_eq!(entry(&db, g.id, B).unwrap().unread, 2);
        let got = read(&db, g.id, B, 0, 0).unwrap();
        assert_eq!(got.iter().map(|m| m.body.as_str()).collect::<Vec<_>>(), ["hi", "there"]);
        assert_eq!(entry(&db, g.id, B).unwrap().unread, 0);
        assert!(read(&db, g.id, B, got[1].id, 0).unwrap().is_empty());
        assert_eq!(read(&db, g.id, B, 0, got[1].id).unwrap().len(), 1);
        assert!(send_tx(&db, g.id, C, "let me in", 1_002).is_err());
    }

    #[test]
    fn owner_passes_on_and_last_one_out_deletes() {
        let db = setup();
        let g = make(&db, A, &[B]).unwrap();
        send_tx(&db, g.id, B, "yo", 1_000).unwrap();
        remove_member(&db, g.id, A).unwrap();
        assert_eq!(owner_of(&db, g.id).unwrap().as_deref(), Some(B));
        remove_member(&db, g.id, B).unwrap();
        assert!(owner_of(&db, g.id).unwrap().is_none());
        let left: i64 = db.query_row("SELECT COUNT(*) FROM chat_group_messages", [], |r| r.get(0)).unwrap();
        assert_eq!(left, 0);
    }

    #[test]
    fn names_are_checked() {
        assert!(clean_name("   ").is_err());
        assert!(clean_name(&"x".repeat(NAME_MAX_LEN + 1)).is_err());
        assert_eq!(clean_name(" a\u{7}b ").unwrap(), "ab");
    }
}
