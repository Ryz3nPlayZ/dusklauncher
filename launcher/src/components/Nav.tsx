import type { Account } from '../lib/api';
import type { Route } from '../routes';
import PlayerHead from './PlayerHead';
import { cellClass, TT } from './px/Px';

const TABS: { route: Route; label: string }[] = [
  { route: 'home', label: 'HOME' },
  { route: 'instances', label: 'INSTANCES' },
  { route: 'cosmetics', label: 'COSMETICS' },
];

export default function Nav({
  route,
  onRoute,
  account,
  skin,
}: {
  route: Route;
  onRoute: (r: Route) => void;
  account: Account | null;
  skin: string | null;
}) {
  return (
    <header className="nav">
      <div className="px px--cell px--grey px--depth nav__cell nav__brand">
        <span className="nav__word">
          DUSK<b>LAUNCHER</b>
        </span>
      </div>

      {TABS.map((t) => (
        <button
          key={t.route}
          className={cellClass(route === t.route, 'nav__tab')}
          onClick={() => onRoute(t.route)}
        >
          <TT size={20}>{t.label}</TT>
        </button>
      ))}

      <div className="nav__gap" data-tauri-drag-region />

      <button
        className={cellClass(route === 'profile', 'nav__profile')}
        onClick={() => onRoute('profile')}
      >
        <PlayerHead skin={skin} size={40} />
        <TT size={16}>{account?.username ?? 'SIGN IN'}</TT>
      </button>

      <button
        className={['px px--cell px--accent nav__cell', route === 'store' ? 'is-open' : ''].join(' ')}
        onClick={() => onRoute('store')}
      >
        <TT size={20} tone="accent">
          STORE
        </TT>
      </button>

      <button
        className={cellClass(route === 'settings')}
        onClick={() => onRoute('settings')}
      >
        <TT size={20}>SETTINGS</TT>
      </button>
    </header>
  );
}
