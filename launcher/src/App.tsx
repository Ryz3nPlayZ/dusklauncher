import { useCallback, useEffect, useMemo, useState } from 'react';
import SceneBackground from './background/SceneBackground';
import CustomWallpaper from './background/CustomWallpaper';
import Nav from './components/Nav';
import { PxBox, TT } from './components/px/Px';
import UpdateButton from './components/UpdateButton';
import type { Pose } from './components/PlayerRender';
import { api, isTauri, listen, type Account, type GameState, type Profile, type Progress, type Settings } from './lib/api';
import { startGameLog } from './lib/gamelog';
import { useUpdater } from './lib/updater';
import type { Route } from './routes';
import Home from './views/Home';
import Instances from './views/Instances';
import Cosmetics from './views/Cosmetics';
import Store from './views/Store';
import ProfileView from './views/Profile';
import SettingsView from './views/Settings';
import SignInGate from './components/SignIn';

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
  const [progress, setProgress] = useState<Progress | null>(null);
  const [game, setGame] = useState<GameState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const updater = useUpdater();

  const refreshProfiles = useCallback(async () => setProfiles(await api.listProfiles()), []);
  const refreshAccount = useCallback(async () => {
    setAccount(await api.getAccount());
    setAccountKnown(true);
    setSkin(await api.accountSkin());
  }, []);

  useEffect(() => {
    void refreshProfiles();
    void refreshAccount();
    void api.getSettings().then(setSettings);
  }, [refreshProfiles, refreshAccount]);

  // first run: seed the bundled default pack (Dusk Essentials) so the
  // launcher never opens empty. One attempt per install — a failed download
  // (offline etc.) just retries on a fresh install or via the store.
  useEffect(() => {
    void (async () => {
      const list = await api.listProfiles();
      if (list.length > 0 || localStorage.getItem('dusk.defaultPackSeeded')) return;
      localStorage.setItem('dusk.defaultPackSeeded', '1');
      try {
        const p = await api.installBundledPack('dusk-essentials');
        setProfiles(await api.listProfiles());
        const s = await api.getSettings();
        if (!s.selectedProfileId) void saveSettings({ ...s, selectedProfileId: p.id });
      } catch (e) {
        console.warn('default pack install failed:', e);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // theme drives both the accent family and which scene plays behind
  useEffect(() => {
    if (settings) document.documentElement.dataset.theme = settings.theme;
  }, [settings?.theme]);

  useEffect(() => {
    startGameLog();
    const unlisten = [
      listen<Progress>('launch-progress', setProgress),
      listen<GameState>('game-state', (s) => {
        setGame(s);
        if (s.state === 'exited') setProgress(null);
      }),
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

  const launch = useCallback(
    async (id: string) => {
      setError(null);
      setProgress({ profileId: id, stage: 'starting', done: 0, total: 0, doneBytes: 0, totalBytes: 0 });
      try {
        await api.launch(id);
        void refreshProfiles();
      } catch (e) {
        setProgress(null);
        setError(String(e));
      }
    },
    [refreshProfiles],
  );

  return (
    <div className="app">
      {settings?.customBackground ? (
        <CustomWallpaper
          name={settings.customBackground}
          scene={settings.theme === 'nether' ? 'mcpvp' : 'dawn'}
          paused={settings.reduceMotion}
        />
      ) : (
        <SceneBackground scene={settings?.theme === 'nether' ? 'mcpvp' : 'dawn'} />
      )}

      <Nav route={route} onRoute={setRoute} account={account} skin={skin} />

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
            onStop={() => void api.stopGame()}
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
            onStop={() => void api.stopGame()}
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
          <Store account={account} skin={skin} pose={pose} onPose={setPose} onWardrobe={() => setRoute('cosmetics')} />
        )}
        {route === 'profile' && <ProfileView account={account} skin={skin} onChange={refreshAccount} />}
        {route === 'settings' && settings && <SettingsView settings={settings} onSave={saveSettings} />}
      </main>

      {accountKnown && !account?.authenticated && <SignInGate onDone={refreshAccount} />}

      <div className="status-bar">
        <UpdateButton status={updater.status} onInstall={() => void updater.install()} onRestart={() => void updater.restart()} />
        <PxBox family="panel" height="sm" className="status-pill">
          <span className={`status-pill__dot ${account?.authenticated ? '' : 'status-pill__dot--off'}`} />
          <TT size={13} tone="dim">
            {game?.state === 'running'
              ? 'GAME RUNNING'
              : account?.authenticated
                ? 'ONLINE'
                : isTauri
                  ? 'OFFLINE — NOT SIGNED IN'
                  : 'BROWSER PREVIEW'}
          </TT>
        </PxBox>

      </div>
    </div>
  );
}
