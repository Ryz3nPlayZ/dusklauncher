//! Quests: coins for playing, like Discord's quests pay orbs.
//!
//! * **daily quests** — three a day from `DAILY`, picked per account when the
//!   day's board is first read and kept for the day (UTC);
//! * **weekly quests** — three a week from `WEEKLY` (weeks start Monday, UTC);
//! * **the streak** — a day with `STREAK_MINUTES` of play keeps it going and
//!   pays `STREAK_COINS` for its place in the week-long cycle;
//! * **achievements** — one-time milestones; the claimed ones are badges on
//!   the account's profile.
//!
//! Everything is claimed (`POST /v1/me/quests/claim`), never paid on its own.
//! At the rates here an active player earns about 300 coins a day.
//!
//! Play time comes from the Dusk mod (`POST /v1/me/play`, once a minute in a
//! world). A tick only counts while the player touched the game in the last
//! few minutes, and credits at most `TICK_MAX_S` since the previous one, so
//! extra ticks never earn faster than real time. "With a friend" is a friend
//! whose own last tick was on the same server. Chat and gifts count where
//! the server stores them (`record_message`).

use crate::{authed, bad, clean_server, now, ApiError, ApiResult, Shared};
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// A play tick older than this doesn't continue the session it started.
const TICK_GAP_S: i64 = 150;
/// The most one tick credits (the mod ticks every 60 s).
const TICK_MAX_S: i64 = 90;
const DAY_S: i64 = 86_400;
/// Play that makes a day count for the streak and "days played".
const STREAK_MINUTES: i64 = 15;
/// What each day of the streak pays, cycling every week.
const STREAK_COINS: [i64; 7] = [20, 25, 30, 35, 40, 50, 100];

#[derive(Clone, Copy, PartialEq)]
enum Metric {
    /// minutes in a world
    Play,
    /// minutes on the same server as a friend
    Friend,
    /// minutes on a multiplayer server
    Server,
    /// screenshots sent in chat
    Shots,
    /// chat messages sent
    Messages,
    /// cosmetics gifted
    Gifts,
    /// different friends played with
    FriendsPlayed,
    /// days with `STREAK_MINUTES` of play
    Days,
    /// friends (lifetime only)
    Friends,
    /// longest streak (lifetime only)
    BestStreak,
    /// daily and weekly quests claimed (lifetime only)
    Claimed,
}

impl Metric {
    fn unit(self) -> &'static str {
        match self {
            Metric::Play | Metric::Friend | Metric::Server => "min",
            _ => "",
        }
    }
}

struct Def {
    id: &'static str,
    title: &'static str,
    metric: Metric,
    goal: i64,
    coins: i64,
    /// needs a friend; left out of boards for accounts without one
    social: bool,
}

const fn q(id: &'static str, title: &'static str, metric: Metric, goal: i64, coins: i64, social: bool) -> Def {
    Def { id, title, metric, goal, coins, social }
}

/// The first `PLAY_FIRST` of each pool are play quests; a board always has
/// one of them, then two of the rest.
const PLAY_FIRST: usize = 2;

const DAILY: &[Def] = &[
    q("play_30", "Play for 30 minutes", Metric::Play, 30, 50, false),
    q("play_60", "Play for an hour", Metric::Play, 60, 80, false),
    q("friend_20", "Play 20 minutes with a friend", Metric::Friend, 20, 80, true),
    q("server_20", "Play 20 minutes on a server", Metric::Server, 20, 60, false),
    q("shot_1", "Send a friend a screenshot", Metric::Shots, 1, 40, true),
    q("chat_5", "Send 5 messages to friends", Metric::Messages, 5, 40, true),
];

const WEEKLY: &[Def] = &[
    q("w_play_300", "Play for 5 hours", Metric::Play, 300, 200, false),
    q("w_days_5", "Play on 5 different days", Metric::Days, 5, 200, false),
    q("w_friend_120", "Play 2 hours with friends", Metric::Friend, 120, 250, true),
    q("w_friends_3", "Play with 3 different friends", Metric::FriendsPlayed, 3, 250, true),
    q("w_server_180", "Play 3 hours on servers", Metric::Server, 180, 200, false),
    q("w_gift_1", "Gift a friend a cosmetic", Metric::Gifts, 1, 150, true),
];

const ACHIEVEMENTS: &[Def] = &[
    q("first_hour", "First hour", Metric::Play, 60, 100, false),
    q("hours_10", "10 hours played", Metric::Play, 600, 250, false),
    q("hours_100", "100 hours played", Metric::Play, 6000, 1000, false),
    q("first_friend", "Made a friend", Metric::Friends, 1, 100, false),
    q("friends_10", "10 friends", Metric::Friends, 10, 300, false),
    q("together", "Played with a friend", Metric::Friend, 10, 100, false),
    q("first_gift", "First gift", Metric::Gifts, 1, 100, false),
    q("streak_7", "7-day streak", Metric::BestStreak, 7, 200, false),
    q("streak_30", "30-day streak", Metric::BestStreak, 30, 750, false),
    q("quests_50", "50 quests done", Metric::Claimed, 50, 500, false),
];

pub fn migrate(db: &Connection) {
    db.execute_batch(
        "CREATE TABLE IF NOT EXISTS activity (
           uuid TEXT NOT NULL REFERENCES accounts(uuid), day INTEGER NOT NULL, key TEXT NOT NULL,
           value INTEGER NOT NULL, PRIMARY KEY (uuid, day, key));
         CREATE TABLE IF NOT EXISTS played_with (
           uuid TEXT NOT NULL REFERENCES accounts(uuid), day INTEGER NOT NULL, friend TEXT NOT NULL,
           PRIMARY KEY (uuid, day, friend));
         CREATE TABLE IF NOT EXISTS quest_boards (
           uuid TEXT NOT NULL REFERENCES accounts(uuid), period TEXT NOT NULL, quests TEXT NOT NULL,
           PRIMARY KEY (uuid, period));
         CREATE TABLE IF NOT EXISTS quest_claims (
           uuid TEXT NOT NULL REFERENCES accounts(uuid), period TEXT NOT NULL, quest TEXT NOT NULL,
           coins INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (uuid, period, quest));",
    )
    .expect("quest schema");
    crate::add_column(db, "accounts", "play_at", "INTEGER");
    crate::add_column(db, "accounts", "play_server", "TEXT");
}

fn day_of(t: i64) -> i64 {
    t.div_euclid(DAY_S)
}

/// Weeks start on Monday; day 0 (1970-01-01) was a Thursday.
fn week_of(day: i64) -> i64 {
    (day + 3).div_euclid(7)
}

fn bump(db: &Connection, uuid: &str, day: i64, key: &str, by: i64) -> Result<(), rusqlite::Error> {
    db.execute(
        "INSERT INTO activity (uuid, day, key, value) VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT(uuid, day, key) DO UPDATE SET value = value + excluded.value",
        params![uuid, day, key, by],
    )?;
    Ok(())
}

/// Counts a chat message towards the quests (called where messages are stored).
pub fn record_message(db: &Connection, from: &str, kind: &str) -> Result<(), rusqlite::Error> {
    let key = match kind {
        "image" => "shots",
        "gift" => "gifts",
        "text" => "messages",
        _ => return Ok(()),
    };
    bump(db, from, day_of(now()), key, 1)
}

// ── reading progress ───────────────────────────────────────────────────────

fn sum(db: &Connection, uuid: &str, key: &str, from_day: i64, to_day: i64) -> Result<i64, rusqlite::Error> {
    db.query_row(
        "SELECT COALESCE(SUM(value), 0) FROM activity WHERE uuid = ?1 AND key = ?2 AND day BETWEEN ?3 AND ?4",
        params![uuid, key, from_day, to_day],
        |r| r.get(0),
    )
}

/// Days with enough play to count, oldest first.
fn played_days(db: &Connection, uuid: &str) -> Result<Vec<i64>, rusqlite::Error> {
    let mut st =
        db.prepare("SELECT day FROM activity WHERE uuid = ?1 AND key = 'play_s' AND value >= ?2 ORDER BY day")?;
    let days = st.query_map(params![uuid, STREAK_MINUTES * 60], |r| r.get(0))?.collect::<Result<Vec<i64>, _>>()?;
    Ok(days)
}

/// The run of played days ending today, or yesterday if today doesn't count yet.
fn current_streak(days: &[i64], today: i64) -> i64 {
    let mut expect = if days.last() == Some(&today) { today } else { today - 1 };
    let mut n = 0;
    for d in days.iter().rev() {
        if *d == expect {
            n += 1;
            expect -= 1;
        } else if *d < expect {
            break;
        }
    }
    n
}

fn best_streak(days: &[i64]) -> i64 {
    let (mut best, mut run, mut prev) = (0, 0, i64::MIN);
    for d in days {
        run = if *d == prev + 1 { run + 1 } else { 1 };
        best = best.max(run);
        prev = *d;
    }
    best
}

fn value(db: &Connection, uuid: &str, m: Metric, from_day: i64, to_day: i64, days: &[i64]) -> Result<i64, rusqlite::Error> {
    Ok(match m {
        Metric::Play => sum(db, uuid, "play_s", from_day, to_day)? / 60,
        Metric::Friend => sum(db, uuid, "friend_s", from_day, to_day)? / 60,
        Metric::Server => sum(db, uuid, "server_s", from_day, to_day)? / 60,
        Metric::Shots => sum(db, uuid, "shots", from_day, to_day)?,
        Metric::Messages => sum(db, uuid, "messages", from_day, to_day)?,
        Metric::Gifts => sum(db, uuid, "gifts", from_day, to_day)?,
        Metric::FriendsPlayed => db.query_row(
            "SELECT COUNT(DISTINCT friend) FROM played_with WHERE uuid = ?1 AND day BETWEEN ?2 AND ?3",
            params![uuid, from_day, to_day],
            |r| r.get(0),
        )?,
        Metric::Days => days.iter().filter(|d| (from_day..=to_day).contains(*d)).count() as i64,
        Metric::Friends => {
            db.query_row("SELECT COUNT(*) FROM friendships WHERE a = ?1 OR b = ?1", params![uuid], |r| r.get(0))?
        }
        Metric::BestStreak => best_streak(days),
        Metric::Claimed => db.query_row(
            "SELECT COUNT(*) FROM quest_claims WHERE uuid = ?1 AND quest != 'streak' AND (period LIKE 'd:%' OR period LIKE 'w:%')",
            params![uuid],
            |r| r.get(0),
        )?,
    })
}

/// This period's board: picked once (seeded by account and period) and kept.
fn board(db: &Connection, uuid: &str, period: &str, pool: &'static [Def]) -> Result<Vec<&'static Def>, rusqlite::Error> {
    let stored: Option<String> = db
        .query_row("SELECT quests FROM quest_boards WHERE uuid = ?1 AND period = ?2", params![uuid, period], |r| r.get(0))
        .optional()?;
    let ids: Vec<String> = match stored {
        Some(s) => s.split(',').map(str::to_string).collect(),
        None => {
            let friends: i64 =
                db.query_row("SELECT COUNT(*) FROM friendships WHERE a = ?1 OR b = ?1", params![uuid], |r| r.get(0))?;
            let ids = pick(uuid, period, pool, friends > 0);
            db.execute(
                "INSERT OR IGNORE INTO quest_boards (uuid, period, quests) VALUES (?1, ?2, ?3)",
                params![uuid, period, ids.join(",")],
            )?;
            ids
        }
    };
    Ok(ids.iter().filter_map(|id| pool.iter().find(|d| d.id == id)).collect())
}

/// One play quest, then two others, shuffled by a hash of account and period.
fn pick(uuid: &str, period: &str, pool: &'static [Def], has_friends: bool) -> Vec<String> {
    let seed = Sha256::digest(format!("{uuid}|{period}").as_bytes());
    let mut bytes = seed.iter().copied();
    let mut next = |n: usize| bytes.next().unwrap_or(0) as usize % n.max(1);
    let first = next(PLAY_FIRST);
    let mut out = vec![pool[first].id.to_string()];
    let mut rest: Vec<&Def> = pool[PLAY_FIRST..].iter().filter(|d| has_friends || !d.social).collect();
    while out.len() < 3 && !rest.is_empty() {
        let d = rest.remove(next(rest.len()));
        out.push(d.id.to_string());
    }
    // without friends the pool runs short: the other play quests fill the board
    for (i, d) in pool[..PLAY_FIRST].iter().enumerate() {
        if out.len() < 3 && i != first {
            out.push(d.id.to_string());
        }
    }
    out
}

fn claimed(db: &Connection, uuid: &str, period: &str, quest: &str) -> Result<bool, rusqlite::Error> {
    Ok(db
        .query_row(
            "SELECT 1 FROM quest_claims WHERE uuid = ?1 AND period = ?2 AND quest = ?3",
            params![uuid, period, quest],
            |_| Ok(true),
        )
        .optional()?
        .unwrap_or(false))
}

#[derive(Serialize, Clone)]
pub struct Quest {
    id: String,
    title: &'static str,
    progress: i64,
    goal: i64,
    /// "min" for minutes, else a count
    unit: &'static str,
    coins: i64,
    done: bool,
    claimed: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Streak {
    /// played days in a row, counting today once it reaches `need_minutes`
    days: i64,
    today_minutes: i64,
    need_minutes: i64,
    /// what today pays once it counts
    coins: i64,
    done: bool,
    claimed: bool,
    /// the week's cycle of payouts, and today's place in it (0-based)
    cycle: [i64; 7],
    cycle_day: usize,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Quests {
    coins: i64,
    daily: Vec<Quest>,
    weekly: Vec<Quest>,
    streak: Streak,
    achievements: Vec<Quest>,
    /// rewards waiting for CLAIM
    claimable: usize,
    /// seconds until the daily / weekly boards change
    daily_reset: i64,
    weekly_reset: i64,
}

fn quest(db: &Connection, uuid: &str, d: &Def, period: &str, from: i64, to: i64, days: &[i64]) -> Result<Quest, rusqlite::Error> {
    let progress = value(db, uuid, d.metric, from, to, days)?;
    Ok(Quest {
        id: d.id.to_string(),
        title: d.title,
        progress: progress.min(d.goal),
        goal: d.goal,
        unit: d.metric.unit(),
        coins: d.coins,
        done: progress >= d.goal,
        claimed: claimed(db, uuid, period, d.id)?,
    })
}

pub fn read_quests(db: &Connection, uuid: &str) -> Result<Quests, rusqlite::Error> {
    read_quests_at(db, uuid, now())
}

fn read_quests_at(db: &Connection, uuid: &str, t: i64) -> Result<Quests, rusqlite::Error> {
    let today = day_of(t);
    let week = week_of(today);
    let week_start = week * 7 - 3;
    let days = played_days(db, uuid)?;
    let (dp, wp) = (format!("d:{today}"), format!("w:{week}"));

    let mut daily = Vec::new();
    for d in board(db, uuid, &dp, DAILY)? {
        daily.push(quest(db, uuid, d, &dp, today, today, &days)?);
    }
    let mut weekly = Vec::new();
    for d in board(db, uuid, &wp, WEEKLY)? {
        weekly.push(quest(db, uuid, d, &wp, week_start, today, &days)?);
    }
    let mut achievements = Vec::new();
    for d in ACHIEVEMENTS {
        achievements.push(quest(db, uuid, d, "a", i64::MIN / 2, today, &days)?);
    }

    let streak_days = current_streak(&days, today);
    let today_counts = days.last() == Some(&today);
    // the place in the cycle today has (or will have, once it counts)
    let place = if today_counts { streak_days } else { streak_days + 1 };
    let cycle_day = ((place - 1).max(0) % 7) as usize;
    let streak = Streak {
        days: streak_days,
        today_minutes: sum(db, uuid, "play_s", today, today)? / 60,
        need_minutes: STREAK_MINUTES,
        coins: STREAK_COINS[cycle_day],
        done: today_counts,
        claimed: claimed(db, uuid, &dp, "streak")?,
        cycle: STREAK_COINS,
        cycle_day,
    };

    let claimable = daily.iter().chain(&weekly).chain(&achievements).filter(|q| q.done && !q.claimed).count()
        + usize::from(streak.done && !streak.claimed);
    let coins = db.query_row("SELECT coins FROM accounts WHERE uuid = ?1", params![uuid], |r| r.get(0))?;
    Ok(Quests {
        coins,
        daily,
        weekly,
        streak,
        achievements,
        claimable,
        daily_reset: (today + 1) * DAY_S - t,
        weekly_reset: (week_start + 7) * DAY_S - t,
    })
}

/// The achievements an account has claimed: its profile badges.
pub fn badges(db: &Connection, uuid: &str) -> Result<Vec<&'static str>, rusqlite::Error> {
    let mut st = db.prepare("SELECT quest FROM quest_claims WHERE uuid = ?1 AND period = 'a' ORDER BY at")?;
    let ids = st.query_map(params![uuid], |r| r.get::<_, String>(0))?.collect::<Result<Vec<_>, _>>()?;
    Ok(ids.iter().filter_map(|id| ACHIEVEMENTS.iter().find(|d| d.id == id)).map(|d| d.title).collect())
}

// ── claiming ───────────────────────────────────────────────────────────────

fn pay(db: &Connection, uuid: &str, period: &str, quest: &str, coins: i64) -> Result<(), rusqlite::Error> {
    let t = now();
    db.execute(
        "INSERT INTO quest_claims (uuid, period, quest, coins, at) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![uuid, period, quest, coins, t],
    )?;
    db.execute("UPDATE accounts SET coins = coins + ?1 WHERE uuid = ?2", params![coins, uuid])?;
    db.execute(
        "INSERT INTO ledger (uuid, delta, reason, at) VALUES (?1, ?2, ?3, ?4)",
        params![uuid, coins, format!("quest:{period}:{quest}"), t],
    )?;
    Ok(())
}

/// Claims `id` ("streak", a quest or achievement id, or "all"); the coins paid.
fn claim_tx(db: &Connection, uuid: &str, id: &str) -> Result<i64, ApiError> {
    let t = now();
    let today = day_of(t);
    let (dp, wp) = (format!("d:{today}"), format!("w:{}", week_of(today)));
    let state = read_quests_at(db, uuid, t)?;
    let mut paid = 0;
    let mut found = false;
    let all = id == "all";
    let groups: [(&[Quest], &str); 3] =
        [(&state.daily[..], dp.as_str()), (&state.weekly[..], wp.as_str()), (&state.achievements[..], "a")];
    for (list, period) in groups {
        for q in list.iter().filter(|q| all || q.id == id) {
            found = true;
            if q.done && !q.claimed {
                pay(db, uuid, period, &q.id, q.coins)?;
                paid += q.coins;
            } else if !all {
                return Err(bad(if q.claimed { "Already claimed." } else { "That quest isn't done yet." }));
            }
        }
    }
    if all || id == "streak" {
        found = true;
        let s = &state.streak;
        if s.done && !s.claimed {
            pay(db, uuid, &dp, "streak", s.coins)?;
            paid += s.coins;
        } else if !all {
            return Err(bad(if s.claimed {
                "Today's streak reward is already claimed."
            } else {
                "Play 15 minutes today first."
            }));
        }
    }
    if !found {
        return Err(ApiError(StatusCode::NOT_FOUND, "That quest isn't on your board.".into()));
    }
    Ok(paid)
}

#[derive(Deserialize)]
pub struct ClaimBody {
    id: String,
}

#[derive(Serialize)]
pub struct Claimed {
    /// coins this claim paid
    paid: i64,
    quests: Quests,
}

pub async fn get_quests(State(app): State<Shared>, headers: HeaderMap) -> ApiResult<Quests> {
    let uuid = authed(&app, &headers)?;
    let db = app.db.lock().unwrap();
    Ok(Json(read_quests(&db, &uuid)?))
}

pub async fn claim(State(app): State<Shared>, headers: HeaderMap, Json(body): Json<ClaimBody>) -> ApiResult<Claimed> {
    let uuid = authed(&app, &headers)?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    let paid = claim_tx(&tx, &uuid, &body.id)?;
    let quests = read_quests(&tx, &uuid)?;
    tx.commit()?;
    if paid > 0 {
        tracing::info!("{uuid} claimed {} (+{paid})", body.id);
    }
    Ok(Json(Claimed { paid, quests }))
}

// ── play time ──────────────────────────────────────────────────────────────

#[derive(Deserialize)]
pub struct PlayBody {
    /// the player touched the game in the last few minutes
    active: bool,
    /// the multiplayer server, as typed; none in singleplayer
    #[serde(default)]
    server: Option<String>,
}

#[derive(Serialize)]
pub struct Ready {
    id: String,
    title: &'static str,
    coins: i64,
}

#[derive(Serialize)]
pub struct Played {
    /// everything waiting for CLAIM, so the game can say when something new is
    ready: Vec<Ready>,
}

fn play_tx(db: &Connection, uuid: &str, body: &PlayBody, t: i64) -> Result<(), rusqlite::Error> {
    let server = body.server.as_deref().and_then(clean_server);
    if !body.active {
        // idle: nothing to credit, and the next active tick starts afresh
        db.execute("UPDATE accounts SET play_at = NULL, play_server = NULL WHERE uuid = ?1", params![uuid])?;
        return Ok(());
    }
    let last: Option<i64> =
        db.query_row("SELECT play_at FROM accounts WHERE uuid = ?1", params![uuid], |r| r.get(0))?;
    db.execute("UPDATE accounts SET play_at = ?1, play_server = ?2 WHERE uuid = ?3", params![t, server, uuid])?;
    let Some(last) = last else { return Ok(()) };
    let gap = t - last;
    if gap <= 0 || gap > TICK_GAP_S {
        return Ok(());
    }
    let credit = gap.min(TICK_MAX_S);
    let today = day_of(t);
    bump(db, uuid, today, "play_s", credit)?;
    let Some(server) = server else { return Ok(()) };
    bump(db, uuid, today, "server_s", credit)?;
    let mut st = db.prepare(
        "SELECT acc.uuid FROM friendships f JOIN accounts acc ON acc.uuid = CASE WHEN f.a = ?1 THEN f.b ELSE f.a END
         WHERE (f.a = ?1 OR f.b = ?1) AND acc.play_server = ?2 AND acc.play_at >= ?3",
    )?;
    let together = st.query_map(params![uuid, server, t - TICK_GAP_S], |r| r.get::<_, String>(0))?.collect::<Result<Vec<_>, _>>()?;
    if !together.is_empty() {
        bump(db, uuid, today, "friend_s", credit)?;
        for f in together {
            db.execute("INSERT OR IGNORE INTO played_with (uuid, day, friend) VALUES (?1, ?2, ?3)", params![uuid, today, f])?;
        }
    }
    Ok(())
}

fn ready(q: &Quests) -> Vec<Ready> {
    let mut out: Vec<Ready> = q
        .daily
        .iter()
        .chain(&q.weekly)
        .chain(&q.achievements)
        .filter(|x| x.done && !x.claimed)
        .map(|x| Ready { id: x.id.clone(), title: x.title, coins: x.coins })
        .collect();
    if q.streak.done && !q.streak.claimed {
        out.push(Ready { id: "streak".into(), title: "Daily streak", coins: q.streak.coins });
    }
    out
}

pub async fn play(State(app): State<Shared>, headers: HeaderMap, Json(body): Json<PlayBody>) -> ApiResult<Played> {
    let uuid = authed(&app, &headers)?;
    let mut db = app.db.lock().unwrap();
    let tx = db.transaction()?;
    play_tx(&tx, &uuid, &body, now())?;
    let quests = read_quests(&tx, &uuid)?;
    tx.commit()?;
    Ok(Json(Played { ready: ready(&quests) }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{friend_pair, issue_token, open_db};

    const A: &str = "ba4161c0-3a42-496c-8ae0-7d13372f3371";
    const B: &str = "0f0e0d0c-0b0a-0908-0706-050403020100";

    fn tick(db: &Connection, uuid: &str, server: Option<&str>, t: i64) {
        play_tx(db, uuid, &PlayBody { active: true, server: server.map(str::to_string) }, t).unwrap();
    }

    #[test]
    fn ticks_credit_real_time_only() {
        let db = open_db(":memory:");
        issue_token(&db, A, "A").unwrap();
        let t0 = 100 * DAY_S;
        tick(&db, A, None, t0);
        // ten ticks a second apart earn ten seconds, not ten minutes
        for i in 1..=10 {
            tick(&db, A, None, t0 + i);
        }
        assert_eq!(sum(&db, A, "play_s", 100, 100).unwrap(), 10);
        // a long gap starts a new session without crediting it
        tick(&db, A, None, t0 + 10 + 3600);
        assert_eq!(sum(&db, A, "play_s", 100, 100).unwrap(), 10);
        // a slow tick credits at most TICK_MAX_S
        tick(&db, A, None, t0 + 10 + 3600 + 140);
        assert_eq!(sum(&db, A, "play_s", 100, 100).unwrap(), 10 + TICK_MAX_S);
    }

    #[test]
    fn friends_on_one_server_play_together() {
        let db = open_db(":memory:");
        issue_token(&db, A, "A").unwrap();
        issue_token(&db, B, "B").unwrap();
        let (a, b) = friend_pair(A, B);
        db.execute("INSERT INTO friendships (a, b, created_at) VALUES (?1, ?2, 0)", params![a, b]).unwrap();
        let t0 = 100 * DAY_S;
        tick(&db, B, Some("mc.example.net"), t0);
        tick(&db, A, Some("mc.example.net"), t0);
        tick(&db, A, Some("MC.example.net"), t0 + 60);
        assert_eq!(sum(&db, A, "friend_s", 100, 100).unwrap(), 60);
        assert_eq!(sum(&db, A, "server_s", 100, 100).unwrap(), 60);
        // someone else's server: playing, but not together
        tick(&db, A, Some("other.net"), t0 + 120);
        assert_eq!(sum(&db, A, "friend_s", 100, 100).unwrap(), 60);
    }

    #[test]
    fn claims_pay_once() {
        let db = open_db(":memory:");
        issue_token(&db, A, "A").unwrap();
        let today = day_of(now());
        bump(&db, A, today, "play_s", 61 * 60).unwrap();
        let q = read_quests(&db, A).unwrap();
        assert!(q.streak.done && q.claimable >= 2); // streak + first hour at least
        let paid = claim_tx(&db, A, "all").unwrap();
        assert!(paid >= STREAK_COINS[0] + 100);
        assert_eq!(claim_tx(&db, A, "all").unwrap(), 0);
        assert!(claim_tx(&db, A, "streak").is_err());
        let coins: i64 = db.query_row("SELECT coins FROM accounts WHERE uuid = ?1", params![A], |r| r.get(0)).unwrap();
        assert_eq!(coins, paid);
        assert_eq!(badges(&db, A).unwrap(), vec!["First hour"]);
    }

    #[test]
    fn boards_are_stable_and_skip_friend_quests_without_friends() {
        let db = open_db(":memory:");
        issue_token(&db, A, "A").unwrap();
        let one = board(&db, A, "d:1", DAILY).unwrap().iter().map(|d| d.id).collect::<Vec<_>>();
        let two = board(&db, A, "d:1", DAILY).unwrap().iter().map(|d| d.id).collect::<Vec<_>>();
        assert_eq!(one, two);
        assert_eq!(one.len(), 3);
        assert!(one[0].starts_with("play_"));
        for p in 0..40 {
            assert!(board(&db, A, &format!("w:{p}"), WEEKLY).unwrap().iter().all(|d| !d.social));
        }
    }

    #[test]
    fn streaks() {
        assert_eq!(current_streak(&[1, 2, 3], 3), 3);
        assert_eq!(current_streak(&[1, 2, 3], 4), 3);
        assert_eq!(current_streak(&[1, 2, 3], 5), 0);
        assert_eq!(current_streak(&[1, 3, 4], 4), 2);
        assert_eq!(best_streak(&[1, 2, 3, 7, 8]), 3);
        assert_eq!(week_of(4), 1); // 1970-01-05, a Monday
        assert_eq!(week_of(3), 0);
    }
}
