/**
 * Still renders of a skin for grids: one shared offscreen skinview3d viewer
 * (same fov, lights and framing as the live PlayerRender) draws each skin
 * once to a PNG, so a wardrobe tile shows the real model — the overlay
 * layer standing off the body, slim arms, shading — without a WebGL
 * context per tile. Results are cached per skin+model for the session.
 */
import type * as SV from 'skinview3d';
import type { SkinModel } from './api';

const W = 256;
const H = 416;

const cache = new Map<string, Promise<string>>();
let viewerPromise: Promise<SV.SkinViewer> | null = null;
/* one viewer, one skin at a time: renders queue behind each other */
let queue: Promise<unknown> = Promise.resolve();

async function viewer(): Promise<SV.SkinViewer> {
  return (viewerPromise ??= import('skinview3d').then((lib) => {
    const canvas = document.createElement('canvas');
    const v = new lib.SkinViewer({
      canvas,
      width: W,
      height: H,
      renderPaused: true,
      preserveDrawingBuffer: true,
      enableControls: false,
    });
    v.fov = 40;
    v.zoom = 1.05;
    v.globalLight.intensity = 2.6;
    v.cameraLight.intensity = 0.5;
    return v;
  }));
}

/** a data-URL PNG of `src` standing front-on, or a rejection if the sheet
 *  fails to load */
export function snapshotSkin(src: string, model: SkinModel | 'auto' = 'auto'): Promise<string> {
  const key = `${model}|${src}`;
  let hit = cache.get(key);
  if (!hit) {
    hit = (queue = queue.then(async () => {
      const v = await viewer();
      await v.loadSkin(src, {
        model: model === 'auto' ? 'auto-detect' : model === 'slim' ? 'slim' : 'default',
      });
      v.render();
      return v.canvas.toDataURL('image/png');
    })) as Promise<string>;
    cache.set(key, hit);
    hit.catch(() => cache.delete(key));
  }
  return hit;
}
