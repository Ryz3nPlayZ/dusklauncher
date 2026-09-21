/**
 * An accessory's front as a flat pixel swatch (the wardrobe grid): the
 * texture region its first element maps to the south (front) face — or the
 * first face it has — blown up with no smoothing. Animated tilesheets cycle
 * at the entry's tick rate.
 */
import { useEffect, useRef } from 'react';
import type { AccessoryEntry, AccessoryModelJson } from '../lib/api';

const FRONT_FIRST = ['south', 'north', 'east', 'west', 'up', 'down'] as const;

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

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas || !src) return;
    let timer: number | null = null;
    let cancelled = false;
    const img = new Image();
    img.src = src;
    void img
      .decode()
      .then(() => {
        if (cancelled) return;
        const frames = entry.frames > 1 && img.naturalHeight % entry.frames === 0 ? entry.frames : 1;
        const fw = img.naturalWidth;
        const fh = img.naturalHeight / frames;
        // the uv rectangle to show, in 0..16 space (whole sheet when unknown)
        let uv: number[] = [0, 0, 16, 16];
        const el = model?.elements?.[0];
        if (el) {
          for (const f of FRONT_FIRST) {
            const face = el.faces?.[f];
            if (face?.uv) {
              uv = face.uv;
              break;
            }
          }
        }
        const x0 = (Math.min(uv[0], uv[2]) / 16) * fw;
        const y0 = (Math.min(uv[1], uv[3]) / 16) * fh;
        const w = Math.max(1, (Math.abs(uv[2] - uv[0]) / 16) * fw);
        const h = Math.max(1, (Math.abs(uv[3] - uv[1]) / 16) * fh);
        const s = Math.min(size / w, size / h);
        canvas.width = Math.round(w * s);
        canvas.height = Math.round(h * s);
        const g = canvas.getContext('2d')!;
        g.imageSmoothingEnabled = false;
        const draw = (f: number) => {
          g.clearRect(0, 0, canvas.width, canvas.height);
          g.drawImage(img, x0, f * fh + y0, w, h, 0, 0, canvas.width, canvas.height);
        };
        draw(0);
        if (frames > 1) {
          let i = 0;
          timer = window.setInterval(() => draw((i = (i + 1) % frames)), Math.max(50, entry.ticksPerFrame * 50));
        }
      })
      .catch(() => {});
    return () => {
      cancelled = true;
      if (timer !== null) window.clearInterval(timer);
    };
  }, [src, model, entry, size]);

  return <canvas ref={ref} className="cape-swatch" width={size} height={size} />;
}
