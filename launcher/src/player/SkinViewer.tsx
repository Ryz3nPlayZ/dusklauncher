import { useEffect, useRef } from 'react';
import * as skinview3d from 'skinview3d';
import { useLaunch } from '../stores/launch';
import { useSettings } from '../stores/settings';

/**
 * Live 3D player render (skinview3d + three.js). Pauses entirely via
 * `renderPaused` while the game runs or when reduce-motion is on; unmounts
 * (dispose) when its view is not visible.
 *
 * Presentation: figure under an explicit 3-point rig parked in world space
 * (key upper-left-front, cool rim behind, faint camera fill), so spinning or
 * zooming changes the shading instead of the light chasing the camera.
 * skinview3d's defaults are a flat ambient wash plus a near-dead camera
 * point light, which is why the model used to look washed out against dark
 * backgrounds. Extra lights are cloned
 * from the viewer's own instances so everything stays in skinview3d's
 * bundled three copy (the app-level three is a different version — never
 * mix objects across the two).
 */
export type SkinViewerProps = {
  skinUrl: string;
  capeUrl?: string | null;
  width?: number;
  height?: number;
  className?: string;
  autoRotate?: boolean;
  zoom?: number;
  /** false = locked pose for Home (no drag/zoom, breathing idle on) */
  interactive?: boolean;
  /** gentle vertical bob (Home only) — disabled under reduce-motion */
  breathe?: boolean;
};

export default function SkinViewer({
  skinUrl,
  capeUrl = null,
  width = 300,
  height = 400,
  className,
  autoRotate = false,
  zoom = 1,
  interactive = true,
  breathe = false,
}: SkinViewerProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const viewerRef = useRef<skinview3d.SkinViewer | null>(null);
  const phase = useLaunch((s) => s.phase);
  const reduceMotion = useSettings((s) => s.settings.reduceMotion);

  useEffect(() => {
    const viewer = new skinview3d.SkinViewer({
      canvas: canvasRef.current!,
      width,
      height,
      fov: 40,
      zoom: 0.88,
      // No animation: the figure holds a static stance (posed below).
      renderPaused: true,
    });
    viewer.autoRotate = autoRotate && interactive;
    viewer.autoRotateSpeed = 0.7;
    viewer.controls.enablePan = false;
    viewer.controls.enableRotate = interactive;
    viewer.controls.enableZoom = interactive;

    // Studio relight, parked in WORLD space: the rig never moves, so
    // spinning / zooming the figure changes the shading instead of the
    // light chasing the camera. The bundled three uses physical light
    // units, so point intensities are scaled by distance-squared to hold
    // a constant exposure across zoom levels.
    const dist = viewer.camera.position.distanceTo(viewer.controls.target);
    viewer.globalLight.intensity = 0.7;
    viewer.globalLight.color.set(0xfff4e8);
    // warm key, fixed upper-left-front of the stage
    const key = viewer.cameraLight.clone();
    key.position.set(-0.55 * dist, 0.75 * dist, 0.65 * dist);
    key.color.set(0xfff1e0);
    key.intensity = 1.35 * key.position.length() ** 2;
    viewer.scene.add(key);
    // camera-attached light drops to a faint fill so faces never go black
    viewer.cameraLight.color.set(0xfff1e0);
    viewer.cameraLight.intensity = 0.15 * dist * dist;
    // cool rim parked in world space behind the player for edge separation
    const rim = viewer.cameraLight.clone();
    rim.position.set(-14, 10, -14);
    rim.color.set(0x9db8ff);
    rim.intensity = 1.6 * rim.position.length() ** 2;
    viewer.scene.add(rim);

    // Relaxed 3/4 stance: body turned, head counter-turned toward the viewer,
    // right arm slightly forward, weight shifted between the legs. Small
    // angles — over-posing reads goofy fast.
    const skin = viewer.playerObject.skin;
    viewer.playerObject.rotation.y = -0.42;
    skin.head.rotation.y = 0.18;
    skin.rightArm.rotation.set(-0.22, 0, -0.1);
    skin.leftArm.rotation.set(0.08, 0, 0.1);
    skin.rightLeg.rotation.x = -0.06;
    skin.leftLeg.rotation.x = 0.06;

    viewerRef.current = viewer;
    requestAnimationFrame(() => {
      if (!viewer.disposed) viewer.renderPaused = false;
    });
    return () => {
      viewer.dispose();
      viewerRef.current = null;
    };
    // width/height are fixed per mount; remount happens through keys upstream
  }, [width, height, autoRotate, interactive]);

  // Breathing idle (Home only): sine bob on the whole player object.
  // Pure transform motion, paused with everything else while running.
  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || !breathe || reduceMotion || phase === 'running') return;
    let raf = 0;
    const t0 = performance.now();
    const tick = (t: number) => {
      const v = viewerRef.current;
      if (v && !v.disposed && !v.renderPaused) {
        const s = (t - t0) / 1000;
        v.playerObject.position.y = Math.sin(s * (Math.PI * 2 / 3.2)) * 0.08;
        v.playerObject.rotation.z = Math.sin(s * (Math.PI * 2 / 6.4)) * 0.012;
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [breathe, reduceMotion, phase]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer) return;
    let cancelled = false;
    void viewer
      .loadSkin(skinUrl, { model: 'auto-detect' })
      .then(() => {
        if (!cancelled && capeUrl) return viewer.loadCape(capeUrl).catch(() => {});
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled && !viewer.disposed) viewer.render();
      });
    return () => {
      cancelled = true;
    };
  }, [skinUrl, capeUrl]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer) return;
    const pause = phase === 'running' || reduceMotion;
    if (viewer.renderPaused !== pause) {
      viewer.renderPaused = pause;
      if (!pause) viewer.render(); // repaint immediately after resume
    }
  }, [phase, reduceMotion]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (viewer && !viewer.disposed) {
      viewer.zoom = 0.88 * zoom;
      viewer.render();
    }
  }, [zoom]);

  return <canvas ref={canvasRef} className={className} />;
}
