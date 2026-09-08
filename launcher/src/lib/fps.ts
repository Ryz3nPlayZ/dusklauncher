/**
 * Frame governor — the single performance primitive for all animated pixels.
 *
 * Every animated surface (background scene, 3D player) renders through a
 * FrameLoop: one rAF loop capped at a target FPS, fully halted when the
 * window is hidden or when any pause reason is active (game running,
 * reduce-motion, view not visible). rAF already stops when the window is
 * occluded by the OS; the visibility listener catches minimized/app-hidden.
 */

export type FpsTarget = 30 | 60 | 0; // 0 = static (render one frame, then stop)

export type FrameCallback = (dt: number, now: number) => void;

const FRAME_MS = { 30: 1000 / 30, 60: 1000 / 60 } as const;

export class FrameLoop {
  private rafId = 0;
  private lastFrame = 0;
  private running = false;
  private fps: FpsTarget = 30;
  private readonly pauses = new Set<string>();
  private readonly callbacks = new Set<FrameCallback>();

  constructor(fps: FpsTarget = 30) {
    this.fps = fps;
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', () => {
        if (document.hidden) this.pause('hidden');
        else this.resume('hidden');
      });
    }
  }

  setFps(fps: FpsTarget) {
    if (this.fps === fps) return;
    this.fps = fps;
    if (fps === 0) {
      this.stopLoop();
      // static mode: paint one frame so the surface isn't stale
      this.emit(16, performance.now());
    } else if (this.callbacks.size > 0) {
      this.startLoop();
    }
  }

  get target() {
    return this.fps;
  }

  on(cb: FrameCallback): () => void {
    this.callbacks.add(cb);
    if (this.fps !== 0) this.startLoop();
    return () => {
      this.callbacks.delete(cb);
      if (this.callbacks.size === 0) this.stopLoop();
    };
  }

  pause(reason: string) {
    this.pauses.add(reason);
    this.stopLoop();
  }

  resume(reason: string) {
    this.pauses.delete(reason);
    const hidden = typeof document !== 'undefined' && document.hidden;
    if (this.callbacks.size > 0 && this.fps !== 0 && !hidden) this.startLoop();
  }

  /** Render a single frame immediately regardless of fps mode. */
  renderOnce() {
    this.emit(16, performance.now());
  }

  private emit(dt: number, now: number) {
    for (const cb of this.callbacks) cb(dt, now);
  }

  private startLoop() {
    if (this.running) return;
    if (this.pauses.size > 0) return;
    if (typeof document !== 'undefined' && document.hidden) return;
    this.running = true;
    this.lastFrame = performance.now();
    this.rafId = requestAnimationFrame(this.tick);
  }

  private stopLoop() {
    this.running = false;
    cancelAnimationFrame(this.rafId);
  }

  private tick = (now: number) => {
    if (!this.running) return;
    this.rafId = requestAnimationFrame(this.tick);
    const min = FRAME_MS[this.fps as 30 | 60] ?? 33.34;
    const dt = now - this.lastFrame;
    if (dt < min - 1) return; // too soon for this fps cap
    // subtract overflow so slow frames don't accumulate drift
    this.lastFrame = now - (dt % min);
    this.emit(Math.min(dt, 100), now);
  };
}

/** App-wide shared loops (background scene and 3D player get their own). */
export const sceneLoop = new FrameLoop(30);
export const playerLoop = new FrameLoop(30);
