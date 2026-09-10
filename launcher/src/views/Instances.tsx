import { useEffect, useMemo, useState } from 'react';
import ProfileBanner from '../components/ProfileBanner';
import PixelGlyph from '../components/px/PixelGlyph';
import { PxBox, PxButton, TT } from '../components/px/Px';
import { ago, api, loaderLabel, type Profile, type Version } from '../lib/api';

const FILTERS = ['ALL', 'VANILLA', 'FABRIC'] as const;
type Filter = (typeof FILTERS)[number];

export default function Instances({
  profiles,
  selected,
  onRefresh,
  onLaunch,
  onSelect,
}: {
  profiles: Profile[];
  selected: Profile | null;
  onRefresh: () => Promise<void> | void;
  onLaunch: (id: string) => void;
  onSelect: (id: string) => void;
}) {
  const [filter, setFilter] = useState<Filter>('ALL');
  const [query, setQuery] = useState('');
  const [creating, setCreating] = useState(false);
  const [confirm, setConfirm] = useState<Profile | null>(null);

  const shown = useMemo(
    () =>
      profiles.filter(
        (p) =>
          (filter === 'ALL' || p.loader.toUpperCase() === filter) &&
          p.name.toLowerCase().includes(query.trim().toLowerCase()),
      ),
    [profiles, filter, query],
  );

  return (
    <div className="page">
      <div className="page__head">
        <h1 className="page__title">Instances</h1>
        <PxBox family="panel" height="md" className="search">
          <PixelGlyph glyph="search" size={20} color="var(--text-3)" />
          <input
            className="input"
            placeholder="Search instances…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </PxBox>
        <PxButton family="accent" height="md" onClick={() => setCreating(true)}>
          <TT size={16} tone="accent">
            NEW INSTANCE
          </TT>
        </PxButton>
      </div>

      <PxBox family="panel" className="sheet">
        <div className="sheet__tabs">
          {FILTERS.map((f) => (
            <PxButton
              key={f}
              family={filter === f ? 'accent' : 'grey'}
              height="md"
              onClick={() => setFilter(f)}
            >
              <TT size={16} tone={filter === f ? 'accent' : undefined}>
                {f}
              </TT>
            </PxButton>
          ))}
          <span className="tabstrip__spacer" />
          <PxBox family="panel" height="md">
            <TT size={16} tone="dim">{`${shown.length} INSTANCE${shown.length === 1 ? '' : 'S'}`}</TT>
          </PxBox>
        </div>

        {shown.length === 0 ? (
          <PxBox family="panel" listing className="empty">
            <TT size={20} tone="dim">
              NOTHING HERE YET
            </TT>
            <span className="meta">
              {profiles.length === 0
                ? 'Create an instance to install a Minecraft version and start playing.'
                : 'No instance matches that filter.'}
            </span>
          </PxBox>
        ) : (
          <div className="sheet__body grid scroll">
            {shown.map((p) => (
              <PxBox key={p.id} family={p.id === selected?.id ? 'accent' : 'panel'} listing className="card">
                <ProfileBanner seed={p.art} className="card__banner" />
                <span className="card__name">
                  <TT size={20} tone={p.id === selected?.id ? 'accent' : undefined}>
                    {p.name}
                  </TT>
                </span>
                <span className="card__meta meta">
                  {loaderLabel(p)} · {p.gameVersion} · {ago(p.lastPlayed)}
                  {p.modCount > 0 ? ` · ${p.modCount} mods` : ''}
                </span>
                <div className="card__row">
                  <PxButton
                    family="install"
                    height="sm"
                    onClick={() => {
                      onSelect(p.id);
                      onLaunch(p.id);
                    }}
                  >
                    <TT size={16} tone="green">
                      PLAY NOW
                    </TT>
                  </PxButton>
                  <PxButton family="red" height="sm" className="card__del" onClick={() => setConfirm(p)}>
                    <TT size={16} tone="red">
                      DELETE
                    </TT>
                  </PxButton>
                </div>
              </PxBox>
            ))}
          </div>
        )}
      </PxBox>

      {creating && (
        <NewInstance
          onClose={() => setCreating(false)}
          onCreated={async (p) => {
            setCreating(false);
            await onRefresh();
            onSelect(p.id);
          }}
        />
      )}

      {confirm && (
        <div className="modal-scrim" onClick={() => setConfirm(null)}>
          <PxBox
            family="red"
            className="px--window modal"
            onClick={(e) => e.stopPropagation()}
          >
            <TT size={22} tone="red">
              DELETE INSTANCE?
            </TT>
            <span className="meta">
              “{confirm.name}” and everything in its folder — worlds, mods, configs — is
              removed from disk. This cannot be undone.
            </span>
            <div className="modal__row">
              <PxButton
                family="grey"
                height="sm"
                onClick={() => setConfirm(null)}
              >
                <TT size={16}>CANCEL</TT>
              </PxButton>
              <PxButton
                family="red"
                height="sm"
                onClick={async () => {
                  await api.deleteProfile(confirm.id);
                  setConfirm(null);
                  await onRefresh();
                }}
              >
                <TT size={16} tone="red">
                  DELETE
                </TT>
              </PxButton>
            </div>
          </PxBox>
        </div>
      )}
    </div>
  );
}

function NewInstance({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: (p: Profile) => void;
}) {
  const [versions, setVersions] = useState<Version[]>([]);
  const [name, setName] = useState('');
  const [version, setVersion] = useState('');
  const [loader, setLoader] = useState<'vanilla' | 'fabric'>('vanilla');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    void api
      .listVersions()
      .then((v) => {
        setVersions(v);
        const latest = v.find((x) => x.type === 'release');
        if (latest) setVersion((cur) => cur || latest.id);
      })
      .catch((e) => setErr(String(e)));
  }, []);

  const releases = versions.filter((v) => v.type === 'release').slice(0, 60);

  return (
    <div className="modal-scrim" onClick={onClose}>
      <PxBox family="panel" className="px--window modal" onClick={(e) => e.stopPropagation()}>
        <TT size={22}>NEW INSTANCE</TT>

        <div className="modal__row">
          <span className="modal__label">
            <TT size={14} tone="dim">
              NAME
            </TT>
          </span>
          <PxBox family="panel" height="sm">
            <input
              className="input"
              placeholder="My instance"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </PxBox>
        </div>

        <div className="modal__row">
          <span className="modal__label">
            <TT size={14} tone="dim">
              VERSION
            </TT>
          </span>
          <PxBox family="panel" height="sm">
            <input
              className="input"
              list="mc-versions"
              placeholder={releases[0]?.id ?? '1.21.4'}
              value={version}
              onChange={(e) => setVersion(e.target.value)}
            />
            <datalist id="mc-versions">
              {releases.map((v) => (
                <option key={v.id} value={v.id} />
              ))}
            </datalist>
          </PxBox>
        </div>

        <div className="modal__row">
          <span className="modal__label">
            <TT size={14} tone="dim">
              LOADER
            </TT>
          </span>
          {(['vanilla', 'fabric'] as const).map((l) => (
            <PxButton
              key={l}
              family={loader === l ? 'accent' : 'grey'}
              height="sm"
              onClick={() => setLoader(l)}
            >
              <TT size={16} tone={loader === l ? 'accent' : undefined}>
                {l.toUpperCase()}
              </TT>
            </PxButton>
          ))}
        </div>

        {err && <span className="meta">{err}</span>}

        <div className="modal__row">
          <PxButton family="grey" height="sm" onClick={onClose}>
            <TT size={16}>CANCEL</TT>
          </PxButton>
          <PxButton
            family="green"
            height="sm"
            disabled={busy || !name.trim() || !version.trim()}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                onCreated(await api.createProfile(name.trim(), version.trim(), loader));
              } catch (e) {
                setErr(String(e));
                setBusy(false);
              }
            }}
          >
            <TT size={16} tone="green">
              CREATE
            </TT>
          </PxButton>
        </div>
      </PxBox>
    </div>
  );
}
