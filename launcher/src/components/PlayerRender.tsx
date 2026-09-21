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
import type * as THREE from 'three';
import steveSkin from '../assets/skins/steve.png';
import { accessoryFrames, bakeAccessory, canvasTexture, partFor } from '../lib/accessoryMesh';
import type { AccessoryEntry, AccessoryModelJson, SkinModel } from '../lib/api';

/** everything the viewer needs to draw one accessory */
export interface AccessoryView {
  entry: AccessoryEntry;
  model: AccessoryModelJson;
  /** texture PNG data URL (a vertical tilesheet when entry.frames > 1) */
  texture: string;
}

export const POSES = ['IDLE', 'WALK', 'RUN', 'FLY', 'WAVE', 'CROUCH', 'SWIM', 'STAND'] as const;
export type Pose = (typeof POSES)[number];

let libPromise: Promise<[typeof SV, typeof THREE]> | null = null;
const loadLib = () => (libPromise ??= Promise.all([import('skinview3d'), import('three')]));

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

/**
 * Decode a cape PNG into per-frame canvases. MinecraftCapes rule: a cape
 * whose height isn't width/2 is a vertical strip of frames, 100 ms each —
 * the same split the client mod does, so the wardrobe preview matches the
 * game.
 */
async function capeFrames(dataUrl: string): Promise<HTMLCanvasElement[]> {
  const img = new Image();
  img.src = dataUrl;
  await img.decode();
  const w = img.naturalWidth;
  const frameH = Math.max(1, Math.floor(w / 2));
  const frames = img.naturalHeight === frameH ? 1 : Math.max(1, Math.floor(img.naturalHeight / frameH));
  const out: HTMLCanvasElement[] = [];
  for (let f = 0; f < frames; f++) {
    const c = document.createElement('canvas');
    c.width = w;
    c.height = frameH;
    c.getContext('2d')!.drawImage(img, 0, f * frameH, w, frameH, 0, 0, w, frameH);
    out.push(c);
  }
  return out;
}

interface Props {
  /** PNG data URL; falls back to the bundled Steve skin. */
  skin?: string | null;
  /** cape PNG data URL (static or animated strip); null/undefined = none */
  cape?: string | null;
  /** ms per cape frame when the strip is animated (100 = MinecraftCapes) */
  capeFrameMs?: number;
  /** 14×7 ears PNG data URL (MinecraftCapes style); null/undefined = none */
  ears?: string | null;
  /** model accessories worn on the body parts (docs/COSMETICS.md §5) */
  accessories?: AccessoryView[];
  /** arm width; 'auto' infers it from the PNG the way the game does */
  model?: SkinModel | 'auto';
  pose?: Pose;
  zoom?: number;
  /** allow drag-to-rotate (wardrobe inspector) */
  interactive?: boolean;
  paused?: boolean;
  className?: string;
  /**
   * Called when the pointer moves onto / off the character itself (a ray
   * against the player mesh, not the canvas box). Home uses it for the
   * hover outline and to make only the figure clickable.
   */
  onHit?: (hit: boolean) => void;
}

export default function PlayerRender({
  skin,
  cape,
  capeFrameMs = 100,
  ears,
  accessories,
  model = 'auto',
  pose = 'IDLE',
  zoom = 0.85,
  interactive = false,
  paused = false,
  className,
  onHit,
}: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const viewerRef = useRef<SV.SkinViewer | null>(null);
  const libRef = useRef<typeof SV | null>(null);
  const threeRef = useRef<typeof THREE | null>(null);
  const pausedRef = useRef(paused);
  pausedRef.current = paused;
  const onHitRef = useRef(onHit);
  onHitRef.current = onHit;
  const [ready, setReady] = useState(0);
  // bumps once a skin has loaded, so accessories can read the arm width
  const [skinLoaded, setSkinLoaded] = useState(0);

  // create / destroy
  useEffect(() => {
    let cancelled = false;
    let viewer: SV.SkinViewer | null = null;
    let ro: ResizeObserver | null = null;
    let onVisibility: (() => void) | null = null;

    void loadLib().then(([lib, three]) => {
      const canvas = canvasRef.current;
      if (cancelled || !canvas) return;
      libRef.current = lib;
      threeRef.current = three;
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
    const done = viewer.loadSkin(skin || steveSkin, {
      model: model === 'auto' ? 'auto-detect' : model === 'slim' ? 'slim' : 'default',
    });
    if (done instanceof Promise) {
      done.then(() => setSkinLoaded((n) => n + 1)).catch((e) => console.error('skin load failed', e));
    } else {
      setSkinLoaded((n) => n + 1);
    }
  }, [skin, model, ready]);

  // accessories: baked meshes hung off the skinview3d joints so they follow
  // the pose; rebuilt when the list or the arm width changes
  useEffect(() => {
    const viewer = viewerRef.current;
    const three = threeRef.current;
    if (!viewer || viewer.disposed || !three || !accessories?.length) return;
    let cancelled = false;
    const added: { parent: THREE.Object3D; mesh: THREE.Mesh; textures: THREE.Texture[] }[] = [];
    const timers: number[] = [];
    const skinObj = viewer.playerObject.skin as unknown as Record<string, THREE.Object3D>;
    const slim = viewer.playerObject.skin.modelType === 'slim';

    void Promise.all(
      accessories.map(async (a) => {
        const frames = await accessoryFrames(a.texture, a.entry.frames);
        if (cancelled || viewer.disposed) return;
        const parent = skinObj[partFor(a.entry.attachment, a.entry.mirrored)];
        if (!parent) return;
        const textures = frames.map((f) => canvasTexture(three, f));
        const material = new three.MeshStandardMaterial({
          map: textures[0],
          side: three.DoubleSide,
          transparent: true,
          alphaTest: 1e-5,
        });
        const mesh = new three.Mesh(bakeAccessory(three, a.model, a.entry, slim), material);
        mesh.name = `accessory-${a.entry.id}`;
        parent.add(mesh);
        added.push({ parent, mesh, textures });
        if (textures.length > 1) {
          let i = 0;
          timers.push(
            window.setInterval(() => {
              i = (i + 1) % textures.length;
              material.map = textures[i];
              material.needsUpdate = true;
            }, Math.max(50, a.entry.ticksPerFrame * 50)),
          );
        }
      }),
    ).catch((e) => console.error('accessory load failed', e));

    return () => {
      cancelled = true;
      timers.forEach((t) => window.clearInterval(t));
      for (const { parent, mesh, textures } of added) {
        parent.remove(mesh);
        mesh.geometry.dispose();
        (mesh.material as THREE.Material).dispose();
        textures.forEach((t) => t.dispose());
      }
    };
  }, [accessories, ready, skinLoaded]);

  // hit-testing: cast a ray from the pointer through the camera at the
  // player mesh, so hover / click answer for the figure and nothing else
  useEffect(() => {
    const viewer = viewerRef.current;
    const three = threeRef.current;
    const canvas = canvasRef.current;
    if (!viewer || viewer.disposed || !three || !canvas || !onHit) return;
    const ray = new three.Raycaster();
    const at = new three.Vector2();
    let last = false;
    const report = (hit: boolean) => {
      if (hit !== last) {
        last = hit;
        onHitRef.current?.(hit);
      }
    };
    const onMove = (e: PointerEvent) => {
      if (viewer.disposed) return;
      const r = canvas.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) return;
      at.set(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1);
      ray.setFromCamera(at, viewer.camera);
      report(ray.intersectObject(viewer.playerObject, true).length > 0);
    };
    const onLeave = () => report(false);
    canvas.addEventListener('pointermove', onMove);
    canvas.addEventListener('pointerleave', onLeave);
    return () => {
      canvas.removeEventListener('pointermove', onMove);
      canvas.removeEventListener('pointerleave', onLeave);
      report(false);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, !!onHit]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.disposed) return;
    if (!cape) {
      viewer.loadCape(null);
      return;
    }
    let cancelled = false;
    let timer: number | null = null;
    void capeFrames(cape)
      .then((frames) => {
        if (cancelled || viewer.disposed) return;
        let i = 0;
        viewer.loadCape(frames[0], { backEquipment: 'cape' });
        if (frames.length > 1) {
          timer = window.setInterval(() => {
            if (viewer.disposed) return;
            i = (i + 1) % frames.length;
            viewer.loadCape(frames[i], { backEquipment: 'cape' });
          }, Math.max(20, capeFrameMs));
        }
      })
      .catch((e) => console.error('cape load failed', e));
    return () => {
      cancelled = true;
      if (timer !== null) window.clearInterval(timer);
    };
  }, [cape, capeFrameMs, ready]);

  useEffect(() => {
    const viewer = viewerRef.current;
    if (!viewer || viewer.disposed) return;
    if (!ears) {
      viewer.loadEars(null);
      return;
    }
    const done = viewer.loadEars(ears, { textureType: 'standalone' });
    if (done instanceof Promise) done.catch((e) => console.error('ears load failed', e));
  }, [ears, ready]);

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
