import './scene.css';

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

export type SceneName = 'dawn' | 'mcpvp';

interface Mob {
  src: string;
  size: number;
  top: string;
  opacity?: number;
  drift: number;
  delay?: number;
  bob: number;
  amp: number;
  flip?: boolean;
}

const MOB_SCALE = 0.45;

const SCENES: Record<SceneName, { layers: string[]; mobs: Mob[] }> = {
  dawn: {
    layers: [dawnRear, dawnSea, dawnForeground, dawnFlower],
    mobs: [
      { src: bee1, size: 168, top: '26%', drift: 38, bob: 6.33, amp: 18 },
      { src: bee2, size: 144, top: '48%', drift: 46, delay: -8, bob: 7.67, amp: 14, flip: true },
      { src: bee1, size: 120, top: '18%', opacity: 0.75, drift: 32, delay: -18, bob: 5.33, amp: 12 },
    ],
  },
  mcpvp: {
    layers: [mcpvpRear, mcpvpSea, mcpvpForeground],
    mobs: [
      { src: ghastBig, size: 288, top: '12%', drift: 72, bob: 9, amp: 18, flip: true },
      { src: ghastSmall, size: 180, top: '22%', opacity: 0.85, drift: 58, delay: -22, bob: 7.25, amp: 14, flip: true },
      { src: ghastBig, size: 240, top: '32%', opacity: 0.6, drift: 88, delay: -48, bob: 11, amp: 22, flip: true },
    ],
  },
};

export default function SceneBackground({ scene = 'dawn' }: { scene?: SceneName }) {
  const { layers, mobs } = SCENES[scene];

  return (
    <div className="scene" aria-hidden="true">
      {layers.map((src, i) => (
        <img key={src + i} className="scene__layer" src={src} alt="" draggable={false} />
      ))}
      {mobs.map((m, i) => (
        <div
          key={i}
          className="scene__mob"
          style={
            {
              top: m.top,
              opacity: m.opacity ?? 1,
              '--drift': `${m.drift}s`,
              '--delay': `${m.delay ?? 0}s`,
            } as React.CSSProperties
          }
        >
          <div className="scene__mob-inner" style={{ '--bob': `${m.bob}s`, '--amp': `${m.amp}px` } as React.CSSProperties}>
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
    </div>
  );
}
