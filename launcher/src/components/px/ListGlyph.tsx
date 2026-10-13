/** The instance picker's glyph: up/down carets, the picker opens a list. The
    legacy look draws its pixel list: two hollow squares, two lines beside
    each, gold over orange. */
import { CaretUpDown } from '@phosphor-icons/react';
import { LEGACY } from '../../lib/ui';

export default function ListGlyph({ className }: { className?: string }) {
  if (LEGACY) return <LegacyListGlyph className={className} />;
  return <CaretUpDown className={className} weight="bold" aria-hidden="true" style={{ flex: 'none' }} />;
}

function LegacyListGlyph({ className }: { className?: string }) {
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
