import { useCallback, useEffect, useState } from 'react';
import PlayerRender, { POSES, type Pose } from '../components/PlayerRender';
import SkinDoll from '../components/SkinDoll';
import { NavCell, NavLabel, PxBox, PxButton, TT } from '../components/px/Px';
import { api, isTauri, type Account, type Skin } from '../lib/api';

export default function Cosmetics({
  account,
  pose,
  onPose,
  onSkinChange,
}: {
  account: Account | null;
  pose: Pose;
  onPose: (p: Pose) => void;
  onSkinChange: () => Promise<void> | void;
}) {
  const [skins, setSkins] = useState<Skin[]>([]);
  const [data, setData] = useState<Record<string, string>>({});
  const [picked, setPicked] = useState<string | null>(null);
  const [tab, setTab] = useState<'SKINS' | 'CAPES'>('SKINS');
  const [note, setNote] = useState<string | null>(null);

  const load = useCallback(async () => {
    const list = await api.listSkins();
    setSkins(list);
    const entries = await Promise.all(
      list.map(async (s) => [s.name, await api.readSkin(s.name).catch(() => '')] as const),
    );
    setData(Object.fromEntries(entries.filter(([, v]) => v)));
    setPicked((cur) => cur ?? list.find((s) => s.selected)?.name ?? null);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const current = skins.find((s) => s.selected) ?? null;

  const importSkin = useCallback(async () => {
    setNote(null);
    try {
      const added = await api.importSkin();
      if (added) {
        await load();
        setPicked(added.name);
      }
    } catch (e) {
      setNote(String(e));
    }
  }, [load]);

  return (
    <div className="page">
      <div className="page__head">
        <h1 className="page__title">Cosmetics</h1>
        <PxButton family="soft" height="md" onClick={() => void importSkin()}>
          <TT size={20} tone="accent">
            ADD SKIN
          </TT>
        </PxButton>
      </div>

      {/* the page window: navbar + translucent pane */}
      <div className="win">
        <div className="win__bar">
          <NavCell label="SKINS" active={tab === 'SKINS'} onClick={() => setTab('SKINS')} />
          <NavCell label="CAPES" active={tab === 'CAPES'} onClick={() => setTab('CAPES')} />
          <div className="win__fill" />
          <NavLabel label={current ? current.name.toUpperCase() : 'NO SKIN SELECTED'} />
        </div>

        <div className="win__body">
          {tab === 'CAPES' ? (
            <PxBox family="panel" className="empty">
              <TT size={20} tone="dim">
                NO CAPES
              </TT>
              <span className="meta">
                Capes come from your Minecraft account. None are attached to this one.
              </span>
            </PxBox>
          ) : (
            <div className="wardrobe">
              {/* the viewer column */}
              <PxBox family="panel" className="viewer">
                <div className="viewer__stage">
                  <PlayerRender
                    skin={picked ? data[picked] : null}
                    pose={pose}
                    zoom={0.72}
                    interactive
                    className="viewer__canvas"
                  />
                </div>

                <div className="viewer__actions">
                  <PxButton
                    family="grey"
                    height="fill"
                    disabled={!picked || picked === current?.name}
                    onClick={() => setPicked(current?.name ?? null)}
                  >
                    <TT size={16}>CANCEL</TT>
                  </PxButton>
                  <PxButton
                    family="install"
                    height="fill"
                    disabled={!picked}
                    title={
                      account?.authenticated
                        ? 'Apply to your Minecraft account'
                        : 'Stores the choice locally; applying to the account needs a Microsoft sign-in'
                    }
                    onClick={async () => {
                      if (!picked) return;
                      setNote(null);
                      try {
                        await api.selectSkin(picked);
                        if (account?.authenticated) await api.uploadSkin(picked, 'classic');
                        await load();
                        await onSkinChange();
                      } catch (e) {
                        setNote(String(e));
                      }
                    }}
                  >
                    <TT size={16}>APPLY SKIN</TT>
                  </PxButton>
                </div>
              </PxBox>

              {/* the gallery: a window of its own inside this one */}
              <div className="win win--inner wardrobe__gallery">
                <div className="win__bar">
                  <NavCell
                    label={pose}
                    onClick={() => onPose(POSES[(POSES.indexOf(pose) + 1) % POSES.length])}
                  />
                  <div className="win__fill" />
                  <NavLabel label={`${skins.length} SKIN${skins.length === 1 ? '' : 'S'}`} />
                </div>

                <div className="win__body">
                  <div className="skin-grid scroll">
                    <PxButton
                      family="grey"
                      height="fill"
                      className="skin-tile"
                      onClick={() => void importSkin()}
                    >
                      <span className="skin-tile__stage">
                        <span className="skin-tile__plus" />
                      </span>
                      <span className="skin-tile__name">
                        <TT size={16} tone="dim">
                          ADD SKIN
                        </TT>
                      </span>
                    </PxButton>

                    {skins.map((s) => (
                      <PxBox
                        key={s.name}
                        family={picked === s.name ? 'accent' : 'panel'}
                        className="skin-tile"
                        onClick={() => setPicked(s.name)}
                        role="button"
                        tabIndex={0}
                        onKeyDown={(e) => e.key === 'Enter' && setPicked(s.name)}
                      >
                        <div className="skin-tile__stage">
                          <SkinDoll skin={data[s.name]} scale={6} />
                        </div>
                        <span className="skin-tile__name">
                          <TT size={16} tone="dim">
                            {s.name.toUpperCase()}
                          </TT>
                        </span>
                        {s.selected && (
                          <span className="skin-tile__badge">
                            <TT size={13}>CURRENT</TT>
                          </span>
                        )}
                      </PxBox>
                    ))}
                  </div>

                  {(note || !account?.authenticated) && (
                    <span className="meta wardrobe__note">
                      {note ??
                        (isTauri
                          ? 'Sign in with Microsoft to apply a skin to your account — the wardrobe still stores it locally.'
                          : 'Browser preview: skins live in the desktop app.')}
                    </span>
                  )}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
