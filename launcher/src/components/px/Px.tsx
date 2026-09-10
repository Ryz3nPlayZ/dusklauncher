/**
 * The construction primitives. Every visible box in the app is one of these —
 * they are the spec.html specimens, nothing more. A view may arrange them and
 * choose a family + a height from the scale; it may never restyle one.
 */
import type { ButtonHTMLAttributes, HTMLAttributes, ReactNode } from 'react';

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
  | 'red'; // 3 · DELETE

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

/** Two-tone pixel text — hard 50/50 split, 0.5px matched stroke. */
export function TT({
  children,
  size = 22,
  tone,
  className,
}: {
  children: string;
  size?: 36 | 22 | 20 | 16 | 14 | 13 | 11;
  tone?: 'yellow' | 'green' | 'blue' | 'red' | 'accent' | 'sub' | 'dim' | 'plain';
  className?: string;
}) {
  return (
    <span
      className={['tt', `tt--${size}`, tone ? `tt--${tone}` : '', className ?? '']
        .filter(Boolean)
        .join(' ')}
      data-text={children}
    >
      {children}
    </span>
  );
}

/* ── navbar cells ─────────────────────────────────────────────────────────
   The strip at the top of the window and the tab strip inside a page are the
   same component. A cell never takes the accent — selection reads as the
   lighter split band (--act-up / --act-lo), exactly as the navbar does. */

export function cellClass(active: boolean, extra = '') {
  return [
    'px px--cell nav__cell',
    active ? 'px--active px--depth-split' : 'px--grey px--depth',
    extra,
  ]
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
      <TT size={20}>{label}</TT>
    </button>
  );
}

/** A cell that only reports something — the instance count, a status. */
export function NavLabel({ label, className }: { label: string; className?: string }) {
  return (
    <div className={cellClass(false, className)}>
      <TT size={20} tone="dim">
        {label}
      </TT>
    </div>
  );
}
