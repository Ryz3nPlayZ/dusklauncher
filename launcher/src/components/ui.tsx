/** Shared pixel UI primitives. */
import {
  useEffect,
  useRef,
  useState,
  type ReactNode,
  type CSSProperties,
} from 'react';
import { createPortal } from 'react-dom';
import { PixelIcon, type IconName } from './PixelIcon';
import { useUi } from '../stores/ui';
import { playSfx } from '../sfx/sfx';

// ── Segmented pixel progress bar ───────────────────────────────────────────

export function ProgressBar({
  value,
  blocks = 24,
  label,
  style,
}: {
  value: number; // 0..1
  blocks?: number;
  label?: string;
  style?: CSSProperties;
}) {
  const filled = Math.round(Math.max(0, Math.min(1, value)) * blocks);
  return (
    <div className="pprog" style={style}>
      <div className="pprog__blocks">
        {Array.from({ length: blocks }, (_, i) => (
          <span key={i} className={i < filled ? 'pprog__on' : ''} />
        ))}
      </div>
      {label && <span className="pprog__label font-pixel">{label}</span>}
    </div>
  );
}

// ── Modal ──────────────────────────────────────────────────────────────────

export function Modal({
  title,
  onClose,
  children,
  width = 520,
  wide = false,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  width?: number;
  wide?: boolean;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return createPortal(
    <div className="modal-overlay anim-fade-in" onMouseDown={onClose}>
      <div
        className={`modal-card pixel-notch anim-pop-in${wide ? ' modal-card--wide' : ''}`}
        style={{ width }}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <header className="modal-card__head">
          <h2 className="font-pixel-bold">{title}</h2>
          <button className="pbtn pbtn--ghost" onClick={onClose} aria-label="Close">
            <PixelIcon name="close" size={14} />
          </button>
        </header>
        <div className="modal-card__body">{children}</div>
      </div>
    </div>,
    document.body,
  );
}

// ── Toasts ─────────────────────────────────────────────────────────────────

export function Toaster() {
  const { toasts, dismissToast } = useUi();
  return createPortal(
    <div className="toaster">
      {toasts.map((t) => (
        <div key={t.id} className={`toast toast--${t.kind} anim-pop-in`} onClick={() => dismissToast(t.id)}>
          <PixelIcon
            name={t.kind === 'error' ? 'close' : t.kind === 'success' ? 'check' : 'sparkle'}
            size={12}
          />
          <span>{t.text}</span>
        </div>
      ))}
    </div>,
    document.body,
  );
}

// ── Dropdown (pixel-styled select) ─────────────────────────────────────────

export interface DropdownOption<T extends string = string> {
  value: T;
  label: string;
}

export function Dropdown<T extends string>({
  value,
  options,
  onChange,
  width = 160,
}: {
  value: T;
  options: DropdownOption<T>[];
  onChange: (v: T) => void;
  width?: number;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  const current = options.find((o) => o.value === value);

  return (
    <div className={`pdrop ${open ? 'pdrop--open' : ''}`} ref={ref} style={{ width }}>
      <button className="pdrop__btn" onClick={() => setOpen(!open)}>
        <span className="pdrop__val">{current?.label ?? value}</span>
        <PixelIcon name={open ? 'chevronUp' : 'chevronDown'} size={10} />
      </button>
      {open && (
        <ul className="pdrop__list anim-pop-in">
          {options.map((o) => (
            <li key={o.value}>
              <button
                className={o.value === value ? 'is-sel' : ''}
                onClick={() => {
                  onChange(o.value);
                  setOpen(false);
                  playSfx('click');
                }}
              >
                {o.label}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ── Toggle ─────────────────────────────────────────────────────────────────

export function Toggle({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <button
      className={`ptoggle ${checked ? 'ptoggle--on' : ''}`}
      role="switch"
      aria-checked={checked}
      onClick={() => {
        onChange(!checked);
        playSfx('click');
      }}
    >
      <span className="ptoggle__knob" />
    </button>
  );
}

// ── Labeled field ──────────────────────────────────────────────────────────

export function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <label className="pfield">
      <span className="pfield__label font-pixel">{label}</span>
      {children}
      {hint && <span className="pfield__hint">{hint}</span>}
    </label>
  );
}

// ── Stat chip (downloads / likes / dates) ──────────────────────────────────

export function StatChip({ icon, children }: { icon: IconName; children: ReactNode }) {
  return (
    <span className="pstat">
      <PixelIcon name={icon} size={11} />
      {children}
    </span>
  );
}
