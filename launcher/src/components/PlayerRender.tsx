/**
 * The live player render (Home + the wardrobe inspector).
 *
 * skinview3d over a transparent canvas, so the hover state can outline the
 * CHARACTER rather than its box: four 2px drop-shadows on the canvas dilate
 * the rendered alpha, which is the silhouette itself — no extra geometry, no
 * per-frame JS, and it tracks whatever pose is playing.
 *
 * three.js is ~700 kB, so the library is imported on demand: the launcher
 * shell paints before any of it is fetched, and a session that never opens a
 * view with a player never pays for it.
 */
import { useEffect, useRef, useState } from 'react';
import type * as SV from 'skinview3d';
import defaultSkin from '../assets/skins/dusk-knight.png';

export const POSES = ['IDLE', 'WALK', 'RUN', 'FLY', 'WAVE', 'CROUCH', 'SWIM', 'STAND'] as const;
export type Pose = (typeof POSES)[number];

let libPromise: Promise<typeof SV> | null = null;
const loadLib = () => (libPromise ??= import('skinview3d'));

function animationFor(lib: typeof SV, pose: Pose): SV.PlayerAnimation | null {
  switch (pose) {
    case 'IDLE':
      return new lib.IdleAnimation();
    case 'WALK':
      return new lib.WalkingAnimation();
    case 'RUN':
      return new lib.RunningAnimation();
    case 'FLY':
      return new lib.FlyingAnimation();
    case 'WAVE':
      return new lib.WaveAnimation();
    case 'CROUCH':
      return new lib.CrouchAnimation();
    case 'SWIM':
      return new lib.SwimAnimation();
    case 'STAND':
    default:
      return null;
  }
}

interface Props {
  /** PNG data URL; falls back to the bundled dusk-knight skin. */
  skin?: string | null;
  pose?: Pose;
  zoom?: number;
  /** allow drag-to-rotate (wardrobe inspector) */
  interactive?: boolean;
  paused?: boolean;
  className?: string;
}

export default function PlayerRender({
  skin,
  pose = 'IDLE',
  zoom = 0.85,
  interactive = false,
  paused = false,
  className,
}: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const viewerRef = useRef<SV.SkinViewer | null>(null);
  const libRef = useRef<typeof SV | null>(null);
  const pausedRef = useRef(paused);
  pausedRef.current = paused;
  const [ready, setReady] = useState(0);

  // create / destroy
  useEffect(() => {
    let cancelled = false;
    let viewer: SV.SkinViewer | null = null;
    let ro: ResizeObserver | null = null;
    let onVisibility: (() => void) | null = null;

    void loadLib().then((lib) => {
      const canvas = canvasRef.current;
      if (cancelled || !canvas) return;
      libRef.current = lib;
      viewer = new lib.SkinViewer({
        canvas,
        width: canvas.clientWidth || 300,
        height: canvas.clientHeight || 400,
      });
      viewer.fov = 40;
      viewer.globalLight.intensity = 2.6;
      viewer.cameraLight.intensity = 0.5;
      viewerRef.current = viewer;
      // dev escape hatch: the preview browser reports document.hidden, which
      // pauses rendering — this lets a dev un-pause it from the console
      if (import.meta.env.DEV) (window as unknown as Record<string, unknown>).__viewer = viewer;

      const parent = canvas.parentElement;
      ro = new ResizeObserver(() => {
        if (!parent || !viewer || viewer.disposed) return;
        viewer.setSize(parent.clientWidth, parent.clientHeight);
      });
      if (parent) ro.observe(parent);

      // never render while the window is hidden or the game has the GPU
      // (DESIGN.md: the launcher never takes frames the game could use)
      onVisibility = () => {
        if (viewer && !viewer.disposed) viewer.renderPaused = pausedRef.current || document.hidden;
      };
      document.addEventListener('visibilitychange', onVisibility);

      setReady((n) => n + 1); // let the prop effects below apply themselves
    });

    return () => {
      cancelled = true;
      ro?.disconnect();
      if (onVisibility) document.removeEventListener('visibilitychange', onVisibility);
      viewer?.dispose();
      viewerRef.current = null;
    };
  }, []);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.disposed) return;
    const done = viewer.loadSkin(skin || defaultSkin, { model: 'auto-detect' });
    if (done instanceof Promise) done.catch((e) => console.error('skin load failed', e));
  }, [skin, ready]);

  useEffect(() => {
    const viewer = viewerRef.current;
    const lib = libRef.current;
    if (!viewer || viewer.disposed || !lib) return;
    viewer.animation = animationFor(lib, pose);
  }, [pose, ready]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.disposed) return;
    viewer.zoom = zoom;
  }, [zoom, ready]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.disposed) return;
    viewer.renderPaused = paused || document.hidden;
  }, [paused, ready]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.disposed) return;
    viewer.controls.enableRotate = interactive;
    viewer.controls.enableZoom = false;
    viewer.controls.enablePan = false;
  }, [interactive, ready]);

  return <canvas ref={canvasRef} className={className} />;
}
