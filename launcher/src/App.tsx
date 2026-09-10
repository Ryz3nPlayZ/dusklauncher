import { useCallback, useEffect, useMemo, useState } from 'react';
import { getCurrentWindow } from '@tauri-apps/api/window';
import SceneBackground from './background/SceneBackground';
import Nav from './components/Nav';
import { PxBox, PxButton, TT } from './components/px/Px';
import PixelGlyph from './components/px/PixelGlyph';
import type { Pose } from './components/PlayerRender';
import { api, isTauri, listen, type Account, type GameState, type Profile, type Progress, type Settings } from './lib/api';
import type { Route } from './routes';
import Home from './views/Home';
import Instances from './views/Instances';
import Cosmetics from './views/Cosmetics';
import Store from './views/Store';
import ProfileView from './views/Profile';
import SettingsView from './views/Settings';

/** The window is undecorated (tauri.conf.json), so the shell owns its chrome. */
const appWindow = () => getCurrentWindow();

export default function App() {
  const [route, setRoute] = useState<Route>('home');
  const [account, setAccount] = useState<Account | null>(null);
  const [skin, setSkin] = useState<string | null>(null);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [pose, setPose] = useState<Pose>('IDLE');
  const [progress, setProgress] = useState<Progress | null>(null);
  const [game, setGame] = useState<GameState | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refreshProfiles = useCallback(async () => setProfiles(await api.listProfiles()), []);
  const refreshAccount = useCallback(async () => {
    setAccount(await api.getAccount());
    setSkin(await api.accountSkin());
  }, []);

  useEffect(() => {
    void refreshProfiles();
    void refreshAccount();
    void api.getSettings().then(setSettings);
  }, [refreshProfiles, refreshAccount]);

  // theme drives both the accent family and which scene plays behind
  useEffect(() => {
    if (settings) document.documentElement.dataset.theme = settings.theme;
  }, [settings?.theme]);

  useEffect(() => {
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
      <SceneBackground scene={settings?.theme === 'nether' ? 'mcpvp' : 'dawn'} />

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
            onStop={() => void api.stopGame()}
          />
        )}
        {route === 'instances' && (
          <Instances profiles={profiles} selected={selected} onRefresh={refreshProfiles} onLaunch={launch} onSelect={selectProfile} />
        )}
        {route === 'cosmetics' && (
          <Cosmetics account={account} pose={pose} onPose={setPose} onSkinChange={refreshAccount} />
        )}
        {route === 'store' && <Store />}
        {route === 'profile' && <ProfileView account={account} skin={skin} onChange={refreshAccount} />}
        {route === 'settings' && settings && <SettingsView settings={settings} onSave={saveSettings} />}
      </main>

      <div className="status-bar">
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

        {isTauri && (
          <div className="win-controls">
            <PxButton family="panel" height="sm" title="Minimize" onClick={() => void appWindow().minimize()}>
              <PixelGlyph glyph="minimize" size={14} color="var(--text-2)" />
            </PxButton>
            <PxButton family="panel" height="sm" title="Maximize" onClick={() => void appWindow().toggleMaximize()}>
              <PixelGlyph glyph="maximize" size={14} color="var(--text-2)" />
            </PxButton>
            <PxButton family="red" height="sm" title="Close" onClick={() => void appWindow().close()}>
              <PixelGlyph glyph="close" size={14} color="var(--r-up)" />
            </PxButton>
          </div>
        )}
      </div>
    </div>
  );
}
