import { useEffect, useState, type ReactNode } from 'react';
import { convertFileSrc } from '@tauri-apps/api/core';
import PixelGlyph from '../components/px/PixelGlyph';
import { NavCell, PxBox, PxButton, TT } from '../components/px/Px';
import instanceBanner from '../assets/brand/instance-banner.webp';
import { api, isTauri, type Wallpaper } from '../lib/api';

/* ── WALLPAPERS ───────────────────────────────────────────────────────────
   The instances page's layout, one tile per wallpaper: the picture (or the
   video's first frame) in the card frame, its name, kind, and USE + a red
   delete square. The built-in scene is the first tile, so switching back
   is the same gesture as picking anything else. */

const FILTERS = ['ALL', 'IMAGES', 'VIDEOS'] as const;
type Filter = (typeof FILTERS)[number];

/** "34455d9192-minecraft-sunset.3840x2160.mp4" → "MINECRAFT SUNSET" —
    the extension, a leading upload id and a trailing resolution all go */
export const wallpaperLabel = (name: string) =>
  name
    .replace(/\.[^.]+$/, '')
    .replace(/^[0-9a-f]{6,}[-_ ]+/i, '')
    .replace(/[-_.]*\d{3,4}x\d{3,4}$/i, '')
    .replace(/[-_.]+/g, ' ')
    .trim()
    .toUpperCase() || name.toUpperCase();

export default function Wallpapers({
  current,
  onPick,
  onBack,
}: {
  /** the settings value: '' for the built-in scene */
  current: string;
  onPick: (name: string) => void;
  onBack: () => void;
}) {
  const [filter, setFilter] = useState<Filter>('ALL');
  const [walls, setWalls] = useState<Wallpaper[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void api
      .listWallpapers()
      .then(setWalls)
      .catch((e) => {
        setWalls([]);
        setError(String(e));
      });
  }, []);

  const importOne = async () => {
    setError(null);
    try {
      const w = await api.importWallpaper();
      if (!w) return; // cancelled
      setWalls((list) => [...(list ?? []).filter((x) => x.name !== w.name), w]);
      onPick(w.name);
    } catch (e) {
      setError(String(e));
    }
  };

  const remove = async (name: string) => {
    setError(null);
    try {
      await api.removeWallpaper(name);
      setWalls((list) => (list ?? []).filter((x) => x.name !== name));
      if (current === name) onPick('');
    } catch (e) {
      setError(String(e));
    }
  };

  const shown = (walls ?? []).filter(
    (w) => filter === 'ALL' || (filter === 'VIDEOS' ? w.kind === 'video' : w.kind === 'image'),
  );

  return (
    <div className="page">
      <div className="page__head">
        <PxButton family="red" height="md" className="browse__back" onClick={onBack}>
          <PixelGlyph glyph="left" size={22} color="var(--r-co)" />
          <TT size={20} tone="red">
            BACK
          </TT>
        </PxButton>
        <h1 className="page__title">Wallpapers</h1>
        <PxButton
          family="blue"
          height="md"
          className="page__cta"
          disabled={!isTauri}
          title={isTauri ? 'Pick an image or video from disk' : 'Needs the desktop app'}
          onClick={() => void importOne()}
        >
          <TT size={16} tone="blue">
            IMPORT
          </TT>
        </PxButton>
      </div>

      <div className="win">
        <div className="win__bar">
          {FILTERS.map((f) => (
            <NavCell key={f} label={f} active={filter === f} onClick={() => setFilter(f)} />
          ))}
          <div className="win__fill">
            {error && <span className="meta editor__count">{error}</span>}
          </div>
        </div>

        <div className="win__body win__body--tiles">
          <div className="grid scroll">
            {filter === 'ALL' && (
              <WallCard
                name="BUILT-IN SCENE"
                info="Parallax · follows the theme"
                active={current === ''}
                preview={<img className="card__banner" src={instanceBanner} alt="" draggable={false} />}
                onUse={() => onPick('')}
              />
            )}
            {shown.map((w) => (
              <WallCard
                key={w.name}
                name={wallpaperLabel(w.name)}
                info={`${w.kind === 'video' ? 'Live wallpaper' : 'Image'} · ${w.name}`}
                active={current === w.name}
                preview={<WallPreview entry={w} />}
                onUse={() => onPick(w.name)}
                onDelete={() => void remove(w.name)}
              />
            ))}
            {walls && shown.length === 0 && filter !== 'ALL' && (
              <PxBox family="panel" className="empty">
                <TT size={20} tone="dim">
                  {`NO ${filter} YET`}
                </TT>
                <span className="meta">IMPORT takes .png / .jpg / .webp and .mp4 / .webm from disk.</span>
              </PxBox>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function WallCard({
  name,
  info,
  active,
  preview,
  onUse,
  onDelete,
}: {
  name: string;
  info: string;
  active: boolean;
  preview: ReactNode;
  onUse: () => void;
  onDelete?: () => void;
}) {
  return (
    <PxBox family="grey" className={['card', 'wall', active ? 'is-selected' : ''].join(' ')}>
      <span className="card__banner-frame">{preview}</span>
      <span className="card__name">{name}</span>
      <span className="card__info">{info}</span>
      <div className="card__row">
        <PxButton
          family={active ? 'accent' : 'grey'}
          height="fill"
          className="card__play"
          disabled={active}
          onClick={onUse}
        >
          <TT size={22} tone="accent" sx={1.15}>
            {active ? 'IN USE' : 'USE'}
          </TT>
        </PxButton>
        {onDelete && (
          <PxButton family="red" height="fill" className="card__gear" title="Delete this wallpaper" onClick={onDelete}>
            <PixelGlyph glyph="close" size={16} color="var(--r-co)" />
          </PxButton>
        )}
      </div>
    </PxBox>
  );
}

/* the file itself, through the asset protocol; the browser preview has no
   files behind its fixtures, so it draws a flat stand-in */
function WallPreview({ entry }: { entry: Wallpaper }) {
  if (!isTauri) return <span className="card__banner wall__stub" />;
  const src = convertFileSrc(entry.path);
  return entry.kind === 'video' ? (
    <video className="card__banner" src={src} muted playsInline preload="metadata" />
  ) : (
    <img className="card__banner" src={src} alt="" draggable={false} />
  );
}
