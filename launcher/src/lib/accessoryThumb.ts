/**
 * Grid thumbnails for model accessories: the whole baked model, turned a
 * little so its depth reads, rendered by one shared offscreen WebGL
 * renderer (a grid of tiles would otherwise exhaust the browser's context
 * limit) and copied out to plain canvases, one per animation frame.
 */
import type * as THREE from 'three';
import type { AccessoryEntry, AccessoryModelJson } from './api';
import { accessoryFrames, bakeAccessory, canvasTexture } from './accessoryMesh';

interface Stage {
  three: typeof THREE;
  renderer: THREE.WebGLRenderer;
  scene: THREE.Scene;
  camera: THREE.OrthographicCamera;
}

let stage: Promise<Stage> | null = null;

function getStage(): Promise<Stage> {
  return (stage ??= import('three').then((three) => {
    const renderer = new three.WebGLRenderer({ alpha: true, antialias: false, preserveDrawingBuffer: true });
    renderer.setPixelRatio(1);
    renderer.setClearColor(0x000000, 0);
    const scene = new three.Scene();
    // skinview3d's light levels, so a thumbnail matches the figure beside it
    scene.add(new three.AmbientLight(0xffffff, 3));
    const sun = new three.DirectionalLight(0xffffff, 1.2);
    sun.position.set(0.5, 1, 1);
    scene.add(sun);
    const camera = new three.OrthographicCamera(-1, 1, 1, -1, -1000, 1000);
    camera.position.set(0, 0, 100);
    return { three, renderer, scene, camera };
  }));
}

/** Every frame of the accessory as a `px`-square canvas, framed to fit. */
export async function accessoryThumbnails(
  model: AccessoryModelJson,
  entry: AccessoryEntry,
  texture: string,
  px: number,
): Promise<HTMLCanvasElement[]> {
  const { three, renderer, scene, camera } = await getStage();
  const frames = await accessoryFrames(texture, entry.frames);
  // everything below is synchronous, so concurrent callers never share the scene

  const geometry = bakeAccessory(three, model, entry, false);
  const textures = frames.map((f) => canvasTexture(three, f));
  const material = new three.MeshLambertMaterial({
    map: textures[0],
    side: three.DoubleSide,
    transparent: true,
    alphaTest: 1e-5,
  });
  const mesh = new three.Mesh(geometry, material);
  const pivot = new three.Group();
  pivot.add(mesh);
  scene.add(pivot);

  // Tilt towards the viewer, unless that leaves the model a sliver (a halo
  // or brim modelled at an angle): then the pitch that fills the frame best.
  const box = new three.Box3();
  const fill = (pitch: number) => {
    pivot.rotation.set(pitch, -0.55, 0);
    pivot.updateMatrixWorld(true);
    box.setFromObject(pivot, true);
    const w = box.max.x - box.min.x;
    const h = box.max.y - box.min.y;
    return Math.min(w, h) / Math.max(w, h, 1e-6);
  };
  let best = 0.3;
  let bestFill = fill(best) * 1.3; // the default wins close calls
  for (const pitch of [0.9, -0.4, -0.9]) {
    const f = fill(pitch);
    if (f > bestFill) [best, bestFill] = [pitch, f];
  }
  fill(best);
  const out: HTMLCanvasElement[] = [];
  if (!box.isEmpty()) {
    const c = box.getCenter(new three.Vector3());
    const half = (Math.max(box.max.x - box.min.x, box.max.y - box.min.y) / 2) * 1.08;
    camera.left = c.x - half;
    camera.right = c.x + half;
    camera.top = c.y + half;
    camera.bottom = c.y - half;
    camera.updateProjectionMatrix();
    renderer.setSize(px, px, false);
    for (const t of textures) {
      material.map = t;
      material.needsUpdate = true;
      renderer.render(scene, camera);
      const canvas = document.createElement('canvas');
      canvas.width = px;
      canvas.height = px;
      canvas.getContext('2d')!.drawImage(renderer.domElement, 0, 0);
      out.push(canvas);
    }
  }

  scene.remove(pivot);
  geometry.dispose();
  material.dispose();
  textures.forEach((t) => t.dispose());
  return out;
}
