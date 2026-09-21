/**
 * The construction primitives. Every visible box in the app is one of these —
 * they are the spec.html specimens, nothing more. A view may arrange them and
 * choose a family + a height from the scale; it may never restyle one.
 */
import { useLayoutEffect, useRef } from 'react';
import type { ButtonHTMLAttributes, CSSProperties, HTMLAttributes, ReactNode } from 'react';

/** Border families, exactly the ones the spec derives. */
export type Family =
  | 'panel' // 5.1 · grey ring, no corners
  | 'grey' // grey ring + #4A4A4A corners
  | 'install' // 5.3 · whisper-green surface, green text
  | 'soft' // 5.3 construction wearing the accent surface — a panel's primary
  | 'accent' // 2 · PLAY NOW and every selected state — follows the theme
  | 'yellow' // 2 · the gold family itself
  | 'green' // 3 · INSTALL
  | 'blue' // 3 · EXAMPLE
  | 'red' // 3 · DELETE
  | 'dusk' // Figma 98:73 · grey ring, purple surface (DUSK PROFILE)
  | 'moss'; // Figma 98:72 · grey ring, moss surface (BROWSE MODPACKS)

/** The height scale. Rows that sit side by side share one value. */
export type Height = 'xl' | 'lg' | 'md' | 'sm' | 'listing' | 'fill';

const heightClass: Record<Height, string> = {
  xl: 'h-xl',
  lg: 'h-lg',
  md: 'h-md',
  sm: 'h-sm',
  listing: 'h-listing',
  fill: '',
};

function classes(family: Family, height: Height, extra?: string, listing?: boolean) {
  return [
    'px',
    `px--${family}`,
    listing ? 'px--listing' : '',
    heightClass[height],
    extra ?? '',
  ]
    .filter(Boolean)
    .join(' ');
}

interface BoxProps extends HTMLAttributes<HTMLDivElement> {
  family?: Family;
  height?: Height;
  /** listing tiles use the darker #181818 body (spec 5.1) */
  listing?: boolean;
  children?: ReactNode;
}

/** A non-interactive panel: search bar, header, listing tile, window. */
export function PxBox({
  family = 'panel',
  height = 'fill',
  listing,
  className,
  children,
  ...rest
}: BoxProps) {
  return (
    <div className={classes(family, height, className, listing)} {...rest}>
      {children}
    </div>
  );
}

interface BtnProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  family?: Family;
  height?: Height;
  children?: ReactNode;
}

/** A button. Presses by translating 2px, per DESIGN.md. */
export function PxButton({
  family = 'grey',
  height = 'lg',
  className,
  children,
  type = 'button',
  ...rest
}: BtnProps) {
  return (
    <button type={type} className={classes(family, height, className)} {...rest}>
      {children}
    </button>
  );
}

/* ── fit-to-cell ──────────────────────────────────────────────────────────
   A label's natural width is its glyphs × 1ch (plus tracking, or × the
   stretch). The cell it sits in is often a fixed plate — a nav tab, PLAY NOW,
   a grid tile — and nothing ties the two together, so the label used to run
   straight past the plate's edge. The rule now: the box may shrink to
   whatever the cell leaves it (.tt has min-width: 0 / max-width: 100%), and
   this hook scales the glyphs down into that box. One mechanism for every
   label, instead of a vw cap per view.

   Both rects are read in the same (transformed) space, so the ratio is the
   layout box over the natural text width regardless of the scale currently
   applied — no reset-and-remeasure. */
const fitObserver =
  typeof ResizeObserver === 'undefined'
    ? null
    : new ResizeObserver((entries) => {
        for (const e of entries) fitLabel(e.target as HTMLElement);
      });
const fitRange = typeof document === 'undefined' ? null : document.createRange();

function fitLabel(el: HTMLElement) {
  if (!fitRange || !el.firstChild) return;
  fitRange.selectNodeContents(el);
  const natural = fitRange.getBoundingClientRect().width;
  const box = el.getBoundingClientRect().width;
  if (!natural || !box) return;
  const sx = Number(el.style.getPropertyValue('--tt-sx')) || 1;
  const fit = Math.min(1, box / natural / sx);
  el.style.setProperty('--tt-fit', fit < 0.999 ? fit.toFixed(4) : '1');
}

/* the box only reports a resize when it is the thing that changed; the
   natural width can move on its own (the font swapping in, --t stepping on
   a window resize while the box stays clamped), so those refit every label */
if (typeof window !== 'undefined') {
  const refitAll = () => document.querySelectorAll<HTMLElement>('.tt').forEach(fitLabel);
  window.addEventListener('resize', refitAll);
  document.fonts?.ready.then(refitAll);
}

function useFit() {
  const ref = useRef<HTMLSpanElement>(null);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el || !fitObserver) return;
    fitObserver.observe(el);
    return () => fitObserver.unobserve(el);
  }, []);
  return ref;
}

/** Two-tone pixel text — hard 50/50 split, 0.5px matched stroke. */
export function TT({
  children,
  size = 22,
  tone,
  sx,
  className,
}: {
  children: string;
  size?: 36 | 22 | 20 | 16 | 14 | 13 | 11;
  tone?: 'yellow' | 'green' | 'blue' | 'red' | 'purple' | 'moss' | 'accent' | 'sub' | 'dim' | 'plain';
  /** horizontal stretch — the Figma labels are scaled vectors (nav 1.5, PLAY NOW 1.15) */
  sx?: number;
  className?: string;
}) {
  const stretched = sx !== undefined && sx !== 1;
  const ref = useFit();
  return (
    <span
      ref={ref}
      className={['tt', `tt--${size}`, tone ? `tt--${tone}` : '', stretched ? 'tt--sx' : '', className ?? '']
        .filter(Boolean)
        .join(' ')}
      style={stretched ? ({ '--tt-sx': sx, '--tt-n': children.length } as CSSProperties) : undefined}
      data-text={children}
    >
      {children}
    </span>
  );
}

/* ── navbar cells ─────────────────────────────────────────────────────────
   The strip at the top of the window and the tab strip inside a page are the
   same component. Every cell is the Figma 8:28 plate — 3px #323232 band,
   #4a4a4a L corners, flat surface, no depth line, and no second construction
   for the open tab: selection reads only as the brighter label (is-active).
   A cell never takes the accent. */

/** Figma 1:3 — the 16px cell labels are stretched 1.4× (HOME = 60px). */
export const NAV_SX = 1.4;

export function cellClass(active: boolean, extra = '') {
  return ['px px--cell px--grey nav__cell', active ? 'is-active' : '', extra]
    .filter(Boolean)
    .join(' ');
}

/** A clickable tab in a navbar or a window bar. */
export function NavCell({
  active = false,
  label,
  className,
  onClick,
}: {
  active?: boolean;
  label: string;
  className?: string;
  onClick?: () => void;
}) {
  return (
    <button className={cellClass(active, className)} onClick={onClick}>
      <TT size={16} sx={NAV_SX}>
        {label}
      </TT>
    </button>
  );
}

/** A cell that only reports something — the instance count, a status. */
export function NavLabel({ label, className }: { label: string; className?: string }) {
  return (
    <div className={cellClass(false, className)}>
      <TT size={16} tone="dim" sx={NAV_SX}>
        {label}
      </TT>
    </div>
  );
}
