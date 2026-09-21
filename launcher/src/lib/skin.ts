/**
 * Skin PNG inspection — the arm-model rule the wardrobe and the in-game
 * upload share, so the preview and Mojang never disagree again.
 *
 * Minecraft has no flag in the PNG for slim (3px) arms; the client infers
 * it from the texture. This is the same test skinview3d's `auto-detect`
 * runs (skinview-utils `inferModelType`): a 64×64 skin whose slim right-arm
 * padding — the two-pixel strips a classic arm would paint — is transparent
 * anywhere, or is uniformly black or white, is slim. Legacy 64×32 skins have
 * no left-arm data and are always classic.
 */
import type { SkinModel } from './api';

/** the four 2px strips a classic arm uses and a slim arm leaves blank
 *  (x, y, w, h at 64×64 scale) */
const SLIM_GAPS: [number, number, number, number][] = [
  [50, 16, 2, 4],
  [54, 20, 2, 12],
  [42, 48, 2, 4],
  [46, 52, 2, 12],
];

function every(
  ctx: CanvasRenderingContext2D,
  scale: number,
  test: (r: number, g: number, b: number, a: number) => boolean,
  area: [number, number, number, number],
): boolean {
  const [x, y, w, h] = area.map((n) => n * scale) as typeof area;
  const px = ctx.getImageData(x, y, w, h).data;
  for (let i = 0; i < px.length; i += 4) {
    if (!test(px[i], px[i + 1], px[i + 2], px[i + 3])) return false;
  }
  return true;
}

/** decide classic vs slim from a skin PNG data URL */
export async function detectSkinModel(src: string): Promise<SkinModel> {
  const img = new Image();
  img.src = src;
  await img.decode();
  const w = img.naturalWidth;
  const h = img.naturalHeight;
  if (w !== h) return 'classic'; // 64×32 legacy layout, or not a skin
  const scale = w / 64;
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const ctx = c.getContext('2d', { willReadFrequently: true })!;
  ctx.drawImage(img, 0, 0);
  const opaque = (_r: number, _g: number, _b: number, a: number) => a === 0xff;
  const black = (r: number, g: number, b: number, a: number) => r === 0 && g === 0 && b === 0 && a === 0xff;
  const white = (r: number, g: number, b: number, a: number) => r === 0xff && g === 0xff && b === 0xff && a === 0xff;
  const anyTransparent = SLIM_GAPS.some((area) => !every(ctx, scale, opaque, area));
  const allBlack = SLIM_GAPS.every((area) => every(ctx, scale, black, area));
  const allWhite = SLIM_GAPS.every((area) => every(ctx, scale, white, area));
  return anyTransparent || allBlack || allWhite ? 'slim' : 'classic';
}
