/**
 * The shared primitives. Every visible box in the app is one of these: a view
 * arranges them and picks a family (its voice) and a height from the scale.
 */
import { useLayoutEffect, useRef } from 'react';
import type { ButtonHTMLAttributes, CSSProperties, HTMLAttributes, ReactNode } from 'react';
import { LEGACY } from '../../lib/ui';

/** Voices. Each swaps only the fill, border and text colour. */
export type Family =
  | 'panel' // a quiet card
  | 'grey' // a raised control: the default button
  | 'install' // green tint: install, get
  | 'soft' // a panel's own primary: brighter raised
  | 'accent' // the primary action and the current pick: white with a soft glow
  | 'yellow' // same as accent (older call sites)
  | 'green' // green tint
  | 'blue' // blue tint
  | 'red' // red tint: delete, stop
  | 'dusk' // violet tint: Dusk's own things
  | 'moss'; // green tint (older call sites)

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
  /** a listing tile */
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

/** A button. Lifts on hover, settles on press. */
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

/* ── label casing ─────────────────────────────────────────────────────────
   Labels used to be written in capitals for the pixel face. A label with no
   lower-case letters is now read as one of those and put in sentence case,
   keeping the words that are always capitals (HUD, FPS, RAM…) and the names
   that are always capitalised (Dusk, Minecraft, Fabric…). Anything already in
   mixed case — and anything marked `keep`, like a player or instance name —
   is left exactly as written. */
const UPPER = new Set([
  'HUD', 'FPS', 'RAM', 'JVM', 'GPU', 'CPU', 'OK', 'ID', 'URL', 'UUID', 'IP', 'UI', 'API', 'MB', 'GB', 'KB',
  'TB', 'XP', 'TPS', 'NBT', 'PNG', 'JPG', 'GIF', 'HD', '2D', '3D', 'MC', 'AFK', 'DM', 'DMS', 'FAQ',
  'TOS', 'EULA', 'MSA', 'RGB', 'HEX', 'SMP', 'UHC', 'FOV', 'VR', 'JSON', 'ZIP', 'CF', 'I',
]);
const PROPER: Record<string, string> = {
  DUSK: 'Dusk', MINECRAFT: 'Minecraft', MICROSOFT: 'Microsoft', XBOX: 'Xbox', FABRIC: 'Fabric',
  QUILT: 'Quilt', FORGE: 'Forge', NEOFORGE: 'NeoForge', MODRINTH: 'Modrinth', CURSEFORGE: 'CurseForge',
  JAVA: 'Java', DISCORD: 'Discord', GITHUB: 'GitHub', WINDOWS: 'Windows', MACOS: 'macOS', LINUX: 'Linux',
  PVP: 'PvP', PVE: 'PvE', OPTIFINE: 'OptiFine', SODIUM: 'Sodium', IRIS: 'Iris', PRISM: 'Prism',
  MULTIMC: 'MultiMC', ATLAUNCHER: 'ATLauncher', LUNAR: 'Lunar', BADLION: 'Badlion', FEATHER: 'Feather',
  OVERWORLD: 'Overworld', NETHER: 'Nether', END: 'End', MOJANG: 'Mojang', TWITCH: 'Twitch',
  YOUTUBE: 'YouTube', KOFI: 'Ko-fi', PAYPAL: 'PayPal', STRIPE: 'Stripe', TAURI: 'Tauri',
};

function caseWord(w: string, first: boolean): string {
  const m = /^([^A-Z0-9]*)(.*?)([^A-Z0-9]*)$/.exec(w);
  if (!m || !m[2]) return w;
  const [, pre, core, post] = m;
  let out: string;
  if (UPPER.has(core)) out = core;
  else if (PROPER[core]) out = PROPER[core];
  else if (/^V\d/.test(core)) out = 'v' + core.slice(1);
  else if (/\d/.test(core)) out = core; // versions, sizes, counts
  else {
    const lower = core.toLowerCase();
    out = first ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
  }
  return pre + out + post;
}

/** Sentence-cases a label written in capitals; mixed-case text is returned as is. */
export function labelCase(text: string): string {
  // tokens with digits (v0.7.5, 1.21.11, 12ms) don't count as lower case
  const words = text.replace(/\S*\d\S*/g, '');
  if (!/[A-Z]/.test(words) || /[a-z]/.test(words)) return text;
  let first = true;
  return text
    .split(/(\s+)/)
    .map((part) => {
      if (/^\s+$/.test(part) || part === '') return part;
      if (part === '·' || part === '—' || part === '|') {
        first = true;
        return part;
      }
      const out = caseWord(part, first);
      if (/[A-Z0-9]/.test(part)) first = /[.!?:]$/.test(part);
      return out;
    })
    .join('');
}

/* ── legacy: fit-to-cell ──────────────────────────────────────────────────
   The pixel face is wide and its labels sit in fixed plates, so in the legacy
   look each label is scaled down to whatever width its cell leaves it. Both
   rects are read in the same (transformed) space, so the ratio holds whatever
   scale is applied now. */
const fitObserver =
  !LEGACY || typeof ResizeObserver === 'undefined'
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

/* the natural width can change while the box stays put (the font swapping
   in, --t stepping on a resize), so those refit every label */
if (LEGACY && typeof window !== 'undefined') {
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

/** One line of UI text. Sentence-cases capitals unless `keep` (names, user
    text); the legacy look sets everything but names in pixel capitals. */
export function TT({
  children,
  size = 22,
  tone,
  keep,
  sx,
  className,
  title,
}: {
  children: string;
  size?: 36 | 22 | 20 | 16 | 14 | 13 | 11;
  tone?: 'yellow' | 'green' | 'blue' | 'red' | 'purple' | 'moss' | 'accent' | 'sub' | 'dim' | 'plain';
  /** a name or user-written text: shown exactly as given */
  keep?: boolean;
  /** horizontal stretch, legacy look only (nav 1.4, Play 1.15) */
  sx?: number;
  className?: string;
  title?: string;
}) {
  const ref = useFit();
  if (LEGACY) {
    const text = keep ? children : children.toUpperCase();
    const stretched = sx !== undefined && sx !== 1;
    return (
      <span
        ref={ref}
        className={['tt', `tt--${size}`, tone ? `tt--${tone}` : '', stretched ? 'tt--sx' : '', className ?? '']
          .filter(Boolean)
          .join(' ')}
        style={stretched ? ({ '--tt-sx': sx, '--tt-n': text.length } as CSSProperties) : undefined}
        data-text={text}
        title={title}
      >
        {text}
      </span>
    );
  }
  const text = keep ? children : labelCase(children);
  return (
    <span
      className={['tt', `tt--${size}`, tone ? `tt--${tone}` : '', className ?? ''].filter(Boolean).join(' ')}
      title={title}
    >
      {text}
    </span>
  );
}

/* ── tab cells ─────────────────────────────────────────────────────────────
   The tab strips inside pages (a window's bar): ghost pills, the open one
   raised. */

/** The tab labels' stretch: legacy only, where they fill fixed plates. */
export const NAV_SX = LEGACY ? 1.4 : 1;

export function cellClass(active: boolean, extra = '') {
  return ['px px--cell nav__cell', LEGACY ? 'px--grey' : '', active ? 'is-active' : '', extra]
    .filter(Boolean)
    .join(' ');
}

/** A clickable tab in a navbar or a window bar. */
export function NavCell({
  active = false,
  label,
  className,
  title,
  keep,
  onClick,
}: {
  active?: boolean;
  label: string;
  className?: string;
  title?: string;
  /** a name (an instance group, a skin): shown as written */
  keep?: boolean;
  onClick?: () => void;
}) {
  return (
    <button className={cellClass(active, className)} title={title} onClick={onClick}>
      <TT size={16} keep={keep} sx={NAV_SX}>{label}</TT>
    </button>
  );
}

/** A cell that only reports something — the instance count, a status. */
export function NavLabel({ label, className, keep }: { label: string; className?: string; keep?: boolean }) {
  return (
    <div className={cellClass(false, className)}>
      <TT size={16} tone="dim" keep={keep} sx={NAV_SX}>{label}</TT>
    </div>
  );
}
