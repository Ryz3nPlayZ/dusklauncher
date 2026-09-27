import { useEffect, useState, type FormEvent } from 'react';
import { PxBox, PxButton, TT } from './px/Px';
import { api, type AccessoryEntry, type CapeEntry, type Loadout, type Outfit } from '../lib/api';

/* ── OUTFITS ──────────────────────────────────────────────────────────────
   Named looks saved on the Dusk account. SAVE keeps whatever the viewer
   wears right now (the picked cape + accessories); WEAR puts an outfit on
   the viewer, and APPLY LOOK makes it the loadout, same as picking by
   hand. */

const errText = (e: unknown) => String(e).replace(/^Error: /, '');
const MAX_NAME = 32;

export default function Outfits({
  capes,
  accessories,
  look,
  onWear,
}: {
  /** owned cosmetics — names for the summaries */
  capes: CapeEntry[];
  accessories: AccessoryEntry[];
  /** the look on the viewer, as a loadout */
  look: Loadout;
  onWear: (outfit: Outfit) => void;
}) {
  const [outfits, setOutfits] = useState<Outfit[] | null>(null);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void api
      .listOutfits()
      .then(setOutfits)
      .catch((e) => {
        setOutfits([]);
        setError(errText(e));
      });
  }, []);

  const save = (e: FormEvent) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    api
      .saveOutfit(trimmed, look)
      .then((list) => {
        setOutfits(list);
        setName('');
      })
      .catch((err) => setError(errText(err)))
      .finally(() => setBusy(false));
  };

  const remove = (id: number) => {
    setError(null);
    api
      .deleteOutfit(id)
      .then(setOutfits)
      .catch((err) => setError(errText(err)));
  };

  const summary = (l: Loadout) => {
    const cape = typeof l.cape === 'number' ? (capes.find((c) => c.id === l.cape)?.name ?? `Cape #${l.cape}`) : null;
    const acc = Array.isArray(l.accessories)
      ? l.accessories.map((id) => accessories.find((a) => a.id === id)?.name ?? `#${id}`)
      : [];
    const parts = [cape, ...acc].filter(Boolean);
    return parts.length > 0 ? parts.join(' · ') : 'Nothing equipped';
  };

  return (
    <div className="outfits scroll">
      <form className="outfits__save" onSubmit={save}>
        <PxBox family="panel" height="md" className="outfits__field">
          <input
            className="input"
            placeholder="NAME THIS LOOK"
            value={name}
            maxLength={MAX_NAME}
            spellCheck={false}
            onChange={(e) => setName(e.target.value)}
          />
        </PxBox>
        <PxButton family="accent" height="md" type="submit" disabled={busy || !name.trim()}>
          <TT size={16} tone="accent">
            {busy ? 'SAVING…' : 'SAVE OUTFIT'}
          </TT>
        </PxButton>
      </form>
      <span className="meta">Saves the look on the viewer: {summary(look)}.</span>

      {error && (
        <PxBox family="red" height="md" className="social__notice" role="alert">
          <span className="meta">{error}</span>
        </PxBox>
      )}

      {outfits?.length === 0 && !error && (
        <PxBox family="panel" className="empty">
          <TT size={20} tone="dim">
            NO OUTFITS YET
          </TT>
          <span className="meta">Pick a cape and accessories, name the look and save it to switch back any time.</span>
        </PxBox>
      )}

      {outfits?.map((o) => (
        <div key={o.id} className="srow outfits__row">
          <div className="srow__text">
            <TT size={20} tone="plain">
              {o.name}
            </TT>
            <span className="meta">{summary(o.loadout)}</span>
          </div>
          <div className="srow__control">
            <PxButton family="blue" height="md" title="Put it on the viewer, then APPLY LOOK" onClick={() => onWear(o)}>
              <TT size={16} tone="blue">
                WEAR
              </TT>
            </PxButton>
            <PxButton family="red" height="md" onClick={() => remove(o.id)}>
              <TT size={16} tone="red">
                DELETE
              </TT>
            </PxButton>
          </div>
        </div>
      ))}
    </div>
  );
}
