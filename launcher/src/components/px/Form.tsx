import type { ReactNode } from 'react';
import { PxButton, TT } from './Px';

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
