/**
 * Generates original pixel art assets by hand-encoding PNGs (no deps):
 *  - public/img/head-placeholder.png  (nav account avatar)
 *  - src/assets/skins/default-skin.png (64x64 Minecraft-format skin:
 *    full-face helmet character with a glowing visor — original art)
 *
 * Run: node scripts/gen-art.mjs
 */
import { deflateSync } from 'node:zlib';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

// ── minimal PNG encoder ─────────────────────────────────────────────────────

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(...bufs) {
  let c = 0xffffffff;
  for (const buf of bufs)
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const typeBuf = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typeBuf, data));
  return Buffer.concat([len, typeBuf, data, crc]);
}

function encodePng(width, height, rgba) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ── tiny canvas ─────────────────────────────────────────────────────────────

function makeCanvas(w, h) {
  const buf = Buffer.alloc(w * h * 4, 0);
  const hex = (s) => [
    parseInt(s.slice(1, 3), 16),
    parseInt(s.slice(3, 5), 16),
    parseInt(s.slice(5, 7), 16),
  ];
  return {
    w,
    h,
    px(x, y, color, alpha = 255) {
      if (x < 0 || y < 0 || x >= w || y >= h) return;
      const i = (y * w + x) * 4;
      const [r, g, b] = hex(color);
      buf[i] = r;
      buf[i + 1] = g;
      buf[i + 2] = b;
      buf[i + 3] = alpha;
    },
    rect(x, y, rw, rh, color, alpha = 255) {
      for (let yy = y; yy < y + rh; yy++) for (let xx = x; xx < x + rw; xx++) this.px(xx, yy, color, alpha);
    },
    toPng() {
      return encodePng(w, h, buf);
    },
  };
}

// ── head placeholder (original "dusk knight" helm) ──────────────────────────

const HELM = {
  edge: '#16121c',
  dark: '#241e2e',
  mid: '#332b40',
  hi: '#463b56',
  visor: '#ff1e43',
  visorHi: '#ff5a75',
};

function genHead() {
  const c = makeCanvas(8, 8);
  c.rect(0, 0, 8, 8, HELM.dark);
  c.rect(1, 1, 6, 6, HELM.mid);
  // visor slit
  c.rect(2, 3, 4, 2, HELM.edge);
  c.px(2, 3, HELM.visor);
  c.rect(3, 3, 1, 2, HELM.visorHi);
  c.px(5, 3, HELM.visor);
  // crest
  c.rect(3, 0, 2, 1, HELM.visor);
  c.rect(0, 0, 8, 1, HELM.edge);
  c.rect(0, 7, 8, 1, HELM.edge);
  c.px(7, 1, HELM.hi);
  c.px(1, 6, HELM.hi);
  return c.toPng();
}

// ── default skin: full-helm dusk knight ─────────────────────────────────────

function shade([r, g, b], f) {
  const cl = (v) => Math.max(0, Math.min(255, Math.round(v)));
  return `#${[cl(r * f), cl(g * f), cl(b * f)].map((v) => v.toString(16).padStart(2, '0')).join('')}`;
}
const rgb = (s) => [
  parseInt(s.slice(1, 3), 16),
  parseInt(s.slice(3, 5), 16),
  parseInt(s.slice(5, 7), 16),
];

function genSkin() {
  const c = makeCanvas(64, 64);
  const BASE = {
    armor: '#332b40',
    suit: '#1b1820',
    red: '#ff1e43',
    redHi: '#ff5a75',
    boot: '#131019',
  };

  // face helpers: shade by face direction for a chunky 3D feel
  const F = { top: 1.25, bottom: 0.55, right: 0.8, left: 0.8, front: 1.0, back: 0.7 };
  const face = (x, y, w, h, color, f) => c.rect(x, y, w, h, shade(rgb(color), f));

  const box = (ox, oy, w, h, d, color) => {
    // standard MC unwrap for a w×h×d box
    face(ox + d, oy, w, d, color, F.top); // top
    face(ox + d + w, oy, w, d, color, F.bottom); // bottom
    face(ox, oy + d, d, h, color, F.right); // right
    face(ox + d, oy + d, w, h, color, F.front); // front
    face(ox + d + w, oy + d, d, h, color, F.left); // left
    face(ox + d + w + d, oy + d, w, h, color, F.back); // back
  };

  // head (8x8x8) at origin + hat layer
  box(0, 0, 8, 8, 8, BASE.armor);
  // visor: front face is x 8..15, y 8..15 → slit rows 11-12
  c.rect(9, 11, 6, 2, HELM.edge);
  c.rect(10, 11, 4, 2, BASE.red);
  c.rect(11, 11, 1, 2, BASE.redHi);
  // crest on top face (x 8..15, y 0..7)
  c.rect(11, 1, 2, 6, BASE.red, 255);
  c.rect(11, 0, 2, 2, HELM.edge);
  // jaw edge on front
  c.rect(8, 14, 8, 1, shade(rgb(BASE.armor), 0.6));

  // body (8x12x4) at (16,16)
  box(16, 16, 8, 12, 4, BASE.armor);
  // chest core emblem on front (x 20..27, y 20..31)
  c.rect(23, 23, 2, 2, BASE.red);
  c.rect(22, 24, 4, 2, BASE.red);
  c.rect(23, 26, 2, 2, BASE.redHi);
  c.rect(23, 22, 2, 1, HELM.edge);
  // belt
  c.rect(20, 29, 8, 1, shade(rgb(BASE.armor), 0.6));
  c.rect(23, 29, 2, 1, BASE.red);

  // right arm (4x12x4) at (40,16)
  box(40, 16, 4, 12, 4, BASE.armor);
  // red band on front (x 44..47)
  c.rect(44, 23, 4, 1, BASE.red);
  c.rect(44, 28, 4, 1, shade(rgb(BASE.armor), 0.6));
  // glove
  c.rect(44, 30, 4, 2, shade(rgb(BASE.suit), 1.2));

  // left arm at (32,48)
  box(32, 48, 4, 12, 4, BASE.armor);
  c.rect(36, 59, 4, 2, shade(rgb(BASE.suit), 1.2)); // glove (front y offset 52..)

  // right leg (4x12x4) at (0,16)
  box(0, 16, 4, 12, 4, BASE.suit);
  // knee plate on front (x 4..7, y 20..31)
  c.rect(4, 23, 4, 2, shade(rgb(BASE.armor), F.front));
  c.rect(4, 29, 4, 3, BASE.boot);

  // left leg at (16,48)
  box(16, 48, 4, 12, 4, BASE.suit);
  c.rect(20, 55, 4, 2, shade(rgb(BASE.armor), F.front));
  c.rect(20, 61, 4, 3, BASE.boot);

  // hat layer: subtle helm crest fin (only top/back visible usually)
  c.rect(43, 0, 2, 8, shade(rgb(BASE.armor), 1.15), 255); // hat top crest
  c.rect(40, 8, 3, 8, shade(rgb(BASE.armor), 1.05), 140); // hat right edge glow

  return c.toPng();
}

// ── app icon: dusk helm on gradient plate, 1024×1024 (16×16 grid × 64) ─────

function genIcon() {
  const G = 64; // grid scale
  const c = makeCanvas(16 * G, 16 * G);

  // rounded-plate background with a dithered dusk gradient + dark rim
  const corner = (x, y) => (x === 0 || x === 15) && (y === 0 || y === 15);
  const corner2 = (x, y) =>
    ((x === 0 || x === 15) && (y === 1 || y === 14)) ||
    ((x === 1 || x === 14) && (y === 0 || y === 15));
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      if (corner(x, y) || corner2(x, y)) continue; // transparent rounded corners
      const t = y / 15;
      const band = Math.floor(t * 4);
      const dither = (x + y) % 2 === 0 ? 0 : 1;
      const shades = ['#2a2237', '#241e2e', '#1f1a28', '#1a1622'];
      let col = shades[Math.min(3, band + dither)];
      const rim =
        x === 0 || x === 15 || y === 0 || y === 15 ||
        ((x === 1 || x === 14) && (y < 2 || y > 13)) ||
        ((y === 1 || y === 14) && (x < 2 || x > 13));
      if (rim) col = '#16121c';
      c.rect(x * G, y * G, G, G, col);
    }
  }

  // helm: 8×8 head (same design as genHead) placed at grid (4,4)
  const H = (gx, gy, color, alpha = 255) => c.rect((4 + gx) * G, (4 + gy) * G, G, G, color, alpha);
  for (let y = 0; y < 8; y++) for (let x = 0; x < 8; x++) H(x, y, HELM.mid);
  for (let y = 1; y < 7; y++) for (let x = 1; x < 7; x++) H(x, y, HELM.mid);
  // block shading: top lighter, bottom darker
  for (let x = 0; x < 8; x++) H(x, 0, HELM.hi);
  for (let x = 0; x < 8; x++) H(x, 7, HELM.edge);
  for (let y = 0; y < 8; y++) {
    H(0, y, HELM.dark);
    H(7, y, HELM.dark);
  }
  // visor slit
  for (let x = 1; x < 7; x++) H(x, 3, HELM.edge);
  H(2, 3, HELM.visor);
  H(3, 3, HELM.visorHi);
  H(4, 3, HELM.visorHi);
  H(5, 3, HELM.visor);
  for (let x = 1; x < 7; x++) H(x, 4, HELM.edge);
  H(3, 4, HELM.visor, 200);
  H(4, 4, HELM.visor, 200);
  // crest
  H(3, 0, HELM.visor);
  H(4, 0, HELM.visorHi);
  // sparks
  H(11, 2, '#ffd000');
  H(12, 2, '#ffd000');
  H(11, 3, '#ffd000');
  H(12, 3, '#ffe873');
  H(12, 1, '#ffe873');

  return c.toPng();
}

mkdirSync(resolve(root, 'public/img'), { recursive: true });
mkdirSync(resolve(root, 'src/assets/skins'), { recursive: true });
mkdirSync(resolve(root, 'src-tauri/icons'), { recursive: true });
writeFileSync(resolve(root, 'public/img/head-placeholder.png'), genHead());
writeFileSync(resolve(root, 'src/assets/skins/default-skin.png'), genSkin());
writeFileSync(resolve(root, 'src-tauri/icons/icon.png'), genIcon());
console.log('generated head-placeholder.png + default-skin.png + icons/icon.png');
