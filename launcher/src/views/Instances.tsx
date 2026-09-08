import { useEffect, useMemo, useState } from 'react';
import { PixelIcon } from '../components/PixelIcon';
import { Dropdown, Field, Modal } from '../components/ui';
import { api, type Loader, type ProfileDto } from '../lib/tauri';
import { useProfiles } from '../stores/profiles';
import { useLaunch } from '../stores/launch';
import { useSettings } from '../stores/settings';
import { useUi } from '../stores/ui';
import InstanceContent from './InstanceContent';
import { ModpackBrowser } from './Modpacks';
import { timeAgo } from '../lib/format';
import { playSfx } from '../sfx/sfx';

type Filter = 'all' | 'vanilla' | 'fabric';

export default function Instances() {
  const { profiles, remove, select } = useProfiles();
  const settings = useSettings((s) => s.settings);
  const launch = useLaunch((s) => s.launch);
  const launchPhase = useLaunch((s) => s.phase);
  const [filter, setFilter] = useState<Filter>('all');
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState<null | 'choose' | 'custom' | 'browse'>(null);
  const [editing, setEditing] = useState<ProfileDto | null>(null);

  const filtered = useMemo(
    () =>
      profiles.filter(
        (p) =>
          (filter === 'all' || p.loader === filter) &&
          p.name.toLowerCase().includes(search.toLowerCase()),
      ),
    [profiles, filter, search],
  );

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

      <div className="page__subhead profiles-subbar">
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

      <div className="inst-list scroll-y">
        {filtered.length === 0 && (
          <div className="inst-empty">
            <PixelIcon name="bolt" size={26} className="text-3" />
            <p className="text-3">
              {profiles.length === 0
                ? 'No instances yet — create one to start playing.'
                : 'Nothing matches this filter.'}
            </p>
            {profiles.length === 0 && (
              <button className="pbtn pbtn--accent-outline" onClick={() => setCreating('choose')}>
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
              className={`inst-row ${active ? 'is-active' : ''}`}
              onClick={() => select(p.id)}
              title={active ? 'Selected instance' : 'Select instance'}
            >
              <span className="inst-row__icon">
                <PixelIcon name="bolt" size={16} />
              </span>
              <span className="inst-row__meta">
                <span className="inst-row__name">{p.name}</span>
                <span className="inst-row__sub">
                  {p.loader === 'fabric' ? 'Fabric' : 'Vanilla'} · {p.gameVersion} ·{' '}
                  {timeAgo(p.lastPlayed ?? p.createdAt)}
                </span>
              </span>
              <span className="inst-row__actions">
                <button
                  className="pbtn pbtn--sm inst-row__play"
                  disabled={busy}
                  title="Launch this instance"
                  onClick={(e) => {
                    e.stopPropagation();
                    select(p.id);
                    void launch(p.id);
                  }}
                >
                  PLAY
                </button>
                <button
                  className="pbtn pbtn--sm inst-row__gear"
                  title="Instance settings"
                  onClick={(e) => {
                    e.stopPropagation();
                    setEditing(p);
                  }}
                >
                  <PixelIcon name="gear" size={12} />
                </button>
                <span className={`pcheck ${active ? 'is-on' : ''}`} aria-hidden="true">
                  {active && <PixelIcon name="check" size={9} />}
                </span>
              </span>
            </article>
          );
        })}
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
              onClick={() => setCreating('browse')}
            >
              BROWSE MODPACKS
            </button>
          </div>
        </Modal>
      )}
      {creating === 'browse' && (
        <Modal title="BROWSE MODPACKS" onClose={() => setCreating(null)} width={960} wide>
          <ModpackBrowser onInstalled={() => setCreating(null)} />
        </Modal>
      )}
      {creating === 'custom' && (
        <CreateModal onClose={() => setCreating(null)} onBack={() => setCreating('choose')} />
      )}
      {editing && (
        <EditModal
          profile={editing}
          onClose={() => setEditing(null)}
          onDelete={async () => {
            await remove(editing.id);
            setEditing(null);
          }}
        />
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

// ── edit modal (per-profile settings) ──────────────────────────────────────

function EditModal({
  profile,
  onClose,
  onDelete,
}: {
  profile: ProfileDto;
  onClose: () => void;
  onDelete: () => Promise<void>;
}) {
  const update = useProfiles((s) => s.update);
  const toast = useUi((s) => s.toast);
  const [name, setName] = useState(profile.name);
  const [server, setServer] = useState(profile.server ?? '');
  const [width, setWidth] = useState(String(profile.resolution[0]));
  const [height, setHeight] = useState(String(profile.resolution[1]));
  const [jvmArgs, setJvmArgs] = useState(profile.jvmArgs.join(' '));
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    try {
      await update(profile.id, {
        name: name.trim(),
        server: server.trim() || null,
        resolution: [Number(width) || 1280, Number(height) || 720],
        jvmArgs: jvmArgs.trim().split(/\s+/).filter(Boolean),
      });
      playSfx('success');
      onClose();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    } finally {
      setBusy(false);
    }
  }

  function setMemory(sizeGb: number) {
    const args = jvmArgs.trim().split(/\s+/).filter(Boolean);
    const xmx = `-Xmx${sizeGb}G`;
    const xms = `-Xms${Math.min(2, sizeGb)}G`;
    let hasXmx = false;
    let hasXms = false;
    const out = args.map((a) => {
      if (a.startsWith('-Xmx')) {
        hasXmx = true;
        return xmx;
      }
      if (a.startsWith('-Xms')) {
        hasXms = true;
        return xms;
      }
      return a;
    });
    if (!hasXmx) out.unshift(xmx);
    if (!hasXms) out.unshift(xms);
    setJvmArgs(out.join(' '));
  }

  return (
    <Modal title={`SETTINGS — ${profile.name}`} onClose={onClose} width={560}>
      <div className="form-col">
        <div className="form-row">
          <Field label="NAME">
            <input className="pinput" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label={`${profile.loader.toUpperCase()} • ${profile.gameVersion}`}>
            <div className="text-3" style={{ padding: '8px 0' }}>
              {profile.loaderVersion ? `loader ${profile.loaderVersion}` : 'loader: latest'}
            </div>
          </Field>
        </div>
        <div className="form-row">
          <Field label="WIDTH">
            <input className="pinput" value={width} onChange={(e) => setWidth(e.target.value)} />
          </Field>
          <Field label="HEIGHT">
            <input className="pinput" value={height} onChange={(e) => setHeight(e.target.value)} />
          </Field>
        </div>
        <Field label="SERVER" hint="Empty = no auto-join">
          <input
            className="pinput"
            placeholder="play.example.net"
            value={server}
            onChange={(e) => setServer(e.target.value)}
          />
        </Field>
        <Field label="JVM ARGUMENTS">
          <textarea
            className="pinput"
            rows={3}
            value={jvmArgs}
            onChange={(e) => setJvmArgs(e.target.value)}
          />
        </Field>
        <Field label="CONTENT">
          <InstanceContent profile={profile} />
        </Field>
        <div className="form-row form-row--start">
          <span className="text-3" style={{ fontSize: 11 }}>
            MEMORY:
          </span>
          {[2, 4, 6, 8, 12, 16].map((g) => (
            <button key={g} className="pbtn pbtn--sm" onClick={() => setMemory(g)}>
              {g}G
            </button>
          ))}
        </div>
        <div className="form-row form-row--end">
          {confirmDelete ? (
            <button className="pbtn pbtn--danger-outline" onClick={() => void onDelete()}>
              <PixelIcon name="trash" size={11} /> REALLY DELETE
            </button>
          ) : (
            <button className="pbtn pbtn--danger-outline" onClick={() => setConfirmDelete(true)}>
              <PixelIcon name="trash" size={11} /> DELETE
            </button>
          )}
          <button className="pbtn" onClick={onClose}>
            CANCEL
          </button>
          <button className="pbtn pbtn--accent" disabled={busy} onClick={() => void save()}>
            SAVE
          </button>
        </div>
      </div>
    </Modal>
  );
}
