/**
 * Friends, requests and 1:1 chat — opened from the status pill. A light
 * dropdown (Home.tsx's popout pattern), not a route: closes on an outside
 * click or Escape, same as the instance picker.
 *
 * While signed in, a heartbeat runs whether the pane is open or not: it is
 * what keeps this account "online" for its friends (and says which game
 * version it's in), and its answer — pending requests, unread messages,
 * friends online — is what the pill shows. The full lists are only fetched
 * while the pane is open.
 */
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { createPortal } from 'react-dom';
import { PxBox, PxButton, TT } from './px/Px';
import PlayerHead from './PlayerHead';
import {
  api,
  type Account,
  type ChatMessage,
  type CosmeticsCatalog,
  type Friend,
  type FriendProfile,
  type FriendRequests,
  type SocialSummary,
} from '../lib/api';

/** well inside the service's two-minute online window */
const HEARTBEAT_MS = 30_000;
/** friends/requests refresh while the pane is open */
const LIST_POLL_MS = 10_000;
const CHAT_POLL_MS = 3_500;
/** mirrors the service's MESSAGE_MAX_LEN */
const MESSAGE_MAX = 1000;
/** a chat gap longer than this gets a timestamp line */
const STAMP_GAP_S = 10 * 60;

const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** `ts` is unix seconds, as the service sends every time */
function ago(ts: number): string {
  const s = Math.max(0, Math.floor(Date.now() / 1000 - ts));
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

function stamp(ts: number): string {
  const d = new Date(ts * 1000);
  const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const today = new Date();
  const yesterday = new Date(today.getTime() - 86_400_000);
  if (d.toDateString() === today.toDateString()) return `Today ${time}`;
  if (d.toDateString() === yesterday.toDateString()) return `Yesterday ${time}`;
  return `${d.toLocaleDateString([], { month: 'short', day: 'numeric' })} ${time}`;
}

function statusLine(f: Friend): { text: string; online: boolean } {
  if (f.online && f.playing) return { text: `PLAYING ${f.playing}`, online: true };
  if (f.online) return { text: 'ONLINE', online: true };
  return { text: `LAST SEEN ${ago(f.lastSeen).toUpperCase()}`, online: false };
}

/* skins are shared across rows, the chat header and the profile, and the
   rows remount every time the pane opens — one fetch per friend per half
   hour instead of one per mount (the backend caches on disk too) */
const SKIN_TTL_MS = 30 * 60_000;
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
  isTauri,
}: {
  account: Account | null;
  gameRunning: boolean;
  /** the running game's version, reported to friends; null when not playing */
  playing: string | null;
  isTauri: boolean;
}) {
  // the browser preview has mocks for every call, so it walks as signed in
  const signedIn = !isTauri || !!account?.authenticated;
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<View>({ kind: 'list' });
  const [summary, setSummary] = useState<SocialSummary | null>(null);
  const [friends, setFriends] = useState<Friend[] | null>(null);
  const [requests, setRequests] = useState<FriendRequests>({ incoming: [], outgoing: [] });
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  // Escape reads these from a listener registered once per open
  const addingRef = useRef(adding);
  addingRef.current = adding;

  const heartbeat = useCallback(() => {
    void api
      .socialHeartbeat(playing)
      .then(setSummary)
      .catch(() => {
        // offline or the service is down — the next beat tries again, and
        // the pill just keeps its last counts
      });
  }, [playing]);

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
      // the lists are fresher than the last beat — keep the pill in step
      setSummary({
        requests: r.incoming.length,
        unread: f.reduce((n, x) => n + x.unread, 0),
        online: f.filter((x) => x.online).length,
      });
    } catch (e) {
      setLoadError(errText(e));
    }
  }, []);

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
      if (addingRef.current) setAdding(false);
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
        <div className="win px--window social__popout" role="dialog" aria-label="Friends and chat">
          {!signedIn ? (
            <PxBox family="panel" className="social__empty">
              <TT size={16} tone="sub">
                SIGN IN TO SEE FRIENDS
              </TT>
              <span className="meta">Friends and chat use your Microsoft account.</span>
            </PxBox>
          ) : view.kind === 'chat' ? (
            <ChatView
              friend={byUuid(view.uuid)}
              uuid={view.uuid}
              onBack={() => toList()}
              onSeen={() => void refresh()}
              onProfile={() => setView({ kind: 'profile', uuid: view.uuid, from: 'chat' })}
            />
          ) : view.kind === 'profile' ? (
            <ProfileView
              friend={byUuid(view.uuid)}
              uuid={view.uuid}
              onBack={() =>
                view.from === 'chat' ? setView({ kind: 'chat', uuid: view.uuid }) : toList()
              }
              onMessage={() => setView({ kind: 'chat', uuid: view.uuid })}
              onRemoved={(list, name) => {
                setFriends(list);
                setView({ kind: 'list' });
                setNotice(`Removed ${name} from your friends.`);
              }}
            />
          ) : (
            <>
              <div className="social__tabs">
                <PxButton
                  family={view.kind === 'list' ? 'accent' : 'grey'}
                  height="sm"
                  onClick={() => setView({ kind: 'list' })}
                  aria-pressed={view.kind === 'list'}
                >
                  <TT size={14} tone={view.kind === 'list' ? 'accent' : undefined}>
                    {friends && friends.length > 0 ? `FRIENDS (${friends.length})` : 'FRIENDS'}
                  </TT>
                </PxButton>
                <PxButton
                  family={view.kind === 'requests' ? 'accent' : 'grey'}
                  height="sm"
                  onClick={() => setView({ kind: 'requests' })}
                  aria-pressed={view.kind === 'requests'}
                >
                  <TT size={14} tone={view.kind === 'requests' ? 'accent' : undefined}>
                    {requests.incoming.length > 0 ? `REQUESTS (${requests.incoming.length})` : 'REQUESTS'}
                  </TT>
                </PxButton>
                <span className="social__fill" />
                <PxButton family="grey" height="sm" onClick={() => setAdding(true)} title="Add a friend by username">
                  <TT size={14}>+ ADD</TT>
                </PxButton>
              </div>

              <div className="social__list scroll">
                {notice && (
                  <PxBox family="green" height="fill" className="social__notice" role="status">
                    <span className="meta">{notice}</span>
                  </PxBox>
                )}
                {loadError && (
                  <PxBox family="red" height="fill" className="social__notice" role="alert">
                    <span className="meta">{loadError}</span>
                    <PxButton family="grey" height="sm" onClick={() => void refresh()}>
                      <TT size={13}>RETRY</TT>
                    </PxButton>
                  </PxBox>
                )}
                {view.kind === 'requests' ? (
                  <RequestsList requests={requests} onChange={setRequests} onAccepted={() => void refresh()} />
                ) : friends === null ? (
                  !loadError && (
                    <PxBox family="panel" className="social__empty">
                      <span className="meta">Loading friends…</span>
                    </PxBox>
                  )
                ) : (
                  <FriendsList
                    friends={friends}
                    onOpen={(f) => setView({ kind: 'chat', uuid: f.uuid })}
                    onAdd={() => setAdding(true)}
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
    </div>
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
        className="px--window modal social__add"
        role="dialog"
        aria-label="Add friend"
        onClick={(e) => e.stopPropagation()}
      >
        <form className="social__add-form" onSubmit={submit}>
          <TT size={22}>ADD FRIEND</TT>
          <span className="meta">Their Minecraft username. They need to have signed in to Dusk Launcher once.</span>
          <div className="modal__row">
            <span className="modal__label">
              <TT size={16} tone="dim">
                USERNAME
              </TT>
            </span>
            <PxBox family="panel" height="md">
              <input
                className="input"
                placeholder="THEIR USERNAME"
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
            <PxBox family="red" height="md" role="alert">
              <span className="meta">{error}</span>
            </PxBox>
          )}
          <div className="modal__row">
            <span className="modal__spacer" />
            <PxButton family="grey" height="md" onClick={onClose}>
              <TT size={16}>CANCEL</TT>
            </PxButton>
            <PxButton family="accent" height="md" type="submit" disabled={busy || !name.trim()}>
              <TT size={16} tone="accent">
                {busy ? 'SENDING…' : 'SEND REQUEST'}
              </TT>
            </PxButton>
          </div>
        </form>
      </PxBox>
    </div>
  );
}

function FriendsList({
  friends,
  onOpen,
  onAdd,
}: {
  friends: Friend[];
  onOpen: (f: Friend) => void;
  onAdd: () => void;
}) {
  if (friends.length === 0) {
    return (
      <PxBox family="panel" className="social__empty">
        <TT size={16} tone="sub">
          NO FRIENDS YET
        </TT>
        <span className="meta">Add someone by their Minecraft username to chat and see when they're on.</span>
        <PxButton family="accent" height="sm" onClick={onAdd}>
          <TT size={14} tone="accent">
            + ADD A FRIEND
          </TT>
        </PxButton>
      </PxBox>
    );
  }
  return (
    <>
      {friends.map((f) => (
        <FriendRow key={f.uuid} friend={f} onOpen={() => onOpen(f)} />
      ))}
    </>
  );
}

function FriendRow({ friend, onOpen }: { friend: Friend; onOpen: () => void }) {
  const status = statusLine(friend);
  return (
    <PxButton
      family="grey"
      height="fill"
      className="social__row"
      onClick={onOpen}
      title={`Chat with ${friend.username}`}
    >
      <FriendHead uuid={friend.uuid} online={friend.online} size={32} />
      <span className="home__popout-name">
        <TT size={16}>{friend.username}</TT>
        <span className={`meta ${status.online ? 'is-online' : ''}`}>{status.text}</span>
      </span>
      {friend.unread > 0 && (
        <span className="social__badge" title={`${friend.unread} unread`}>
          <TT size={11}>{friend.unread > 99 ? '99+' : String(friend.unread)}</TT>
        </span>
      )}
    </PxButton>
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
      <PxBox family="panel" className="social__empty">
        <TT size={16} tone="sub">
          NO PENDING REQUESTS
        </TT>
        <span className="meta">Requests you send and receive show up here.</span>
      </PxBox>
    );
  }
  return (
    <>
      {error && (
        <PxBox family="red" height="fill" className="social__notice" role="alert">
          <span className="meta">{error}</span>
        </PxBox>
      )}
      {requests.incoming.length > 0 && (
        <span className="social__section">
          <TT size={13} tone="dim">
            RECEIVED
          </TT>
        </span>
      )}
      {requests.incoming.map((r) => (
        <PxBox key={r.id} family="grey" height="fill" className="social__row social__row--static">
          <FriendHead uuid={r.uuid} size={32} />
          <span className="home__popout-name">
            <TT size={16}>{r.username}</TT>
            <span className="meta">wants to be friends · {ago(r.createdAt)}</span>
          </span>
          <span className="social__row-actions">
            <PxButton
              family="green"
              height="sm"
              disabled={busy !== null}
              onClick={() => act(r.id, api.acceptFriendRequest, true)}
            >
              <TT size={13}>ACCEPT</TT>
            </PxButton>
            <PxButton family="grey" height="sm" disabled={busy !== null} onClick={() => act(r.id, api.declineFriendRequest)}>
              <TT size={13}>DECLINE</TT>
            </PxButton>
          </span>
        </PxBox>
      ))}
      {requests.outgoing.length > 0 && (
        <span className="social__section">
          <TT size={13} tone="dim">
            SENT
          </TT>
        </span>
      )}
      {requests.outgoing.map((r) => (
        <PxBox key={r.id} family="panel" height="fill" className="social__row social__row--static">
          <FriendHead uuid={r.uuid} size={32} />
          <span className="home__popout-name">
            <TT size={16} tone="dim">
              {r.username}
            </TT>
            <span className="meta">waiting for them · {ago(r.createdAt)}</span>
          </span>
          <span className="social__row-actions">
            <PxButton family="grey" height="sm" disabled={busy !== null} onClick={() => act(r.id, api.declineFriendRequest)}>
              <TT size={13}>CANCEL</TT>
            </PxButton>
          </span>
        </PxBox>
      ))}
    </>
  );
}

function ChatView({
  friend,
  uuid,
  onBack,
  onSeen,
  onProfile,
}: {
  /** undefined only until the list first loads, or after they unfriend us */
  friend: Friend | undefined;
  uuid: string;
  onBack: () => void;
  /** a fetch just marked their messages read — the badges are stale */
  onSeen: () => void;
  onProfile: () => void;
}) {
  const [messages, setMessages] = useState<ChatMessage[] | null>(null);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const afterId = useRef(0);
  // follow new lines only when already at the bottom — never yank someone
  // who scrolled up to read back
  const pinned = useRef(true);
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
    setMessages(null);
    const poll = async () => {
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
    return () => {
      live = false;
      clearInterval(id);
    };
  }, [uuid, merge]);

  useEffect(() => {
    const el = listRef.current;
    if (el && pinned.current) el.scrollTo({ top: el.scrollHeight });
  }, [messages]);

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

  const name = friend?.username ?? 'Friend';
  const status = friend ? statusLine(friend) : null;

  return (
    <>
      <div className="social__tabs">
        <PxButton family="grey" height="sm" onClick={onBack} title="Back to friends">
          <TT size={14}>← BACK</TT>
        </PxButton>
        <button className="social__chat-title" onClick={onProfile} title={`View ${name}'s profile`}>
          <FriendHead uuid={uuid} online={friend?.online} size={24} />
          <span className="home__popout-name">
            <TT size={16}>{name}</TT>
            {status && <span className={`meta ${status.online ? 'is-online' : ''}`}>{status.text}</span>}
          </span>
        </button>
      </div>

      <div
        className="social__chat scroll"
        ref={listRef}
        onScroll={(e) => {
          const el = e.currentTarget;
          pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
        }}
        aria-live="polite"
      >
        {messages === null ? (
          <span className="meta social__chat-empty">Loading…</span>
        ) : messages.length === 0 ? (
          <span className="meta social__chat-empty">Say hello — this is the start of your conversation with {name}.</span>
        ) : (
          messages.map((m, i) => {
            const prev = messages[i - 1];
            const showStamp = !prev || m.sentAt - prev.sentAt > STAMP_GAP_S;
            const theirs = m.fromUuid === uuid;
            return (
              <div key={m.id} className="social__line">
                {showStamp && <span className="meta social__stamp">{stamp(m.sentAt)}</span>}
                <div
                  className={`social__bubble ${theirs ? '' : 'social__bubble--mine'}`}
                  title={new Date(m.sentAt * 1000).toLocaleString()}
                >
                  <span className="meta">{m.body}</span>
                </div>
              </div>
            );
          })
        )}
      </div>

      {error && (
        <PxBox family="red" height="md" role="alert">
          <span className="meta">{error}</span>
        </PxBox>
      )}

      <form className="social__composer" onSubmit={send}>
        <PxBox family="panel" height="md" className="social__composer-field">
          <input
            className="input"
            placeholder={`MESSAGE ${name.toUpperCase()}`}
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
    </>
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
  onBack,
  onMessage,
  onRemoved,
}: {
  friend: Friend | undefined;
  uuid: string;
  onBack: () => void;
  onMessage: () => void;
  onRemoved: (friends: Friend[], name: string) => void;
}) {
  const [profile, setProfile] = useState<FriendProfile | null>(null);
  const [catalog, setCatalog] = useState<CosmeticsCatalog | null>(null);
  const [confirming, setConfirming] = useState(false);
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
  const status = friend
    ? statusLine(friend)
    : profile
      ? statusLine({ ...profile, unread: 0 })
      : null;

  const capeName = profile?.cape != null ? catalog?.capes.find((c) => c.id === profile.cape)?.name : undefined;
  const accessoryNames = (profile?.accessories ?? [])
    .map((id) => catalog?.accessories.find((a) => a.id === id)?.name)
    .filter((n): n is string => !!n);
  const wearing = [capeName, ...accessoryNames].filter(Boolean).join(', ');

  const remove = () => {
    setBusy(true);
    setError(null);
    api
      .removeFriend(uuid)
      .then((list) => onRemoved(list, name))
      .catch((e) => {
        setError(errText(e));
        setBusy(false);
      });
  };

  return (
    <>
      <div className="social__tabs">
        <PxButton family="grey" height="sm" onClick={onBack} title="Back">
          <TT size={14}>← BACK</TT>
        </PxButton>
      </div>
      <div className="social__profile">
        <FriendHead uuid={uuid} online={status?.online} size={72} />
        <TT size={22}>{name}</TT>
        {status && <span className={`meta ${status.online ? 'is-online' : ''}`}>{status.text}</span>}
        {wearing && <span className="meta social__wearing">Wearing {wearing}</span>}

        {error && (
          <PxBox family="red" height="md" role="alert">
            <span className="meta">{error}</span>
          </PxBox>
        )}

        {confirming ? (
          <div className="social__confirm">
            <span className="meta">Remove {name}? You'll need a new friend request to chat again.</span>
            <span className="social__row-actions">
              <PxButton family="grey" height="sm" disabled={busy} onClick={() => setConfirming(false)}>
                <TT size={13}>KEEP</TT>
              </PxButton>
              <PxButton family="red" height="sm" disabled={busy} onClick={remove}>
                <TT size={13}>{busy ? 'REMOVING…' : 'REMOVE'}</TT>
              </PxButton>
            </span>
          </div>
        ) : (
          <span className="social__row-actions social__profile-actions">
            <PxButton family="accent" height="sm" onClick={onMessage}>
              <TT size={14} tone="accent">
                MESSAGE
              </TT>
            </PxButton>
            <PxButton family="grey" height="sm" onClick={() => setConfirming(true)}>
              <TT size={14}>REMOVE FRIEND</TT>
            </PxButton>
          </span>
        )}
      </div>
    </>
  );
}
