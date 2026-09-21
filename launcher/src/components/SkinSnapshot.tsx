/**
 * A wardrobe tile's picture of a skin: the offscreen skinview3d still from
 * lib/skinSnapshot, with the flat paper doll standing in until it lands (or
 * if WebGL is unavailable).
 */
import { useEffect, useState } from 'react';
import steveSkin from '../assets/skins/steve.png';
import type { SkinModel } from '../lib/api';
import { snapshotSkin } from '../lib/skinSnapshot';
import SkinDoll from './SkinDoll';

export default function SkinSnapshot({
  skin,
  model = 'auto',
  className,
}: {
  skin?: string | null;
  model?: SkinModel | 'auto';
  className?: string;
}) {
  const src = skin || steveSkin;
  const [png, setPng] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setPng(null);
    snapshotSkin(src, model)
      .then((url) => !cancelled && setPng(url))
      .catch((e) => console.warn('skin snapshot failed', e));
    return () => {
      cancelled = true;
    };
  }, [src, model]);

  if (!png) return <SkinDoll skin={src} scale={6} className={className} />;
  return <img className={['snap', className ?? ''].join(' ')} src={png} alt="" draggable={false} />;
}
