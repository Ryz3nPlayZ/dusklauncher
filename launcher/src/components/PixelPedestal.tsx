/**
 * Pixel ledge the home player stands on — anchors the 3D model to the scene
 * the way Dawn's terrain block does. Original art: a staircase-tapered block
 * (netherrack in the Nether, grass-over-dirt in the Overworld) rendered as
 * crispEdges SVG rects on the same 8px grid. Deterministic per theme.
 */
import type { ReactNode } from 'react';
import { mulberry32 } from '../lib/format';

const U = 8; // screen px per art pixel
const W = 26; // art px wide (208px)
const ROWS = 7; // rim + top face + 4 body rows + dithered shadow row

const THEMES = {
  nether: {
    rim: '#8f4133',
    top: ['#6b2f27', '#7a372c', '#5e2820', '#733228'],
    side: ['#47201b', '#3f1b16', '#522419'],
    speck: '#2c0f0b',
    shadow: 'rgba(0,0,0,0.5)',
  },
  overworld: {
    rim: '#8dbf55',
    top: ['#5c8e32', '#6da343', '#527f2c', '#66993b'],
    side: ['#7a5230', '#6b4626', '#835a35'],
    speck: '#553617',
    shadow: 'rgba(20,40,12,0.5)',
  },
} as const;

export default function PixelPedestal({ theme }: { theme: 'nether' | 'overworld' }) {
  const p = THEMES[theme];
  const rng = mulberry32(theme === 'nether' ? 0x0d05c : 0x0a0c8);
  const pick = (arr: readonly string[]) => arr[Math.floor(rng() * arr.length)];

  const rects: ReactNode[] = [];
  const px = (x: number, y: number, w: number, h: number, fill: string, key: string) =>
    rects.push(<rect key={key} x={x * U} y={y * U} width={w * U} height={h * U} fill={fill} />);

  // row 0: rim highlight in chunky dashes
  for (let x = 0; x < W; x += 2) if (rng() < 0.8) px(x, 0, 2, 1, p.rim, `rim${x}`);
  // row 1: the surface the feet rest on
  for (let x = 0; x < W; x++) px(x, 1, 1, 1, pick(p.top), `top${x}`);
  // rows 2–5: body, tapering inward every two rows (staircase silhouette)
  const insets = [1, 1, 2, 2];
  insets.forEach((ins, r) => {
    for (let x = ins; x < W - ins; x++) {
      px(x, 2 + r, 1, 1, rng() < 0.08 ? p.speck : pick(p.side), `b${r}-${x}`);
    }
  });
  // row 6: dithered contact shadow under the base
  for (let x = 3; x < W - 3; x += 2) px(x, 6, 1, 1, p.shadow, `sh${x}`);

  return (
    <svg
      className="home__pedestal"
      viewBox={`0 0 ${W * U} ${ROWS * U}`}
      width={W * U}
      height={ROWS * U}
      shapeRendering="crispEdges"
      aria-hidden="true"
    >
      {rects}
    </svg>
  );
}
