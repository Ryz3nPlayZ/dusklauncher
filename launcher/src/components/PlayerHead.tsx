/**
 * The 8×8 front face of a skin PNG (plus its hat layer), scaled up with
 * nearest-neighbour. Cheap enough for lists — no canvas, no 3D.
 */
import defaultSkin from '../assets/skins/dusk-knight.png';

export default function PlayerHead({
  skin,
  size = 32,
  className,
}: {
  skin?: string | null;
  size?: number;
  className?: string;
}) {
  const src = skin || defaultSkin;
  const scale = size / 8; // the face is 8×8 in a 64-wide sheet
  const sheet = `${64 * scale}px ${64 * scale}px`;
  return (
    <div className={['head', className ?? ''].join(' ')} style={{ width: size, height: size }}>
      <div
        className="head__face"
        style={{
          backgroundImage: `url(${src})`,
          backgroundSize: sheet,
          backgroundPosition: `-${8 * scale}px -${8 * scale}px`,
        }}
      />
      <div
        className="head__hat"
        style={{
          backgroundImage: `url(${src})`,
          backgroundSize: sheet,
          backgroundPosition: `-${40 * scale}px -${8 * scale}px`,
        }}
      />
    </div>
  );
}
