/**
 * The instance-picker glyph — Figma 28:232, a 23×14 pixel list: two hollow
 * 6×6 squares (2px ring) down the left, two 2px lines beside each, the top
 * pair gold and the bottom pair orange.
 */
export default function ListGlyph({ className }: { className?: string }) {
  const ring = (y: number, fill: string) => (
    <path
      d={`M0 ${y}h6v6h-6z M2 ${y + 2}h2v2h-2z`}
      fill={fill}
      fillRule="evenodd"
    />
  );
  return (
    <svg
      viewBox="0 0 23 14"
      shapeRendering="crispEdges"
      aria-hidden="true"
      className={className}
      style={{ flex: 'none' }}
    >
      {ring(0, 'var(--accent-up)')}
      <rect x="8" y="0" width="15" height="2" fill="var(--accent-up)" />
      <rect x="8" y="4" width="12" height="2" fill="var(--accent-up)" />
      {ring(8, 'var(--accent-lo)')}
      <rect x="8" y="8" width="15" height="2" fill="var(--accent-lo)" />
      <rect x="8" y="12" width="12" height="2" fill="var(--accent-lo)" />
    </svg>
  );
}
