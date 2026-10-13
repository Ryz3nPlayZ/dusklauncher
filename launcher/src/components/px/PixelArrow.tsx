/** The play triangle, sized in em off the label beside it; the legacy look
    draws the gold-over-orange pixel arrow instead. */
import { Play } from '@phosphor-icons/react';
import { LEGACY } from '../../lib/ui';

const STEPS: [number, number][] = [
  [3, 3],
  [6, 6],
  [9, 9],
  [12, 12],
  [9, 15],
  [6, 18],
  [3, 21],
];

export default function PixelArrow({ size }: { size?: string }) {
  if (LEGACY) return <LegacyArrow size={size ?? '1.35em'} />;
  const s = size ?? '1.1em';
  return <Play weight="fill" aria-hidden="true" style={{ width: s, height: s, flex: 'none' }} />;
}

function LegacyArrow({ size }: { size: string }) {
  return (
    <svg
      viewBox="0 0 15 27"
      shapeRendering="crispEdges"
      aria-hidden="true"
      style={{ height: size, width: 'auto', flex: 'none' }}
    >
      <defs>
        {/* in the arrow's own units: every step splits at the arrow's midline, not its own */}
        <linearGradient id="px-arrow" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="27">
          <stop offset="0.5" stopColor="var(--accent-up)" />
          <stop offset="0.5" stopColor="var(--accent-lo)" />
        </linearGradient>
      </defs>
      <rect x="0" y="0" width="3" height="27" fill="url(#px-arrow)" />
      {STEPS.map(([x, y]) => (
        <rect key={`${x},${y}`} x={x} y={y} width="3" height="3" fill="url(#px-arrow)" />
      ))}
    </svg>
  );
}
