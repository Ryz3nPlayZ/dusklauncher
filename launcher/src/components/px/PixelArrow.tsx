/**
 * The play triangle — Figma vector 27:136, a 15×27 pixel arrow on a 3px
 * grid: a full-height bar on the left, seven 3×3 steps out and back. Gold
 * above the midline, orange below, hard split. Sized in em off whatever row
 * it sits in, so it tracks the label beside it through every window size.
 */
const STEPS: [number, number][] = [
  [3, 3],
  [6, 6],
  [9, 9],
  [12, 12],
  [9, 15],
  [6, 18],
  [3, 21],
];

export default function PixelArrow({ size = '1.35em' }: { size?: string }) {
  return (
    <svg
      viewBox="0 0 15 27"
      shapeRendering="crispEdges"
      aria-hidden="true"
      style={{ height: size, width: 'auto', flex: 'none' }}
    >
      <defs>
        <linearGradient id="px-arrow" x1="0" y1="0" x2="0" y2="1">
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
