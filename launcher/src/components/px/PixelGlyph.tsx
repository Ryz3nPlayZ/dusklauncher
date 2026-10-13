/**
 * The app's small icons, by name. Phosphor (MIT) at one weight, so every
 * glyph in the app reads as one family.
 */
import {
  CaretLeft,
  CaretRight,
  Cube,
  GearSix,
  MagnifyingGlass,
  Minus,
  Square,
  Star,
  X,
} from '@phosphor-icons/react';
import type { Icon } from '@phosphor-icons/react';
import { LEGACY } from '../../lib/ui';

export type Glyph = 'minimize' | 'maximize' | 'close' | 'search' | 'gear' | 'left' | 'right' | 'box' | 'star';

/* the legacy look's glyphs: crisp pixel rects on a 12×12 grid */
const RECTS: Record<Glyph, [number, number, number, number][]> = {
  right: [
    [2, 2, 2, 2],
    [4, 4, 2, 2],
    [6, 6, 2, 2],
    [4, 8, 2, 2],
    [2, 10, 2, 2],
  ],
  star: [
    [5, 0, 2, 2],
    [4, 2, 4, 2],
    [0, 4, 12, 2],
    [2, 6, 8, 2],
    [3, 8, 6, 1],
    [2, 9, 3, 2],
    [7, 9, 3, 2],
    [1, 11, 2, 1],
    [9, 11, 2, 1],
  ],
  left: [
    [8, 2, 2, 2],
    [6, 4, 2, 2],
    [4, 6, 2, 2],
    [6, 8, 2, 2],
    [8, 10, 2, 2],
  ],
  box: [
    [2, 2, 8, 2],
    [2, 4, 2, 6],
    [8, 4, 2, 6],
    [2, 10, 8, 2],
    [4, 4, 2, 2],
    [6, 6, 2, 2],
    [6, 4, 2, 2],
    [4, 6, 2, 2],
  ],
  minimize: [[1, 9, 10, 2]],
  maximize: [
    [1, 1, 10, 2],
    [1, 9, 10, 2],
    [1, 3, 2, 6],
    [9, 3, 2, 6],
  ],
  close: [
    [1, 1, 2, 2],
    [3, 3, 2, 2],
    [5, 5, 2, 2],
    [7, 7, 2, 2],
    [9, 9, 2, 2],
    [9, 1, 2, 2],
    [7, 3, 2, 2],
    [3, 7, 2, 2],
    [1, 9, 2, 2],
  ],
  gear: [
    /* four cardinal teeth */
    [5, 0, 2, 2],
    [5, 10, 2, 2],
    [0, 5, 2, 2],
    [10, 5, 2, 2],
    /* four diagonal teeth */
    [2, 2, 2, 2],
    [8, 2, 2, 2],
    [2, 8, 2, 2],
    [8, 8, 2, 2],
    /* the ring, drawn as four bars around an open 2×2 hub */
    [3, 3, 6, 2],
    [3, 7, 6, 2],
    [3, 5, 2, 2],
    [7, 5, 2, 2],
  ],
  search: [
    [1, 1, 8, 2],
    [1, 3, 2, 4],
    [7, 3, 2, 4],
    [1, 7, 8, 2],
    [9, 9, 2, 2],
  ],
};

const ICONS: Record<Glyph, Icon> = {
  minimize: Minus,
  maximize: Square,
  close: X,
  search: MagnifyingGlass,
  gear: GearSix,
  left: CaretLeft,
  right: CaretRight,
  box: Cube,
  star: Star,
};

export default function PixelGlyph({
  glyph,
  size = 12,
  color = 'currentColor',
  filled,
  className,
}: {
  glyph: Glyph;
  size?: number;
  color?: string;
  /** the solid cut — a favourite's star */
  filled?: boolean;
  className?: string;
}) {
  if (LEGACY) {
    return (
      <svg
        className={className}
        width={size}
        height={size}
        viewBox="0 0 12 12"
        shapeRendering="crispEdges"
        aria-hidden="true"
        style={{ flex: 'none' }}
      >
        {RECTS[glyph].map(([x, y, w, h], i) => (
          <rect key={i} x={x} y={y} width={w} height={h} fill={color} />
        ))}
      </svg>
    );
  }
  const I = ICONS[glyph];
  return (
    <I
      className={className}
      size={Math.round(size * 1.25)}
      color={color}
      weight={filled ? 'fill' : 'bold'}
      aria-hidden="true"
      style={{ flex: 'none' }}
    />
  );
}
