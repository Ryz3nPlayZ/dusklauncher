import { useState } from 'react';
import { PixelIcon } from '../components/PixelIcon';
import SkinPreview2D from '../components/SkinPreview2D';
import LazySkinViewer from '../player/LazySkinViewer';
import { useSkins } from '../stores/skins';
import { useAccount } from '../stores/account';
import { playSfx } from '../sfx/sfx';

type Tab = 'inventory' | 'skins' | 'registry';

/**
 * Wireframe: main panel (tabs + lists) on the left, 3D inspector pinned on
 * the right. The inspector is ALWAYS present — switching tabs never moves
 * or unmounts the viewer, so lighting/rotation state survives tab switches.
 *
 *   +--------------------------------------+ +------------------+
 *   | INVENTORY | SKINS | REGISTRY        | | 3D PREVIEW  [-][+] |
 *   | +-----+ +-----+ +-----+ +-----+    | |                  |
 *   | | tile| | tile| | tile| | tile|    | |    (viewer)      |
 *   | +-----+ +-----+ +-----+ +-----+    | |                  |
 *   |  name    name    name    name       | |  Friendly Name   |
 *   +--------------------------------------+ [CANCEL] [APPLY]  |
 *                                              +------------------+
 */
export default function Skins() {
  const [tab, setTab] = useState<Tab>('skins');
  const [inspect, setInspect] = useState<string | null>(null);
  const [zoom, setZoom] = useState(1);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const { skins, dataUrls, importSkin, select, rename, remove } = useSkins();
  const account = useAccount((s) => s.account);

  const inspectedName = inspect ?? skins.find((s) => s.selected)?.name ?? null;
  const inspectedUrl = inspectedName ? dataUrls[inspectedName] || null : null;

  async function commitRename(oldName: string) {
    const next = draft.trim();
    setRenaming(null);
    if (!next || next === oldName) return;
    const ok = await rename(oldName, next);
    if (ok && inspect === oldName) setInspect(next);
  }

  return (
    <div className="page">
      <div className="page__head">
        <h1 className="page__title">COSMETICS</h1>
      </div>

      <div className="cos-layout">
        <section className="pcard cos-main">
          <nav className="ptabs cos-tabs">
            {(['inventory', 'skins', 'registry'] as Tab[]).map((t) => (
              <button
                key={t}
                className={`ptab ${tab === t ? 'ptab--active' : ''}`}
                onClick={() => {
                  setTab(t);
                  playSfx('tab');
                }}
              >
                {t.toUpperCase()}
              </button>
            ))}
          </nav>

          <div className="cos-body">
            {tab === 'skins' && (
              <div className="skins-grid scroll-y">
                <button className="skin-card skin-card--add" onClick={() => void importSkin()}>
                  <div className="skin-card--add__figure">
                    <PixelIcon name="plus" size={18} />
                  </div>
                  <span className="font-pixel text-3">ADD SKIN</span>
                </button>

                {skins.map((s) => {
                  const url = dataUrls[s.name];
                  const isRenaming = renaming === s.name;
                  return (
                    <article
                      key={s.name}
                      className={`skin-card ${s.selected ? 'is-current' : ''}`}
                      onClick={() => {
                        if (!isRenaming) setInspect(s.name);
                      }}
                    >
                      <div className="skin-card__art">
                        {url ? (
                          <SkinPreview2D skinUrl={url} />
                        ) : (
                          <div className="skin2d skin2d--broken" />
                        )}
                        {s.selected && <span className="skin-card__badge font-pixel">CURRENT</span>}
                      </div>
                      <div className="skin-card__foot">
                        {isRenaming ? (
                          <input
                            className="pinput skin-card__rename"
                            autoFocus
                            value={draft}
                            maxLength={48}
                            onChange={(e) => setDraft(e.target.value)}
                            onClick={(e) => e.stopPropagation()}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter') void commitRename(s.name);
                              if (e.key === 'Escape') setRenaming(null);
                            }}
                            onBlur={() => void commitRename(s.name)}
                          />
                        ) : (
                          <span className="skin-card__name" title={s.name}>
                            {s.name}
                          </span>
                        )}
                        <span className="skin-card__tools">
                          {!s.selected && !isRenaming && (
                            <button
                              title="Set as launcher skin"
                              onClick={(e) => {
                                e.stopPropagation();
                                void select(s.name);
                              }}
                            >
                              <PixelIcon name="check" size={10} />
                            </button>
                          )}
                          {!isRenaming && (
                            <button
                              title="Rename skin"
                              onClick={(e) => {
                                e.stopPropagation();
                                setDraft(s.name);
                                setRenaming(s.name);
                              }}
                            >
                              <PixelIcon name="pencil" size={10} />
                            </button>
                          )}
                          <button
                            title="Delete skin"
                            onClick={(e) => {
                              e.stopPropagation();
                              void remove(s.name);
                            }}
                          >
                            <PixelIcon name="trash" size={10} />
                          </button>
                        </span>
                      </div>
                      {inspectedName === s.name && (
                        <span className="skin-card__inspect font-pixel">INSPECTING</span>
                      )}
                    </article>
                  );
                })}

                {skins.length === 0 && (
                  <div className="skins-empty text-3">
                    No skins yet — import a 64x64 PNG with the ADD SKIN tile. It renders on your
                    home avatar instantly; applying it in-game needs Microsoft login.
                  </div>
                )}
              </div>
            )}

            {tab !== 'skins' && (
              <div className="modpacks__empty" style={{ flex: 1 }}>
                <PixelIcon name="diamond" size={24} className="text-3" />
                <p className="text-3">
                  {tab === 'inventory'
                    ? 'Cosmetics inventory lands with network accounts — no cosmetics, no ads, ever.'
                    : 'The cosmetics registry opens when online accounts are wired.'}
                </p>
              </div>
            )}
          </div>
        </section>

        <aside className="skins-inspector pcard">
          <div className="skins-inspector__head">
            <span className="font-pixel text-3">3D PREVIEW</span>
            <div className="zoom-btns">
              <button
                title="Zoom out"
                onClick={() => setZoom((z) => Math.max(0.6, z - 0.15))}
              >
                <PixelIcon name="minus" size={9} />
              </button>
              <button title="Zoom in" onClick={() => setZoom((z) => Math.min(2, z + 0.15))}>
                <PixelIcon name="plus" size={9} />
              </button>
            </div>
          </div>
          <div className="skins-inspector__stage">
            {inspectedUrl ? (
              <LazySkinViewer
                key={inspectedUrl}
                skinUrl={inspectedUrl}
                width={280}
                height={380}
                zoom={zoom}
                autoRotate
                className="skins-inspector__viewer"
              />
            ) : (
              <div className="skins-inspector__empty">
                <PixelIcon name="shirt" size={40} className="text-3" />
                <p className="font-pixel text-3">NO SKIN</p>
                <p className="text-3">Import one with the ADD SKIN tile.</p>
              </div>
            )}
          </div>
          <div className="skins-inspector__tag font-pixel" title={inspectedName ?? undefined}>
            {inspectedName ?? 'NO SKIN'}
          </div>
          <p className="skins-inspector__hint text-3">Drag to spin · scroll or +/− to zoom</p>
          <div className="skins-inspector__actions">
            <button
              className="pbtn pbtn--block"
              onClick={() => setInspect(null)}
              disabled={!inspect}
            >
              CANCEL
            </button>
            <button
              className="pbtn pbtn--block pbtn--install"
              disabled={!inspect || !account?.authenticated}
              title={
                account?.authenticated
                  ? 'Apply this skin to your Mojang account'
                  : 'Applying skins in-game requires Microsoft login (coming with accounts)'
              }
            >
              APPLY SKIN
            </button>
          </div>
        </aside>
      </div>
    </div>
  );
}
