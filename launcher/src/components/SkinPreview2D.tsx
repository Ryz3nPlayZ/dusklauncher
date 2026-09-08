import { useEffect, useRef, useState } from 'react';

/**
 * Cheap 2D paper-doll preview of a 64x64 skin — front faces of head/body/
 * arms/legs blitted onto a 16x32 canvas. Used in skin grids where a live 3D
 * render per card would be wasteful.
 */
export default function SkinPreview2D({ skinUrl }: { skinUrl: string }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setFailed(false);
    const cv = ref.current;
    if (!cv) return;
    cv.width = 16;
    cv.height = 32;
    const ctx = cv.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;
    const img = new Image();
    img.onload = () => {
      ctx.clearRect(0, 0, 16, 32);
      // (sx, sy, w, h, dx, dy) — front faces from the standard 64x64 layout
      const parts: [number, number, number, number, number, number][] = [
        [8, 8, 8, 8, 4, 0], // head
        [44, 20, 4, 12, 0, 8], // right arm
        [20, 20, 8, 12, 4, 8], // body
        [36, 52, 4, 12, 12, 8], // left arm
        [4, 20, 4, 12, 4, 20], // right leg
        [20, 52, 4, 12, 8, 20], // left leg
      ];
      for (const [sx, sy, sw, sh, dx, dy] of parts) {
        ctx.drawImage(img, sx, sy, sw, sh, dx, dy, sw, sh);
      }
    };
    img.onerror = () => setFailed(true);
    img.src = skinUrl;
  }, [skinUrl]);

  if (failed) {
    return <div className="skin2d skin2d--broken" />;
  }
  return <canvas ref={ref} className="skin2d" />;
}
