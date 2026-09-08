import { useEffect, useMemo, useState } from 'react';
import { PixelIcon } from '../components/PixelIcon';
import { Dropdown, Field, Modal } from '../components/ui';
import { api, type Loader, type ProfileDto } from '../lib/tauri';
import { useProfiles } from '../stores/profiles';
import { useLaunch } from '../stores/launch';
import { useSettings } from '../stores/settings';
import { useUi } from '../stores/ui';
import InstanceDetail from './InstanceDetail';
import { ModpackBrowse, ModpackDetail } from './Modpacks';
import { timeAgo } from '../lib/format';
import { playSfx } from '../sfx/sfx';
import dawnBanner from '../assets/background/dawn/rear.png';
import mcpvpBanner from '../assets/background/mcpvp/rear.png';
import type { ModpackHit } from '../lib/tauri';

type Filter = 'all' | 'vanilla' | 'fabric';

export default function Instances() {
  const { profiles, select } = useProfiles();
  const settings = useSettings((s) => s.settings);
  const banner = settings.theme === 'nether' ? mcpvpBanner : dawnBanner;
  const launch = useLaunch((s) => s.launch);
  const launchPhase = useLaunch((s) => s.phase);
  const [filter, setFilter] = useState<Filter>('all');
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState<null | 'choose' | 'custom'>(null);
  /** full instance screen (Dawn parity) — replaces the old edit popup */
  const [detailId, setDetailId] = useState<string | null>(null);
  /** add-instance flow: null = list, 'browse' = modpack results, hit = detail */
  const [flow, setFlow] = useState<null | 'browse' | ModpackHit>(null);

  const filtered = useMemo(
    () =>
      profiles.filter(
        (p) =>
          (filter === 'all' || p.loader === filter) &&
          p.name.toLowerCase().includes(search.toLowerCase()),
      ),
    [profiles, filter, search],
  );

  // full instance screen (content / worlds / logs / settings)
  if (detailId) {
    const detail = profiles.find((p) => p.id === detailId);
    if (detail) {
      return (
        <InstanceDetail
          profile={detail}
          onBack={() => setDetailId(null)}
          onDeleted={() => setDetailId(null)}
        />
      );
    }
  }
  // add-instance flow renders full-view (never a popup): browse results or a
  // pack detail, with the back arrow returning to the previous step
  if (flow === 'browse') {
    return <ModpackBrowse onOpen={(hit) => setFlow(hit)} onBack={() => setFlow(null)} />;
  }
  if (flow !== null) {
    return (
      <ModpackDetail hit={flow} onBack={() => setFlow('browse')} onInstalled={() => setFlow(null)} />
    );
  }

  return (
    <div className="page">
      <div className="page__head">
        <h1 className="page__title">INSTANCES</h1>
        <div className="page__spacer" />
        <button className="pbtn profiles-new" onClick={() => setCreating('choose')}>
          NEW INSTANCE
        </button>
        <div className="psearch">
          <PixelIcon name="search" size={11} />
          <input
            className="psearch__input"
            placeholder="SEARCH"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>

      {/* combined tab strip + grid in one frame — the panel is a lighter
          gray translucent base so rows read on both scenes */}
      <div className="inst-panel">
        <div className="inst-panel__tabs">
          <nav className="ptabs">
            {(['all', 'vanilla', 'fabric'] as Filter[]).map((f) => (
              <button
                key={f}
                className={`ptab ${filter === f ? 'ptab--active' : ''}`}
                onClick={() => {
                  setFilter(f);
                  playSfx('tab');
                }}
              >
                {f.toUpperCase()}
              </button>
            ))}
          </nav>
          <div className="page__spacer" />
          <span className="font-pixel profiles-count">
            {filtered.length} PROFILE{filtered.length === 1 ? '' : 'S'}
          </span>
        </div>

        <div className="inst-grid scroll-y">
          {filtered.length === 0 && (
            <div className="inst-empty">
              <PixelIcon name="bolt" size={26} className="text-3" />
              <p className="text-3">
                {profiles.length === 0
                  ? 'No instances yet — create one to start playing.'
                  : 'Nothing matches this filter.'}
              </p>
              {profiles.length === 0 && (
                <button
                  className="pbtn pbtn--accent-outline"
                  onClick={() => setCreating('choose')}
                >
                  <PixelIcon name="plus" size={11} /> NEW INSTANCE
                </button>
              )}
            </div>
          )}
          {filtered.map((p) => {
            const active = p.id === settings.selectedProfileId;
            const busy = launchPhase !== 'idle' && launchPhase !== 'error';
            return (
              <article
                key={p.id}
                className={`inst-card ${active ? 'is-active' : ''}`}
                onClick={() => select(p.id)}
                title={active ? 'Selected instance' : 'Select instance'}
              >
                <div className="inst-card__banner" style={{ backgroundImage: `url(${banner})` }}>
                  <span className="inst-card__icon">
                    <PixelIcon name="bolt" size={18} />
                  </span>
                </div>
                <div className="inst-card__body">
                  <div className="inst-card__name">{p.name}</div>
                  <div className="inst-card__sub">
                    {p.loader === 'fabric' ? 'Fabric' : 'Vanilla'} · {p.gameVersion} ·{' '}
                    {timeAgo(p.lastPlayed ?? p.createdAt)}
                  </div>
                  <div className="inst-card__actions">
                    <button
                      className="pbtn inst-card__play"
                      disabled={busy}
                      title="Launch this instance"
                      onClick={(e) => {
                        e.stopPropagation();
                        select(p.id);
                        void launch(p.id);
                      }}
                    >
                      PLAY NOW
                    </button>
                <button
                  className="pbtn inst-card__gear"
                  title="Open instance screen"
                  onClick={(e) => {
                    e.stopPropagation();
                    setDetailId(p.id);
                  }}
                >
                  <PixelIcon name="gear" size={13} />
                </button>
                  </div>
                </div>
              </article>
            );
          })}
        </div>
      </div>

      {creating === 'choose' && (
        <Modal title="CREATE INSTANCE" onClose={() => setCreating(null)} width={520}>
          <div className="form-col">
            <p className="text-2" style={{ fontSize: 13 }}>
              Choose how this instance should be created.
            </p>
            <button
              className="pbtn pbtn--hero pbtn--block pbtn--lg"
              onClick={() => setCreating('custom')}
            >
              CUSTOM INSTANCE
            </button>
            <button
              className="pbtn pbtn--install pbtn--block pbtn--lg"
              onClick={() => {
                setCreating(null);
                setFlow('browse');
              }}
            >
              BROWSE MODPACKS
            </button>
          </div>
        </Modal>
      )}
      {creating === 'custom' && (
        <CreateModal onClose={() => setCreating(null)} onBack={() => setCreating('choose')} />
      )}
    </div>
  );
}

// ── create modal ────────────────────────────────────────────────────────────

function CreateModal({ onClose, onBack }: { onClose: () => void; onBack: () => void }) {
  const create = useProfiles((s) => s.create);
  const { settings } = useSettings();
  const toast = useUi((s) => s.toast);
  const [name, setName] = useState('');
  const [version, setVersion] = useState('26.2');
  const [loader, setLoader] = useState<Loader>('fabric');
  const [server, setServer] = useState('');
  const [versions, setVersions] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void api
      .listVersions()
      .then((vs) => {
        const releases = vs.filter((v) => v.type === 'release').map((v) => v.id);
        if (releases.length) {
          setVersions(releases.slice(0, 60));
          setVersion(releases[0]);
        }
      })
      .catch(() => {});
  }, []);

  async function submit() {
    if (!name.trim()) {
      toast('Give the profile a name', 'error');
      return;
    }
    setBusy(true);
    try {
      await create(name.trim(), version, loader, server.trim() || undefined);
      playSfx('success');
      onClose();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title="NEW INSTANCE" onClose={onClose} width={480}>
      <div className="form-col">
        <Field label="NAME">
          <input
            className="pinput"
            autoFocus
            placeholder="e.g. PERFORMIUM"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && void submit()}
          />
        </Field>
        <div className="form-row">
          <Field label="GAME VERSION">
            {versions.length ? (
              <Dropdown
                value={version}
                onChange={setVersion}
                options={versions.map((v) => ({ value: v, label: v }))}
              />
            ) : (
              <input className="pinput" value={version} onChange={(e) => setVersion(e.target.value)} />
            )}
          </Field>
          <Field label="MOD LOADER">
            <Dropdown
              value={loader}
              onChange={(v) => setLoader(v as Loader)}
              options={[
                { value: 'fabric', label: 'Fabric' },
                { value: 'vanilla', label: 'Vanilla' },
              ]}
            />
          </Field>
        </div>
        <Field label="SERVER (OPTIONAL)" hint="Joins this address right after launch (--server)">
          <input
            className="pinput"
            placeholder="play.example.net"
            value={server}
            onChange={(e) => setServer(e.target.value)}
          />
        </Field>
        <p className="text-3 form-note">
          JVM args default to your settings: <span className="mono">{settings.defaultJvmArgs}</span>
        </p>
        <div className="form-row form-row--end">
          <button className="pbtn" onClick={onBack}>
            BACK
          </button>
          <button className="pbtn" onClick={onClose}>
            CANCEL
          </button>
          <button className="pbtn pbtn--install" disabled={busy} onClick={() => void submit()}>
            <PixelIcon name="plus" size={11} /> CREATE
          </button>
        </div>
      </div>
    </Modal>
  );
}
