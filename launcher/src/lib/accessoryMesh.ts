/**
 * Cosmetica-style model accessories on the skinview3d player.
 *
 * The client mod places an accessory with Cosmetica's transform chain
 * (docs/COSMETICS.md §5): in Minecraft part-local space, a model point m
 * (Blockbench pixels) lands at
 *
 *     q = m/16 + (−½, −¼, −½) + offset          offset = ((ox+dx)/16, (oy+dy)/16, oz/16)
 *     p = mirrored ? (qx, −qy, qz) : (−qx, −qy, qz)
 *
 * skinview3d's parts are the same joints in pixel units with y up and the
 * figure facing +z, i.e. sv = (16·px, −16·py, −16·pz). Folding that in:
 *
 *     sv = mirrored ? (Qx, Qy, −Qz) : (−Qx, Qy, −Qz)     Q = m + (−8, −4, −8) + 16·offset
 *
 * so the wardrobe shows the accessory exactly where the game draws it.
 */
import type * as THREE from 'three';
import type { AccessoryEntry, AccessoryModelJson } from './api';

type Vec3 = [number, number, number];

/** Cosmetica's per-attachment shift (pixels), plus the slim-arm nudge. */
function attachmentShift(att: AccessoryEntry['attachment'], slim: boolean): [number, number] {
  switch (att) {
    case 'head':
      return [0, 4];
    case 'left_arm':
      return [-1 + (slim ? 0.5 : 0), -6];
    case 'right_arm':
      return [1 - (slim ? 0.5 : 0), -6];
    default:
      return [0, -8];
  }
}

/** Which skinview3d part an accessory hangs off (mirroring swaps sides). */
export function partFor(att: AccessoryEntry['attachment'], mirrored: boolean) {
  const swap = (a: string, b: string) => (mirrored ? b : a);
  switch (att) {
    case 'head':
      return 'head';
    case 'left_arm':
      return swap('leftArm', 'rightArm');
    case 'right_arm':
      return swap('rightArm', 'leftArm');
    case 'left_leg':
      return swap('leftLeg', 'rightLeg');
    case 'right_leg':
      return swap('rightLeg', 'leftLeg');
    default:
      return 'body';
  }
}

// Cosmetica's FaceInfo vertex order: 0 = from, 1 = to, per axis
const FACES: Record<string, number[][]> = {
  down: [[0, 0, 1], [0, 0, 0], [1, 0, 0], [1, 0, 1]],
  up: [[0, 1, 0], [0, 1, 1], [1, 1, 1], [1, 1, 0]],
  north: [[1, 1, 0], [1, 0, 0], [0, 0, 0], [0, 1, 0]],
  south: [[0, 1, 1], [0, 0, 1], [1, 0, 1], [1, 1, 1]],
  west: [[0, 1, 0], [0, 0, 0], [0, 0, 1], [0, 1, 1]],
  east: [[1, 1, 1], [1, 0, 1], [1, 0, 0], [1, 1, 0]],
};

// BlockFaceUV: index 0 (u0,v0), 1 (u0,v1), 2 (u1,v1), 3 (u1,v0)
const faceU = (uv: number[], i: number) => (i % 4 > 1 ? uv[2] : uv[0]);
const faceV = (uv: number[], i: number) => (i % 4 > 0 && i % 4 < 3 ? uv[3] : uv[1]);

function defaultUv(face: string, from: Vec3, to: Vec3): number[] {
  switch (face) {
    case 'down':
    case 'up':
      return [from[0], from[2], to[0], to[2]];
    case 'north':
    case 'south':
      return [from[0], 16 - to[1], to[0], 16 - from[1]];
    default:
      return [from[2], 16 - to[1], to[2], 16 - from[1]];
  }
}

function rot2(p0: number, p1: number, o0: number, o1: number, deg: number): [number, number] {
  const a = (deg * Math.PI) / 180;
  const s = Math.sin(a);
  const c = Math.cos(a);
  p0 -= o0;
  p1 -= o1;
  return [p0 * c - p1 * s + o0, p0 * s + p1 * c + o1];
}

function rotateCorner(c: Vec3, o: Vec3, rx: number, ry: number, rz: number): Vec3 {
  let [x, y, z] = c;
  if (rx) [y, z] = rot2(y, z, o[1], o[2], rx);
  if (ry) [x, z] = rot2(x, z, o[0], o[2], -ry);
  if (rz) [x, y] = rot2(x, y, o[0], o[1], rz);
  return [x, y, z];
}

/**
 * Bake the model into a skinview3d part-local BufferGeometry (pixel units).
 * Exported so the placement math can be unit-tested without a renderer.
 */
export function bakeAccessory(
  three: typeof THREE,
  model: AccessoryModelJson,
  entry: AccessoryEntry,
  slim: boolean,
): THREE.BufferGeometry {
  const [dx, dy] = attachmentShift(entry.attachment, slim);
  const off: Vec3 = [entry.offset[0] + dx - 8, entry.offset[1] + dy - 4, entry.offset[2] - 8];
  const pos: number[] = [];
  const uvs: number[] = [];
  const idx: number[] = [];
  for (const el of model.elements ?? []) {
    const r = el.rotation;
    const origin: Vec3 = r?.origin ?? [0, 0, 0];
    let rx = r?.x ?? 0;
    let ry = r?.y ?? 0;
    let rz = r?.z ?? 0;
    if (r?.angle !== undefined) {
      if (r.axis === 'x') rx = r.angle;
      else if (r.axis === 'z') rz = r.angle;
      else ry = r.angle;
    }
    for (const [face, order] of Object.entries(FACES)) {
      const f = el.faces?.[face as keyof typeof el.faces];
      if (!f) continue;
      const uv = f.uv ?? defaultUv(face, el.from, el.to);
      const quadrant = ((f.rotation ?? 0) / 90) & 3;
      const base = pos.length / 3;
      for (let i = 0; i < 4; i++) {
        const o = order[i];
        const c = rotateCorner(
          [o[0] ? el.to[0] : el.from[0], o[1] ? el.to[1] : el.from[1], o[2] ? el.to[2] : el.from[2]],
          origin,
          rx,
          ry,
          rz,
        );
        const Q: Vec3 = [c[0] + off[0], c[1] + off[1], c[2] + off[2]];
        pos.push(entry.mirrored ? Q[0] : -Q[0], Q[1], -Q[2]);
        // three.js samples v from the bottom; Minecraft from the top
        uvs.push(faceU(uv, i + quadrant) / 16, 1 - faceV(uv, i + quadrant) / 16);
      }
      idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
    }
  }
  const geo = new three.BufferGeometry();
  geo.setAttribute('position', new three.Float32BufferAttribute(pos, 3));
  geo.setAttribute('uv', new three.Float32BufferAttribute(uvs, 2));
  geo.setIndex(idx);
  geo.computeVertexNormals();
  return geo;
}

/** Split a Cosmetica tilesheet (vertical, `frames` tiles) into canvases. */
export async function accessoryFrames(dataUrl: string, frames: number): Promise<HTMLCanvasElement[]> {
  const img = new Image();
  img.src = dataUrl;
  await img.decode();
  const n = frames > 1 && img.naturalHeight % frames === 0 ? frames : 1;
  const fh = img.naturalHeight / n;
  const out: HTMLCanvasElement[] = [];
  for (let f = 0; f < n; f++) {
    const c = document.createElement('canvas');
    c.width = img.naturalWidth;
    c.height = fh;
    c.getContext('2d')!.drawImage(img, 0, f * fh, img.naturalWidth, fh, 0, 0, img.naturalWidth, fh);
    out.push(c);
  }
  return out;
}

export function canvasTexture(three: typeof THREE, canvas: HTMLCanvasElement): THREE.CanvasTexture {
  const t = new three.CanvasTexture(canvas);
  t.magFilter = three.NearestFilter;
  t.minFilter = three.NearestFilter;
  t.colorSpace = three.SRGBColorSpace;
  return t;
}
