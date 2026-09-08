import { useEffect, useRef, useState } from 'react';
import LazySkinViewer from '../player/LazySkinViewer';
import { PixelIcon } from '../components/PixelIcon';
import PixelPedestal from '../components/PixelPedestal';
import { ProgressBar } from '../components/ui';
import { useAccount } from '../stores/account';
import { useProfiles } from '../stores/profiles';
import { useLaunch } from '../stores/launch';
import { useSettings } from '../stores/settings';
import { useSkins } from '../stores/skins';
import { useUi } from '../stores/ui';
import { timeAgo } from '../lib/format';
import { playSfx } from '../sfx/sfx';

const STAGE_LABELS: Record<string, string> = {
  libraries: 'DOWNLOADING LIBRARIES',
  client: 'DOWNLOADING CLIENT',
  assets: 'DOWNLOADING ASSETS',
  java: 'PROVISIONING JAVA',
  mods: 'DOWNLOADING MODS',
  launching: 'LAUNCHING',
};

export default function Home() {
  const account = useAccount((s) => s.account);
  const { profiles, selected, select } = useProfiles();
  const phase = useLaunch((s) => s.phase);
  const progress = useLaunch((s) => s.progress);
  const launch = useLaunch((s) => s.launch);
  const selectedSkin = useSkins((s) => s.selectedSkin);
  const theme = useSettings((s) => s.settings.theme);
  const setView = useUi((s) => s.setView);
  const [popoutOpen, setPopoutOpen] = useState(false);

  const profile = selected();
  const busy = phase === 'preparing' || phase === 'downloading';
  const running = phase === 'running';
  const stop = useLaunch((s) => s.stop);

  return (
    <div className="home">
      <AudioWidget />

      <div className="home__center anim-fade-in">
        <div className="home__nametag font-pixel">{account?.username ?? 'PLAYER'}</div>
        <button
          className="home__anchor home__player-btn"
          onClick={() => setView('skins')}
          title="Open wardrobe"
          aria-label="Click for wardrobe"
        >
          {selectedSkin ? (
            <LazySkinViewer
              skinUrl={selectedSkin}
              className="home__player"
              interactive={false}
              breathe
            />
          ) : (
            <div className="home__player home__player-empty" aria-hidden="true">
              <PixelIcon name="shirt" size={44} className="text-3" />
              <span className="font-pixel">NO SKIN</span>
            </div>
          )}
          {/* dithered contact shadow sits behind the (transparent) player
              canvas, on the pedestal surface, grounding the feet */}
          <div className="home__contact" />
          <PixelPedestal theme={theme} />
          <span className="home__wardrobe-tip font-pixel">
            {selectedSkin ? 'CLICK FOR WARDROBE' : 'OPEN WARDROBE — ADD A SKIN'}
          </span>
        </button>
      </div>

      <div className="home__console home__console--centered">
        {busy && progress ? (
          <div className="home__progress pcard pixel-notch">
            <div className="home__progress-head">
              <PixelIcon name="download" size={13} className="text-accent" />
              <span className="font-pixel">{STAGE_LABELS[progress.stage] ?? progress.stage}</span>
              <span className="mono" style={{ marginLeft: 'auto' }}>
                {Math.round((progress.done / Math.max(1, progress.total)) * 100)}%
              </span>
            </div>
            <ProgressBar value={progress.done / Math.max(1, progress.total)} />
            <div className="home__progress-sub text-3">
              {progress.done}/{progress.total} files
            </div>
          </div>
        ) : running ? (
          <div className="home__cta">
            <div className="home__play is-running" aria-live="polite">
              <PixelIcon name="play" size={22} />
              <span className="home__play-text">
                <span className="font-pixel-bold text-success">ACTIVE</span>
                <span className="home__play-sub font-pixel">
                  {profile ? profile.name : 'GAME RUNNING'}
                </span>
              </span>
            </div>
            <button
              className="home__stop"
              title="Stop game"
              aria-label="Stop game"
              onClick={() => void stop()}
            >
              <PixelIcon name="close" size={14} />
            </button>

            <div className="home__toggle-wrap">
              <button
                className={`home__toggle ${popoutOpen ? 'is-open' : ''}`}
                title="Quick instance selector"
                onClick={() => {
                  setPopoutOpen(!popoutOpen);
                  playSfx('click');
                }}
              >
                <PixelIcon name="list" size={16} />
              </button>
              {popoutOpen && (
                <ProfilePopout
                  profiles={profiles}
                  activeId={profile?.id ?? null}
                  onPick={(id) => {
                    select(id);
                    setPopoutOpen(false);
                  }}
                  onClose={() => setPopoutOpen(false)}
                />
              )}
            </div>
          </div>
        ) : (
          <>
            <div className="home__cta-glow">
              <div className="home__cta">
                <button
                  className="home__play"
                  disabled={!profile}
                  onClick={() => profile && void launch(profile.id)}
                >
                  <PixelIcon name="play" size={22} />
                  <span className="home__play-text">
                    <span className="font-pixel-bold">PLAY NOW</span>
                    <span className="home__play-sub font-pixel">
                      {profile ? profile.name : 'NO INSTANCE'}
                    </span>
                  </span>
                </button>

                <div className="home__toggle-wrap">
                  <button
                    className={`home__toggle ${popoutOpen ? 'is-open' : ''}`}
                    title="Quick instance selector"
                    onClick={() => {
                      setPopoutOpen(!popoutOpen);
                      playSfx('click');
                    }}
                  >
                    <PixelIcon name="list" size={16} />
                  </button>
                  {popoutOpen && (
                    <ProfilePopout
                      profiles={profiles}
                      activeId={profile?.id ?? null}
                      onPick={(id) => {
                        select(id);
                        setPopoutOpen(false);
                      }}
                      onClose={() => setPopoutOpen(false)}
                    />
                  )}
                </div>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function ProfilePopout({
  profiles,
  activeId,
  onPick,
  onClose,
}: {
  profiles: ReturnType<typeof useProfiles.getState>['profiles'];
  activeId: string | null;
  onPick: (id: string) => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const setView = useUi((s) => s.setView);

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [onClose]);

  return (
    <div className="popout anim-pop-in" ref={ref}>
      {profiles.length === 0 && <div className="popout__empty text-3">No instances yet</div>}
      {profiles.map((p) => (
        <button
          key={p.id}
          className={`popout__item ${p.id === activeId ? 'is-active' : ''}`}
          onClick={() => onPick(p.id)}
        >
          <PixelIcon name="bolt" size={14} className={p.id === activeId ? 'text-accent' : 'text-3'} />
          <span className="popout__meta">
            <span className="font-pixel">{p.name}</span>
            <span className="text-3">
              {p.loader} • {p.gameVersion} • {timeAgo(p.lastPlayed ?? p.createdAt)}
            </span>
          </span>
          <span className={`pcheck ${p.id === activeId ? 'is-on' : ''}`}>
            {p.id === activeId && <PixelIcon name="check" size={9} />}
          </span>
        </button>
      ))}
      <button
        className="popout__footer font-pixel"
        onClick={() => {
          onClose();
          setView('instances');
        }}
      >
        MANAGE INSTANCES
      </button>
    </div>
  );
}

function AudioWidget() {
  const { settings, update } = useSettings();
  const pct = Math.round(settings.volume * 100);
  return (
    <div className="home__audio">
      <button
        className="home__audio-btn"
        title={settings.muted ? 'Unmute' : 'Mute'}
        onClick={() => {
          update({ muted: !settings.muted });
          playSfx('click');
        }}
      >
        <PixelIcon name={settings.muted ? 'volumeOff' : 'volume'} size={13} />
      </button>
      <input
        className="prange"
        type="range"
        min={0}
        max={100}
        value={settings.muted ? 0 : pct}
        onChange={(e) => update({ volume: Number(e.target.value) / 100, muted: false })}
      />
      <span className="font-pixel home__audio-pct">{settings.muted ? 'MUTE' : `${pct}%`}</span>
    </div>
  );
}
