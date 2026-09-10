/**
 * Generates the bundled default skin — the "dusk knight" (DESIGN.md brand:
 * full-helm character, glowing red visor). Original art, drawn here as a
 * pixel grid; no Mojang asset is ever copied.
 *
 *   node scripts/gen-default-skin.mjs
 */
import { deflateSync } from 'node:zlib';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const W = 64;
const H = 64;
const px = new Uint8Array(W * H * 4); // RGBA, transparent by default

const C = {
  helm: '#3a3f4a',
  helmTop: '#4a5060',
  helmDark: '#2b2f38',
  visorUp: '#ff1100',
  visorLo: '#dd0626',
  robe: '#1e1e1e',
  robeDark: '#161616',
  trim: '#323232',
  gold: '#ffc600',
  goldLo: '#dd7f06',
  arm: '#23262d',
  armDark: '#1a1d23',
  glove: '#3a3f4a',
  leg: '#16181c',
  boot: '#2b2f38',
};

const hex = (h) => [
  parseInt(h.slice(1, 3), 16),
  parseInt(h.slice(3, 5), 16),
  parseInt(h.slice(5, 7), 16),
  255,
];

function set(x, y, color) {
  if (x < 0 || y < 0 || x >= W || y >= H) return;
  const [r, g, b, a] = hex(color);
  const i = (y * W + x) * 4;
  px[i] = r;
  px[i + 1] = g;
  px[i + 2] = b;
  px[i + 3] = a;
}

function rect(x, y, w, h, color) {
  for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) set(x + i, y + j, color);
}

// ── head: helm on all four sides, lighter crown, dark chin band ──────────
rect(0, 8, 32, 8, C.helm); // right / front / left / back
rect(8, 0, 8, 8, C.helmTop); // top
rect(16, 0, 8, 8, C.helmDark); // bottom
for (const faceX of [0, 8, 16, 24]) {
  rect(faceX, 8, 8, 1, C.helmTop); // crown highlight row
  rect(faceX, 14, 8, 2, C.helmDark); // chin band
}
// visor on the front face only (8,8)-(16,16)
rect(9, 10, 6, 2, C.visorUp);
rect(9, 12, 6, 1, C.visorLo);
set(8, 11, C.visorLo);
set(15, 11, C.visorLo);

// ── body: robe with a gold clasp on the chest ────────────────────────────
rect(16, 16, 24, 16, C.robe);
rect(20, 16, 8, 4, C.trim); // top
rect(28, 16, 8, 4, C.robeDark); // bottom
rect(16, 20, 4, 12, C.robeDark); // right side
rect(28, 20, 4, 12, C.robeDark); // left side
rect(20, 20, 8, 2, C.trim); // collar, front
rect(32, 20, 8, 2, C.trim); // collar, back
rect(23, 23, 2, 2, C.gold); // clasp
rect(23, 25, 2, 1, C.goldLo);
rect(20, 30, 8, 2, C.robeDark); // hem, front
rect(32, 30, 8, 2, C.robeDark); // hem, back

// ── arms: sleeves ending in gauntlets ────────────────────────────────────
for (const [ax, ay] of [
  [40, 16],
  [32, 48],
]) {
  rect(ax, ay, 16, 16, C.arm);
  rect(ax + 4, ay, 4, 4, C.armDark); // top
  rect(ax + 8, ay, 4, 4, C.armDark); // bottom
  rect(ax, ay + 4, 16, 1, C.trim); // shoulder line
  rect(ax, ay + 12, 16, 4, C.glove); // gauntlet
}

// ── legs: dark trousers, boots at the foot ───────────────────────────────
for (const [lx, ly] of [
  [0, 16],
  [16, 48],
]) {
  rect(lx, ly, 16, 16, C.leg);
  rect(lx + 4, ly, 4, 4, C.robeDark);
  rect(lx + 8, ly, 4, 4, C.robeDark);
  rect(lx, ly + 12, 16, 4, C.boot);
}

// ── PNG encode (no dependencies) ─────────────────────────────────────────
const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(W, 0);
ihdr.writeUInt32BE(H, 4);
ihdr[8] = 8; // bit depth
ihdr[9] = 6; // RGBA
const raw = Buffer.alloc(H * (W * 4 + 1));
for (let y = 0; y < H; y++) {
  raw[y * (W * 4 + 1)] = 0; // filter: none
  Buffer.from(px.buffer, y * W * 4, W * 4).copy(raw, y * (W * 4 + 1) + 1);
}
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
]);

const out = resolve(dirname(fileURLToPath(import.meta.url)), '../src/assets/skins/dusk-knight.png');
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, png);
console.log(`wrote ${out} (${png.length} bytes)`);
