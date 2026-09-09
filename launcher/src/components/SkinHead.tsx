import { useEffect, useRef, useState } from 'react';

/**
 * Head-only avatar crop of a 64x64 skin: face (8,8) with the hat overlay
 * (40,8) stamped on top, blitted onto an 8x8 canvas and scaled up by CSS
 * (`image-rendering: pixelated` keeps it crisp).
 */
export default function SkinHead({ skinUrl }: { skinUrl: string }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    setFailed(false);
    const cv = ref.current;
    if (!cv) return;
    cv.width = 8;
    cv.height = 8;
    const ctx = cv.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;
    const img = new Image();
    img.onload = () => {
      ctx.clearRect(0, 0, 8, 8);
      ctx.drawImage(img, 8, 8, 8, 8, 0, 0, 8, 8); // face
      ctx.drawImage(img, 40, 8, 8, 8, 0, 0, 8, 8); // hat overlay
    };
    img.onerror = () => setFailed(true);
    img.src = skinUrl;
  }, [skinUrl]);

  if (failed) return <div className="skin-head skin-head--broken" />;
  return <canvas ref={ref} className="skin-head" />;
}
