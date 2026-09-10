import { useCallback, useEffect, useState } from 'react';
import PlayerRender, { POSES, type Pose } from '../components/PlayerRender';
import SkinDoll from '../components/SkinDoll';
import { PxBox, PxButton, TT } from '../components/px/Px';
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
  const [zoom, setZoom] = useState(0.85);
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
        <h1 className="page__title">Cosmetics / Skins</h1>
        <PxButton family="grey" height="md" onClick={() => void importSkin()}>
          <TT size={16}>ADD SKIN</TT>
        </PxButton>
      </div>

      <div className="wardrobe">
        <PxBox family="panel" className="sheet wardrobe__left">
          <div className="sheet__tabs">
            <PxBox family="panel" height="md">
              <TT size={16} tone="dim">{`${skins.length} SKIN${skins.length === 1 ? '' : 'S'}`}</TT>
            </PxBox>
            <span className="tabstrip__spacer" />
            <PxBox family="panel" height="md">
              <TT size={16} tone="dim">
                {current ? current.name.toUpperCase() : 'NO SKIN SELECTED'}
              </TT>
            </PxBox>
          </div>
          <div className="sheet__body grid wardrobe__grid scroll">
            <PxButton family="grey" height="fill" className="skin-tile" onClick={() => void importSkin()}>
              <span className="skin-tile__stage">
                <span className="skin-tile__plus" />
              </span>
              <span className="skin-tile__name">
                <TT size={13} tone="dim">
                  ADD SKIN
                </TT>
              </span>
            </PxButton>

            {skins.map((s) => (
              <PxBox
                key={s.name}
                family={picked === s.name ? 'accent' : 'panel'}
                listing
                className="skin-tile"
                onClick={() => setPicked(s.name)}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => e.key === 'Enter' && setPicked(s.name)}
              >
                <div className="skin-tile__stage">
                  <SkinDoll skin={data[s.name]} scale={4} />
                </div>
                <span className="skin-tile__name">
                  <TT size={13} tone="dim">
                    {s.name.toUpperCase()}
                  </TT>
                </span>
                {s.selected && (
                  <span className="skin-tile__badge">
                    <TT size={11}>CURRENT</TT>
                  </span>
                )}
              </PxBox>
            ))}

          </div>
        </PxBox>

        <PxBox family="panel" className="inspector">
          <div className="inspector__zoom">
            <PxButton family="grey" height="sm" onClick={() => setZoom((z) => Math.max(0.4, z - 0.15))}>
              <TT size={20}>—</TT>
            </PxButton>
            <PxButton family="grey" height="sm" onClick={() => setZoom((z) => Math.min(1.6, z + 0.15))}>
              <TT size={20}>+</TT>
            </PxButton>
          </div>

          <div className="inspector__stage">
            <PlayerRender
              skin={picked ? data[picked] : null}
              pose={pose}
              zoom={zoom}
              interactive
              className="inspector__canvas"
            />
          </div>

          <div className="inspector__poses">
            {POSES.map((p) => (
              <PxButton
                key={p}
                family={pose === p ? 'accent' : 'grey'}
                height="sm"
                onClick={() => onPose(p)}
              >
                <TT size={11} tone={pose === p ? 'accent' : undefined}>
                  {p}
                </TT>
              </PxButton>
            ))}
          </div>

          {note && <span className="meta">{note}</span>}
          {!account?.authenticated && (
            <span className="meta">
              {isTauri
                ? 'Sign in with Microsoft to apply a skin to your account — the wardrobe still stores it locally.'
                : 'Browser preview: skins live in the desktop app.'}
            </span>
          )}

          <div className="inspector__zoom">
            <PxButton
              family="red"
              height="sm"
              disabled={!picked}
              onClick={async () => {
                if (!picked) return;
                await api.deleteSkin(picked);
                setPicked(null);
                await load();
              }}
            >
              <TT size={13} tone="red">
                DELETE
              </TT>
            </PxButton>
            <PxButton
              family="grey"
              height="sm"
              disabled={!picked || picked === current?.name}
              onClick={() => setPicked(current?.name ?? null)}
            >
              <TT size={13}>CANCEL</TT>
            </PxButton>
            <PxButton
              family="install"
              height="sm"
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
              <TT size={13} tone="green">
                APPLY SKIN
              </TT>
            </PxButton>
          </div>
        </PxBox>
      </div>
    </div>
  );
}
