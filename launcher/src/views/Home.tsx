import { useState } from 'react';
import PlayerRender, { type Pose } from '../components/PlayerRender';
import PixelArrow from '../components/px/PixelArrow';
import { PxBox, PxButton, TT } from '../components/px/Px';
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
  onStop: () => void;
}) {
  const [popout, setPopout] = useState(false);
  const running = game?.state === 'running';
  const pct = progress && progress.total > 0 ? Math.round((progress.done / progress.total) * 100) : 0;

  return (
    <div className="home">
      <div className="home__stage">
        <PxBox family="panel" className="home__tag">
          <TT size={16}>{account?.username ?? 'NOT SIGNED IN'}</TT>
        </PxBox>

        <button className="home__player" onClick={onWardrobe} title="Open the wardrobe">
          {/* the canvas sizes itself off this box — it must never be the flex
              child that decides the column's height, or it feeds itself */}
          <span className="home__stage-box">
            <PlayerRender skin={skin} pose={pose} zoom={0.95} className="home__canvas" paused={running} />
          </span>
          <span className="home__wardrobe">
            <TT size={16} tone="sub">
              CLICK FOR WARDROBE
            </TT>
          </span>
        </button>
      </div>

      <div className="home__console-wrap">
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
              <span className="home__play-line">
                <TT size={36} tone="accent">
                  {running ? 'STOP GAME' : 'PLAY NOW'}
                </TT>
                {!running && <PixelArrow />}
              </span>
              <TT size={16} tone="sub">
                {selected ? selected.name : 'NO INSTANCE — CREATE ONE'}
              </TT>
            </PxButton>

            <PxButton
              family="grey"
              height="xl"
              className="home__quick"
              onClick={() => setPopout((v) => !v)}
              title="Pick an instance"
            >
              <span className="home__quick-bar" />
              <span className="home__quick-bar" />
              <span className="home__quick-bar" />
            </PxButton>
          </div>
        )}

        {popout && (
          <PxBox family="panel" className="px--window home__popout scroll">
            {profiles.map((p) => (
              <PxButton
                key={p.id}
                family={p.id === selected?.id ? 'accent' : 'grey'}
                height="md"
                className="home__popout-row"
                onClick={() => {
                  onSelect(p.id);
                  setPopout(false);
                }}
              >
                <TT size={20}>{p.name}</TT>
                <span className="meta">
                  {loaderLabel(p)} · {p.gameVersion} · {ago(p.lastPlayed)}
                </span>
              </PxButton>
            ))}
            {profiles.length === 0 && (
              <PxBox family="panel" height="md" className="home__popout-row">
                <span className="meta">No instances yet — create one in INSTANCES.</span>
              </PxBox>
            )}
          </PxBox>
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
