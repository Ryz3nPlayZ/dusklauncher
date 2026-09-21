import { useCallback, useEffect, useMemo, useState } from 'react';
import AccessorySwatch from '../components/AccessorySwatch';
import CapeSwatch from '../components/CapeSwatch';
import PlayerRender, { POSES, type AccessoryView, type Pose } from '../components/PlayerRender';
import SkinSnapshot from '../components/SkinSnapshot';
import { NavCell, NavLabel, PxBox, PxButton, TT } from '../components/px/Px';
import {
  api,
  isTauri,
  type Account,
  type AccessoryEntry,
  type AccessoryModelJson,
  type CapeEntry,
  type Loadout,
  type Skin,
  type SkinModel,
} from '../lib/api';
import { detectSkinModel } from '../lib/skin';

/** the cape slot of a loadout, or null when nothing is equipped */
const equippedCape = (l: Loadout | null) => (typeof l?.cape === 'number' ? l.cape : null);
/** the accessories slot of a loadout (ids, in wear order) */
const equippedAccessories = (l: Loadout | null): number[] =>
  Array.isArray(l?.accessories) ? l.accessories.filter((v): v is number => typeof v === 'number') : [];
const sameIds = (a: number[], b: number[]) => a.length === b.length && a.every((v, i) => v === b[i]);

type Tab = 'SKINS' | 'CAPES' | 'ACCESSORIES';

export default function Cosmetics({
  account,
  pose,
  onPose,
  onSkinChange,
  onStore,
}: {
  account: Account | null;
  pose: Pose;
  onPose: (p: Pose) => void;
  onSkinChange: () => Promise<void> | void;
  /** the wardrobe only lists what the store has claimed; this is the way there */
  onStore: () => void;
}) {
  const [skins, setSkins] = useState<Skin[]>([]);
  const [data, setData] = useState<Record<string, string>>({});
  /* what the PNG says about each skin's arms — the game reads the same
     pixels, so AUTO here is what AUTO means in-game */
  const [detected, setDetected] = useState<Record<string, SkinModel>>({});
  const [picked, setPicked] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('SKINS');
  const [note, setNote] = useState<string | null>(null);

  // the look: the registry bundled in the client mod + the launcher-wide loadout
  const [capes, setCapes] = useState<CapeEntry[]>([]);
  const [capeData, setCapeData] = useState<Record<number, { cape: string; ears?: string }>>({});
  const [accessories, setAccessories] = useState<AccessoryEntry[]>([]);
  const [accData, setAccData] = useState<Record<number, { texture: string; model: AccessoryModelJson }>>({});
  const [loadout, setLoadout] = useState<Loadout | null>(null);
  const [pickedCape, setPickedCape] = useState<number | null>(null);
  const [pickedAcc, setPickedAcc] = useState<number[]>([]);
  const [capeNote, setCapeNote] = useState<string | null>(null);

  const load = useCallback(async () => {
    const list = await api.listSkins();
    setSkins(list);
    const entries = await Promise.all(
      list.map(async (s) => [s.name, await api.readSkin(s.name).catch(() => '')] as const),
    );
    setData(Object.fromEntries(entries.filter(([, v]) => v)));
    setPicked((cur) => cur ?? list.find((s) => s.selected)?.name ?? null);
    const models = await Promise.all(
      entries
        .filter(([, v]) => v)
        .map(async ([name, png]) => [name, await detectSkinModel(png).catch(() => 'classic' as const)] as const),
    );
    setDetected(Object.fromEntries(models));
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const loadWardrobe = useCallback(async () => {
    setCapeNote(null);
    try {
      const [catalog, lo, inv] = await Promise.all([api.listCosmetics(), api.getLoadout(), api.getInventory()]);
      // the wardrobe is what you own; the whole catalog is the store's
      const owned = new Set(inv.owned);
      catalog.capes = catalog.capes.filter((c) => owned.has(c.id));
      catalog.accessories = catalog.accessories.filter((a) => owned.has(a.id));
      setCapes(catalog.capes);
      setAccessories(catalog.accessories);
      setLoadout(lo);
      setPickedCape(equippedCape(lo));
      setPickedAcc(equippedAccessories(lo));
      const [capeEntries, accEntries] = await Promise.all([
        Promise.all(
          catalog.capes.map(async (c) => {
            const cape = await api.readCosmeticTexture('cape', c.id).catch(() => '');
            const ears = c.ears ? await api.readCosmeticTexture('ears', c.id).catch(() => '') : '';
            return [c.id, { cape, ears: ears || undefined }] as const;
          }),
        ),
        Promise.all(
          catalog.accessories.map(async (a) => {
            const [texture, model] = await Promise.all([
              api.readCosmeticTexture('accessory', a.id).catch(() => ''),
              api.readCosmeticModel(a.id).catch(() => null),
            ]);
            return [a.id, texture && model ? { texture, model } : null] as const;
          }),
        ),
      ]);
      setCapeData(Object.fromEntries(capeEntries.filter(([, v]) => v.cape)));
      setAccData(Object.fromEntries(accEntries.filter((e): e is readonly [number, { texture: string; model: AccessoryModelJson }] => !!e[1])));
    } catch (e) {
      setCapes([]);
      setAccessories([]);
      setCapeNote(String(e));
    }
  }, []);

  useEffect(() => {
    if (tab !== 'SKINS' && loadout === null) void loadWardrobe();
  }, [tab, loadout, loadWardrobe]);

  const current = skins.find((s) => s.selected) ?? null;
  /** the arm model the upload declares: read off the PNG the way the game does */
  const pickedModel: SkinModel = (picked ? detected[picked] : undefined) ?? 'classic';
  /** the previewed skin is the one already on the account / selected locally */
  const applied = !!picked && picked === current?.name;
  const wornCape = equippedCape(loadout);
  const wornAcc = useMemo(() => equippedAccessories(loadout), [loadout]);
  const lookDirty = pickedCape !== wornCape || !sameIds(pickedAcc, wornAcc);

  /** the accessories the viewer should wear right now (picked, with assets) */
  const previewAccessories = useMemo<AccessoryView[]>(
    () =>
      pickedAcc.flatMap((id) => {
        const entry = accessories.find((a) => a.id === id);
        const data = accData[id];
        return entry && data ? [{ entry, model: data.model, texture: data.texture }] : [];
      }),
    [pickedAcc, accessories, accData],
  );

  const toggleAccessory = useCallback((id: number) => {
    setPickedAcc((cur) => (cur.includes(id) ? cur.filter((v) => v !== id) : [...cur, id]));
  }, []);

  const applyLook = useCallback(async () => {
    setCapeNote(null);
    try {
      const next: Loadout = { ...(loadout ?? {}) };
      if (pickedCape === null) delete next.cape;
      else next.cape = pickedCape;
      if (pickedAcc.length === 0) delete next.accessories;
      else next.accessories = pickedAcc;
      const saved = await api.setLoadout(next);
      setLoadout(saved);
      setPickedCape(equippedCape(saved));
      setPickedAcc(equippedAccessories(saved));
    } catch (e) {
      setCapeNote(String(e));
    }
  }, [loadout, pickedCape, pickedAcc]);

  const cancelLook = useCallback(() => {
    setPickedCape(wornCape);
    setPickedAcc(wornAcc);
  }, [wornCape, wornAcc]);

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
          <NavCell label="ACCESSORIES" active={tab === 'ACCESSORIES'} onClick={() => setTab('ACCESSORIES')} />
          <div className="win__fill" />
          <NavLabel label={current ? current.name.toUpperCase() : 'NO SKIN SELECTED'} />
        </div>

        <div className="win__body">
          {tab !== 'SKINS' ? (
            <div className="wardrobe">
              {/* the viewer column: the account's skin wearing the picked look */}
              <PxBox family="panel" className="viewer">
                <div className="viewer__stage">
                  <PlayerRender
                    skin={current ? data[current.name] : null}
                    cape={pickedCape === null ? null : capeData[pickedCape]?.cape}
                    capeFrameMs={pickedCape === null ? 100 : (capes.find((c) => c.id === pickedCape)?.frameMs ?? 100)}
                    ears={pickedCape === null ? null : capeData[pickedCape]?.ears}
                    accessories={previewAccessories}
                    pose={pose}
                    zoom={0.62}
                    interactive
                    className="viewer__canvas"
                  />
                </div>

                <div className="viewer__actions">
                  <PxButton family="grey" height="fill" disabled={!lookDirty} onClick={cancelLook}>
                    <TT size={16}>CANCEL</TT>
                  </PxButton>
                  <PxButton
                    family="install"
                    height="fill"
                    disabled={!lookDirty}
                    title="Worn in-game by every Fabric instance through the bundled client mod"
                    onClick={() => void applyLook()}
                  >
                    <TT size={16}>APPLY LOOK</TT>
                  </PxButton>
                </div>
              </PxBox>

              <div className="win win--inner wardrobe__gallery">
                <div className="win__bar">
                  <NavCell
                    label={pose}
                    onClick={() => onPose(POSES[(POSES.indexOf(pose) + 1) % POSES.length])}
                  />
                  <div className="win__fill" />
                  <NavLabel
                    label={
                      tab === 'CAPES'
                        ? `${capes.length} CAPE${capes.length === 1 ? '' : 'S'}`
                        : `${accessories.length} ACCESSOR${accessories.length === 1 ? 'Y' : 'IES'}`
                    }
                  />
                </div>

                <div className="win__body">
                  {tab === 'CAPES' ? (
                    <div className="skin-grid scroll">
                      <PxBox
                        /* "none" is a real choice: the green ring when nothing is worn */
                        family={pickedCape === null ? 'accent' : wornCape === null ? 'green' : 'panel'}
                        className="skin-tile"
                        onClick={() => setPickedCape(null)}
                        role="button"
                        tabIndex={0}
                        onKeyDown={(e) => e.key === 'Enter' && setPickedCape(null)}
                      >
                        <div className="skin-tile__stage">
                          <span className="skin-tile__none" />
                        </div>
                        <span className="skin-tile__name">
                          <TT size={16} tone={wornCape === null ? 'green' : 'dim'}>
                            NONE
                          </TT>
                        </span>
                      </PxBox>

                      {capes.map((c) => (
                        <PxBox
                          key={c.id}
                          family={pickedCape === c.id ? 'accent' : wornCape === c.id ? 'green' : 'panel'}
                          className="skin-tile"
                          onClick={() => setPickedCape(c.id)}
                          role="button"
                          tabIndex={0}
                          onKeyDown={(e) => e.key === 'Enter' && setPickedCape(c.id)}
                          title={[c.glint && 'glint', c.ears && 'ears', c.upsideDown && 'upside down']
                            .filter(Boolean)
                            .join(' · ')}
                        >
                          <div className="skin-tile__stage">
                            <CapeSwatch src={capeData[c.id]?.cape} scale={6} frameMs={c.frameMs} />
                          </div>
                          <span className="skin-tile__name">
                            <TT size={16} tone={wornCape === c.id ? 'green' : 'dim'}>
                              {c.name.toUpperCase()}
                            </TT>
                          </span>
                        </PxBox>
                      ))}
                      <GetMoreTile onClick={onStore} />
                    </div>
                  ) : (
                    <div className="skin-grid scroll">
                      {/* accessories stack: every tile is a toggle */}
                      {accessories.map((a) => {
                        const on = pickedAcc.includes(a.id);
                        const worn = wornAcc.includes(a.id);
                        return (
                          <PxBox
                            key={a.id}
                            family={on ? 'accent' : worn ? 'green' : 'panel'}
                            className="skin-tile"
                            onClick={() => toggleAccessory(a.id)}
                            role="checkbox"
                            aria-checked={on}
                            tabIndex={0}
                            onKeyDown={(e) => e.key === 'Enter' && toggleAccessory(a.id)}
                            title={`${a.attachment.replace('_', ' ')}${a.source ? ` · ${a.source}` : ''}`}
                          >
                            <div className="skin-tile__stage">
                              <AccessorySwatch src={accData[a.id]?.texture} model={accData[a.id]?.model} entry={a} />
                            </div>
                            <span className="skin-tile__name">
                              <TT size={16} tone={worn ? 'green' : 'dim'}>
                                {a.name.toUpperCase()}
                              </TT>
                            </span>
                          </PxBox>
                        );
                      })}
                      <GetMoreTile onClick={onStore} />
                    </div>
                  )}

                  <span className="meta wardrobe__note">
                    {capeNote ??
                      (isTauri
                        ? tab === 'CAPES'
                          ? 'Capes are drawn in-game by the bundled FasterClient mod. Players without a cape here still show their MinecraftCapes one. Claim more in the store.'
                          : 'Accessories are Cosmetica models drawn on your body parts by the bundled FasterClient mod. Pick as many as you like; claim more in the store.'
                        : 'Browser preview: the real cosmetics live in the client mod jar.')}
                  </span>
                </div>
              </div>
            </div>
          ) : (
            <div className="wardrobe">
              {/* the viewer column */}
              <PxBox family="panel" className="viewer">
                <div className="viewer__stage">
                  <PlayerRender
                    skin={picked ? data[picked] : null}
                    model={pickedModel}
                    pose={pose}
                    zoom={0.62}
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
                    disabled={!picked || applied}
                    title={
                      applied
                        ? 'This skin is already applied'
                        : account?.authenticated
                        ? 'Apply to your Minecraft account'
                        : 'Stores the choice locally; applying to the account needs a Microsoft sign-in'
                    }
                    onClick={async () => {
                      if (!picked) return;
                      setNote(null);
                      try {
                        await api.selectSkin(picked);
                        if (account?.authenticated) await api.uploadSkin(picked, pickedModel);
                        await load();
                        await onSkinChange();
                      } catch (e) {
                        setNote(String(e));
                      }
                    }}
                  >
                    <TT size={16}>{applied ? 'APPLIED' : 'APPLY'}</TT>
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
                        /* the skin on the account wears the green ring; the
                           one being previewed, the accent */
                        family={picked === s.name ? 'accent' : s.selected ? 'green' : 'panel'}
                        className="skin-tile"
                        onClick={() => setPicked(s.name)}
                        role="button"
                        tabIndex={0}
                        onKeyDown={(e) => e.key === 'Enter' && setPicked(s.name)}
                      >
                        <div className="skin-tile__stage">
                          <SkinSnapshot skin={data[s.name]} model={detected[s.name] ?? 'auto'} />
                        </div>
                        <span className="skin-tile__name">
                          <TT size={16} tone={s.selected ? 'green' : 'dim'}>
                            {s.name.toUpperCase()}
                          </TT>
                        </span>
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

/** the last tile of a wardrobe grid: the way to the store */
function GetMoreTile({ onClick }: { onClick: () => void }) {
  return (
    <PxButton family="grey" height="fill" className="skin-tile" onClick={onClick} title="Open the store">
      <span className="skin-tile__stage">
        <span className="skin-tile__plus" />
      </span>
      <span className="skin-tile__name">
        <TT size={16} tone="dim">
          GET MORE
        </TT>
      </span>
    </PxButton>
  );
}
