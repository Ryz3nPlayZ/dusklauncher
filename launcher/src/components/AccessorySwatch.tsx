/**
 * An accessory in the wardrobe grid: the whole model rendered as a small
 * three-quarter thumbnail (lib/accessoryThumb). Animated tilesheets cycle
 * at the entry's tick rate.
 */
import { useEffect, useRef } from 'react';
import type { AccessoryEntry, AccessoryModelJson } from '../lib/api';
import { accessoryThumbnails } from '../lib/accessoryThumb';

export default function AccessorySwatch({
  src,
  model,
  entry,
  size = 60,
}: {
  src?: string | null;
  model?: AccessoryModelJson | null;
  entry: AccessoryEntry;
  size?: number;
}) {
  const ref = useRef<HTMLCanvasElement>(null);
  // rendered at twice the display size so thin parts keep their pixels
  const px = size * 2;

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas || !src || !model) return;
    let timer: number | null = null;
    let cancelled = false;
    void accessoryThumbnails(model, entry, src, px)
      .then((frames) => {
        if (cancelled || frames.length === 0) return;
        const g = canvas.getContext('2d')!;
        const draw = (f: number) => {
          g.clearRect(0, 0, px, px);
          g.drawImage(frames[f], 0, 0);
        };
        draw(0);
        if (frames.length > 1) {
          let i = 0;
          timer = window.setInterval(() => draw((i = (i + 1) % frames.length)), Math.max(50, entry.ticksPerFrame * 50));
        }
      })
      .catch((e) => console.error('accessory thumbnail failed', e));
    return () => {
      cancelled = true;
      if (timer !== null) window.clearInterval(timer);
    };
  }, [src, model, entry, px]);

  return <canvas ref={ref} className="cape-swatch" width={px} height={px} style={{ width: size, height: size }} />;
}
