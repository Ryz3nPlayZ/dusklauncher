import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowRight,
  ArrowsLeftRight,
  ChatCircle,
  Check,
  Cube,
  Globe,
  Play,
  Plus,
  Stop,
  TShirt,
  UserPlus,
} from '@phosphor-icons/react';
import InstanceArt from '../components/InstanceArt';
import PlayerRender, { type Pose } from '../components/PlayerRender';
import { FriendHead, openSocial, statusLine } from '../components/SocialPane';
import {
  ago,
  api,
  isTauri,
  loaderLabel,
  playtime,
  type Account,
  type Friend,
  type GameState,
  type LaunchTarget,
  type Profile,
  type Progress,
  type RecentPlay,
} from '../lib/api';
import { LEGACY } from '../lib/ui';
import HomeLegacy from './legacy/HomeLegacy';

const sentence = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
/** the friends column refreshes this often while Home is open */
const FRIENDS_POLL_MS = 15_000;
/** instances the side panel lists, most recently played first */
const RECENT_INSTANCES = 4;

type HomeProps = {
  account: Account | null;
  skin: string | null;
  pose: Pose;
  profiles: Profile[];
  selected: Profile | null;
  progress: Progress | null;
  game: GameState | null;
  error: string | null;
  onLaunch: (id: string, to?: LaunchTarget) => void;
  onSelect: (id: string) => void;
  onWardrobe: () => void;
  onManage: () => void;
  onStop: () => void;
  /** REDUCE MOTION: the character stands still */
  still?: boolean;
};

/* Home, after Laby's: your character big on a stage that fills most of the
   window, Play pinned to its bottom edge with a swap button for the
   instance; down the right, your instances and the worlds played last above
   your friends. */
export default function Home(props: HomeProps) {
  if (LEGACY) return <HomeLegacy {...props} />;
  const {
    account,
    skin,
    pose,
    profiles,
    selected,
    progress,
    game,
    error,
    onLaunch,
    onSelect,
    onWardrobe,
    onManage,
    onStop,
    still = false,
  } = props;
  const [menu, setMenu] = useState(false);
  const consoleRef = useRef<HTMLDivElement>(null);
  /* "jump back in": the worlds and servers played last */
  const [recent, setRecent] = useState<RecentPlay[]>([]);
  useEffect(() => {
    void api
      .recentPlays(3)
      .then(setRecent)
      .catch(() => setRecent([]));
  }, [game?.state]);
  // the switcher is a light menu, not a modal: a click anywhere else or
  // Escape closes it
  useEffect(() => {
    if (!menu) return;
    const onDown = (e: PointerEvent) => {
      if (!consoleRef.current?.contains(e.target as Node)) setMenu(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenu(false);
    };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [menu]);
  /* the game in flight, whichever instance it belongs to — the dock is
     about *that* instance while it is up, not the one picked */
  const live = game && game.state !== 'exited' ? game : null;
  const running = live?.state === 'running';
  const liveProfile = live ? (profiles.find((p) => p.id === live.profileId) ?? null) : null;
  const shown = liveProfile ?? selected;
  const pct = progress && progress.total > 0 ? Math.round((progress.done / progress.total) * 100) : 0;

  /* the lists show most recently played first, never-played after by creation */
  const ordered = useMemo(
    () => [...profiles].sort((a, b) => (b.lastPlayed ?? 0) - (a.lastPlayed ?? 0) || b.createdAt - a.createdAt),
    [profiles],
  );

  const kicker = live
    ? running
      ? 'Now playing'
      : live.state === 'stopping'
        ? 'Stopping'
        : 'Starting'
    : shown
      ? shown.lastPlayed
        ? `Last played ${ago(shown.lastPlayed)}`
        : 'Ready for its first run'
      : 'Welcome to Dusk';

  const facts = shown
    ? [
        `${loaderLabel(shown)} ${shown.gameVersion}`,
        shown.modCount > 0 ? `${shown.modCount} mods` : null,
        shown.playSecs > 0 ? `${playtime(shown.playSecs)} played` : null,
      ].filter((f): f is string => f !== null)
    : [];

  return (
    <div className="home">
      <section className="home__main">
        <Stage account={account} skin={skin} pose={pose} paused={running || still} onWardrobe={onWardrobe} />

        {/* the one big action, pinned across the stage's bottom edge the way
            Laby pins Launch under the character */}
        <div className="home__dock">
          <span className="home__tag">
            {running && <span className="home__live-dot" />}
            {kicker}
          </span>
          <div className="home__console" ref={consoleRef}>
            {progress ? (
              <div className="home__progress">
                <div className="home__progress-head">
                  <span className="home__progress-stage">{sentence(progress.stage)}</span>
                  <span className="home__progress-count num">
                    {progress.total > 0 ? `${progress.done} / ${progress.total} · ${pct}%` : 'Preparing…'}
                  </span>
                </div>
                <div className="home__bar">
                  <div className="home__bar-fill" style={{ width: `${pct}%` }} />
                </div>
              </div>
            ) : (
              <button
                className={['home__play', live ? 'is-stop' : ''].join(' ')}
                // `starting` with no progress box is the gap between the spawn
                // and the first event — nothing sensible to click yet; with no
                // instance at all the button does what its label says
                disabled={live ? !running : false}
                onClick={() => (live ? running && onStop() : selected ? onLaunch(selected.id) : onManage())}
                title={shown?.name}
              >
                {shown && !live ? (
                  <InstanceArt profile={shown} className="home__play-art" />
                ) : (
                  <span className="home__play-icon">
                    {live ? running && <Stop size={20} weight="fill" /> : <Plus size={20} weight="bold" />}
                  </span>
                )}
                <span className="home__play-text">
                  <span className="home__play-label">
                    {live
                      ? running
                        ? 'Stop'
                        : live.state === 'stopping'
                          ? 'Stopping…'
                          : 'Starting…'
                      : selected
                        ? 'Play'
                        : 'Create instance'}
                  </span>
                  <span className="home__play-sub">
                    {shown ? shown.name : 'A game version with its own mods and worlds'}
                  </span>
                </span>
                {!live && selected && <Play className="home__play-glyph" size={22} weight="fill" />}
              </button>
            )}

            {profiles.length > 0 && (
              <button
                className={['home__switch', menu ? 'is-open' : ''].join(' ')}
                onClick={() => setMenu((v) => !v)}
                aria-haspopup="listbox"
                aria-expanded={menu}
                title="Change instance"
                aria-label="Change instance"
              >
                <ArrowsLeftRight size={22} weight="bold" />
              </button>
            )}

            {menu && (
              <div className="home__menu" role="listbox" aria-label="Instances">
                <div className="home__menu-head">
                  <span>Your instances</span>
                  <span className="num">{profiles.length}</span>
                </div>
                <div className="home__menu-list scroll">
                  {ordered.map((p) => (
                    <InstanceRow
                      key={p.id}
                      profile={p}
                      current={p.id === selected?.id}
                      running={p.id === live?.profileId}
                      canPlay={!live}
                      onPick={() => {
                        onSelect(p.id);
                        setMenu(false);
                      }}
                      onPlay={() => {
                        setMenu(false);
                        onLaunch(p.id);
                      }}
                    />
                  ))}
                </div>
                <button
                  className="home__menu-foot"
                  onClick={() => {
                    setMenu(false);
                    onManage();
                  }}
                >
                  Manage instances
                  <ArrowRight size={14} weight="bold" />
                </button>
              </div>
            )}
          </div>
          {facts.length > 0 && !progress && (
            <div className="home__facts">
              {facts.map((f) => (
                <span key={f} className="home__fact">
                  {f}
                </span>
              ))}
            </div>
          )}
          {error && <p className="home__error">{error}</p>}
        </div>
      </section>

      <aside className="home__side">
        <section className="home__panel home__recent" aria-label="Instances">
          <div className="home__panel-head">
            <span className="home__panel-title">Instances</span>
            <button className="home__panel-link" onClick={onManage}>
              Show all
              <ArrowRight size={13} weight="bold" />
            </button>
          </div>
          <div className="home__recent-list scroll">
            {ordered.length === 0 ? (
              <p className="home__friends-empty">No instances yet. Make one and it shows up here.</p>
            ) : (
              ordered.slice(0, RECENT_INSTANCES).map((p) => (
                <InstanceRow
                  key={p.id}
                  profile={p}
                  current={p.id === selected?.id}
                  running={p.id === live?.profileId}
                  canPlay={!live}
                  onPick={() => onSelect(p.id)}
                  onPlay={() => onLaunch(p.id)}
                />
              ))
            )}
            {recent.length > 0 && (
              <>
                <span className="home__friends-group">Jump back in</span>
                {recent.map((r) => (
                  <button
                    key={`${r.profileId}|${r.kind}|${r.id}`}
                    className="home__jump"
                    disabled={live !== null}
                    title={
                      live
                        ? 'A game is already running'
                        : `${r.kind === 'server' ? `Join ${r.id}` : `Open ${r.name}`} in ${r.profileName}`
                    }
                    onClick={() => onLaunch(r.profileId, r.kind === 'server' ? { server: r.id } : { world: r.id })}
                  >
                    <span className="home__jump-icon">
                      {r.kind === 'server' ? <Globe size={18} weight="bold" /> : <Cube size={18} weight="bold" />}
                    </span>
                    <span className="home__menu-text">
                      <span className="home__menu-name">{r.name}</span>
                      <span className="home__menu-meta">
                        {r.kind === 'server' ? 'Server' : 'World'} · {r.profileName}
                      </span>
                    </span>
                    <span className="home__jump-go">
                      <Play size={13} weight="fill" />
                    </span>
                  </button>
                ))}
              </>
            )}
          </div>
        </section>
        <FriendsColumn account={account} />
      </aside>
    </div>
  );
}

/** one instance in the switcher or the Instances panel: pick it, or play it
    straight away from the button that shows on hover */
function InstanceRow({
  profile: p,
  current,
  running,
  canPlay,
  onPick,
  onPlay,
}: {
  profile: Profile;
  current: boolean;
  running: boolean;
  canPlay: boolean;
  onPick: () => void;
  onPlay: () => void;
}) {
  return (
    <div className={['home__menu-row', current ? 'is-current' : ''].join(' ')}>
      <button className="home__menu-pick" role="option" aria-selected={current} onClick={onPick}>
        <InstanceArt profile={p} className="home__menu-art" />
        <span className="home__menu-text">
          <span className="home__menu-name">{p.name}</span>
          <span className="home__menu-meta">
            {loaderLabel(p)} {p.gameVersion}
            {' · '}
            {running ? (
              <span className="is-live">Running</span>
            ) : p.lastPlayed ? (
              `played ${ago(p.lastPlayed)}`
            ) : (
              'never played'
            )}
          </span>
        </span>
        {current && <Check className="home__menu-check" size={16} weight="bold" />}
      </button>
      {canPlay && (
        <button className="home__menu-play" title={`Play ${p.name}`} aria-label={`Play ${p.name}`} onClick={onPlay}>
          <Play size={13} weight="fill" />
        </button>
      )}
    </div>
  );
}

/** the stage: your character big in the middle of a lit room, your name and
    the wardrobe across the top; click the character for the wardrobe */
function Stage({
  account,
  skin,
  pose,
  paused,
  onWardrobe,
}: {
  account: Account | null;
  skin: string | null;
  pose: Pose;
  paused: boolean;
  onWardrobe: () => void;
}) {
  /* true while the pointer is over the character itself (ray-tested), not
     the canvas box — drives the outline and gates the click */
  const [hit, setHit] = useState(false);
  const name = account?.username ?? 'Not signed in';
  const sub = !account ? 'Sign in to save your look' : account.offline ? 'Offline account' : 'Microsoft account';
  return (
    <div className="home__you" aria-label="Your character">
      <div className="home__you-head">
        <span className="home__you-name">{name}</span>
        <span className="home__you-sub">{sub}</span>
      </div>
      <button className="home__you-btn" onClick={onWardrobe}>
        <TShirt size={15} weight="bold" />
        Wardrobe
      </button>
      <button
        className={['home__stage', hit ? 'is-hit' : ''].join(' ')}
        onClick={() => hit && onWardrobe()}
        tabIndex={-1}
        aria-hidden="true"
      >
        {/* the canvas sizes itself off this box — it must never be the flex
            child that decides the card's height, or it feeds itself */}
        <span className="home__stage-box">
          <PlayerRender
            skin={skin}
            model={account?.skinVariant || 'auto'}
            pose={pose}
            zoom={0.74}
            className="home__canvas"
            paused={paused}
            onHit={setHit}
          />
        </span>
      </button>
    </div>
  );
}

/** who's online, what they're playing, and who's written; a row opens their
    chat in the friends drawer */
function FriendsColumn({ account }: { account: Account | null }) {
  // the browser preview has mocks for every call, so it walks as signed in
  const signedIn = !isTauri || !!account?.authenticated || !!account?.offline;
  const [friends, setFriends] = useState<Friend[] | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!signedIn) return;
    let alive = true;
    const load = () =>
      void api
        .listFriends()
        .then((f) => {
          if (!alive) return;
          setFriends(f);
          setFailed(false);
        })
        .catch(() => alive && setFailed(true));
    load();
    const id = setInterval(load, FRIENDS_POLL_MS);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, [signedIn]);

  const online = friends?.filter((f) => f.online) ?? [];
  const offline = friends?.filter((f) => !f.online) ?? [];

  const row = (f: Friend) => {
    const s = statusLine(f);
    return (
      <button key={f.uuid} className="home__friend" onClick={() => openSocial({ uuid: f.uuid })} title={`Chat with ${f.username}`}>
        <FriendHead uuid={f.uuid} online={f.online} size={34} />
        <span className="home__friend-text">
          <span className="home__friend-name">{f.username}</span>
          <span className={['home__friend-status', s.online ? 'is-online' : ''].join(' ')}>{s.text}</span>
        </span>
        {f.unread > 0 ? (
          <span className="home__friend-unread num" title={`${f.unread} unread`}>
            {f.unread > 99 ? '99+' : f.unread}
          </span>
        ) : (
          <ChatCircle className="home__friend-chat" size={16} weight="bold" />
        )}
      </button>
    );
  };

  return (
    <aside className="home__panel home__friends" aria-label="Friends">
      <div className="home__panel-head">
        <span className="home__panel-title">Friends</span>
        {signedIn && friends && (
          <span className="home__friends-count">
            <span className="num">{online.length}</span> online
          </span>
        )}
        {signedIn && (
          <button className="home__friends-add" onClick={() => openSocial({ add: true })} title="Add a friend" aria-label="Add a friend">
            <UserPlus size={16} weight="bold" />
          </button>
        )}
      </div>

      <div className="home__friends-list scroll">
        {!signedIn ? (
          <p className="home__friends-empty">Sign in to see your friends, what they're playing, and to chat.</p>
        ) : friends === null ? (
          failed ? (
            <p className="home__friends-empty">Friends show up here when you're online.</p>
          ) : (
            <div className="home__friends-skeleton" aria-hidden="true">
              <span />
              <span />
              <span />
            </div>
          )
        ) : friends.length === 0 ? (
          <div className="home__friends-empty">
            <p>No friends yet. Add someone by their Minecraft name to chat and join their servers.</p>
            <button className="home__friends-cta" onClick={() => openSocial({ add: true })}>
              <UserPlus size={15} weight="bold" />
              Add a friend
            </button>
          </div>
        ) : (
          <>
            {online.length > 0 && <span className="home__friends-group">Online</span>}
            {online.map(row)}
            {offline.length > 0 && <span className="home__friends-group">Offline</span>}
            {offline.map(row)}
          </>
        )}
      </div>
    </aside>
  );
}
