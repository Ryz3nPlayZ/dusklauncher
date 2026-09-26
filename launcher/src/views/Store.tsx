import { useCallback, useEffect, useMemo, useState } from 'react';
import AccessorySwatch from '../components/AccessorySwatch';
import CapeSwatch from '../components/CapeSwatch';
import PlayerRender, { POSES, type AccessoryView, type Pose } from '../components/PlayerRender';
import { NavCell, NavLabel, PxBox, PxButton, TT } from '../components/px/Px';
import {
  api,
  type Account,
  type AccessoryEntry,
  type AccessoryModelJson,
  type CapeEntry,
  type Loadout,
  type StoreItem,
} from '../lib/api';

/**
 * The store: everything the bundled client mod can draw, whether or not the
 * account owns it yet. Picking an item tries it on over the look you wear;
 * BUY spends Dusk coins on it (the wallet, the prices and the inventory all
 * live on the Dusk API — animated capes 750, static items 500; coins come
 * from redeem codes in Settings), EQUIP writes it into the launcher-wide
 * loadout the mod reads. The wardrobe only ever lists what was bought here.
 */

type Filter = 'ALL' | 'CAPES' | 'ACCESSORIES';
type Kind = 'cape' | 'accessory';
type Picked = { kind: Kind; id: number };

const equippedCape = (l: Loadout | null) => (typeof l?.cape === 'number' ? l.cape : null);
const equippedAccessories = (l: Loadout | null): number[] =>
  Array.isArray(l?.accessories) ? l.accessories.filter((v): v is number => typeof v === 'number') : [];

/** what a cape does beyond being a cape, for the tile tooltip / detail line */
function capeTraits(c: CapeEntry, animated: boolean) {
  return [animated && 'animated', c.glint && 'glint', c.ears && 'ears', c.upsideDown && 'upside down'].filter(
    (v): v is string => typeof v === 'string',
  );
}

export default function Store({
  account,
  skin,
  pose,
  onPose,
  onWardrobe,
}: {
  account: Account | null;
  skin: string | null;
  pose: Pose;
  onPose: (p: Pose) => void;
  onWardrobe: () => void;
}) {
  const [capes, setCapes] = useState<CapeEntry[]>([]);
  const [capeData, setCapeData] = useState<Record<number, { cape: string; ears?: string; animated: boolean }>>({});
  const [accessories, setAccessories] = useState<AccessoryEntry[]>([]);
  const [accData, setAccData] = useState<Record<number, { texture: string; model: AccessoryModelJson }>>({});
  const [loadout, setLoadout] = useState<Loadout | null>(null);
  const [owned, setOwned] = useState<Set<number>>(new Set());
  const [prices, setPrices] = useState<Record<number, StoreItem>>({});
  const [coins, setCoins] = useState<number | null>(null);
  const [signedIn, setSignedIn] = useState(true);
  const [picked, setPicked] = useState<Picked | null>(null);
  const [filter, setFilter] = useState<Filter>('ALL');
  const [elytra, setElytra] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setNote(null);
    try {
      const [catalog, lo, store] = await Promise.all([api.listCosmetics(), api.getLoadout(), api.getStore()]);
      setCapes(catalog.capes);
      setAccessories(catalog.accessories);
      setLoadout(lo);
      const own = new Set(store.owned);
      setOwned(own);
      setPrices(Object.fromEntries(store.items.map((i) => [i.id, i])));
      setCoins(store.signedIn ? store.coins : null);
      setSignedIn(store.signedIn);
      if (!store.signedIn && store.error) setNote(store.error);
      // land on something to look at: the first thing not yet bought
      setPicked((cur) => {
        if (cur) return cur;
        const cape = catalog.capes.find((c) => !own.has(c.id));
        if (cape) return { kind: 'cape', id: cape.id };
        const acc = catalog.accessories.find((a) => !own.has(a.id));
        if (acc) return { kind: 'accessory', id: acc.id };
        if (catalog.capes[0]) return { kind: 'cape', id: catalog.capes[0].id };
        return catalog.accessories[0] ? { kind: 'accessory', id: catalog.accessories[0].id } : null;
      });
      const [capeEntries, accEntries] = await Promise.all([
        Promise.all(
          catalog.capes.map(async (c) => {
            const cape = await api.readCosmeticTexture('cape', c.id).catch(() => '');
            const ears = c.ears ? await api.readCosmeticTexture('ears', c.id).catch(() => '') : '';
            const animated = cape ? await isAnimatedStrip(cape) : false;
            return [c.id, { cape, ears: ears || undefined, animated }] as const;
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
      setAccData(
        Object.fromEntries(
          accEntries.filter((e): e is readonly [number, { texture: string; model: AccessoryModelJson }] => !!e[1]),
        ),
      );
    } catch (e) {
      setCapes([]);
      setAccessories([]);
      setNote(String(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const wornCape = equippedCape(loadout);
  const wornAcc = useMemo(() => equippedAccessories(loadout), [loadout]);

  const pickedCape = picked?.kind === 'cape' ? capes.find((c) => c.id === picked.id) ?? null : null;
  const pickedAcc = picked?.kind === 'accessory' ? accessories.find((a) => a.id === picked.id) ?? null : null;
  const pickedOwned = picked ? owned.has(picked.id) : false;
  const pickedWorn = picked
    ? picked.kind === 'cape'
      ? wornCape === picked.id
      : wornAcc.includes(picked.id)
    : false;

  /* try-on: the look you wear, with the picked item on top */
  const previewCape = picked?.kind === 'cape' ? picked.id : wornCape;
  const previewAccIds = useMemo(
    () => (picked?.kind === 'accessory' && !wornAcc.includes(picked.id) ? [...wornAcc, picked.id] : wornAcc),
    [picked, wornAcc],
  );
  const previewAccessories = useMemo<AccessoryView[]>(
    () =>
      previewAccIds.flatMap((id) => {
        const entry = accessories.find((a) => a.id === id);
        const data = accData[id];
        return entry && data ? [{ entry, model: data.model, texture: data.texture }] : [];
      }),
    [previewAccIds, accessories, accData],
  );

  const buy = useCallback(async () => {
    if (!picked) return;
    setBusy(true);
    setNote(null);
    try {
      const store = await api.buyCosmetic(picked.id);
      setOwned(new Set(store.owned));
      setCoins(store.coins);
      setPrices(Object.fromEntries(store.items.map((i) => [i.id, i])));
    } catch (e) {
      setNote(String(e));
    } finally {
      setBusy(false);
    }
  }, [picked]);

  const setWorn = useCallback(
    async (wear: boolean) => {
      if (!picked) return;
      setBusy(true);
      setNote(null);
      try {
        const next: Loadout = { ...(loadout ?? {}) };
        if (picked.kind === 'cape') {
          if (wear) next.cape = picked.id;
          else delete next.cape;
        } else {
          const ids = wear ? [...wornAcc.filter((v) => v !== picked.id), picked.id] : wornAcc.filter((v) => v !== picked.id);
          if (ids.length === 0) delete next.accessories;
          else next.accessories = ids;
        }
        setLoadout(await api.setLoadout(next));
      } catch (e) {
        setNote(String(e));
      } finally {
        setBusy(false);
      }
    },
    [picked, loadout, wornAcc],
  );

  const showCapes = filter !== 'ACCESSORIES';
  const showAcc = filter !== 'CAPES';
  const total = capes.length + accessories.length;
  const ownedCount = [...capes, ...accessories].filter((i) => owned.has(i.id)).length;
  const priceOf = (id: number) => prices[id]?.price ?? null;
  const pickedPrice = picked ? priceOf(picked.id) : null;
  const canAfford = coins !== null && pickedPrice !== null && coins >= pickedPrice;
  /* the tile / card tag: what the item is to you, or what it costs */
  const tagFor = (id: number, worn: boolean) =>
    worn ? 'WORN' : owned.has(id) ? 'OWNED' : priceOf(id) === null ? '—' : `${priceOf(id)} COINS`;

  const detail = pickedCape
    ? [
        'CAPE',
        ...capeTraits(pickedCape, !!capeData[pickedCape.id]?.animated).map((t) => t.toUpperCase()),
      ]
    : pickedAcc
      ? [
          'ACCESSORY',
          pickedAcc.attachment.replace('_', ' ').toUpperCase(),
          ...(pickedAcc.frames > 1 ? ['ANIMATED'] : []),
          ...(pickedAcc.source?.startsWith('cosmetica:') ? ['COSMETICA'] : []),
        ]
      : [];

  return (
    <div className="page">
      <div className="page__head">
        <h1 className="page__title">Store</h1>
        <PxButton family="soft" height="md" onClick={onWardrobe} title="Everything you have claimed, ready to wear">
          <TT size={20} tone="accent">
            WARDROBE
          </TT>
        </PxButton>
      </div>

      <div className="win">
        <div className="win__bar">
          <NavCell label="ALL" active={filter === 'ALL'} onClick={() => setFilter('ALL')} />
          <NavCell label="CAPES" active={filter === 'CAPES'} onClick={() => setFilter('CAPES')} />
          <NavCell label="ACCESSORIES" active={filter === 'ACCESSORIES'} onClick={() => setFilter('ACCESSORIES')} />
          <div className="win__fill" />
          <NavLabel label={coins === null ? 'NOT SIGNED IN' : `${coins} COINS`} />
          <NavLabel label={`${ownedCount} / ${total} OWNED`} />
        </div>

        <div className="win__body">
          <div className="wardrobe">
            {/* the viewer column: your look with the picked item tried on */}
            <PxBox family="panel" className="viewer">
              <div className="viewer__stage">
                <PlayerRender
                  skin={skin}
                  model={account?.skinVariant || 'auto'}
                  cape={previewCape === null ? null : capeData[previewCape]?.cape}
                  capeFrameMs={previewCape === null ? 100 : (capes.find((c) => c.id === previewCape)?.frameMs ?? 100)}
                  ears={previewCape === null ? null : capeData[previewCape]?.ears}
                  accessories={previewAccessories}
                  pose={pose}
                  zoom={0.62}
                  backEquipment={elytra ? 'elytra' : 'cape'}
                  /* a cape is a back-piece: turn the model around to show it */
                  back={picked?.kind === 'cape'}
                  interactive
                  className="viewer__canvas"
                />
              </div>

              {/* the item card: what is picked, what it is, what it costs — then the one thing to do about it */}
              <div className="store__item">
                <div className="store__item-head">
                  <TT size={16} tone={pickedWorn ? 'green' : 'plain'} className="store__item-name">
                    {(pickedCape?.name ?? pickedAcc?.name ?? 'NOTHING PICKED').toUpperCase()}
                  </TT>
                  {picked && (
                    <TT size={13} tone={pickedWorn ? 'green' : pickedOwned ? 'sub' : 'accent'}>
                      {tagFor(picked.id, pickedWorn)}
                    </TT>
                  )}
                </div>
                <TT size={13} tone="sub" className="store__item-meta">
                  {detail.length > 0 ? detail.join(' · ') : 'PICK SOMETHING FROM THE SHELF'}
                </TT>
              </div>

              <div className="viewer__actions">
                {!pickedOwned ? (
                  <PxButton
                    family={canAfford ? 'install' : 'grey'}
                    height="fill"
                    disabled={!picked || busy || !signedIn || !canAfford}
                    title={
                      !signedIn
                        ? 'Sign in with a Microsoft account to buy cosmetics'
                        : canAfford
                          ? 'Spend Dusk coins on this; it lands in your wardrobe'
                          : 'Not enough coins — redeem a code under Settings → General'
                    }
                    onClick={() => void buy()}
                  >
                    <TT size={16} tone={canAfford ? undefined : 'sub'}>
                      {pickedPrice === null
                        ? 'BUY'
                        : !signedIn
                          ? 'SIGN IN TO BUY'
                          : canAfford
                            ? `BUY · ${pickedPrice}`
                            : `NEED ${pickedPrice - (coins ?? 0)} MORE COINS`}
                    </TT>
                  </PxButton>
                ) : pickedWorn ? (
                  <PxButton family="grey" height="fill" disabled={busy} onClick={() => void setWorn(false)}>
                    <TT size={16}>UNEQUIP</TT>
                  </PxButton>
                ) : (
                  <PxButton
                    family="install"
                    height="fill"
                    disabled={busy}
                    title="Worn in-game by every Fabric instance through the bundled client mod"
                    onClick={() => void setWorn(true)}
                  >
                    <TT size={16}>EQUIP</TT>
                  </PxButton>
                )}
              </div>
            </PxBox>

            <div className="win win--inner wardrobe__gallery">
              <div className="win__bar">
                <NavCell label={pose} onClick={() => onPose(POSES[(POSES.indexOf(pose) + 1) % POSES.length])} />
                <NavCell label="ELYTRA" active={elytra} onClick={() => setElytra((v) => !v)} />
                <div className="win__fill" />
                <NavLabel
                  label={
                    filter === 'CAPES'
                      ? `${capes.length} CAPE${capes.length === 1 ? '' : 'S'}`
                      : filter === 'ACCESSORIES'
                        ? `${accessories.length} ACCESSOR${accessories.length === 1 ? 'Y' : 'IES'}`
                        : `${total} ITEM${total === 1 ? '' : 'S'}`
                  }
                />
              </div>

              <div className="win__body">
                <div className="skin-grid scroll">
                  {showCapes &&
                    capes.map((c) => {
                      const on = picked?.kind === 'cape' && picked.id === c.id;
                      const worn = wornCape === c.id;
                      const own = owned.has(c.id);
                      return (
                        <PxBox
                          key={`cape-${c.id}`}
                          family={on ? 'accent' : worn ? 'green' : 'panel'}
                          className="skin-tile store-tile"
                          onClick={() => setPicked({ kind: 'cape', id: c.id })}
                          role="button"
                          tabIndex={0}
                          onKeyDown={(e) => e.key === 'Enter' && setPicked({ kind: 'cape', id: c.id })}
                          title={['cape', ...capeTraits(c, !!capeData[c.id]?.animated)].join(' · ')}
                        >
                          <div className="skin-tile__stage">
                            <CapeSwatch src={capeData[c.id]?.cape} scale={6} frameMs={c.frameMs} />
                          </div>
                          <span className="skin-tile__name">
                            <TT size={16} tone={worn ? 'green' : 'dim'}>
                              {c.name.toUpperCase()}
                            </TT>
                          </span>
                          <span className="store-tile__tag">
                            <TT size={13} tone={worn ? 'green' : own ? 'sub' : 'accent'}>
                              {tagFor(c.id, worn)}
                            </TT>
                          </span>
                        </PxBox>
                      );
                    })}

                  {showAcc &&
                    accessories.map((a) => {
                      const on = picked?.kind === 'accessory' && picked.id === a.id;
                      const worn = wornAcc.includes(a.id);
                      const own = owned.has(a.id);
                      return (
                        <PxBox
                          key={`acc-${a.id}`}
                          family={on ? 'accent' : worn ? 'green' : 'panel'}
                          className="skin-tile store-tile"
                          onClick={() => setPicked({ kind: 'accessory', id: a.id })}
                          role="button"
                          tabIndex={0}
                          onKeyDown={(e) => e.key === 'Enter' && setPicked({ kind: 'accessory', id: a.id })}
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
                          <span className="store-tile__tag">
                            <TT size={13} tone={worn ? 'green' : own ? 'sub' : 'accent'}>
                              {tagFor(a.id, worn)}
                            </TT>
                          </span>
                        </PxBox>
                      );
                    })}

                  {total === 0 && !note && (
                    <span className="meta wardrobe__note">Nothing in this build's catalog.</span>
                  )}
                </div>

                {note && <span className="meta wardrobe__note">{note}</span>}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/** MinecraftCapes rule: a cape PNG whose height ≠ width/2 is a vertical frame strip. */
function isAnimatedStrip(src: string): Promise<boolean> {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => resolve(img.naturalHeight !== img.naturalWidth / 2);
    img.onerror = () => resolve(false);
    img.src = src;
  });
}
