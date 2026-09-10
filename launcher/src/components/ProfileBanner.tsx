/**
 * Per-instance banner: a tiny pixel landscape drawn once from the profile's
 * `art` seed, at 48×14 cells and upscaled with nearest-neighbour. Deterministic,
 * so an instance always looks like itself. Palette comes from the tokens.
 */
import { useEffect, useRef } from 'react';

const CELLS_X = 48;
const CELLS_Y = 14;

function rng(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export default function ProfileBanner({ seed, className }: { seed: number; className?: string }) {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    canvas.width = CELLS_X;
    canvas.height = CELLS_Y;
    const rand = rng(seed);

    // sky: three flat bands, hue picked from the seed but kept in the
    // dark-panel range so the card never out-shouts its own text
    const hue = Math.floor(rand() * 360);
    const sky = [`hsl(${hue} 22% 20%)`, `hsl(${hue} 20% 16%)`, `hsl(${hue} 18% 13%)`];
    sky.forEach((c, i) => {
      ctx.fillStyle = c;
      ctx.fillRect(0, i * 3, CELLS_X, 3);
    });

    // a sun/moon block
    ctx.fillStyle = `hsl(${(hue + 40) % 360} 60% 62%)`;
    ctx.fillRect(4 + Math.floor(rand() * (CELLS_X - 12)), 1 + Math.floor(rand() * 3), 3, 3);

    // terrain: integer-frequency sines, so the silhouette tiles cleanly
    const a = 1 + Math.floor(rand() * 3);
    const b = 1 + Math.floor(rand() * 4);
    const phase = rand() * Math.PI * 2;
    for (let x = 0; x < CELLS_X; x++) {
      const t = (x / CELLS_X) * Math.PI * 2;
      const h = 4 + Math.round(1.6 * Math.sin(a * t + phase) + 1.2 * Math.sin(b * t));
      ctx.fillStyle = `hsl(${(hue + 150) % 360} 26% 26%)`;
      ctx.fillRect(x, CELLS_Y - h, 1, 1);
      ctx.fillStyle = `hsl(${(hue + 150) % 360} 24% 15%)`;
      ctx.fillRect(x, CELLS_Y - h + 1, 1, h - 1);
    }
  }, [seed]);

  return <canvas ref={ref} className={className} aria-hidden="true" />;
}
