import type { Account } from '../lib/api';
import type { Route } from '../routes';
import PlayerHead from './PlayerHead';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { isTauri } from '../lib/api';
import { cellClass, NAV_SX, TT } from './px/Px';
import PixelGlyph from './px/PixelGlyph';
import mark from '../assets/brand/dusk-mark.png';

const appWindow = () => getCurrentWindow();

/* Figma 1:3 — the tab cells are fixed plates, not label-sized: HOME 116→218,
   INSTANCES 216→388, COSMETICS 386→561, each sharing its 2px dividers with
   its neighbours. `w` is the band between dividers, in Figma px. */
const TABS: { route: Route; label: string; w: number }[] = [
  { route: 'home', label: 'HOME', w: 98 },
  { route: 'instances', label: 'INSTANCES', w: 168 },
  { route: 'cosmetics', label: 'COSMETICS', w: 171 },
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
      <div className="px px--cell px--grey nav__cell nav__brand" data-tauri-drag-region>
        <img className="nav__brand-mark" src={mark} alt="" draggable={false} />
        <TT size={16} sx={NAV_SX}>
          DUSK
        </TT>
      </div>

      {TABS.map((t) => (
        <button
          key={t.route}
          className={cellClass(route === t.route, 'nav__tab')}
          style={{ width: `calc(${t.w * 1.91} * var(--u))` }}
          onClick={() => onRoute(t.route)}
        >
          <TT size={16} sx={NAV_SX}>
            {t.label}
          </TT>
        </button>
      ))}

      <div className="nav__gap" data-tauri-drag-region />

      <button
        className={cellClass(route === 'profile', 'nav__profile')}
        onClick={() => onRoute('profile')}
      >
        <PlayerHead skin={skin} size={40} />
        <TT size={16} sx={NAV_SX}>
          {account?.username ?? 'SIGN IN'}
        </TT>
      </button>

      <button
        className={['px px--cell px--accent nav__cell', route === 'store' ? 'is-open' : ''].join(' ')}
        onClick={() => onRoute('store')}
      >
        <TT size={16} tone="accent" sx={NAV_SX}>
          STORE
        </TT>
      </button>

      <button
        className={cellClass(route === 'settings')}
        onClick={() => onRoute('settings')}
      >
        <TT size={16} sx={NAV_SX}>
          SETTINGS
        </TT>
      </button>

      {/* the window is undecorated, so minimise / maximise / close close the
          strip on the right — three narrow cells, the last one red */}
      {isTauri && (
        <>
          <button className={cellClass(false, 'nav__win')} title="Minimize" onClick={() => void appWindow().minimize()}>
            <PixelGlyph glyph="minimize" size={14} color="var(--text-2)" />
          </button>
          <button className={cellClass(false, 'nav__win')} title="Maximize" onClick={() => void appWindow().toggleMaximize()}>
            <PixelGlyph glyph="maximize" size={14} color="var(--text-2)" />
          </button>
          <button className="px px--cell px--red nav__cell nav__win nav__win--close" title="Close" onClick={() => void appWindow().close()}>
            <PixelGlyph glyph="close" size={14} color="var(--r-co)" />
          </button>
        </>
      )}
    </header>
  );
}
