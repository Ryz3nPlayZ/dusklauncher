//! The instance's multiplayer list (`servers.dat`) and a status ping per
//! entry, so the WORLDS tab can show each server's MOTD, players and ping and
//! JOIN it straight from the launcher (Quick Play, see `launch.rs`).

use crate::appstate::AppState;
use serde::Serialize;
use std::time::{Duration, Instant};
use tauri::State;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

const DEFAULT_PORT: u16 = 25565;
const TIMEOUT: Duration = Duration::from_secs(5);
/// a status reply is a few KB plus the favicon; anything near this is junk
const MAX_PACKET: usize = 1 << 20;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerDto {
    pub name: String,
    pub address: String,
    /// the icon the game cached at its last ping, as a data URL
    pub icon: Option<String>,
}

#[derive(Debug, Serialize, PartialEq)]
pub struct MotdPart {
    pub text: String,
    /// `#rrggbb`, or none for the default grey
    pub color: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerStatus {
    pub motd: Vec<MotdPart>,
    pub online: i64,
    pub max: i64,
    pub version: String,
    pub ping_ms: u32,
    pub icon: Option<String>,
}

// ---- servers.dat ----

#[derive(Debug)]
pub(crate) enum Tag {
    Byte(i8),
    Int(i32),
    Long(i64),
    Str(String),
    List(Vec<Tag>),
    Compound(Vec<(String, Tag)>),
    Other,
}

impl Tag {
    pub(crate) fn get(&self, key: &str) -> Option<&Tag> {
        match self {
            Tag::Compound(fields) => fields.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }
    pub(crate) fn str(&self) -> Option<&str> {
        match self {
            Tag::Str(s) => Some(s),
            _ => None,
        }
    }
    /// any whole-number tag, widened
    pub(crate) fn int(&self) -> Option<i64> {
        match self {
            Tag::Byte(v) => Some(*v as i64),
            Tag::Int(v) => Some(*v as i64),
            Tag::Long(v) => Some(*v),
            _ => None,
        }
    }
}

/// An uncompressed NBT file's root compound.
pub(crate) fn read_nbt(bytes: &[u8]) -> Option<Tag> {
    let mut r = Nbt { b: bytes, i: 0 };
    if r.u8()? != 10 {
        return None;
    }
    r.string()?;
    r.tag(10, 0)
}

struct Nbt<'a> {
    b: &'a [u8],
    i: usize,
}

impl Nbt<'_> {
    fn take(&mut self, n: usize) -> Option<&[u8]> {
        let end = self.i.checked_add(n)?;
        let s = self.b.get(self.i..end)?;
        self.i = end;
        Some(s)
    }
    fn u8(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }
    fn u16(&mut self) -> Option<u16> {
        Some(u16::from_be_bytes(self.take(2)?.try_into().ok()?))
    }
    fn i32(&mut self) -> Option<i32> {
        Some(i32::from_be_bytes(self.take(4)?.try_into().ok()?))
    }
    fn len(&mut self) -> Option<usize> {
        usize::try_from(self.i32()?).ok()
    }
    fn string(&mut self) -> Option<String> {
        let n = self.u16()? as usize;
        Some(modified_utf8(self.take(n)?))
    }
    fn tag(&mut self, kind: u8, depth: u32) -> Option<Tag> {
        if depth > 64 {
            return None;
        }
        Some(match kind {
            1 => Tag::Byte(self.u8()? as i8),
            2 => self.take(2).map(|_| Tag::Other)?,
            3 => Tag::Int(self.i32()?),
            5 => self.take(4).map(|_| Tag::Other)?,
            4 => Tag::Long(i64::from_be_bytes(self.take(8)?.try_into().ok()?)),
            6 => self.take(8).map(|_| Tag::Other)?,
            7 => {
                let n = self.len()?;
                self.take(n).map(|_| Tag::Other)?
            }
            8 => Tag::Str(self.string()?),
            9 => {
                let elem = self.u8()?;
                let n = self.len()?;
                let mut out = Vec::new();
                for _ in 0..n {
                    out.push(self.tag(elem, depth + 1)?);
                }
                Tag::List(out)
            }
            10 => {
                let mut fields = Vec::new();
                loop {
                    let k = self.u8()?;
                    if k == 0 {
                        break;
                    }
                    let name = self.string()?;
                    fields.push((name, self.tag(k, depth + 1)?));
                }
                Tag::Compound(fields)
            }
            11 => {
                let n = self.len()?;
                self.take(n.checked_mul(4)?).map(|_| Tag::Other)?
            }
            12 => {
                let n = self.len()?;
                self.take(n.checked_mul(8)?).map(|_| Tag::Other)?
            }
            _ => return None,
        })
    }
}

/// Java's modified UTF-8: plain UTF-8 except NUL (`C0 80`) and astral chars
/// (a surrogate pair, three bytes each half).
fn modified_utf8(b: &[u8]) -> String {
    if let Ok(s) = std::str::from_utf8(b) {
        return s.to_string();
    }
    let mut units = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        let c = b[i] as u16;
        let cont = |j: usize| b.get(j).filter(|x| *x & 0xC0 == 0x80).map(|x| (*x & 0x3F) as u16);
        if c < 0x80 {
            units.push(c);
            i += 1;
        } else if let (0xC0..=0xDF, Some(c1)) = (c, cont(i + 1)) {
            units.push((c & 0x1F) << 6 | c1);
            i += 2;
        } else if let (0xE0..=0xEF, Some(c1), Some(c2)) = (c, cont(i + 1), cont(i + 2)) {
            units.push((c & 0x0F) << 12 | c1 << 6 | c2);
            i += 3;
        } else {
            units.push(0xFFFD);
            i += 1;
        }
    }
    String::from_utf16_lossy(&units)
}

/// The visible entries of a `servers.dat`, in list order. Entries the game
/// marks hidden (direct-connect history) are left out, like the game does.
fn parse_servers(bytes: &[u8]) -> Vec<ServerDto> {
    let root = read_nbt(bytes);
    let Some(Tag::List(list)) = root.as_ref().and_then(|t| t.get("servers")) else {
        return Vec::new();
    };
    list.iter()
        .filter(|s| !matches!(s.get("hidden"), Some(Tag::Byte(1))))
        .filter_map(|s| {
            let address = s.get("ip")?.str()?.trim().to_string();
            if address.is_empty() {
                return None;
            }
            Some(ServerDto {
                name: s.get("name").and_then(Tag::str).unwrap_or("Minecraft Server").to_string(),
                address,
                icon: s
                    .get("icon")
                    .and_then(Tag::str)
                    .filter(|i| !i.is_empty())
                    .map(|i| format!("data:image/png;base64,{i}")),
            })
        })
        .collect()
}

pub(crate) fn profile_root(state: &AppState, profile_id: &str) -> Result<std::path::PathBuf, String> {
    let store = state.profiles.lock().unwrap();
    let profile = store.profiles.iter().find(|p| p.id == profile_id).ok_or("profile not found")?;
    Ok(profile.dirs(&state.data_dir).root)
}

#[tauri::command(async)]
pub fn list_servers(state: State<'_, AppState>, profile_id: String) -> Result<Vec<ServerDto>, String> {
    let root = profile_root(&state, &profile_id)?;
    match std::fs::read(root.join("servers.dat")) {
        Ok(bytes) => Ok(parse_servers(&bytes)),
        Err(_) => Ok(Vec::new()),
    }
}

// ---- editing servers.dat ----
//
// Edits keep every byte the launcher doesn't touch: the root's other fields
// and each untouched entry (its cached icon, resource-pack choice, …) are
// copied through as they were read.

/// `servers.dat` cut into the root's other fields and each entry's compound
/// payload, both raw.
#[derive(Debug, Default)]
struct RawServers {
    other: Vec<u8>,
    entries: Vec<Vec<u8>>,
}

fn split_raw(bytes: &[u8]) -> Option<RawServers> {
    let mut r = Nbt { b: bytes, i: 0 };
    if r.u8()? != 10 {
        return None;
    }
    r.string()?;
    let mut out = RawServers::default();
    loop {
        let start = r.i;
        let k = r.u8()?;
        if k == 0 {
            return Some(out);
        }
        let name = r.string()?;
        if name == "servers" && k == 9 {
            let elem = r.u8()?;
            let n = r.len()?;
            if n > 0 && elem != 10 {
                return None;
            }
            for _ in 0..n {
                let s = r.i;
                r.tag(10, 1)?;
                out.entries.push(bytes[s..r.i].to_vec());
            }
        } else {
            r.tag(k, 1)?;
            out.other.extend_from_slice(&bytes[start..r.i]);
        }
    }
}

/// Java's modified UTF-8, the string encoding NBT uses.
fn put_str(out: &mut Vec<u8>, s: &str) {
    let mut b = Vec::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '\0' => b.extend_from_slice(&[0xC0, 0x80]),
            c if (c as u32) > 0xFFFF => {
                let mut units = [0u16; 2];
                for u in c.encode_utf16(&mut units) {
                    let u = *u as u32;
                    b.extend_from_slice(&[0xE0 | (u >> 12) as u8, 0x80 | ((u >> 6) & 0x3F) as u8, 0x80 | (u & 0x3F) as u8]);
                }
            }
            c => b.extend_from_slice(c.encode_utf8(&mut [0; 4]).as_bytes()),
        }
    }
    out.extend_from_slice(&(b.len().min(u16::MAX as usize) as u16).to_be_bytes());
    out.extend_from_slice(&b[..b.len().min(u16::MAX as usize)]);
}

fn join_raw(raw: &RawServers) -> Vec<u8> {
    let mut out = vec![10];
    put_str(&mut out, "");
    out.extend_from_slice(&raw.other);
    out.push(9);
    put_str(&mut out, "servers");
    out.push(10);
    out.extend_from_slice(&(raw.entries.len() as i32).to_be_bytes());
    for e in &raw.entries {
        out.extend_from_slice(e);
    }
    out.push(0);
    out
}

/// A new entry the way the game's Add Server screen saves one.
fn new_entry(name: &str, address: &str) -> Vec<u8> {
    let mut out = vec![8];
    put_str(&mut out, "name");
    put_str(&mut out, name);
    out.push(8);
    put_str(&mut out, "ip");
    put_str(&mut out, address);
    out.push(0);
    out
}

/// `raw` with its `name` and `ip` replaced and every other field (the cached
/// icon, the resource-pack choice, …) copied through, the way the game's Edit
/// Server screen saves.
fn retag_entry(raw: &[u8], name: &str, address: &str) -> Option<Vec<u8>> {
    let mut out = new_entry(name, address);
    out.pop();
    let mut r = Nbt { b: raw, i: 0 };
    loop {
        let start = r.i;
        let k = r.u8()?;
        if k == 0 {
            out.push(0);
            return Some(out);
        }
        let key = r.string()?;
        r.tag(k, 1)?;
        if key != "name" && key != "ip" {
            out.extend_from_slice(&raw[start..r.i]);
        }
    }
}

/// Whether a raw entry is the visible server `name` at `address`.
fn is_entry(raw: &[u8], name: &str, address: &str) -> bool {
    let Some(tag) = (Nbt { b: raw, i: 0 }).tag(10, 1) else {
        return false;
    };
    !matches!(tag.get("hidden"), Some(Tag::Byte(1)))
        && tag.get("ip").and_then(Tag::str).map(str::trim) == Some(address)
        && tag.get("name").and_then(Tag::str).unwrap_or("Minecraft Server") == name
}

/// Read, change and save the list the way the game does (the old file is
/// kept as `servers.dat_old`), then return the new visible list.
async fn edit_servers(
    state: &AppState,
    profile_id: &str,
    change: impl FnOnce(&mut RawServers) -> Result<(), String>,
) -> Result<Vec<ServerDto>, String> {
    let root = profile_root(state, profile_id)?;
    crate::worlds::ensure_closed(state, profile_id).await?;
    let path = root.join("servers.dat");
    let mut raw = match std::fs::read(&path) {
        Ok(bytes) => split_raw(&bytes).ok_or("The server list is damaged; edit it in the game.")?,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => RawServers::default(),
        Err(e) => return Err(e.to_string()),
    };
    change(&mut raw)?;
    let bytes = join_raw(&raw);
    let tmp = root.join("servers.dat.tmp");
    std::fs::write(&tmp, &bytes).map_err(|e| format!("Couldn't save the server list: {e}"))?;
    if path.exists() {
        let _ = std::fs::copy(&path, root.join("servers.dat_old"));
    }
    std::fs::rename(&tmp, &path).map_err(|e| format!("Couldn't save the server list: {e}"))?;
    Ok(parse_servers(&bytes))
}

/// Add a server to the end of the instance's multiplayer list.
#[tauri::command]
pub async fn add_server(
    state: State<'_, AppState>,
    profile_id: String,
    name: String,
    address: String,
) -> Result<Vec<ServerDto>, String> {
    let address = address.trim().to_string();
    if !valid_address(&address) {
        return Err("That server address doesn't look right.".into());
    }
    let name = match name.trim() {
        "" => "Minecraft Server".to_string(),
        n => n.chars().take(64).collect(),
    };
    edit_servers(&state, &profile_id, |raw| {
        raw.entries.push(new_entry(&name, &address));
        Ok(())
    })
    .await
}

/// Rename a saved server or change its address, keeping its place in the list.
#[tauri::command]
pub async fn edit_server(
    state: State<'_, AppState>,
    profile_id: String,
    name: String,
    address: String,
    new_name: String,
    new_address: String,
) -> Result<Vec<ServerDto>, String> {
    let new_address = new_address.trim().to_string();
    if !valid_address(&new_address) {
        return Err("That server address doesn't look right.".into());
    }
    let new_name = match new_name.trim() {
        "" => "Minecraft Server".to_string(),
        n => n.chars().take(64).collect(),
    };
    edit_servers(&state, &profile_id, |raw| {
        let entry = raw
            .entries
            .iter_mut()
            .find(|e| is_entry(e, &name, &address))
            .ok_or("That server isn't on the list any more.")?;
        *entry = retag_entry(entry, &new_name, &new_address).ok_or("The server list is damaged; edit it in the game.")?;
        Ok(())
    })
    .await
}

/// Take a server off the instance's multiplayer list.
#[tauri::command]
pub async fn remove_server(
    state: State<'_, AppState>,
    profile_id: String,
    name: String,
    address: String,
) -> Result<Vec<ServerDto>, String> {
    edit_servers(&state, &profile_id, |raw| {
        let i = raw
            .entries
            .iter()
            .position(|e| is_entry(e, &name, &address))
            .ok_or("That server isn't on the list any more.")?;
        raw.entries.remove(i);
        Ok(())
    })
    .await
}

// ---- status ping ----

/// The same shape check the friends-list JOIN uses.
pub fn valid_address(addr: &str) -> bool {
    !addr.is_empty() && addr.len() <= 64 && addr.chars().all(|c| c.is_ascii_alphanumeric() || ".-_:[]".contains(c))
}

/// `host[:port]`, with `[v6]:port` for IPv6 literals.
fn split_address(addr: &str) -> (String, Option<u16>) {
    if let Some(rest) = addr.strip_prefix('[') {
        if let Some((host, tail)) = rest.split_once(']') {
            return (host.to_string(), tail.strip_prefix(':').and_then(|p| p.parse().ok()));
        }
    }
    match addr.rsplit_once(':') {
        Some((host, port)) if !host.contains(':') => match port.parse() {
            Ok(p) => (host.to_string(), Some(p)),
            Err(_) => (addr.to_string(), None),
        },
        _ => (addr.to_string(), None),
    }
}

/// Where the game itself would connect: an explicit port wins, otherwise the
/// `_minecraft._tcp` SRV record, otherwise the host on 25565.
async fn resolve(addr: &str) -> (String, u16) {
    let (host, port) = split_address(addr);
    if let Some(port) = port {
        return (host, port);
    }
    if host.parse::<std::net::IpAddr>().is_err() {
        if let Ok(resolver) = hickory_resolver::TokioResolver::builder_tokio().and_then(|b| b.build()) {
            if let Ok(Ok(found)) =
                tokio::time::timeout(TIMEOUT, resolver.srv_lookup(format!("_minecraft._tcp.{host}."))).await
            {
                let best = found
                    .answers()
                    .iter()
                    .filter_map(|r| match &r.data {
                        hickory_resolver::proto::rr::RData::SRV(srv) => Some(srv),
                        _ => None,
                    })
                    .min_by_key(|srv| (srv.priority, std::cmp::Reverse(srv.weight)));
                if let Some(srv) = best {
                    let target = srv.target.to_utf8();
                    return (target.trim_end_matches('.').to_string(), srv.port);
                }
            }
        }
    }
    (host, DEFAULT_PORT)
}

fn put_varint(out: &mut Vec<u8>, mut v: i32) {
    loop {
        let byte = (v & 0x7f) as u8;
        v = ((v as u32) >> 7) as i32;
        if v == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

fn packet(id: i32, body: &[u8]) -> Vec<u8> {
    let mut inner = Vec::new();
    put_varint(&mut inner, id);
    inner.extend_from_slice(body);
    let mut out = Vec::new();
    put_varint(&mut out, inner.len() as i32);
    out.extend(inner);
    out
}

async fn read_varint(s: &mut TcpStream) -> Result<i32, String> {
    let mut v: u32 = 0;
    for i in 0..5 {
        let b = s.read_u8().await.map_err(|e| e.to_string())?;
        v |= ((b & 0x7f) as u32) << (7 * i);
        if b & 0x80 == 0 {
            return Ok(v as i32);
        }
    }
    Err("bad varint".into())
}

fn varint_at(b: &[u8], i: &mut usize) -> Option<i32> {
    let mut v: u32 = 0;
    for n in 0..5 {
        let byte = *b.get(*i)?;
        *i += 1;
        v |= ((byte & 0x7f) as u32) << (7 * n);
        if byte & 0x80 == 0 {
            return Some(v as i32);
        }
    }
    None
}

async fn read_packet(s: &mut TcpStream) -> Result<(i32, Vec<u8>), String> {
    let len = usize::try_from(read_varint(s).await?).map_err(|_| "bad length")?;
    if len == 0 || len > MAX_PACKET {
        return Err("bad length".into());
    }
    let mut buf = vec![0; len];
    s.read_exact(&mut buf).await.map_err(|e| e.to_string())?;
    let mut i = 0;
    let id = varint_at(&buf, &mut i).ok_or("bad packet")?;
    Ok((id, buf.split_off(i)))
}

async fn status(host: &str, port: u16) -> Result<(serde_json::Value, u32), String> {
    let mut s = TcpStream::connect((host, port)).await.map_err(|e| e.to_string())?;
    s.set_nodelay(true).ok();
    let mut hs = Vec::new();
    put_varint(&mut hs, -1); // "whatever you are" for a status ping
    put_varint(&mut hs, host.len() as i32);
    hs.extend_from_slice(host.as_bytes());
    hs.extend_from_slice(&port.to_be_bytes());
    put_varint(&mut hs, 1); // next state: status
    let mut out = packet(0x00, &hs);
    out.extend(packet(0x00, &[]));
    s.write_all(&out).await.map_err(|e| e.to_string())?;

    let sent = Instant::now();
    let (id, body) = read_packet(&mut s).await?;
    let mut ms = sent.elapsed().as_millis() as u32;
    if id != 0x00 {
        return Err("unexpected reply".into());
    }
    let mut i = 0;
    let n = usize::try_from(varint_at(&body, &mut i).ok_or("bad reply")?).map_err(|_| "bad reply")?;
    let json = body.get(i..i + n).ok_or("bad reply")?;
    let value: serde_json::Value = serde_json::from_slice(json).map_err(|e| e.to_string())?;

    // the ping round trip is the honest number; the status reply also had to
    // build JSON, so it only stands in when a server won't answer pings
    let stamp = 0x6475_736bi64;
    let pinged = async {
        s.write_all(&packet(0x01, &stamp.to_be_bytes())).await.ok()?;
        let at = Instant::now();
        let (id, _) = read_packet(&mut s).await.ok()?;
        (id == 0x01).then(|| at.elapsed().as_millis() as u32)
    };
    if let Ok(Some(p)) = tokio::time::timeout(Duration::from_secs(2), pinged).await {
        ms = p;
    }
    Ok((value, ms))
}

const NAMED: &[(&str, char, &str)] = &[
    ("black", '0', "#000000"),
    ("dark_blue", '1', "#0000aa"),
    ("dark_green", '2', "#00aa00"),
    ("dark_aqua", '3', "#00aaaa"),
    ("dark_red", '4', "#aa0000"),
    ("dark_purple", '5', "#aa00aa"),
    ("gold", '6', "#ffaa00"),
    ("gray", '7', "#aaaaaa"),
    ("dark_gray", '8', "#555555"),
    ("blue", '9', "#5555ff"),
    ("green", 'a', "#55ff55"),
    ("aqua", 'b', "#55ffff"),
    ("red", 'c', "#ff5555"),
    ("light_purple", 'd', "#ff55ff"),
    ("yellow", 'e', "#ffff55"),
    ("white", 'f', "#ffffff"),
];

fn named_color(name: &str) -> Option<String> {
    if name.starts_with('#') && name.len() == 7 {
        return Some(name.to_ascii_lowercase());
    }
    NAMED.iter().find(|(n, _, _)| *n == name).map(|(_, _, hex)| hex.to_string())
}

/// Text with legacy `§` codes → coloured parts. Formatting codes (bold etc.)
/// are dropped; `§r` goes back to the component's own colour.
fn push_legacy(out: &mut Vec<MotdPart>, text: &str, base: &Option<String>) {
    let mut color = base.clone();
    let mut chars = text.chars().peekable();
    let mut cur = String::new();
    while let Some(c) = chars.next() {
        if c == '§' {
            let Some(code) = chars.next() else { break };
            let code = code.to_ascii_lowercase();
            let next = if code == 'r' {
                Some(base.clone())
            } else {
                NAMED.iter().find(|(_, k, _)| *k == code).map(|(_, _, hex)| Some(hex.to_string()))
            };
            if let Some(next) = next {
                if !cur.is_empty() {
                    out.push(MotdPart { text: std::mem::take(&mut cur), color: color.clone() });
                }
                color = next;
            }
            continue;
        }
        cur.push(c);
    }
    if !cur.is_empty() {
        out.push(MotdPart { text: cur, color });
    }
}

fn flatten(v: &serde_json::Value, inherited: &Option<String>, out: &mut Vec<MotdPart>, depth: u32) {
    if depth > 16 {
        return;
    }
    match v {
        serde_json::Value::String(s) => push_legacy(out, s, inherited),
        serde_json::Value::Array(items) => {
            // a bare array is a component whose first entry styles the rest
            let mut base = inherited.clone();
            for (n, item) in items.iter().enumerate() {
                flatten(item, &base, out, depth + 1);
                if n == 0 {
                    if let Some(c) = item.get("color").and_then(|c| c.as_str()).and_then(named_color) {
                        base = Some(c);
                    }
                }
            }
        }
        serde_json::Value::Object(o) => {
            let color = o.get("color").and_then(|c| c.as_str()).and_then(named_color).or_else(|| inherited.clone());
            if let Some(t) = o.get("text").and_then(|t| t.as_str()) {
                push_legacy(out, t, &color);
            } else if let Some(t) = o.get("translate").and_then(|t| t.as_str()) {
                push_legacy(out, o.get("fallback").and_then(|f| f.as_str()).unwrap_or(t), &color);
            }
            // siblings in `extra` each inherit from this component only
            for item in o.get("extra").and_then(|e| e.as_array()).into_iter().flatten() {
                flatten(item, &color, out, depth + 1);
            }
        }
        _ => {}
    }
}

/// The MOTD as coloured parts, one `\n` between its lines. Servers pad lines
/// with spaces to centre them in the game's list; the launcher lays them out
/// itself, so that padding goes.
fn motd(description: Option<&serde_json::Value>) -> Vec<MotdPart> {
    let mut flat = Vec::new();
    if let Some(d) = description {
        flatten(d, &None, &mut flat, 0);
    }
    let mut lines: Vec<Vec<MotdPart>> = vec![Vec::new()];
    for part in flat {
        let mut pieces = part.text.split('\n');
        if let Some(first) = pieces.next() {
            lines.last_mut().unwrap().push(MotdPart { text: first.to_string(), color: part.color.clone() });
        }
        for piece in pieces {
            lines.push(vec![MotdPart { text: piece.to_string(), color: part.color.clone() }]);
        }
    }
    let mut out = Vec::new();
    for mut line in lines {
        line.retain(|p| !p.text.is_empty());
        while line.first().is_some_and(|p| p.text.trim().is_empty()) {
            line.remove(0);
        }
        while line.last().is_some_and(|p| p.text.trim().is_empty()) {
            line.pop();
        }
        if let Some(p) = line.first_mut() {
            p.text = p.text.trim_start().to_string();
        }
        if let Some(p) = line.last_mut() {
            p.text = p.text.trim_end().to_string();
        }
        if line.is_empty() {
            continue;
        }
        if !out.is_empty() {
            out.push(MotdPart { text: "\n".into(), color: None });
        }
        out.extend(line);
    }
    out
}

fn to_status(v: serde_json::Value, ping_ms: u32) -> ServerStatus {
    let players = v.get("players");
    let n = |k: &str| players.and_then(|p| p.get(k)).and_then(|x| x.as_i64()).unwrap_or(0);
    let version = v
        .get("version")
        .and_then(|x| x.get("name"))
        .and_then(|x| x.as_str())
        .map(strip_codes)
        .unwrap_or_default();
    ServerStatus {
        motd: motd(v.get("description")),
        online: n("online"),
        max: n("max"),
        version,
        ping_ms,
        icon: v
            .get("favicon")
            .and_then(|f| f.as_str())
            .filter(|f| f.starts_with("data:image/png;base64,"))
            .map(str::to_string),
    }
}

/// A version name like "§cPaper 1.21.11" without its colour codes.
fn strip_codes(s: &str) -> String {
    let mut out = String::new();
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '§' {
            chars.next();
        } else {
            out.push(c);
        }
    }
    out.trim().to_string()
}

#[tauri::command]
pub async fn ping_server(address: String) -> Result<ServerStatus, String> {
    let address = address.trim().to_string();
    if !valid_address(&address) {
        return Err("That server address doesn't look right.".into());
    }
    let (host, port) = resolve(&address).await;
    match tokio::time::timeout(TIMEOUT, status(&host, port)).await {
        Ok(Ok((v, ms))) => Ok(to_status(v, ms)),
        Ok(Err(_)) | Err(_) => Err("Can't reach the server".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn nbt_str(out: &mut Vec<u8>, s: &str) {
        out.extend_from_slice(&(s.len() as u16).to_be_bytes());
        out.extend_from_slice(s.as_bytes());
    }

    fn entry(out: &mut Vec<u8>, name: &str, ip: &str, hidden: bool) {
        out.push(8);
        nbt_str(out, "name");
        nbt_str(out, name);
        out.push(8);
        nbt_str(out, "ip");
        nbt_str(out, ip);
        out.push(1);
        nbt_str(out, "hidden");
        out.push(hidden as u8);
        out.push(0);
    }

    fn read_nbt_compound(raw: &[u8]) -> Tag {
        (Nbt { b: raw, i: 0 }).tag(10, 1).unwrap()
    }

    #[test]
    fn reads_servers_dat() {
        let mut b = vec![10];
        nbt_str(&mut b, "");
        b.push(9);
        nbt_str(&mut b, "servers");
        b.push(10);
        b.extend_from_slice(&3i32.to_be_bytes());
        entry(&mut b, "smp", "mc.example.net", false);
        entry(&mut b, "direct", "10.0.0.2", true);
        entry(&mut b, "pvp", "pvp.example.net:25566", false);
        b.push(0);
        let list = parse_servers(&b);
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].name, "smp");
        assert_eq!(list[1].address, "pvp.example.net:25566");
        assert!(list[0].icon.is_none());
        // truncated or empty files read as no servers, not a crash
        assert!(parse_servers(&b[..b.len() / 2]).is_empty());
        assert!(parse_servers(&[]).is_empty());
    }

    #[test]
    fn edits_keep_what_they_dont_touch() {
        let mut b = vec![10];
        nbt_str(&mut b, "");
        // a root field the launcher knows nothing about
        b.push(3);
        nbt_str(&mut b, "extra");
        b.extend_from_slice(&7i32.to_be_bytes());
        b.push(9);
        nbt_str(&mut b, "servers");
        b.push(10);
        b.extend_from_slice(&2i32.to_be_bytes());
        entry(&mut b, "smp", "mc.example.net", false);
        entry(&mut b, "direct", "10.0.0.2", true);
        b.push(0);

        let mut raw = split_raw(&b).unwrap();
        assert_eq!(raw.entries.len(), 2);
        // nothing changed → the same bytes
        assert_eq!(join_raw(&raw), b);

        raw.entries.push(new_entry("Café \0 🎮", "pvp.example.net:25566"));
        let list = parse_servers(&join_raw(&raw));
        assert_eq!(list.len(), 2);
        assert_eq!(list[1].address, "pvp.example.net:25566");
        // NUL and astral chars go out as modified UTF-8
        let tail = &raw.entries[2];
        assert!(tail.windows(2).any(|w| w == [0xC0, 0x80]));
        assert!(!tail.windows(4).any(|w| w == "🎮".as_bytes()));

        // the hidden direct-connect entry never matches a visible row
        assert!(!is_entry(&raw.entries[1], "direct", "10.0.0.2"));
        let i = raw.entries.iter().position(|e| is_entry(e, "smp", "mc.example.net")).unwrap();
        raw.entries.remove(i);
        let out = join_raw(&raw);
        assert!(out.windows(5).any(|w| w == b"extra"));
        assert_eq!(parse_servers(&out)[0].name, "Café \0 🎮");

        // an edit renames in place and keeps the entry's other fields
        raw.entries[0] = retag_entry(&raw.entries[0], "lan", "192.168.1.9").unwrap();
        assert!(matches!(read_nbt_compound(&raw.entries[0]).get("hidden"), Some(Tag::Byte(1))));
        raw.entries[1] = retag_entry(&raw.entries[1], "Renamed", "renamed.example.net").unwrap();
        let list = parse_servers(&join_raw(&raw));
        assert_eq!(list.len(), 1);
        assert_eq!((list[0].name.as_str(), list[0].address.as_str()), ("Renamed", "renamed.example.net"));
        assert!(is_entry(&raw.entries[1], "Renamed", "renamed.example.net"));

        // a missing file starts an empty list
        let fresh = join_raw(&RawServers::default());
        assert!(parse_servers(&fresh).is_empty());
        assert_eq!(split_raw(&fresh).unwrap().entries.len(), 0);
    }

    #[test]
    fn splits_ports() {
        assert_eq!(split_address("mc.example.net"), ("mc.example.net".into(), None));
        assert_eq!(split_address("mc.example.net:25570"), ("mc.example.net".into(), Some(25570)));
        assert_eq!(split_address("[::1]:25566"), ("::1".into(), Some(25566)));
    }

    #[test]
    fn motd_components_and_legacy_codes() {
        let v = serde_json::json!({
            "text": "",
            "extra": [{"text": "zWork ", "color": "gold"}, {"text": "SMP", "color": "#40C0FF"}, "\n§aopen §rnow"]
        });
        let parts = motd(Some(&v));
        assert_eq!(parts[0], MotdPart { text: "zWork ".into(), color: Some("#ffaa00".into()) });
        assert_eq!(parts[1], MotdPart { text: "SMP".into(), color: Some("#40c0ff".into()) });
        assert_eq!(parts[2], MotdPart { text: "\n".into(), color: None });
        assert_eq!(parts[3], MotdPart { text: "open ".into(), color: Some("#55ff55".into()) });
        assert_eq!(parts[4], MotdPart { text: "now".into(), color: None });
        assert!(motd(Some(&serde_json::json!("   "))).is_empty());
        // centring padding goes, the line break stays
        let parts = motd(Some(&serde_json::json!({"text": "      §6Hello  \n     §7world   "})));
        let text: String = parts.iter().map(|p| p.text.as_str()).collect();
        assert_eq!(text, "Hello\nworld");
    }

    #[test]
    fn varints_round_trip() {
        for v in [0, 1, 127, 128, 25565, -1, i32::MAX] {
            let mut b = Vec::new();
            put_varint(&mut b, v);
            let mut i = 0;
            assert_eq!(varint_at(&b, &mut i), Some(v));
            assert_eq!(i, b.len());
        }
    }
}
