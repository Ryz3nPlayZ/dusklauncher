import type { ReactNode } from 'react';
import type { Account } from '../lib/api';
import type { Route } from '../routes';
import PlayerHead from './PlayerHead';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { isTauri } from '../lib/api';
import { GearSix, Minus, Square, X } from '@phosphor-icons/react';
import mark from '../assets/brand/dusk-mark.png';
import { LEGACY } from '../lib/ui';
import NavLegacy from './legacy/NavLegacy';

const appWindow = () => getCurrentWindow();

/* the pages, in the order you reach for them; the account and settings sit
   apart on the right */
const TABS: { route: Route; label: string }[] = [
  { route: 'home', label: 'Home' },
  { route: 'instances', label: 'Instances' },
  { route: 'cosmetics', label: 'Cosmetics' },
  { route: 'store', label: 'Store' },
  { route: 'quests', label: 'Quests' },
];

export default function Nav({
  route,
  onRoute,
  account,
  skin,
  claimable = 0,
  update,
}: {
  route: Route;
  onRoute: (r: Route) => void;
  account: Account | null;
  skin: string | null;
  /** quest rewards waiting for Claim */
  claimable?: number;
  /** the launcher-update button, when there is one */
  update?: ReactNode;
}) {
  if (LEGACY) return <NavLegacy route={route} onRoute={onRoute} account={account} skin={skin} claimable={claimable} />;
  return (
    <header className="nav" data-tauri-drag-region>
      <div className="nav__brand" data-tauri-drag-region>
        <img className="nav__brand-mark" src={mark} alt="" draggable={false} />
        <span className="nav__brand-name">Dusk</span>
      </div>

      <nav className="nav__tabs" aria-label="Pages">
        {TABS.map((t) => (
          <button
            key={t.route}
            className={['nav__tab', route === t.route ? 'is-active' : ''].join(' ')}
            aria-current={route === t.route ? 'page' : undefined}
            onClick={() => onRoute(t.route)}
            title={
              t.route === 'quests' && claimable > 0
                ? `${claimable} reward${claimable === 1 ? '' : 's'} to claim`
                : undefined
            }
          >
            {t.label}
            {t.route === 'quests' && claimable > 0 && (
              <span className="nav__badge num">{claimable > 99 ? '99+' : claimable}</span>
            )}
          </button>
        ))}
      </nav>

      <div className="nav__gap" data-tauri-drag-region />

      {update}

      {/* SocialPane portals its Friends button in here: it owns the state */}
      <div id="nav-social" className="nav__slot" />

      <button
        className={['nav__profile', route === 'profile' ? 'is-active' : ''].join(' ')}
        onClick={() => onRoute('profile')}
        title={account ? 'Your profile' : 'Sign in'}
      >
        <PlayerHead skin={skin} size={22} />
        <span className="nav__profile-name">{account?.username ?? 'Sign in'}</span>
      </button>

      <button
        className={['nav__icon', route === 'settings' ? 'is-active' : ''].join(' ')}
        onClick={() => onRoute('settings')}
        title="Settings"
        aria-label="Settings"
      >
        <GearSix size={18} weight="bold" />
      </button>

      {/* the window is undecorated, so minimise / maximise / close live here */}
      {isTauri && (
        <div className="nav__win">
          <button className="nav__icon" title="Minimize" aria-label="Minimize" onClick={() => void appWindow().minimize()}>
            <Minus size={16} weight="bold" />
          </button>
          <button className="nav__icon" title="Maximize" aria-label="Maximize" onClick={() => void appWindow().toggleMaximize()}>
            <Square size={14} weight="bold" />
          </button>
          <button className="nav__icon nav__icon--close" title="Close" aria-label="Close" onClick={() => void appWindow().close()}>
            <X size={16} weight="bold" />
          </button>
        </div>
      )}
    </header>
  );
}
