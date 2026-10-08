import { useEffect, useState } from 'react';
import { convertFileSrc } from '@tauri-apps/api/core';
import instanceBanner from '../assets/brand/instance-banner.webp';
import { isTauri, type Profile } from '../lib/api';

/** An instance's own picture, or the stock banner. The picture sits whole
    over a blurred copy of itself, so a square pack icon and a wide
    screenshot both fill the frame without being cropped; small pixel-art
    icons scale up crisp. */
export default function InstanceArt({ profile, className }: { profile: Profile; className?: string }) {
  const [broken, setBroken] = useState(false);
  const [small, setSmall] = useState(false);
  useEffect(() => {
    setBroken(false);
    setSmall(false);
  }, [profile.icon]);

  if (!profile.icon || broken) {
    return <img className={className} src={instanceBanner} alt="" draggable={false} />;
  }
  const src = isTauri ? convertFileSrc(profile.icon) : profile.icon;
  return (
    <span className={`art${className ? ` ${className}` : ''}`}>
      <img className="art__fill" src={src} alt="" draggable={false} />
      <img
        className={`art__pic${small ? ' is-small' : ''}`}
        src={src}
        alt=""
        draggable={false}
        onLoad={(e) => setSmall(e.currentTarget.naturalWidth <= 160)}
        onError={() => setBroken(true)}
      />
    </span>
  );
}
