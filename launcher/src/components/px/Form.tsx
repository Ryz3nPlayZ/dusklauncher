import { useEffect, useRef, useState, type ReactNode } from 'react';
import { PxBox, PxButton, TT } from './Px';

/* ── settings rows ──────────────────────────────────────────────────────────
   The reading-surface form: a labelled row (`.srow`) with its control pinned
   right, and a segmented choice built from accent/grey buttons. Shared by
   Settings and the instance editor so both forms read the same. */

export function Row({
  label,
  hint,
  controlClassName,
  children,
}: {
  label: string;
  hint?: string;
  /** extra classes for the control side (e.g. `srow__control--wrap`) */
  controlClassName?: string;
  children: ReactNode;
}) {
  return (
    <div className="srow">
      <div className="srow__text">
        <TT size={20} tone="plain">
          {label}
        </TT>
        {hint && <span className="meta">{hint}</span>}
      </div>
      <div className={`srow__control${controlClassName ? ` ${controlClassName}` : ''}`}>{children}</div>
    </div>
  );
}

export function Choice<T extends string | number>({
  value,
  options,
  onPick,
}: {
  value: T;
  options: { value: T; label: string }[];
  onPick: (v: T) => void;
}) {
  return (
    <>
      {options.map((o) => (
        <PxButton
          key={String(o.value)}
          family={value === o.value ? 'accent' : 'grey'}
          height="md"
          onClick={() => onPick(o.value)}
        >
          <TT size={16} tone={value === o.value ? 'accent' : undefined}>
            {o.label}
          </TT>
        </PxButton>
      ))}
    </>
  );
}

/* ── combobox ───────────────────────────────────────────────────────────────
   A text field with its own list instead of the OS <datalist>/<select>
   popup: type to filter (prefix matches first), arrows + Enter to pick, the
   caret opens the full list. Free text stays allowed — the list suggests, it
   doesn't restrict. The list is the instance popout's window in miniature. */
export interface ComboOption {
  value: string;
  /** a short trailing note on the row ("SNAPSHOT", a date) */
  meta?: string;
}

export function Combobox({
  value,
  options,
  onChange,
  placeholder,
  className,
  max = 60,
}: {
  value: string;
  options: ComboOption[];
  onChange: (v: string) => void;
  placeholder?: string;
  className?: string;
  /** most rows the list renders */
  max?: number;
}) {
  const [open, setOpen] = useState(false);
  /* filter only once the user types: opening on a filled field shows all */
  const [typed, setTyped] = useState(false);
  const [hi, setHi] = useState(0);
  const root = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);

  const q = value.trim().toLowerCase();
  const shown = (
    typed && q
      ? options
          .filter((o) => o.value.toLowerCase().includes(q))
          .sort((a, b) => Number(!a.value.toLowerCase().startsWith(q)) - Number(!b.value.toLowerCase().startsWith(q)))
      : options
  ).slice(0, max);

  useEffect(() => {
    if (!open) return;
    const away = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', away);
    return () => document.removeEventListener('mousedown', away);
  }, [open]);

  /* opening on the full list starts at the current value */
  useEffect(() => {
    if (open && !typed) setHi(Math.max(0, shown.findIndex((o) => o.value === value)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    list.current?.querySelector<HTMLElement>(`[data-i="${hi}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [hi, open]);

  const pick = (v: string) => {
    onChange(v);
    setOpen(false);
    setTyped(false);
  };

  return (
    <div className={`combo${className ? ` ${className}` : ''}`} ref={root}>
      <PxBox family="panel" height="md" className="combo__field">
        <input
          ref={input}
          className="input"
          role="combobox"
          aria-expanded={open}
          aria-autocomplete="list"
          placeholder={placeholder}
          value={value}
          onFocus={() => setOpen(true)}
          onClick={() => setOpen(true)}
          onChange={(e) => {
            onChange(e.target.value);
            setTyped(true);
            setOpen(true);
            setHi(0);
          }}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
              e.preventDefault();
              if (!open) return setOpen(true);
              const step = e.key === 'ArrowDown' ? 1 : -1;
              setHi((i) => (shown.length ? (i + step + shown.length) % shown.length : 0));
            } else if (e.key === 'Enter' && open && shown[hi]) {
              e.preventDefault();
              pick(shown[hi].value);
            } else if (e.key === 'Escape' && open) {
              e.stopPropagation();
              setOpen(false);
            } else if (e.key === 'Tab') {
              setOpen(false);
            }
          }}
        />
        <button
          type="button"
          className={`combo__caret${open ? ' is-open' : ''}`}
          tabIndex={-1}
          aria-label="Show all"
          onMouseDown={(e) => {
            e.preventDefault();
            setTyped(false);
            setOpen((o) => !o);
            input.current?.focus();
          }}
        />
      </PxBox>
      {open && shown.length > 0 && (
        <div className="win px--window combo__list scroll" role="listbox" ref={list}>
          {shown.map((o, i) => (
            <button
              type="button"
              key={o.value}
              data-i={i}
              role="option"
              aria-selected={o.value === value}
              className={['combo__opt', i === hi ? 'is-hi' : '', o.value === value ? 'is-sel' : ''].join(' ')}
              onMouseDown={(e) => {
                e.preventDefault();
                pick(o.value);
              }}
              onMouseEnter={() => setHi(i)}
            >
              <span className="combo__val">{o.value}</span>
              {o.meta && <span className="meta">{o.meta}</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
