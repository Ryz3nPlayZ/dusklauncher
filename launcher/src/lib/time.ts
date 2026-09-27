/** Timestamps in the launcher's clock format (the "24-hour clock" setting). */

export function clockTime(d: Date, clock24h: boolean): string {
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: !clock24h });
}

/** "Today 14:02" / "Yesterday 2:02 PM" / "Mar 4 14:02" (+ the year when it isn't this one) */
export function stamp(ms: number, clock24h: boolean): string {
  const d = new Date(ms);
  const time = clockTime(d, clock24h);
  const today = new Date();
  const yesterday = new Date(today.getTime() - 86_400_000);
  if (d.toDateString() === today.toDateString()) return `Today ${time}`;
  if (d.toDateString() === yesterday.toDateString()) return `Yesterday ${time}`;
  const date = d.toLocaleDateString([], {
    month: 'short',
    day: 'numeric',
    ...(d.getFullYear() === today.getFullYear() ? {} : { year: 'numeric' }),
  });
  return `${date} ${time}`;
}

export function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
