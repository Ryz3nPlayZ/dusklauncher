import './scene.css';

import duskRear from '../assets/background/dusk/rear.png';
import duskSea from '../assets/background/dusk/sea-anim.webp';
import duskForeground from '../assets/background/dusk/foreground.png';
import duskFlower from '../assets/background/dusk/flower-anim.webp';
import duskSeaStill from '../assets/background/dusk/sea-still.png';
import duskFlowerStill from '../assets/background/dusk/flower-still.png';
import mcpvpRear from '../assets/background/mcpvp/rear.png';
import mcpvpSea from '../assets/background/mcpvp/sea-anim.webp';
import mcpvpForeground from '../assets/background/mcpvp/foreground.png';
import mcpvpSeaStill from '../assets/background/mcpvp/sea-still.png';
import bee1 from '../assets/background/mobs/bee1.webp';
import bee2 from '../assets/background/mobs/bee2.webp';
import ghastBig from '../assets/background/mobs/ghast-big.webp';
import ghastSmall from '../assets/background/mobs/ghast-small.webp';

export type SceneName = 'dusk' | 'mcpvp';

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

const SCENES: Record<SceneName, { layers: string[]; still: string[]; mobs: Mob[] }> = {
  dusk: {
    layers: [duskRear, duskSea, duskForeground, duskFlower],
    still: [duskRear, duskSeaStill, duskForeground, duskFlowerStill],
    mobs: [
      { src: bee1, size: 168, top: '26%', drift: 38, bob: 6.33, amp: 18 },
      { src: bee2, size: 144, top: '48%', drift: 46, delay: -8, bob: 7.67, amp: 14, flip: true },
      { src: bee1, size: 120, top: '18%', opacity: 0.75, drift: 32, delay: -18, bob: 5.33, amp: 12 },
    ],
  },
  mcpvp: {
    layers: [mcpvpRear, mcpvpSea, mcpvpForeground],
    still: [mcpvpRear, mcpvpSeaStill, mcpvpForeground],
    mobs: [
      { src: ghastBig, size: 288, top: '12%', drift: 72, bob: 9, amp: 18, flip: true },
      { src: ghastSmall, size: 180, top: '22%', opacity: 0.85, drift: 58, delay: -22, bob: 7.25, amp: 14, flip: true },
      { src: ghastBig, size: 240, top: '32%', opacity: 0.6, drift: 88, delay: -48, bob: 11, amp: 22, flip: true },
    ],
  },
};

/** `still`: the first frame of every layer and no mobs — REDUCE MOTION, SCENE
 *  FPS → STATIC, and whenever the game is running. `fps` 30 moves the mobs
 *  in 30 steps a second instead of every display frame. */
export default function SceneBackground({
  scene = 'dusk',
  still = false,
  fps = 60,
}: {
  scene?: SceneName;
  still?: boolean;
  fps?: number;
}) {
  const { layers, mobs } = SCENES[scene];
  const stepped = fps > 0 && fps < 60;

  return (
    <div className="scene" aria-hidden="true">
      {(still ? SCENES[scene].still : layers).map((src, i) => (
        <img key={src + i} className="scene__layer" src={src} alt="" draggable={false} />
      ))}
      {!still && mobs.map((m, i) => (
        <div
          key={i}
          className="scene__mob"
          style={
            {
              top: m.top,
              opacity: m.opacity ?? 1,
              '--drift': `${m.drift}s`,
              '--delay': `${m.delay ?? 0}s`,
              ...(stepped && { animationTimingFunction: `steps(${Math.round(m.drift * fps)})` }),
            } as React.CSSProperties
          }
        >
          <div
            className="scene__mob-inner"
            style={
              {
                '--bob': `${m.bob}s`,
                '--amp': `${m.amp}px`,
                // half a bob each way, so the steps go per half
                ...(stepped && { animationTimingFunction: `steps(${Math.round((m.bob * fps) / 2)})` }),
              } as React.CSSProperties
            }
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
    </div>
  );
}
