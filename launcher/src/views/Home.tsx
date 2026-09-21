import { useEffect, useRef, useState } from 'react';
import PlayerRender, { type Pose } from '../components/PlayerRender';
import ListGlyph from '../components/px/ListGlyph';
import PixelArrow from '../components/px/PixelArrow';
import { NavLabel, PxBox, PxButton, TT } from '../components/px/Px';
import { ago, loaderLabel, type Account, type GameState, type Profile, type Progress } from '../lib/api';

export default function Home({
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
}: {
  account: Account | null;
  skin: string | null;
  pose: Pose;
  profiles: Profile[];
  selected: Profile | null;
  progress: Progress | null;
  game: GameState | null;
  error: string | null;
  onLaunch: (id: string) => void;
  onSelect: (id: string) => void;
  onWardrobe: () => void;
  onManage: () => void;
  onStop: () => void;
}) {
  const [popout, setPopout] = useState(false);
  /* true while the pointer is over the character itself (ray-tested), not
     the canvas box — drives the outline and gates the wardrobe click */
  const [hit, setHit] = useState(false);
  const consoleRef = useRef<HTMLDivElement>(null);
  // the popout is a light dropdown, not a modal: a click anywhere else or
  // Escape closes it
  useEffect(() => {
    if (!popout) return;
    const onDown = (e: PointerEvent) => {
      if (!consoleRef.current?.contains(e.target as Node)) setPopout(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setPopout(false);
    };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [popout]);
  const running = game?.state === 'running';
  const pct = progress && progress.total > 0 ? Math.round((progress.done / progress.total) * 100) : 0;

  return (
    <div className="home">
      <div className="home__stage">
        {/* Figma 28:145 — a plain half-black plate, no construction; the
            name is set as-is, not upper-cased */}
        <div className="home__tag">{account?.username ?? 'not signed in'}</div>

        <button
          className={['home__player', hit ? 'is-hit' : ''].join(' ')}
          onClick={() => hit && onWardrobe()}
          title="Open the wardrobe"
        >
          {/* the canvas sizes itself off this box — it must never be the flex
              child that decides the column's height, or it feeds itself */}
          <span className="home__stage-box">
            <PlayerRender
              skin={skin}
              model={account?.skinVariant || 'auto'}
              pose={pose}
              zoom={0.92}
              className="home__canvas"
              paused={running}
              onHit={setHit}
            />
          </span>
          <span className="home__wardrobe">click for wardrobe</span>
        </button>
      </div>

      <div className="home__console-wrap" ref={consoleRef}>
        {progress ? (
          <PxBox family="accent" height="xl" className="home__progress">
            <div className="home__progress-head">
              <TT size={20} tone="accent">
                {progress.stage.toUpperCase()}
              </TT>
              <TT size={16} tone="sub">{`${pct}%`}</TT>
            </div>
            <div className="home__bar">
              <div className="home__bar-fill" style={{ width: `${pct}%` }} />
            </div>
            <span className="meta">
              {progress.total > 0
                ? `${progress.done} / ${progress.total} files`
                : 'preparing…'}
            </span>
          </PxBox>
        ) : (
          <div className="home__console">
            <PxButton
              family="accent"
              height="xl"
              className="home__play"
              disabled={!selected}
              onClick={() => (running ? onStop() : selected && onLaunch(selected.id))}
            >
              {!running && <PixelArrow />}
              <span className="home__play-text">
                <TT size={22} tone="accent" sx={1.15}>
                  {running ? 'STOP GAME' : 'PLAY NOW'}
                </TT>
                <span className="home__play-sub">{selected ? selected.name : 'NO INSTANCE — CREATE ONE'}</span>
              </span>
            </PxButton>

            <PxButton
              family="grey"
              height="xl"
              className={popout ? 'home__quick is-open' : 'home__quick'}
              onClick={() => setPopout((v) => !v)}
              title="Pick an instance"
              aria-expanded={popout}
            >
              <ListGlyph className="home__quick-glyph" />
            </PxButton>
          </div>
        )}

        {popout && (
          <div className="win px--window home__popout" role="listbox" aria-label="Instances">
            <div className="win__bar">
              <NavLabel label="INSTANCES" />
              <div className="win__fill" />
            </div>
            <div className="home__popout-list scroll">
              {profiles.map((p) => {
                const current = p.id === selected?.id;
                return (
                  <PxButton
                    key={p.id}
                    family={current ? 'accent' : 'grey'}
                    height="listing"
                    className="home__popout-row"
                    role="option"
                    aria-selected={current}
                    onClick={() => {
                      onSelect(p.id);
                      setPopout(false);
                    }}
                  >
                    <span className="home__popout-name">
                      <TT size={16} tone={current ? 'accent' : undefined}>
                        {p.name}
                      </TT>
                      <span className="meta">
                        {loaderLabel(p)} · {p.gameVersion} · {ago(p.lastPlayed)}
                      </span>
                    </span>
                    {current && <span className="home__popout-mark" aria-hidden="true" />}
                  </PxButton>
                );
              })}
              {profiles.length === 0 && (
                <PxBox family="panel" className="home__popout-empty">
                  <TT size={16} tone="sub">
                    NO INSTANCES YET
                  </TT>
                  <PxButton
                    family="accent"
                    height="md"
                    onClick={() => {
                      setPopout(false);
                      onManage();
                    }}
                  >
                    <TT size={16} tone="accent">
                      CREATE ONE
                    </TT>
                  </PxButton>
                </PxBox>
              )}
            </div>
          </div>
        )}

        {error && (
          <PxBox family="red" height="md" className="home__error">
            <span className="meta">{error}</span>
          </PxBox>
        )}
      </div>
    </div>
  );
}
