/**
 * A cape's outer face as a flat pixel swatch (the wardrobe grid). Pure 2D
 * canvas — no three.js per tile. Animated strips (MinecraftCapes rule:
 * height ≠ width/2 → one frame per width/2 rows) cycle at the registry's
 * speed (100 ms unless the entry says otherwise).
 */
import { useEffect, useRef } from 'react';

export default function CapeSwatch({
  src,
  scale = 6,
  frameMs = 100,
}: {
  src?: string | null;
  scale?: number;
  frameMs?: number;
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
        const s = img.naturalWidth / 64; // the sheet's pixels per cape unit
        const frameH = img.naturalHeight === img.naturalWidth / 2 ? img.naturalHeight : img.naturalWidth / 2;
        const frames = Math.max(1, Math.floor(img.naturalHeight / frameH));
        canvas.width = 10 * scale;
        canvas.height = 16 * scale;
        const g = canvas.getContext('2d')!;
        g.imageSmoothingEnabled = false;
        const draw = (f: number) => {
          g.clearRect(0, 0, canvas.width, canvas.height);
          // the outer face sits at (1,1)–(11,17) of a 64×32 sheet
          g.drawImage(img, s, f * frameH + s, 10 * s, 16 * s, 0, 0, canvas.width, canvas.height);
        };
        draw(0);
        if (frames > 1) {
          let i = 0;
          timer = window.setInterval(() => draw((i = (i + 1) % frames)), Math.max(20, frameMs));
        }
      })
      .catch(() => {});
    return () => {
      cancelled = true;
      if (timer !== null) window.clearInterval(timer);
    };
  }, [src, scale, frameMs]);

  return <canvas ref={ref} className="cape-swatch" width={10 * scale} height={16 * scale} />;
}
