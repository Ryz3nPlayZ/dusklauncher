/**
 * Friends, requests and 1:1 chat — opened from the status pill. A light
 * dropdown (Home.tsx's popout pattern), not a route: closes on an outside
 * click or Escape, same as the instance picker.
 *
 * While signed in, a heartbeat runs whether the pane is open or not: it is
 * what keeps this account "online" for its friends (and says which game
 * version it's in), and its answer — pending requests, unread messages,
 * friends online — is what the pill shows. The full lists are only fetched
 * while the pane is open — or, with OS notifications on, once a beat so a
 * friend coming online or a new message can raise one while the launcher
 * sits in the background.
 *
 * Friends on a server get a JOIN button (row, profile, invites); chat
 * carries invites, screenshots and gifts as well as text, and links in
 * text open through a warning unless that's switched off.
 */
import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { NavCell, NavLabel, PxBox, PxButton, TT } from './px/Px';
import PlayerHead from './PlayerHead';
import { Row } from './px/Form';
import {
  api,
  type Account,
  type ChatMessage,
  type CosmeticsCatalog,
  type Friend,
  type FriendProfile,
  type FriendRequests,
  type GameActivity,
  type Screenshot,
  type SocialSummary,
  type StoreItem,
} from '../lib/api';
import { convertFileSrc } from '@tauri-apps/api/core';
import { stamp as timeStamp } from '../lib/time';

/** well inside the service's two-minute online window */
const HEARTBEAT_MS = 30_000;
/** friends/requests refresh while the pane is open */
const LIST_POLL_MS = 10_000;
const CHAT_POLL_MS = 3_500;
/** mirrors the service's MESSAGE_MAX_LEN */
const MESSAGE_MAX = 1000;
/** a chat gap longer than this gets a timestamp line */
const STAMP_GAP_S = 10 * 60;
/** one sender's lines closer together than this read as one run */
const RUN_GAP_S = 5 * 60;
/** gifts sent back to back fold into one card */
const GIFT_RUN_GAP_S = 10 * 60;
/** gift names a folded card lists before "+N more" */
const GIFT_PREVIEW = 6;

/** a chat line: one message, or a run of gifts folded into one card */
type ChatRow = {
  key: number;
  messages: ChatMessage[];
  mine: boolean;
  /** continues the previous line's run — drawn tight under it */
  cont: boolean;
  /** a divider above it, when the conversation picks up after a gap */
  stamp: number | null;
};

function chatRows(messages: ChatMessage[], theirUuid: string): ChatRow[] {
  const rows: ChatRow[] = [];
  let prev: ChatMessage | undefined;
  for (const m of messages) {
    const mine = m.fromUuid !== theirUuid;
    const last = rows[rows.length - 1];
    const gap = prev ? m.sentAt - prev.sentAt : Infinity;
    const sameSender = !!prev && prev.fromUuid === m.fromUuid;
    if (m.kind === 'gift' && last && sameSender && prev?.kind === 'gift' && gap <= GIFT_RUN_GAP_S) {
      last.messages.push(m);
    } else {
      const stamp = gap > STAMP_GAP_S ? m.sentAt : null;
      rows.push({ key: m.id, messages: [m], mine, cont: sameSender && stamp == null && gap <= RUN_GAP_S, stamp });
    }
    prev = m;
  }
  return rows;
}

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** `ts` is unix seconds, as the service sends every time */
function ago(ts: number): string {
  const s = Math.max(0, Math.floor(Date.now() / 1000 - ts));
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

/** the settings the pane follows */
export interface SocialPrefs {
  clock24h: boolean;
  warnOnLinks: boolean;
  notifyFriendsOnline: boolean;
  notifyMessages: boolean;
}

function statusLine(f: Pick<Friend, 'online' | 'playing' | 'server' | 'lastSeen'>): { text: string; online: boolean } {
  if (f.online && f.playing && f.server) return { text: `PLAYING ${f.playing} · ${f.server}`, online: true };
  if (f.online && f.playing) return { text: `PLAYING ${f.playing}`, online: true };
  if (f.online) return { text: 'ONLINE', online: true };
  return { text: `LAST SEEN ${ago(f.lastSeen).toUpperCase()}`, online: false };
}

/* http(s) links in chat text; trailing punctuation stays text */
const LINK_RE = /https?:\/\/[^\s<>"']+/g;
function splitLinks(text: string): { text: string; url?: string }[] {
  const out: { text: string; url?: string }[] = [];
  let last = 0;
  for (const m of text.matchAll(LINK_RE)) {
    const url = m[0].replace(/[.,!?;:)\]]+$/, '');
    const at = m.index ?? 0;
    if (at > last) out.push({ text: text.slice(last, at) });
    out.push({ text: url, url });
    last = at + url.length;
  }
  if (last < text.length) out.push({ text: text.slice(last) });
  return out;
}

async function openLink(url: string) {
  if (!/^https?:\/\//i.test(url)) return;
  try {
    const { openUrl } = await import('@tauri-apps/plugin-opener');
    await openUrl(url);
  } catch {
    window.open(url, '_blank', 'noopener');
  }
}

function LinkText({ text, onLink }: { text: string; onLink: (url: string) => void }) {
  return (
    <span className="social__text">
      {splitLinks(text).map((part, i) =>
        part.url ? (
          <a
            key={i}
            className="social__link"
            href={part.url}
            onClick={(e) => {
              e.preventDefault();
              onLink(part.url!);
            }}
          >
            {part.text}
          </a>
        ) : (
          <span key={i}>{part.text}</span>
        ),
      )}
    </span>
  );
}

/* skins are shared across rows, the chat header and the profile, and the
   rows remount every time the pane opens — one ask per friend every few
   minutes instead of one per mount. Short, because the backend already keeps
   each skin on disk for half an hour (and only then asks Mojang): stacking a
   second half hour here left a changed skin up to an hour old */
const SKIN_TTL_MS = 5 * 60_000;
const skinCache = new Map<string, { at: number; skin: Promise<string | null> }>();
function loadSkin(uuid: string): Promise<string | null> {
  const hit = skinCache.get(uuid);
  if (hit && Date.now() - hit.at < SKIN_TTL_MS) return hit.skin;
  const skin = api.getPublicSkin(uuid).catch(() => {
    skinCache.delete(uuid);
    return null;
  });
  skinCache.set(uuid, { at: Date.now(), skin });
  return skin;
}

function useSkin(uuid: string | null) {
  const [skin, setSkin] = useState<string | null>(null);
  useEffect(() => {
    setSkin(null);
    if (!uuid) return;
    let live = true;
    void loadSkin(uuid).then((s) => live && setSkin(s));
    return () => {
      live = false;
    };
  }, [uuid]);
  return skin;
}

/** a dialog on top of the pane takes Escape before the pane's own handler
    (on document, bubbling), which would close everything at once */
function useEscape(active: boolean, close: () => void) {
  const closeRef = useRef(close);
  closeRef.current = close;
  useEffect(() => {
    if (!active) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      e.stopPropagation();
      closeRef.current();
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [active]);
}

function FriendHead({ uuid, online, size }: { uuid: string; online?: boolean; size: number }) {
  const skin = useSkin(uuid);
  return (
    <span className="social__head">
      <PlayerHead skin={skin} size={size} />
      {online && <span className="social__presence" />}
    </span>
  );
}

type View =
  | { kind: 'list' }
  | { kind: 'requests' }
  | { kind: 'chat'; uuid: string }
  | { kind: 'profile'; uuid: string; from: 'list' | 'chat' };

export default function SocialPane({
  account,
  gameRunning,
  playing,
  activity,
  prefs,
  onJoin,
  onGift,
  isTauri,
}: {
  account: Account | null;
  gameRunning: boolean;
  /** the running game's version, reported to friends; null when not playing */
  playing: string | null;
  /** what the running game is doing — its server is what INVITE sends */
  activity: GameActivity | null;
  prefs: SocialPrefs;
  /** start the game straight onto a friend's server */
  onJoin: (server: string, version: string | null) => void;
  /** open the store, buying for this friend */
  onGift: (uuid: string, name: string) => void;
  isTauri: boolean;
}) {
  // the browser preview has mocks for every call, so it walks as signed in
  const signedIn = !isTauri || !!account?.authenticated || !!account?.offline;
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<View>({ kind: 'list' });
  const [summary, setSummary] = useState<SocialSummary | null>(null);
  const [friends, setFriends] = useState<Friend[] | null>(null);
  const [requests, setRequests] = useState<FriendRequests>({ incoming: [], outgoing: [] });
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  /* a link waiting on the "open this?" warning */
  const [link, setLink] = useState<string | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  // Escape reads these from a listener registered once per open
  const addingRef = useRef(adding);
  addingRef.current = adding;
  const linkRef = useRef(link);
  linkRef.current = link;
  const prefsRef = useRef(prefs);
  prefsRef.current = prefs;
  const openRef = useRef(open);
  openRef.current = open;

  const onLink = useCallback((url: string) => {
    if (prefsRef.current.warnOnLinks) setLink(url);
    else void openLink(url);
  }, []);

  /* OS notifications: diff each friends list against the last one — who
     just came online, whose unread count went up. Only while the launcher
     is in the background (in front, the pill's badge says it), and never
     for the first list, which is just the baseline. */
  const lastSeen = useRef<Map<string, { online: boolean; unread: number }> | null>(null);
  const noticeFriends = useCallback(
    (list: Friend[]) => {
      const prev = lastSeen.current;
      lastSeen.current = new Map(list.map((f) => [f.uuid, { online: f.online, unread: f.unread }]));
      if (!prev || !isTauri || document.hasFocus()) return;
      const p = prefsRef.current;
      for (const f of list) {
        const was = prev.get(f.uuid);
        if (!was) continue;
        if (p.notifyFriendsOnline && f.online && !was.online) {
          const what = f.playing ? `Playing Minecraft ${f.playing}${f.server ? ` on ${f.server}` : ''}` : 'Now online';
          void api.notify(`${f.username} is online`, what).catch(() => {});
        }
        if (p.notifyMessages && f.unread > was.unread) {
          const n = f.unread - was.unread;
          void api.notify(f.username, n === 1 ? 'Sent you a message' : `Sent you ${n} messages`).catch(() => {});
        }
      }
    },
    [isTauri],
  );

  const server = activity?.server ?? null;
  const heartbeat = useCallback(() => {
    void api
      .socialHeartbeat(playing)
      .then((s) => {
        setSummary(s);
        // the open pane polls the list itself; closed, notifications need it
        const p = prefsRef.current;
        if (!openRef.current && (p.notifyFriendsOnline || p.notifyMessages)) {
          void api.listFriends().then(noticeFriends).catch(() => {});
        }
      })
      .catch(() => {
        // offline or the service is down — the next beat tries again, and
        // the pill just keeps its last counts
      });
    // `server` isn't sent from here (the backend reads it) but a change of
    // server should reach friends at once, like a change of version
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playing, server, noticeFriends]);

  // runs regardless of the pane: this is what keeps us online for friends.
  // `playing` is a dependency, so starting or stopping the game beats at once
  useEffect(() => {
    if (!signedIn) return;
    heartbeat();
    const id = setInterval(heartbeat, HEARTBEAT_MS);
    return () => clearInterval(id);
  }, [signedIn, heartbeat]);

  const refresh = useCallback(async () => {
    try {
      const [f, r] = await Promise.all([api.listFriends(), api.listFriendRequests()]);
      setFriends(f);
      setRequests(r);
      setLoadError(null);
      noticeFriends(f);
      // the lists are fresher than the last beat — keep the pill in step
      setSummary({
        requests: r.incoming.length,
        unread: f.reduce((n, x) => n + x.unread, 0),
        online: f.filter((x) => x.online).length,
      });
    } catch (e) {
      setLoadError(errText(e));
    }
  }, [noticeFriends]);

  useEffect(() => {
    if (!open || !signedIn) return;
    void refresh();
    const id = setInterval(() => void refresh(), LIST_POLL_MS);
    return () => clearInterval(id);
  }, [open, signedIn, refresh]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      const target = e.target as Node;
      // the add-friend dialog is portaled to <body> to escape .social's
      // small positioning box, so it isn't a DOM descendant of ref — a click
      // inside it must not read as an outside click and close the pane
      if (ref.current?.contains(target)) return;
      if ((target as Element).closest?.('.modal-scrim')) return;
      setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      // the dialog on top takes the first Escape, the pane the next
      if (linkRef.current) setLink(null);
      else if (addingRef.current) setAdding(false);
      else setOpen(false);
    };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  // closing drops whatever friend the pane was showing
  useEffect(() => {
    if (!open) {
      setView({ kind: 'list' });
      setNotice(null);
    }
  }, [open]);

  // a notice (request sent, friend removed) is a moment, not a state
  useEffect(() => {
    if (!notice) return;
    const id = setTimeout(() => setNotice(null), 4000);
    return () => clearTimeout(id);
  }, [notice]);

  const toList = (kind: 'list' | 'requests' = 'list') => {
    setView({ kind });
    // leaving a chat may have read messages — clear their badges now
    void refresh();
  };

  const pending = summary?.requests ?? 0;
  const unread = summary?.unread ?? 0;
  const badge = pending + unread;
  const onlineCount = summary?.online ?? 0;
  const byUuid = (uuid: string) => friends?.find((f) => f.uuid === uuid);

  let label: string;
  if (!signedIn) label = 'OFFLINE — NOT SIGNED IN';
  else {
    label = gameRunning ? 'IN GAME' : 'FRIENDS';
    if (onlineCount > 0) label += ` · ${onlineCount} ONLINE`;
  }
  const badgeTitle = [
    pending > 0 && `${pending} friend request${pending === 1 ? '' : 's'}`,
    unread > 0 && `${unread} unread message${unread === 1 ? '' : 's'}`,
  ]
    .filter(Boolean)
    .join(', ');

  return (
    <div className="social" ref={ref}>
      <PxButton
        family="panel"
        height="sm"
        className="status-pill"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-haspopup="dialog"
        title={badgeTitle ? `Friends and chat — ${badgeTitle}` : 'Friends and chat'}
      >
        <span className={`status-pill__dot ${signedIn ? '' : 'status-pill__dot--off'}`} />
        <TT size={13} tone="dim">
          {label}
        </TT>
        {badge > 0 && (
          <span className="social__badge" aria-label={badgeTitle}>
            <TT size={11}>{badge > 99 ? '99+' : String(badge)}</TT>
          </span>
        )}
      </PxButton>

      {open && (
        <div className="win win--solid social__win" role="dialog" aria-label="Friends and chat">
          {!signedIn ? (
            <>
              <div className="win__bar">
                <NavLabel label="FRIENDS" />
                <div className="win__fill" />
              </div>
              <div className="win__body">
                <PxBox family="panel" className="empty">
                  <TT size={20} tone="dim">
                    NOT SIGNED IN
                  </TT>
                  <span className="meta">Friends and chat use your Microsoft account — sign in to see them.</span>
                </PxBox>
              </div>
            </>
          ) : view.kind === 'chat' ? (
            <ChatView
              friend={byUuid(view.uuid)}
              uuid={view.uuid}
              clock24h={prefs.clock24h}
              activity={activity}
              gameRunning={gameRunning}
              isTauri={isTauri}
              onJoin={onJoin}
              onLink={onLink}
              onBack={() => toList()}
              onSeen={() => void refresh()}
              onProfile={() => setView({ kind: 'profile', uuid: view.uuid, from: 'chat' })}
            />
          ) : view.kind === 'profile' ? (
            <ProfileView
              friend={byUuid(view.uuid)}
              uuid={view.uuid}
              from={view.from}
              onBack={() =>
                view.from === 'chat' ? setView({ kind: 'chat', uuid: view.uuid }) : toList()
              }
              onMessage={() => setView({ kind: 'chat', uuid: view.uuid })}
              gameRunning={gameRunning}
              onJoin={onJoin}
              onGift={(name) => {
                setOpen(false);
                onGift(view.uuid, name);
              }}
              onRemoved={(list, name) => {
                setFriends(list);
                setView({ kind: 'list' });
                setNotice(`Removed ${name} from your friends.`);
              }}
              onBlocked={(name) => {
                setView({ kind: 'list' });
                setNotice(`Blocked ${name}. Unblock them in Settings → Social.`);
                void refresh();
              }}
            />
          ) : (
            <>
              <div className="win__bar">
                <NavCell
                  label={friends && friends.length > 0 ? `FRIENDS ${friends.length}` : 'FRIENDS'}
                  active={view.kind === 'list'}
                  onClick={() => setView({ kind: 'list' })}
                />
                <NavCell
                  label={requests.incoming.length > 0 ? `REQUESTS ${requests.incoming.length}` : 'REQUESTS'}
                  active={view.kind === 'requests'}
                  onClick={() => setView({ kind: 'requests' })}
                />
                <div className="win__fill" />
                <NavCell label="+ ADD" onClick={() => setAdding(true)} />
              </div>

              <div className="win__body social__body scroll">
                {activity?.hosting && !activity.server && (
                  <PxBox family="accent" height="md" className="social__notice" role="status">
                    <span className="meta">Opening your world to friends — its address shows here in a few seconds.</span>
                  </PxBox>
                )}
                {activity?.hosting && activity.server && (
                  <PxBox family="accent" height="md" className="social__notice" role="status">
                    <span className="meta">
                      Hosting at <b>{activity.server}</b> — open a friend's chat and INVITE, or share the address.
                    </span>
                    <PxButton
                      family="grey"
                      height="sm"
                      onClick={() => void navigator.clipboard?.writeText(activity.server ?? '').catch(() => {})}
                    >
                      <TT size={14}>COPY</TT>
                    </PxButton>
                  </PxBox>
                )}
                {notice && (
                  <PxBox family="green" height="md" className="social__notice" role="status">
                    <span className="meta">{notice}</span>
                  </PxBox>
                )}
                {loadError && (
                  <PxBox family="red" height="md" className="social__notice" role="alert">
                    <span className="meta">{loadError}</span>
                    <PxButton family="grey" height="sm" onClick={() => void refresh()}>
                      <TT size={14}>RETRY</TT>
                    </PxButton>
                  </PxBox>
                )}
                {view.kind === 'requests' ? (
                  <RequestsList requests={requests} onChange={setRequests} onAccepted={() => void refresh()} />
                ) : friends === null ? (
                  !loadError && (
                    <PxBox family="panel" className="empty">
                      <span className="meta">Loading friends…</span>
                    </PxBox>
                  )
                ) : (
                  <FriendsList
                    friends={friends}
                    gameRunning={gameRunning}
                    onOpen={(f) => setView({ kind: 'chat', uuid: f.uuid })}
                    onAdd={() => setAdding(true)}
                    onJoin={onJoin}
                  />
                )}
              </div>
            </>
          )}
        </div>
      )}

      {adding &&
        createPortal(
          <AddFriendDialog
            onClose={() => setAdding(false)}
            onSent={(r, name) => {
              setAdding(false);
              setRequests(r);
              // the service auto-accepts when they had already asked us, so
              // the name lands in friends rather than in the sent list
              const pendingOut = r.outgoing.some((x) => x.username.toLowerCase() === name.toLowerCase());
              setView({ kind: pendingOut ? 'requests' : 'list' });
              setNotice(pendingOut ? `Request sent to ${name}.` : `You and ${name} are now friends.`);
              void refresh();
            }}
          />,
          document.body,
        )}

      {link &&
        createPortal(
          <div className="modal-scrim" onClick={() => setLink(null)}>
            <PxBox family="panel" className="px--window modal" role="dialog" aria-label="Open link" onClick={(e) => e.stopPropagation()}>
              <TT size={22}>OPEN THIS LINK?</TT>
              <span className="meta social__link-url">{link}</span>
              <span className="meta">
                It opens in your browser. Links from people can lead anywhere — never sign in or enter a code a stranger
                sent you.
              </span>
              <div className="modal__row modal__row--tall">
                <PxButton family="grey" height="md" onClick={() => setLink(null)}>
                  <TT size={20}>CANCEL</TT>
                </PxButton>
                <PxButton
                  family="blue"
                  height="md"
                  autoFocus
                  onClick={() => {
                    void openLink(link);
                    setLink(null);
                  }}
                >
                  <TT size={20} tone="blue">
                    OPEN
                  </TT>
                </PxButton>
              </div>
            </PxBox>
          </div>,
          document.body,
        )}
    </div>
  );
}

/** JOIN: start the game on the friend's server (their version when an
    instance has it) — not while a game already runs, which can't be moved */
function JoinButton({
  server,
  version,
  gameRunning,
  onJoin,
}: {
  server: string;
  version: string | null;
  gameRunning: boolean;
  onJoin: (server: string, version: string | null) => void;
}) {
  return (
    <PxButton
      family="green"
      height="md"
      disabled={gameRunning}
      title={gameRunning ? 'Close the running game first' : `Launch and join ${server}`}
      onClick={(e) => {
        e.stopPropagation();
        onJoin(server, version);
      }}
    >
      <TT size={16} tone="green">
        JOIN
      </TT>
    </PxButton>
  );
}

function AddFriendDialog({
  onClose,
  onSent,
}: {
  onClose: () => void;
  onSent: (r: FriendRequests, name: string) => void;
}) {
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    api
      .sendFriendRequest(trimmed)
      .then((r) => onSent(r, trimmed))
      .catch((err) => {
        setError(errText(err));
        setBusy(false);
      });
  };

  return (
    <div className="modal-scrim" onClick={onClose}>
      <PxBox
        family="panel"
        className="px--window modal"
        role="dialog"
        aria-label="Add friend"
        onClick={(e) => e.stopPropagation()}
      >
        <form className="social__form" onSubmit={submit}>
          <TT size={22}>ADD FRIEND</TT>
          <span className="meta">
            Their Minecraft username. They need to have signed in to Dusk Launcher once.
          </span>
          <div className="modal__row">
            <PxBox family="panel" height="md">
              <input
                className="input"
                placeholder="USERNAME"
                value={name}
                maxLength={16}
                autoFocus
                spellCheck={false}
                autoComplete="off"
                onChange={(e) => {
                  setName(e.target.value);
                  setError(null);
                }}
              />
            </PxBox>
          </div>
          {error && (
            <PxBox family="red" height="md" className="social__notice" role="alert">
              <span className="meta">{error}</span>
            </PxBox>
          )}
          <div className="modal__row modal__row--tall">
            <PxButton family="grey" height="md" onClick={onClose}>
              <TT size={20}>CANCEL</TT>
            </PxButton>
            <PxButton family="accent" height="md" type="submit" disabled={busy || !name.trim()}>
              <TT size={20} tone="accent">
                {busy ? 'SENDING…' : 'SEND REQUEST'}
              </TT>
            </PxButton>
          </div>
        </form>
      </PxBox>
    </div>
  );
}

/** the settings row (`.srow`) with a head in front — every person in the
    pane is one of these, whether it opens a chat or carries buttons */
function PersonRow({
  uuid,
  name,
  online,
  meta,
  metaOnline,
  dim,
  onClick,
  title,
  children,
}: {
  uuid: string;
  name: string;
  online?: boolean;
  meta: string;
  metaOnline?: boolean;
  dim?: boolean;
  onClick?: () => void;
  title?: string;
  children?: ReactNode;
}) {
  const body = (
    <>
      <FriendHead uuid={uuid} online={online} size={40} />
      <div className="srow__text">
        <TT size={20} tone={dim ? 'dim' : 'plain'}>
          {name}
        </TT>
        <span className={`meta ${metaOnline ? 'is-online' : ''}`}>{meta}</span>
      </div>
      {children && <div className="srow__control">{children}</div>}
    </>
  );
  // a div, not a <button>: the row can carry buttons of its own (JOIN)
  return onClick ? (
    <div
      className="srow social__row social__row--link"
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(e) => {
        if (e.target !== e.currentTarget) return;
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onClick();
        }
      }}
      title={title}
    >
      {body}
    </div>
  ) : (
    <div className="srow social__row">{body}</div>
  );
}

function FriendsList({
  friends,
  gameRunning,
  onOpen,
  onAdd,
  onJoin,
}: {
  friends: Friend[];
  gameRunning: boolean;
  onOpen: (f: Friend) => void;
  onAdd: () => void;
  onJoin: (server: string, version: string | null) => void;
}) {
  if (friends.length === 0) {
    return (
      <PxBox family="panel" className="empty">
        <TT size={20} tone="dim">
          NO FRIENDS YET
        </TT>
        <span className="meta">Add someone by their Minecraft username to chat and see when they're on.</span>
        <PxButton family="accent" height="md" className="social__empty-cta" onClick={onAdd}>
          <TT size={16} tone="accent">
            ADD A FRIEND
          </TT>
        </PxButton>
      </PxBox>
    );
  }
  return (
    <>
      {friends.map((f) => {
        const status = statusLine(f);
        return (
          <PersonRow
            key={f.uuid}
            uuid={f.uuid}
            name={f.username}
            online={f.online}
            meta={f.offline ? `${status.text} · CRACKED` : status.text}
            metaOnline={status.online}
            dim={!f.online}
            onClick={() => onOpen(f)}
            title={`Chat with ${f.username}`}
          >
            {f.online && f.server && (
              <JoinButton server={f.server} version={f.playing ?? null} gameRunning={gameRunning} onJoin={onJoin} />
            )}
            {f.unread > 0 && (
              <span className="social__badge" title={`${f.unread} unread`}>
                <TT size={13}>{f.unread > 99 ? '99+' : String(f.unread)}</TT>
              </span>
            )}
          </PersonRow>
        );
      })}
    </>
  );
}

function RequestsList({
  requests,
  onChange,
  onAccepted,
}: {
  requests: FriendRequests;
  onChange: (r: FriendRequests) => void;
  onAccepted: () => void;
}) {
  // one action at a time per row — a double click must not fire twice
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const act = (id: number, call: (id: number) => Promise<FriendRequests>, accepted = false) => {
    if (busy !== null) return;
    setBusy(id);
    setError(null);
    call(id)
      .then((r) => {
        onChange(r);
        if (accepted) onAccepted();
      })
      .catch((e) => setError(errText(e)))
      .finally(() => setBusy(null));
  };

  if (requests.incoming.length === 0 && requests.outgoing.length === 0) {
    return (
      <PxBox family="panel" className="empty">
        <TT size={20} tone="dim">
          NO PENDING REQUESTS
        </TT>
        <span className="meta">Requests you send and receive show up here.</span>
      </PxBox>
    );
  }
  return (
    <>
      {error && (
        <PxBox family="red" height="md" className="social__notice" role="alert">
          <span className="meta">{error}</span>
        </PxBox>
      )}
      {requests.incoming.length > 0 && (
        <div className="social__section">
          <TT size={16} tone="dim">
            RECEIVED
          </TT>
        </div>
      )}
      {requests.incoming.map((r) => (
        <PersonRow key={r.id} uuid={r.uuid} name={r.username} meta={`Wants to be friends · ${ago(r.createdAt)}`}>
          <PxButton
            family="green"
            height="md"
            disabled={busy !== null}
            onClick={() => act(r.id, api.acceptFriendRequest, true)}
          >
            <TT size={16} tone="green">
              ACCEPT
            </TT>
          </PxButton>
          <PxButton family="grey" height="md" disabled={busy !== null} onClick={() => act(r.id, api.declineFriendRequest)}>
            <TT size={16}>DECLINE</TT>
          </PxButton>
        </PersonRow>
      ))}
      {requests.outgoing.length > 0 && (
        <div className="social__section">
          <TT size={16} tone="dim">
            SENT
          </TT>
        </div>
      )}
      {requests.outgoing.map((r) => (
        <PersonRow
          key={r.id}
          uuid={r.uuid}
          name={r.username}
          dim
          meta={`Waiting for them · sent ${ago(r.createdAt)}`}
        >
          <PxButton family="grey" height="md" disabled={busy !== null} onClick={() => act(r.id, api.declineFriendRequest)}>
            <TT size={16}>CANCEL</TT>
          </PxButton>
        </PersonRow>
      ))}
    </>
  );
}

function ChatView({
  friend,
  uuid,
  clock24h,
  activity,
  gameRunning,
  isTauri,
  onJoin,
  onLink,
  onBack,
  onSeen,
  onProfile,
}: {
  /** undefined only until the list first loads, or after they unfriend us */
  friend: Friend | undefined;
  uuid: string;
  clock24h: boolean;
  activity: GameActivity | null;
  gameRunning: boolean;
  isTauri: boolean;
  onJoin: (server: string, version: string | null) => void;
  onLink: (url: string) => void;
  onBack: () => void;
  /** a fetch just marked their messages read — the badges are stale */
  onSeen: () => void;
  onProfile: () => void;
}) {
  const [messages, setMessages] = useState<ChatMessage[] | null>(null);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [picking, setPicking] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);
  const afterId = useRef(0);
  // follow new lines only when already at the bottom — never yank someone
  // who scrolled up to read back
  const pinned = useRef(true);
  // messages landed while scrolled up — offer a jump back down
  const [missed, setMissed] = useState(0);
  const seenCount = useRef(0);
  const onSeenRef = useRef(onSeen);
  onSeenRef.current = onSeen;

  // the poll and a send can both return the same message (the poll lands
  // after the insert but before the send resolves) — merge by id, in order
  const merge = useCallback((batch: ChatMessage[]) => {
    if (batch.length === 0) return;
    afterId.current = Math.max(afterId.current, ...batch.map((m) => m.id));
    setMessages((prev) => {
      const seen = new Set((prev ?? []).map((m) => m.id));
      const fresh = batch.filter((m) => !seen.has(m.id));
      if (prev && fresh.length === 0) return prev;
      return [...(prev ?? []), ...fresh].sort((a, b) => a.id - b.id);
    });
  }, []);

  useEffect(() => {
    let live = true;
    afterId.current = 0;
    pinned.current = true;
    seenCount.current = 0;
    setMissed(0);
    setMessages(null);
    // nothing while the window is hidden (in game, minimised): no one is
    // reading, and a poll would mark what arrives as seen
    const poll = async () => {
      if (document.hidden && afterId.current !== 0) return;
      try {
        const batch = await api.getMessages(uuid, afterId.current);
        if (!live) return;
        setMessages((prev) => prev ?? []);
        merge(batch);
        if (batch.some((m) => m.fromUuid === uuid)) onSeenRef.current();
        setError((e) => (e?.startsWith('Couldn’t load') ? null : e));
      } catch (e) {
        // a transient failure just waits for the next tick; say so only if
        // there is nothing on screen yet
        if (live && afterId.current === 0) setError(`Couldn’t load messages: ${errText(e)}`);
      }
    };
    void poll();
    const id = setInterval(() => void poll(), CHAT_POLL_MS);
    const shown = () => !document.hidden && void poll();
    document.addEventListener('visibilitychange', shown);
    return () => {
      live = false;
      clearInterval(id);
      document.removeEventListener('visibilitychange', shown);
    };
  }, [uuid, merge]);

  useEffect(() => {
    const el = listRef.current;
    const count = messages?.length ?? 0;
    if (el && pinned.current) {
      el.scrollTo({ top: el.scrollHeight });
      setMissed(0);
    } else if (count > seenCount.current) {
      setMissed((n) => n + count - seenCount.current);
    }
    seenCount.current = count;
  }, [messages]);

  const jumpDown = () => {
    const el = listRef.current;
    pinned.current = true;
    setMissed(0);
    el?.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  };

  const send = (e?: FormEvent) => {
    e?.preventDefault();
    const body = draft.trim();
    if (!body) return;
    setDraft('');
    setError(null);
    pinned.current = true;
    api
      .sendMessage(uuid, body)
      .then((msg) => merge([msg]))
      .catch((err) => {
        setError(errText(err));
        // hand the text back rather than lose it — unless they've already
        // started typing something new
        setDraft((d) => d || body);
      });
  };

  const invite = () => {
    if (!activity?.server) {
      setError(
        activity?.hosting
          ? 'Your world is still opening — INVITE once its address shows.'
          : 'Join a server or HOST a world first — then INVITE sends them its address.',
      );
      return;
    }
    setError(null);
    pinned.current = true;
    api
      .sendInvite(uuid, activity.server, activity.gameVersion)
      .then((msg) => merge([msg]))
      .catch((err) => setError(errText(err)));
  };

  const sendShot = (shot: Screenshot) => {
    setPicking(false);
    setError(null);
    pinned.current = true;
    api
      .sendScreenshot(uuid, shot.path)
      .then((msg) => merge([msg]))
      .catch((err) => setError(errText(err)));
  };

  const name = friend?.username ?? 'Friend';
  const status = friend ? statusLine(friend) : null;

  return (
    <>
      <div className="win__bar">
        <NavCell label="← FRIENDS" onClick={onBack} />
        <div className="win__fill" />
        <NavCell label="INVITE" onClick={invite} />
        <NavCell label="SCREENSHOT" onClick={() => setPicking(true)} />
        <NavCell label="PROFILE" onClick={onProfile} />
      </div>

      <div className="win__body social__chat-body">
        <PersonRow
          uuid={uuid}
          name={name}
          online={friend?.online}
          meta={status?.text ?? ''}
          metaOnline={status?.online}
          onClick={onProfile}
          title={`View ${name}'s profile`}
        >
          {friend?.online && friend.server && (
            <JoinButton server={friend.server} version={friend.playing ?? null} gameRunning={gameRunning} onJoin={onJoin} />
          )}
        </PersonRow>

        <div className="social__chat-wrap">
          <div
            className="social__chat scroll"
            ref={listRef}
            onScroll={(e) => {
              const el = e.currentTarget;
              pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
              if (pinned.current) setMissed(0);
            }}
            aria-live="polite"
          >
            {messages === null ? (
              <span className="meta social__chat-empty">Loading…</span>
            ) : messages.length === 0 ? (
              <span className="meta social__chat-empty">
                Say hello — this is the start of your conversation with {name}.
              </span>
            ) : (
              chatRows(messages, uuid).map((row) => {
                const m = row.messages[0];
                const lastAt = row.messages[row.messages.length - 1].sentAt;
                return (
                  <div
                    key={row.key}
                    className={`social__line${row.mine ? ' social__line--mine' : ''}${row.cont ? ' social__line--cont' : ''}`}
                  >
                    {row.stamp != null && (
                      <div className="social__stamp" role="separator">
                        <span className="meta">{timeStamp(row.stamp * 1000, clock24h)}</span>
                      </div>
                    )}
                    <PxBox
                      family={row.mine ? 'soft' : 'panel'}
                      className={`social__bubble social__bubble--${m.kind ?? 'text'}`}
                      title={timeStamp(lastAt * 1000, clock24h)}
                    >
                      {row.messages.length > 1 ? (
                        <GiftRun gifts={row.messages} mine={row.mine} name={name} />
                      ) : (
                        <MessageBody
                          m={m}
                          mine={row.mine}
                          name={name}
                          gameRunning={gameRunning}
                          onJoin={onJoin}
                          onLink={onLink}
                        />
                      )}
                    </PxBox>
                  </div>
                );
              })
            )}
          </div>
          {missed > 0 && (
            <PxButton family="accent" height="sm" className="social__jump" onClick={jumpDown}>
              <TT size={14} tone="accent">
                {missed === 1 ? '1 NEW MESSAGE ↓' : `${missed} NEW MESSAGES ↓`}
              </TT>
            </PxButton>
          )}
        </div>

        {error && (
          <PxBox family="red" height="md" className="social__notice" role="alert">
            <span className="meta">{error}</span>
          </PxBox>
        )}

        <form className="social__composer" onSubmit={send}>
          <PxBox family="panel" height="md" className="social__composer-field">
            <input
              className="input"
              placeholder={`Message ${name}`}
              value={draft}
              maxLength={MESSAGE_MAX}
              autoFocus
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                // Enter mid-composition (IME) picks a candidate, not send
                if (e.key === 'Enter' && e.nativeEvent.isComposing) e.preventDefault();
              }}
            />
            {draft.length > MESSAGE_MAX - 100 && (
              <span className="meta social__count">{MESSAGE_MAX - draft.length}</span>
            )}
          </PxBox>
          <PxButton family="accent" height="md" type="submit" disabled={!draft.trim()}>
            <TT size={16} tone="accent">
              SEND
            </TT>
          </PxButton>
        </form>
      </div>

      {picking &&
        createPortal(
          <ShotPicker isTauri={isTauri} onPick={sendShot} onClose={() => setPicking(false)} />,
          document.body,
        )}
    </>
  );
}

/** what a bubble holds, by message kind */
function MessageBody({
  m,
  mine,
  name,
  gameRunning,
  onJoin,
  onLink,
}: {
  m: ChatMessage;
  mine: boolean;
  name: string;
  gameRunning: boolean;
  onJoin: (server: string, version: string | null) => void;
  onLink: (url: string) => void;
}) {
  const meta = m.meta ?? {};
  if (m.kind === 'invite' && meta.server) {
    return (
      <div className="social__card">
        <TT size={14} tone="dim">
          {mine ? 'YOU SENT AN INVITE' : `${name.toUpperCase()} INVITED YOU`}
        </TT>
        <span className="social__text social__card-title">{meta.server}</span>
        {meta.version && <span className="meta">Minecraft {meta.version}</span>}
        {!mine && (
          <div className="social__card-row">
            <JoinButton server={meta.server} version={meta.version ?? null} gameRunning={gameRunning} onJoin={onJoin} />
          </div>
        )}
      </div>
    );
  }
  if (m.kind === 'gift') {
    return (
      <div className="social__card social__card--gift">
        <TT size={14} tone="dim">
          {mine ? `YOU GIFTED ${name.toUpperCase()}` : `${name.toUpperCase()} GIFTED YOU`}
        </TT>
        <span className="social__text social__card-title">{meta.name ?? 'A cosmetic'}</span>
        {!mine && <span className="meta">Equip it under COSMETICS.</span>}
      </div>
    );
  }
  if (m.kind === 'image' && meta.image) return <ChatImage id={meta.image} />;
  return <LinkText text={m.body} onLink={onLink} />;
}

/** gifts sent back to back, as one card listing what was sent */
function GiftRun({ gifts, mine, name }: { gifts: ChatMessage[]; mine: boolean; name: string }) {
  const [open, setOpen] = useState(false);
  const names = gifts.map((g) => g.meta?.name ?? 'A cosmetic');
  const shown = open ? names : names.slice(0, GIFT_PREVIEW);
  const more = names.length - shown.length;
  return (
    <div className="social__card social__card--gift">
      <TT size={14} tone="dim">
        {mine ? `YOU GIFTED ${name.toUpperCase()} ${gifts.length} ITEMS` : `${name.toUpperCase()} GIFTED YOU ${gifts.length} ITEMS`}
      </TT>
      <ul className="social__gift-names">
        {shown.map((n, i) => (
          <li key={gifts[i].id} className="social__text">
            {n}
          </li>
        ))}
      </ul>
      {more > 0 && (
        <button type="button" className="meta social__more" onClick={() => setOpen(true)}>
          +{more} more
        </button>
      )}
      {!mine && <span className="meta">Equip them under COSMETICS.</span>}
    </div>
  );
}

/* chat images come through the backend (which caches them on disk); keep
   the data URLs for the session so re-opening a chat doesn't refetch */
const imageCache = new Map<string, Promise<string | null>>();
function ChatImage({ id }: { id: string }) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [zoom, setZoom] = useState(false);
  useEscape(zoom, () => setZoom(false));
  useEffect(() => {
    let live = true;
    let hit = imageCache.get(id);
    if (!hit) {
      hit = api.getChatImage(id).catch(() => {
        imageCache.delete(id);
        return null;
      });
      imageCache.set(id, hit);
    }
    void hit.then((s) => {
      if (!live) return;
      if (s) setSrc(s);
      else setFailed(true);
    });
    return () => {
      live = false;
    };
  }, [id]);
  if (failed) return <span className="meta">Image unavailable</span>;
  if (!src) return <span className="social__image social__image--loading" />;
  return (
    <>
      <img className="social__image" src={src} alt="Screenshot" draggable={false} onClick={() => setZoom(true)} />
      {zoom &&
        createPortal(
          <div className="modal-scrim social__zoom" onClick={() => setZoom(false)}>
            <img src={src} alt="Screenshot" draggable={false} />
          </div>,
          document.body,
        )}
    </>
  );
}

/** the newest screenshots across instances, as thumbnails; one click sends */
function ShotPicker({
  isTauri,
  onPick,
  onClose,
}: {
  isTauri: boolean;
  onPick: (s: Screenshot) => void;
  onClose: () => void;
}) {
  const [shots, setShots] = useState<Screenshot[] | null>(null);
  useEscape(true, onClose);
  useEffect(() => {
    void api
      .listScreenshots()
      .then((list) => setShots(list.slice(0, 24)))
      .catch(() => setShots([]));
  }, []);
  return (
    <div className="modal-scrim" onClick={onClose}>
      <PxBox
        family="panel"
        className="px--window modal social__shots"
        role="dialog"
        aria-label="Send a screenshot"
        onClick={(e) => e.stopPropagation()}
      >
        <TT size={22}>SEND A SCREENSHOT</TT>
        <span className="meta">Your newest shots from every instance. Up to 8 MB each.</span>
        <div className="social__shot-grid scroll">
          {shots === null && <span className="meta">Loading…</span>}
          {shots?.length === 0 && <span className="meta">No screenshots yet — press F2 in game.</span>}
          {shots?.map((s) => (
            <button key={s.path} className="social__shot" title={`${s.profileName} · ${s.name}`} onClick={() => onPick(s)}>
              <img src={isTauri ? convertFileSrc(s.path) : s.path} alt="" loading="lazy" draggable={false} />
            </button>
          ))}
        </div>
        <div className="modal__row modal__row--tall">
          <PxButton family="grey" height="md" onClick={onClose}>
            <TT size={20}>CANCEL</TT>
          </PxButton>
        </div>
      </PxBox>
    </div>
  );
}

/* the catalog is the same for every profile opened — read it once */
let catalogOnce: Promise<CosmeticsCatalog | null> | null = null;
function loadCatalog() {
  catalogOnce ??= api.listCosmetics().catch(() => {
    catalogOnce = null;
    return null;
  });
  return catalogOnce;
}

function ProfileView({
  friend,
  uuid,
  from,
  onBack,
  onMessage,
  gameRunning,
  onJoin,
  onGift,
  onRemoved,
  onBlocked,
}: {
  friend: Friend | undefined;
  uuid: string;
  /** where BACK returns to */
  from: 'list' | 'chat';
  onBack: () => void;
  onMessage: () => void;
  gameRunning: boolean;
  onJoin: (server: string, version: string | null) => void;
  onGift: (name: string) => void;
  onRemoved: (friends: Friend[], name: string) => void;
  onBlocked: (name: string) => void;
}) {
  const [profile, setProfile] = useState<FriendProfile | null>(null);
  const [catalog, setCatalog] = useState<CosmeticsCatalog | null>(null);
  const [confirming, setConfirming] = useState<'remove' | 'block' | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    api
      .getFriendProfile(uuid)
      .then((p) => live && setProfile(p))
      .catch((e) => live && setError(errText(e)));
    void loadCatalog().then((c) => live && setCatalog(c));
    return () => {
      live = false;
    };
  }, [uuid]);

  const name = profile?.username ?? friend?.username ?? 'Friend';
  const status = friend ? statusLine(friend) : profile ? statusLine(profile) : null;
  const server = friend?.online ? friend.server : profile?.online ? profile.server : undefined;
  const version = (friend?.online ? friend.playing : profile?.playing) ?? null;

  const capeName = profile?.cape != null ? catalog?.capes.find((c) => c.id === profile.cape)?.name : undefined;
  const accessoryNames = (profile?.accessories ?? [])
    .map((id) => catalog?.accessories.find((a) => a.id === id)?.name)
    .filter((n): n is string => !!n);

  // the confirm dialog takes the first Escape, not while it's working
  useEscape(confirming !== null, () => !busy && setConfirming(null));

  const act = () => {
    const blocking = confirming === 'block';
    setBusy(true);
    setError(null);
    (blocking ? api.blockPlayer(uuid).then(() => onBlocked(name)) : api.removeFriend(uuid).then((list) => onRemoved(list, name))).catch(
      (e) => {
        setError(errText(e));
        setBusy(false);
        setConfirming(null);
      },
    );
  };

  return (
    <>
      <div className="win__bar">
        <NavCell label={from === 'chat' ? '← CHAT' : '← FRIENDS'} onClick={onBack} />
        <div className="win__fill" />
      </div>

      <div className="win__body settings__body social__body scroll">
        <div className="social__hero">
          <FriendHead uuid={uuid} online={status?.online} size={88} />
          <div className="srow__text">
            <TT size={36} tone="plain">
              {name}
            </TT>
            {status && <span className={`meta ${status.online ? 'is-online' : ''}`}>{status.text}</span>}
          </div>
          {server && <JoinButton server={server} version={version} gameRunning={gameRunning} onJoin={onJoin} />}
          <PxButton family="accent" height="md" className="social__hero-cta" onClick={onMessage}>
            <TT size={16} tone="accent">
              MESSAGE
            </TT>
          </PxButton>
        </div>

        {error && (
          <PxBox family="red" height="md" className="social__notice" role="alert">
            <span className="meta">{error}</span>
          </PxBox>
        )}
        <Row label="CAPE" hint={profile ? (capeName ?? 'None equipped') : 'Loading…'}>
          {null}
        </Row>
        <Row
          label="ACCESSORIES"
          hint={profile ? (accessoryNames.length > 0 ? accessoryNames.join(', ') : 'None equipped') : 'Loading…'}
        >
          {null}
        </Row>
        {profile?.offline && (
          <Row label="ACCOUNT" hint="Cracked: no Microsoft account behind this name, so only Dusk vouches for it.">
            {null}
          </Row>
        )}
        {profile && profile.badges.length > 0 && (
          <Row label="BADGES" hint={profile.badges.join(' · ')}>
            {null}
          </Row>
        )}
        <Row label="SEND A GIFT" hint="Opens the store for them: buy cosmetics with your coins and they land in their wardrobe.">
          <PxButton family="blue" height="md" onClick={() => onGift(name)}>
            <TT size={16} tone="blue">
              GIFT
            </TT>
          </PxButton>
        </Row>
        <Row label="REMOVE FRIEND" hint="Takes them off your list. You'd need a new request to chat again.">
          <PxButton family="red" height="md" onClick={() => setConfirming('remove')}>
            <TT size={16} tone="red">
              REMOVE
            </TT>
          </PxButton>
        </Row>
        <Row label="BLOCK" hint="Unfriends them and stops their requests and messages. Undo in Settings → Social.">
          <PxButton family="red" height="md" onClick={() => setConfirming('block')}>
            <TT size={16} tone="red">
              BLOCK
            </TT>
          </PxButton>
        </Row>
      </div>

      {confirming &&
        createPortal(
          <div className="modal-scrim" onClick={() => !busy && setConfirming(null)}>
            <PxBox family="red" className="px--window modal" onClick={(e) => e.stopPropagation()}>
              <TT size={22} tone="red">
                {`${confirming === 'block' ? 'BLOCK' : 'REMOVE'} ${name.toUpperCase()}?`}
              </TT>
              <span className="meta">
                {confirming === 'block'
                  ? "They're removed from your friends and can't send you requests or messages until you unblock them."
                  : 'They drop off your friends list and your chat closes. Either of you can send a new request later.'}
              </span>
              <div className="modal__row modal__row--tall">
                <PxButton family="grey" height="md" disabled={busy} onClick={() => setConfirming(null)}>
                  <TT size={20}>CANCEL</TT>
                </PxButton>
                <PxButton family="red" height="md" disabled={busy} onClick={act}>
                  <TT size={20} tone="red">
                    {busy ? 'WORKING…' : confirming === 'block' ? 'BLOCK' : 'REMOVE'}
                  </TT>
                </PxButton>
              </div>
            </PxBox>
          </div>,
          document.body,
        )}

    </>
  );
}

