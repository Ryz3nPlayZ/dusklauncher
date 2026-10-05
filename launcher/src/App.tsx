import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import SceneBackground from './background/SceneBackground';
import CustomWallpaper from './background/CustomWallpaper';
import Nav from './components/Nav';
import UpdateButton from './components/UpdateButton';
import type { Pose } from './components/PlayerRender';
import {
  api,
  DUSK_PROFILE,
  isTauri,
  listen,
  type Account,
  type CrashInfo,
  type GameActivity,
  type GameState,
  type LaunchTarget,
  type Profile,
  type Progress,
  type Settings,
} from './lib/api';
import { startGameLog } from './lib/gamelog';
import { useUpdater } from './lib/updater';
import type { Route } from './routes';
import Home from './views/Home';
import Instances from './views/Instances';
import Cosmetics from './views/Cosmetics';
import Store from './views/Store';
import Quests from './views/Quests';
import ProfileView from './views/Profile';
import SettingsView from './views/Settings';
import SignInGate from './components/SignIn';
import SocialPane from './components/SocialPane';
import CrashDialog from './components/CrashDialog';

/** The window is undecorated (tauri.conf.json), so the shell owns its chrome. */

export default function App() {
  const [route, setRoute] = useState<Route>('home');
  const [account, setAccount] = useState<Account | null>(null);
  // null until the first account read lands — the gate must not flash
  // before we know whether a session exists
  const [accountKnown, setAccountKnown] = useState(false);
  const [skin, setSkin] = useState<string | null>(null);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [pose, setPose] = useState<Pose>('IDLE');
  /* the store buys for this friend while set (SEND A GIFT in the social pane) */
  const [giftFor, setGiftFor] = useState<{ uuid: string; name: string } | null>(null);
  const [claimable, setClaimable] = useState(0);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [game, setGame] = useState<GameState | null>(null);
  /* where the running game is (server / singleplayer) — INVITE sends it */
  const [activity, setActivity] = useState<GameActivity | null>(null);
  const [error, setError] = useState<string | null>(null);
  /* the last game crash, until OK; the browser preview shows a sample with ?crash */
  const [crash, setCrash] = useState<CrashInfo | null>(() =>
    !isTauri && new URLSearchParams(window.location.search).has('crash') ? PREVIEW_CRASH : null,
  );
  const updater = useUpdater();
  // mirrors `game` for the event handlers, which are registered once
  const gameRef = useRef<GameState | null>(null);
  gameRef.current = game;

  /* PLAY / STOP follows the backend, not the last event we happened to see:
     ask it outright on mount and after every launch attempt, so a missed or
     late event can't strand the button in an install or running state */
  const syncGame = useCallback(async () => {
    try {
      const live = await api.gameState();
      setGame((prev) =>
        live ? (prev?.state === 'stopping' ? prev : live) : prev?.state === 'starting' ? prev : null,
      );
      if (live) setProgress(null);
    } catch (e) {
      console.warn('game state query failed:', e);
    }
  }, []);

  const refreshProfiles = useCallback(async () => setProfiles(await api.listProfiles()), []);
  const refreshAccount = useCallback(async () => {
    setAccount(await api.getAccount());
    setAccountKnown(true);
    setSkin(await api.accountSkin());
  }, []);

  // gifting ends when you leave the store
  useEffect(() => {
    if (route !== 'store') setGiftFor(null);
  }, [route]);

  /* the QUESTS badge: rewards waiting to be claimed, rechecked every few
     minutes and whenever the page changes (the Quests view keeps it live) */
  const signedIn = !isTauri || !!account?.authenticated;
  useEffect(() => {
    if (!signedIn) {
      setClaimable(0);
      return;
    }
    let live = true;
    const check = () =>
      api
        .getQuests()
        .then((q) => live && setClaimable(q.claimable))
        .catch(() => {});
    void check();
    const t = window.setInterval(check, 5 * 60_000);
    return () => {
      live = false;
      window.clearInterval(t);
    };
  }, [signedIn, route]);

  useEffect(() => {
    void refreshProfiles();
    void refreshAccount();
    void syncGame();
    void api.getSettings().then(setSettings);
    void api.gameActivity().then(setActivity).catch(() => {});
  }, [refreshProfiles, refreshAccount, syncGame]);

  /* the same instance NEW INSTANCE → a version card makes: Fabric plus Dusk
     Essentials resolved for the default game version. The bundled Dusk
     Essentials pack is only the offline fallback. */
  const seedDuskInstance = useCallback(async () => {
    const seedDusk = async () => {
      const v = DUSK_PROFILE.defaultGameVersion;
      const p = await api.createProfile(DUSK_PROFILE.instanceName(v), v, 'fabric');
      try {
        await api.installDuskEssentials(p.id);
      } catch (e) {
        // offline: drop the bare instance so the bundled pack takes its place
        await api.deleteProfile(p.id).catch(() => {});
        throw e;
      }
      return p;
    };
    try {
      const p = await seedDusk().catch((e) => {
        console.warn('dusk profile seed failed, using the bundled pack:', e);
        return api.installBundledPack('dusk-essentials');
      });
      setProfiles(await api.listProfiles());
      const s = await api.getSettings();
      if (!s.selectedProfileId) void saveSettings({ ...s, selectedProfileId: p.id });
    } catch (e) {
      console.warn('default pack install failed:', e);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // first run: seed the Dusk instance so the launcher never opens empty.
  // One attempt per install.
  useEffect(() => {
    void (async () => {
      const list = await api.listProfiles();
      if (list.length > 0 || localStorage.getItem('dusk.defaultPackSeeded')) return;
      localStorage.setItem('dusk.defaultPackSeeded', '1');
      await seedDuskInstance();
    })();
  }, [seedDuskInstance]);

  /* the sign-in gate: up while nobody is signed in, until ADD ACCOUNT LATER;
     PLAY without an account brings it back */
  const [gateDismissed, setGateDismissed] = useState(false);
  const canPlay = !!account?.authenticated || !!account?.offline;

  /* redeeming the offline code: the account becomes the offline name, and an
     empty launcher gets its Dusk instance to play */
  const onUnlocked = useCallback(
    async (what: string) => {
      if (what !== 'offline') return;
      await refreshAccount();
      setSettings(await api.getSettings());
      if ((await api.listProfiles()).length === 0) await seedDuskInstance();
    },
    [refreshAccount, seedDuskInstance],
  );

  // theme drives both the accent family and which scene plays behind
  useEffect(() => {
    if (settings) document.documentElement.dataset.theme = settings.theme;
  }, [settings?.theme]);

  useEffect(() => {
    startGameLog();
    const unlisten = [
      // the install bar only makes sense before the process exists; a
      // straggling progress event after `running` must not bring it back
      listen<Progress>('launch-progress', (p) => {
        const g = gameRef.current;
        if (g && g.profileId === p.profileId && g.state === 'running') return;
        setProgress(p);
      }),
      listen<GameState>('game-state', (s) => {
        setGame(s);
        if (s.state !== 'starting') setProgress(null);
      }),
      listen<GameActivity | null>('game-activity', setActivity),
      listen<CrashInfo>('game-crash', setCrash),
    ];
    return () => {
      void Promise.all(unlisten).then((fns) => fns.forEach((f) => f?.()));
    };
  }, []);

  const selected = useMemo(
    () => profiles.find((p) => p.id === settings?.selectedProfileId) ?? profiles[0] ?? null,
    [profiles, settings?.selectedProfileId],
  );

  const saveSettings = useCallback(async (next: Settings) => {
    setSettings(next);
    await api.setSettings(next);
  }, []);

  const selectProfile = useCallback(
    (id: string) => {
      if (settings) void saveSettings({ ...settings, selectedProfileId: id });
    },
    [settings, saveSettings],
  );

  /* `server`: straight onto that multiplayer server (a friend's JOIN, the
     WORLDS tab); `world`: into that singleplayer save (the WORLDS tab);
     `replay`: straight into that clip or replay (WATCH on the media page) */
  const launch = useCallback(
    async (id: string, to?: LaunchTarget) => {
      const { server, world, replay } = to ?? {};
      if (!canPlay) {
        setGateDismissed(false);
        return;
      }
      setError(null);
      setGame({ profileId: id, state: 'starting', code: null });
      setProgress({ profileId: id, stage: 'starting', done: 0, total: 0, doneBytes: 0, totalBytes: 0 });
      try {
        // resolves once the process has spawned — from here on the game is
        // running whatever order the events landed in
        await (replay
          ? api.watchRecording(id, replay)
          : server
            ? api.joinServer(id, server)
            : world
              ? api.playWorld(id, world)
              : api.launch(id));
        setProgress(null);
        setGame({ profileId: id, state: 'running', code: null });
        void refreshProfiles();
      } catch (e) {
        setProgress(null);
        setError(String(e));
        // e.g. "already running": the backend knows what is actually up
        setGame(null);
        void syncGame();
      }
    },
    [refreshProfiles, syncGame, canPlay],
  );

  /* JOIN from the social pane: the selected instance when it runs the
     friend's version (or no version is known), else another instance on
     that version, else the selected one anyway — servers often accept a
     range. Home shows the install / launch progress. */
  const join = useCallback(
    (server: string, version: string | null) => {
      const g = gameRef.current;
      if (g && (g.state === 'running' || g.state === 'starting')) return;
      const target =
        (version && selected?.gameVersion !== version && profiles.find((p) => p.gameVersion === version)) || selected;
      if (!target) {
        setError('Create an instance first, then JOIN.');
        setRoute('home');
        return;
      }
      setRoute('home');
      void launch(target.id, { server });
    },
    [profiles, selected, launch],
  );

  const stop = useCallback(async () => {
    setGame((g) => (g?.state === 'running' ? { ...g, state: 'stopping' } : g));
    try {
      await api.stopGame();
    } catch (e) {
      setGame((g) => (g?.state === 'stopping' ? { ...g, state: 'running' } : g));
      setError(String(e));
    }
    // the supervisor's `exited` event clears the button; this covers a kill
    // that raced it
    void syncGame();
  }, [syncGame]);

  // what friends see beside our name while the game is up
  const playing =
    game?.state === 'running' ? (profiles.find((p) => p.id === game.profileId)?.gameVersion ?? null) : null;

  return (
    <div className="app">
      {settings?.customBackground ? (
        <CustomWallpaper
          name={settings.customBackground}
          scene={settings.theme === 'nether' ? 'mcpvp' : 'dusk'}
          paused={settings.reduceMotion}
        />
      ) : (
        <SceneBackground scene={settings?.theme === 'nether' ? 'mcpvp' : 'dusk'} />
      )}

      <Nav route={route} onRoute={setRoute} account={account} skin={skin} claimable={claimable} />

      <main className={`view${route === 'home' ? '' : ' view--dim'}`}>
        {route === 'home' && (
          <Home
            account={account}
            skin={skin}
            pose={pose}
            profiles={profiles}
            selected={selected}
            progress={progress}
            game={game}
            error={error}
            onLaunch={launch}
            onSelect={selectProfile}
            onWardrobe={() => setRoute('cosmetics')}
            onManage={() => setRoute('instances')}
            onStop={() => void stop()}
          />
        )}
        {route === 'instances' && (
          <Instances
            profiles={profiles}
            selected={selected}
            game={game}
            onRefresh={refreshProfiles}
            onLaunch={launch}
            onSelect={selectProfile}
            onStop={() => void stop()}
            onWatch={(id, path) => {
              setRoute('home');
              void launch(id, { replay: path });
            }}
            clock24h={settings?.clock24h ?? false}
          />
        )}
        {route === 'cosmetics' && (
          <Cosmetics
            account={account}
            pose={pose}
            onPose={setPose}
            onSkinChange={refreshAccount}
            onStore={() => setRoute('store')}
          />
        )}
        {route === 'store' && (
          <Store
            account={account}
            skin={skin}
            pose={pose}
            onPose={setPose}
            onWardrobe={() => setRoute('cosmetics')}
            giftFor={giftFor}
            onEndGift={() => setGiftFor(null)}
          />
        )}
        {route === 'quests' && <Quests account={account} onClaimable={setClaimable} />}
        {route === 'profile' && <ProfileView account={account} skin={skin} onChange={refreshAccount} />}
        {route === 'settings' && settings && (
          <SettingsView
            settings={settings}
            onSave={saveSettings}
            offline={!!account?.offline}
            onUnlocked={(w) => void onUnlocked(w)}
          />
        )}
      </main>

      {crash && (
        <CrashDialog
          crash={crash}
          instance={profiles.find((p) => p.id === crash.profileId)?.name ?? null}
          onClose={() => setCrash(null)}
        />
      )}

      {accountKnown && !canPlay && !gateDismissed && (
        <SignInGate onDone={refreshAccount} onLater={() => setGateDismissed(true)} />
      )}

      <div className="status-bar">
        <UpdateButton status={updater.status} onInstall={() => void updater.install()} onRestart={() => void updater.restart()} />
        <SocialPane
          // a different account starts from a clean pane — no stale friends or chat
          key={account?.uuid ?? 'signed-out'}
          account={account}
          gameRunning={!!game && game.state !== 'exited'}
          playing={playing}
          activity={activity}
          prefs={{
            clock24h: settings?.clock24h ?? false,
            warnOnLinks: settings?.warnOnLinks ?? true,
            notifyFriendsOnline: settings?.notifyFriendsOnline ?? true,
            notifyMessages: settings?.notifyMessages ?? true,
          }}
          onJoin={join}
          onGift={(uuid, name) => {
            setGiftFor({ uuid, name });
            setRoute('store');
          }}
          isTauri={isTauri}
        />
      </div>
    </div>
  );
}

const PREVIEW_CRASH: CrashInfo = {
  profileId: 'p-dusk',
  code: 1,
  title: "Some mods don't work together",
  advice: ['Install fabric-api, any version.', "Replace 'Iris' (iris) 1.6.4 with any version that is compatible with 1.21.11."],
  report: null,
  details: 'Incompatible mods found!',
};
