import { useEffect, useState } from 'react';
import SceneBackground from './background/SceneBackground';
import { PixelIcon, type IconName } from './components/PixelIcon';
import { Toaster } from './components/ui';
import AccountsModal from './views/AccountsModal';
import PvpWipe from './components/PvpWipe';
import Onboarding from './views/Onboarding';
import Home from './views/Home';
import Instances from './views/Instances';
import Settings from './views/Settings';
import Skins from './views/Skins';
import { useUi, type View } from './stores/ui';
import { useSettings } from './stores/settings';
import { useProfiles } from './stores/profiles';
import { useAccount } from './stores/account';
import { useSkins } from './stores/skins';
import { useLaunch } from './stores/launch';
import { isTauri } from './lib/tauri';
import { playSfx } from './sfx/sfx';

const TABS: { view: View; label: string; icon: IconName }[] = [
  { view: 'home', label: 'HOME', icon: 'home' },
  { view: 'instances', label: 'INSTANCES', icon: 'bolt' },
  { view: 'skins', label: 'COSMETICS', icon: 'shirt' },
  { view: 'settings', label: 'SETTINGS', icon: 'gear' },
];

const VIEWS: Record<View, () => JSX.Element> = {
  home: Home,
  instances: Instances,
  settings: Settings,
  skins: Skins,
};

function Nav() {
  const { view, setView, setAccountsOpen } = useUi();
  const account = useAccount((s) => s.account);

  async function winAction(action: 'min' | 'max' | 'close') {
    if (!isTauri) return;
    const { getCurrentWindow } = await import('@tauri-apps/api/window');
    const w = getCurrentWindow();
    if (action === 'min') void w.minimize();
    else if (action === 'max') void w.toggleMaximize();
    else void w.close();
  }

  return (
    <header className="nav" data-tauri-drag-region>
      <div className="nav__brand">
        <PixelIcon name="sparkle" size={30} className="nav__logo" />
        <span className="nav__word">
          DUSK<span>LAUNCHER</span>
        </span>
      </div>

      <nav className="nav__tabs">
        {TABS.map((t) => (
          <button
            key={t.view}
            className={`ptab ${view === t.view ? 'ptab--active' : ''}`}
            onClick={() => {
              setView(t.view);
              playSfx('click');
            }}
          >
            <span className="dawn-text">{t.label}</span>
          </button>
        ))}
      </nav>

      <div className="nav__right">
        <PvpButton />
        <button
          className="acct-pill dawn-frame--plain"
          title={account?.authenticated ? 'Account' : 'Accounts — not signed in'}
          onClick={() => setAccountsOpen(true)}
        >
          <img
            className="acct-pill__head"
            src="img/head-placeholder.png"
            alt=""
            draggable={false}
          />
          <span className="acct-pill__name dawn-text">{account?.username ?? 'PLAYER'}</span>
        </button>
        <button className="win-btn" title="Minimize" onClick={() => winAction('min')}>
          <PixelIcon name="minus" size={15} />
        </button>
        <button className="win-btn" title="Fullscreen" onClick={() => winAction('max')}>
          <PixelIcon name="fullscreen" size={15} />
        </button>
        <button
          className="win-btn win-btn--close"
          title="Close"
          onClick={() => winAction('close')}
        >
          <PixelIcon name="close" size={15} />
        </button>
      </div>
    </header>
  );
}

/** PVPMODE toggle: nether (red PvP) <-> overworld (gold), always through
 *  the noise-dissolve wipe so the two states feel like modes, not themes. */
function PvpButton() {
  const theme = useSettings((s) => s.settings.theme);
  const startPvpWipe = useUi((s) => s.startPvpWipe);
  const pvp = theme === 'nether';
  return (
    <button
      className={`pvp-btn dawn-frame--cta ${pvp ? 'is-on' : ''}`}
      title={pvp ? 'PVPMODE on — wipe back to Overworld' : 'Wipe to PVPMODE'}
      onClick={() => {
        playSfx('click');
        startPvpWipe(!pvp);
      }}
    >
      <span className="dawn-text--accent">PVPMODE</span>
    </button>
  );
}

/** Floating chrome: no footer bar, no status pill. The launch console is a
 *  small floating tile bottom-left that opens the drawer as an overlay. */
function FloatingChrome() {
  const { drawerOpen, toggleDrawer } = useUi();
  const phase = useLaunch((s) => s.phase);

  return (
    <>
      {drawerOpen && <LogDrawer />}
      <button
        className={`console-fab ${phase !== 'idle' ? 'is-live' : ''}`}
        onClick={toggleDrawer}
        title="Launch console"
      >
        <span className="live-dot" />
        CONSOLE
        <PixelIcon name={drawerOpen ? 'chevronDown' : 'chevronUp'} size={9} />
      </button>
    </>
  );
}

function LogDrawer() {
  const { drawerOpen, toggleDrawer } = useUi();
  const log = useLaunch((s) => s.log);
  const progress = useLaunch((s) => s.progress);

  return (
    <div className={`drawer ${drawerOpen ? 'drawer--open' : ''}`}>
      <div className="drawer__head">
        <PixelIcon name="java" size={11} />
        GAME OUTPUT
        {progress && (
          <span style={{ marginLeft: 'auto', color: 'var(--text-accent)' }}>
            {progress.stage.toUpperCase()} {progress.done}/{progress.total}
          </span>
        )}
        <button className="pbtn pbtn--ghost pbtn--sm" onClick={toggleDrawer}>
          <PixelIcon name="chevronDown" size={9} />
        </button>
      </div>
      <div className="drawer__log">
        {log.length === 0 && <div className="text-3">No output yet — launch a profile.</div>}
        {log.map((l, i) => (
          <div key={i} className={`line ${l.stream === 'err' ? 'err' : ''}`}>
            {l.line}
          </div>
        ))}
      </div>
    </div>
  );
}

export default function App() {
  const view = useUi((s) => s.view);
  const account = useAccount((s) => s.account);
  const accountLoaded = useAccount((s) => s.loaded);
  const [onboardDismissed, setOnboardDismissed] = useState(false);
  const showOnboarding = accountLoaded && !account?.authenticated && !onboardDismissed;

  useEffect(() => {
    void useSettings.getState().load();
    void useProfiles.getState().load();
    void useAccount.getState().load();
    void useSkins.getState().load();
    useLaunch.getState().bindEvents();
  }, []);

  const View = VIEWS[view] ?? Home;

  return (
    <>
      <div className="stage">
        <SceneBackground />
        <PvpWipe />
      </div>
      <div className="app">
        <Nav />
        <main className="view">
          <View />
        </main>
        <FloatingChrome />
      </div>
      <AccountsModal />
      {showOnboarding && <Onboarding onDone={() => setOnboardDismissed(true)} />}
      <Toaster />
    </>
  );
}
