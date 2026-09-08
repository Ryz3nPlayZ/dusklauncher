import { useEffect, useRef } from 'react';
import { hash32, mulberry32 } from '../lib/format';

/**
 * Deterministic pixel banner for profile cards — a seeded block pattern in
 * the theme's accent family. No assets, unique per profile id.
 */
export default function ProfileBanner({
  seed,
  height = 44,
  className,
}: {
  seed: number;
  height?: number;
  className?: string;
}) {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const cv = ref.current;
    if (!cv) return;
    const W = 64; // internal resolution; CSS upscales with pixelated
    const H = Math.round((height / 320) * W * 0.45);
    cv.width = W;
    cv.height = H;
    const ctx = cv.getContext('2d')!;

    const rng = mulberry32(seed || 1);
    const dark = '#160710';
    const tones = ['#2b0d14', '#43101c', '#6f0d26', '#a3123a'];
    ctx.fillStyle = dark;
    ctx.fillRect(0, 0, W, H);
    // stacked strata
    for (let y = 0; y < H; y++) {
      const t = y / H;
      const tone = tones[Math.min(tones.length - 1, Math.floor(t * tones.length + rng() * 0.8))];
      ctx.fillStyle = tone;
      ctx.fillRect(0, y, W, 1);
      // jagged right-edge variation per stratum
      const cuts = 2 + Math.floor(rng() * 4);
      for (let c = 0; c < cuts; c++) {
        const x = Math.floor(rng() * W);
        const w = 1 + Math.floor(rng() * 3);
        ctx.fillStyle = rng() < 0.5 ? dark : tones[Math.max(0, tones.indexOf(tone) - 1)];
        ctx.fillRect(x, y, w, 1);
      }
    }
    // ember specks
    ctx.fillStyle = '#ff1e43';
    for (let i = 0; i < 10; i++) {
      ctx.globalAlpha = 0.35 + rng() * 0.6;
      ctx.fillRect(Math.floor(rng() * W), Math.floor(rng() * H), 1, 1);
    }
    ctx.globalAlpha = 1;
  }, [seed, height]);

  return <canvas ref={ref} className={className} />;
}
