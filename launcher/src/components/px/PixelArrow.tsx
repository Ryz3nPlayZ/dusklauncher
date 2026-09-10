/**
 * The CTA arrow (spec §2): a 28×36 triangle in the accent two-tone, drawn as
 * crisp-edged SVG so its diagonal steps like the rest of the UI instead of
 * being anti-aliased by the browser.
 */
export default function PixelArrow({ height = 36 }: { height?: number }) {
  const w = Math.round((height * 28) / 36);
  return (
    <svg
      width={w}
      height={height}
      viewBox="0 0 28 36"
      shapeRendering="crispEdges"
      aria-hidden="true"
      style={{ flex: 'none' }}
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
