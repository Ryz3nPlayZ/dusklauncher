import './scene.css';
import { convertFileSrc } from '@tauri-apps/api/core';
import { ThemeName } from '../lib/tauri';
import { isTauri } from '../lib/tauri';
import { useSettings } from '../stores/settings';
import { useLaunch } from '../stores/launch';

import dawnRear from '../assets/background/dawn/rear.png';
import dawnSea from '../assets/background/dawn/sea-anim.webp';
import dawnForeground from '../assets/background/dawn/foreground.png';
import dawnFlower from '../assets/background/dawn/flower-anim.webp';
import mcpvpRear from '../assets/background/mcpvp/rear.png';
import mcpvpSea from '../assets/background/mcpvp/sea-anim.webp';
import mcpvpForeground from '../assets/background/mcpvp/foreground.png';
import bee1 from '../assets/background/mobs/bee1.webp';
import bee2 from '../assets/background/mobs/bee2.webp';
import ghastBig from '../assets/background/mobs/ghast-big.webp';
import ghastSmall from '../assets/background/mobs/ghast-small.webp';

/**
 * Scene background — layered pixel art (400×280 native), extracted from
 * dawn.gg's landing page (local placeholder art). Each scene stacks its
 * layers bottom→top with cover/bottom anchoring; the water and flower layers
 * are pre-baked animated WebPs that loop inside a plain <img>. Both scenes
 * stay mounted and crossfade via opacity when the theme changes.
 */

type SceneName = 'dawn' | 'mcpvp';

interface Mob {
  src: string;
  /** site-original sprite size (px) — rendered at size × MOB_SCALE */
  size: number;
  top: string;
  opacity: number;
  /** full left→right crossing time (s); negative delay desyncs instances */
  drift: number;
  delay: number;
  /** vertical bob period (s) and amplitude (px) */
  bob: number;
  amp: number;
  flip?: boolean;
}

/** dawn.gg renders these at 1.0; we tune them down to taste. */
const MOB_SCALE = 0.45;

const BEES: Mob[] = [
  { src: bee1, size: 168, top: '26%', opacity: 1, drift: 38, delay: 0, bob: 6.33, amp: 18 },
  { src: bee2, size: 144, top: '48%', opacity: 1, drift: 46, delay: -8, bob: 7.67, amp: 14, flip: true },
  { src: bee1, size: 120, top: '18%', opacity: 0.75, drift: 32, delay: -18, bob: 5.33, amp: 12 },
];

const GHASTS: Mob[] = [
  { src: ghastBig, size: 288, top: '12%', opacity: 1, drift: 72, delay: 0, bob: 9, amp: 18, flip: true },
  { src: ghastSmall, size: 180, top: '22%', opacity: 0.85, drift: 58, delay: -22, bob: 7.25, amp: 14, flip: true },
  { src: ghastBig, size: 240, top: '32%', opacity: 0.6, drift: 88, delay: -48, bob: 11, amp: 22, flip: true },
  { src: ghastSmall, size: 144, top: '8%', opacity: 0.7, drift: 64, delay: -10, bob: 8, amp: 10, flip: true },
];

interface SceneSpec {
  /** static + animated layers, bottom→top stacking order */
  layers: string[];
  mobs: Mob[];
  mobZ: number;
}

const SCENES: Record<SceneName, SceneSpec> = {
  dawn: {
    layers: [dawnRear, dawnSea, dawnForeground, dawnFlower],
    mobs: BEES,
    mobZ: 4,
  },
  mcpvp: {
    layers: [mcpvpRear, mcpvpSea, mcpvpForeground],
    mobs: GHASTS,
    mobZ: 3,
  },
};

/** Launcher theme → scene. Swap here if the mapping changes. */
const THEME_SCENE: Record<ThemeName, SceneName> = {
  overworld: 'dawn',
  nether: 'mcpvp',
};

function Mobs({ scene }: { scene: SceneName }) {
  const { mobs, mobZ } = SCENES[scene];
  return (
    <>
      {mobs.map((m, i) => (
        <div
          key={i}
          className="scene-bg__mob"
          style={{
            zIndex: mobZ,
            top: m.top,
            opacity: m.opacity,
            animation: `scene-drift ${m.drift}s linear ${m.delay}s infinite`,
          }}
        >
          <div
            className="scene-bg__mob-inner"
            style={{ '--bob': `${m.bob}s`, '--amp': `${m.amp}px` } as React.CSSProperties}
          >
            <img
              src={m.src}
              width={Math.round(m.size * MOB_SCALE)}
              alt=""
              draggable={false}
              style={m.flip ? { transform: 'scaleX(-1)' } : undefined}
            />
          </div>
        </div>
      ))}
    </>
  );
}

/**
 * User-supplied background (image or video). Replaces the procedural scene;
 * the CSS vignette in app.css still applies on top. Videos pause while the
 * game runs or under reduce-motion.
 */
function CustomBackground({ src, paused }: { src: string; paused: boolean }) {
  let url = src;
  if (isTauri && !/^(https?|asset|blob|data):/.test(src)) {
    url = convertFileSrc(src);
  }
  const isVideo = /\.(mp4|webm|mov|m4v)(\?|#|$)/i.test(src);
  return (
    <div className="scene-bg" aria-hidden="true">
      <div className="scene-bg__scene is-active">
        {isVideo ? (
          <video
            className="scene-bg__layer"
            src={url}
            autoPlay
            loop
            muted
            playsInline
            ref={(el) => {
              if (!el) return;
              if (paused) void el.pause();
              else void el.play().catch(() => {});
            }}
          />
        ) : (
          <img className="scene-bg__layer" src={url} alt="" draggable={false} />
        )}
      </div>
    </div>
  );
}
/**
 * Full-viewport scene background. Mobs freeze while the game runs or
 * reduce-motion is on; the animated layers are plain <img> loops the browser
 * controls (no JS frame loop). A user custom background (image/video)
 * replaces the scene entirely when set.
 */
export default function SceneBackground() {
  const theme = useSettings((s) => s.settings.theme);
  const customBackground = useSettings((s) => s.settings.customBackground);
  const reduceMotion = useSettings((s) => s.settings.reduceMotion);
  const phase = useLaunch((s) => s.phase);

  const paused = reduceMotion || phase === 'running';

  if (customBackground) {
    return <CustomBackground src={customBackground} paused={paused} />;
  }

  return (
    <div className={`scene-bg ${paused ? 'scene-bg--paused' : ''}`} aria-hidden="true">
      {(Object.keys(SCENES) as SceneName[]).map((name) => {
        const s = SCENES[name];
        return (
          <div
            key={name}
            className={`scene-bg__scene ${THEME_SCENE[theme] === name ? 'is-active' : ''}`}
          >
            {s.layers.map((src, i) => (
              <img key={i} className="scene-bg__layer" src={src} alt="" draggable={false} />
            ))}
            <Mobs scene={name} />
          </div>
        );
      })}
    </div>
  );
}
