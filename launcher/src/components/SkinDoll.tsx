/**
 * Cheap 2D paper-doll preview: six CSS crops out of the skin sheet, laid out
 * on a 16×32 unit grid. Used in grids where spinning up a WebGL context per
 * tile would be absurd.
 */
import { useEffect, useState } from 'react';
import defaultSkin from '../assets/skins/dusk-knight.png';

type Part = [sx: number, sy: number, sw: number, sh: number, dx: number, dy: number];

//                     sx  sy  sw  sh  dx  dy
const BASE: Part[] = [
  [8, 8, 8, 8, 4, 0], // head
  [20, 20, 8, 12, 4, 8], // body
  [44, 20, 4, 12, 0, 8], // right arm
  [4, 20, 4, 12, 4, 20], // right leg
];
const MODERN: Part[] = [
  [36, 52, 4, 12, 12, 8], // left arm
  [20, 52, 4, 12, 8, 20], // left leg
];
const OVERLAY: Part[] = [
  [40, 8, 8, 8, 4, 0], // hat
  [20, 36, 8, 12, 4, 8], // jacket
  [44, 36, 4, 12, 0, 8], // right sleeve
  [4, 36, 4, 12, 4, 20], // right trouser
];

export default function SkinDoll({
  skin,
  scale = 5,
  className,
}: {
  skin?: string | null;
  scale?: number;
  className?: string;
}) {
  const src = skin || defaultSkin;
  const [legacy, setLegacy] = useState(false);

  useEffect(() => {
    const img = new Image();
    img.onload = () => setLegacy(img.naturalHeight === 32);
    img.src = src;
  }, [src]);

  // a legacy 64×32 sheet has no left-limb regions — mirror the right ones,
  // which is exactly what the game does
  const mirrored: Part[] = [
    [44, 20, 4, 12, 12, 8],
    [4, 20, 4, 12, 8, 20],
  ];
  const parts = [...BASE, ...(legacy ? mirrored : MODERN), ...(legacy ? [] : OVERLAY)];

  return (
    <div className={['doll', className ?? ''].join(' ')} style={{ width: 16 * scale, height: 32 * scale }}>
      {parts.map(([sx, sy, sw, sh, dx, dy], i) => (
        <span
          key={i}
          style={{
            left: dx * scale,
            top: dy * scale,
            width: sw * scale,
            height: sh * scale,
            backgroundImage: `url(${src})`,
            backgroundSize: `${64 * scale}px ${64 * scale}px`,
            backgroundPosition: `-${sx * scale}px -${sy * scale}px`,
          }}
        />
      ))}
    </div>
  );
}
