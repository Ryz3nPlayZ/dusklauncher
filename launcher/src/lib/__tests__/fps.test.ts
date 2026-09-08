import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { FrameLoop } from '../fps';
import { compactNumber, timeAgo, bytes, hash32, mulberry32 } from '../format';

describe('FrameLoop', () => {
  const raf = vi.fn();
  beforeEach(() => {
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
      raf(cb);
      return 1;
    });
    vi.stubGlobal('cancelAnimationFrame', () => {});
  });
  afterEach(() => vi.unstubAllGlobals());

  it('does not run callbacks while paused', () => {
    const loop = new FrameLoop(30);
    const cb = vi.fn();
    loop.on(cb);
    loop.pause('game');
    // manually tick — paused loops have no scheduled frame
    expect(cb).not.toHaveBeenCalled();
    loop.resume('game');
    expect(raf).toHaveBeenCalled();
  });

  it('supports multiple named pause reasons', () => {
    const loop = new FrameLoop(30);
    const cb = vi.fn();
    loop.on(cb);
    loop.pause('a');
    loop.pause('b');
    loop.resume('a');
    // still paused by b — no new frame scheduled after resume
    const callsAfterA = raf.mock.calls.length;
    loop.resume('b');
    expect(raf.mock.calls.length).toBeGreaterThan(callsAfterA);
  });

  it('renders one frame in static (fps=0) mode', () => {
    const loop = new FrameLoop(30);
    const cb = vi.fn();
    loop.on(cb);
    loop.setFps(0);
    expect(cb).toHaveBeenCalled();
  });
});

describe('format utils', () => {
  it('compactNumber', () => {
    expect(compactNumber(999)).toBe('999');
    expect(compactNumber(1600)).toBe('1.6K');
    expect(compactNumber(16000)).toBe('16K');
    expect(compactNumber(1600000)).toBe('1.6M');
  });

  it('timeAgo', () => {
    const now = Date.now();
    expect(timeAgo(null)).toBe('never');
    expect(timeAgo(now - 30_000)).toBe('just now');
    expect(timeAgo(now - 5 * 60_000)).toBe('5 minutes ago');
    expect(timeAgo(now - 3 * 3600_000)).toBe('3 hours ago');
    expect(timeAgo(now - 2 * 86400_000)).toBe('2 days ago');
  });

  it('bytes', () => {
    expect(bytes(null)).toBe('');
    expect(bytes(512)).toBe('512 B');
    expect(bytes(2048)).toBe('2.0 KB');
    expect(bytes(5 * 1024 * 1024)).toBe('5.0 MB');
  });

  it('seeded rng is deterministic', () => {
    const a = mulberry32(hash32('profile-1'));
    const b = mulberry32(hash32('profile-1'));
    expect(a()).toBe(b());
    expect(a()).toBe(b());
  });
});
