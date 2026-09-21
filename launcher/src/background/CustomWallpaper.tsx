import { useEffect, useRef, useState } from 'react';
import { convertFileSrc } from '@tauri-apps/api/core';
import { api, isTauri, type Wallpaper } from '../lib/api';
import SceneBackground, { type SceneName } from './SceneBackground';
import './scene.css';

/**
 * A user-imported wallpaper (live-wallpaper video or image) streamed from
 * `<data>/wallpapers/` through the asset protocol. Falls back to the built-in
 * scene when the saved name no longer resolves (file deleted by hand).
 */
export default function CustomWallpaper({
  name,
  scene,
  paused,
}: {
  name: string;
  scene: SceneName;
  paused?: boolean;
}) {
  const [entry, setEntry] = useState<Wallpaper | null>(null);
  const [missing, setMissing] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    let live = true;
    setEntry(null);
    setMissing(false);
    api
      .listWallpapers()
      .then((list) => {
        if (!live) return;
        const hit = list.find((w) => w.name === name);
        if (hit) setEntry(hit);
        else setMissing(true);
      })
      .catch(() => live && setMissing(true));
    return () => {
      live = false;
    };
  }, [name]);

  useEffect(() => {
    const v = videoRef.current;
    if (!v) return;
    if (paused) v.pause();
    else void v.play().catch(() => {}); // autoplay can race the source attach
  }, [paused, entry?.path]);

  if (missing) return <SceneBackground scene={scene} />;
  // until the entry resolves (or in the browser preview) stay dark rather
  // than flashing the scene underneath
  if (!entry || !isTauri) return <div className="scene" aria-hidden="true" />;

  const src = convertFileSrc(entry.path);
  return (
    <div className="scene" aria-hidden="true">
      {entry.kind === 'video' ? (
        <video
          key={src}
          ref={videoRef}
          className="scene__media"
          src={src}
          autoPlay={!paused}
          loop
          muted
          playsInline
        />
      ) : (
        <img key={src} className="scene__media" src={src} alt="" draggable={false} />
      )}
    </div>
  );
}
