/**
 * The play triangle (spec §2). Sized in em off whatever row it sits in, so
 * it tracks the label beside it through every window size — a fixed pixel
 * height stopped matching the moment the shell rescaled.
 */
export default function PixelArrow({ size = '0.62em' }: { size?: string }) {
  return (
    <svg
      viewBox="0 0 28 36"
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
      <polygon points="0,0 0,36 28,18" fill="url(#px-arrow)" />
    </svg>
  );
}
