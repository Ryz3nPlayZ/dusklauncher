/**
 * Hand-drawn pixel glyphs as crisp SVG rects — no icon font, no emoji
 * (DESIGN.md). Each glyph is drawn on a 12×12 grid.
 */
export type Glyph = 'minimize' | 'maximize' | 'close' | 'search';

const RECTS: Record<Glyph, [number, number, number, number][]> = {
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
  search: [
    [1, 1, 8, 2],
    [1, 3, 2, 4],
    [7, 3, 2, 4],
    [1, 7, 8, 2],
    [9, 9, 2, 2],
  ],
};

export default function PixelGlyph({
  glyph,
  size = 12,
  color = 'currentColor',
}: {
  glyph: Glyph;
  size?: number;
  color?: string;
}) {
  return (
    <svg
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
