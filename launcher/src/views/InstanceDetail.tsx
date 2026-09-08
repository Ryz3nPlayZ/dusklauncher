import { useEffect, useMemo, useState } from 'react';
import { PixelIcon } from '../components/PixelIcon';
import { Dropdown, Field } from '../components/ui';
import {
  api,
  type ContentKind,
  type ModHit,
  type ProfileDto,
  type ProfileModDto,
  type WorldDto,
} from '../lib/tauri';
import { useProfiles } from '../stores/profiles';
import { useLaunch } from '../stores/launch';
import { useSettings } from '../stores/settings';
import { useUi } from '../stores/ui';
import { timeAgo } from '../lib/format';
import { playSfx } from '../sfx/sfx';

type DetailTab = 'content' | 'worlds' | 'logs' | 'settings';

const MODRINTH_KIND_PATH: Record<ContentKind, string> = {
  mod: 'mod',
  resourcepack: 'resourcepack',
  shader: 'shader',
};

const KIND_LABEL: Record<ContentKind, string> = {
  mod: 'MODS',
  resourcepack: 'RESOURCE PACKS',
  shader: 'SHADERS',
};

/**
 * Full instance screen (Dawn parity) — replaces the old settings popup.
 * Header (icon, name, meta, folder, PLAY), tabs CONTENT / WORLDS / LOGS /
 * SETTINGS. Content splits into MODS / RESOURCE PACKS / SHADERS tables with
 * enable toggles, deletes, Modrinth links, and an ADD CONTENT search that is
 * just a Modrinth wrapper: every result links straight to its project page.
 */
export default function InstanceDetail({
  profile,
  onBack,
  onDeleted,
}: {
  profile: ProfileDto;
  onBack: () => void;
  onDeleted: () => void;
}) {
  const [tab, setTab] = useState<DetailTab>('content');
  const launch = useLaunch((s) => s.launch);
  const launchPhase = useLaunch((s) => s.phase);
  const toast = useUi((s) => s.toast);
  const busy = launchPhase !== 'idle' && launchPhase !== 'error';

  async function openFolder(subdir: string) {
    try {
      await api.showInFolder(profile.id, subdir);
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    }
  }

  return (
    <div className="page page--split">
      <div className="idetail__head">
        <span className="idetail__icon">
          <PixelIcon name="bolt" size={22} />
        </span>
        <div className="idetail__titlewrap">
          <h1 className="page__title">{profile.name}</h1>
          <p className="idetail__meta">
            {profile.loader === 'fabric' ? 'Fabric' : 'Vanilla'} · {profile.gameVersion} ·{' '}
            {timeAgo(profile.lastPlayed ?? profile.createdAt)}
          </p>
        </div>
        <span className="pill pill--accent">{profile.loader.toUpperCase()}</span>
        <div className="page__spacer" />
        <button className="pbtn" onClick={onBack} title="Back to instances">
          <PixelIcon name="back" size={11} />
        </button>
        <button
          className="pbtn"
          title="Show instance folder"
          onClick={() => void openFolder('')}
        >
          <PixelIcon name="folder" size={12} /> FOLDER
        </button>
        <button
          className="pbtn pbtn--hero"
          disabled={busy}
          onClick={() => void launch(profile.id)}
        >
          <PixelIcon name="play" size={13} /> PLAY NOW
        </button>
      </div>

      <div className="idetail__panel">
        <div className="idetail__tabs">
          <nav className="ptabs">
            {(['content', 'worlds', 'logs', 'settings'] as DetailTab[]).map((t) => (
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
        </div>

        <div className="idetail__body">
          {tab === 'content' && <ContentTab profile={profile} />}
          {tab === 'worlds' && <WorldsTab profile={profile} />}
          {tab === 'logs' && <LogsTab profile={profile} />}
          {tab === 'settings' && (
            <SettingsTab profile={profile} onDeleted={onDeleted} />
          )}
        </div>
      </div>
    </div>
  );
}

// ── content: installed tables + add-content search ─────────────────────────

function ContentTab({ profile }: { profile: ProfileDto }) {
  const [kind, setKind] = useState<ContentKind>('mod');
  const [adding, setAdding] = useState(false);
  const [installed, setInstalled] = useState<ProfileModDto[]>([]);
  const toast = useUi((s) => s.toast);
  const [kindCounts, setKindCounts] = useState<Record<ContentKind, number>>({
    mod: 0,
    resourcepack: 0,
    shader: 0,
  });

  useEffect(() => {
    void (async () => {
      try {
        const [mods, rps, shaders] = await Promise.all([
          api.listProfileContent(profile.id, 'mod'),
          api.listProfileContent(profile.id, 'resourcepack'),
          api.listProfileContent(profile.id, 'shader'),
        ]);
        setInstalled(kind === 'mod' ? mods : kind === 'resourcepack' ? rps : shaders);
        setKindCounts({ mod: mods.length, resourcepack: rps.length, shader: shaders.length });
      } catch (e) {
        toast(e instanceof Error ? e.message : String(e), 'error');
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile.id, kind]);

  async function toggle(m: ProfileModDto) {
    try {
      await api.setContentEnabled(profile.id, m.filename, !m.enabled, kind);
      playSfx('click');
      const list = await api.listProfileContent(profile.id, kind);
      setInstalled(list);
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    }
  }

  async function remove(m: ProfileModDto) {
    try {
      await api.removeProfileContent(profile.id, m.filename, kind);
      const list = await api.listProfileContent(profile.id, kind);
      setInstalled(list);
      setKindCounts((c) => ({ ...c, [kind]: list.length }));
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    }
  }

  /** filename → Modrinth project page (best search match in a new tab) */
  async function viewOnModrinth(m: ProfileModDto) {
    const stem = m.filename
      .replace(/\.disabled$/, '')
      .replace(/\.(jar|zip)$/i, '')
      .split('-')[0]!;
    try {
      const hits = await api.searchContent(stem, profile.gameVersion, profile.loader, 3, kind);
      const best = hits[0];
      if (!best) {
        toast(`No Modrinth match for ${stem}`, 'error');
        return;
      }
      window.open(`https://modrinth.com/${MODRINTH_KIND_PATH[kind]}/${best.slug}`, '_blank', 'noopener');
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    }
  }

  return (
    <div className="idetail__content">
      <div className="idetail__contentbar">
        <nav className="ptabs">
          {(Object.keys(KIND_LABEL) as ContentKind[]).map((k) => (
            <button
              key={k}
              className={`ptab ${kind === k ? 'ptab--active' : ''}`}
              onClick={() => {
                setKind(k);
                playSfx('tab');
              }}
            >
              {KIND_LABEL[k]} ({kindCounts[k]})
            </button>
          ))}
        </nav>
        <div className="page__spacer" />
        <button
          className={`pbtn pbtn--install ${adding ? 'is-open' : ''}`}
          onClick={() => {
            setAdding(!adding);
            playSfx('click');
          }}
        >
          <PixelIcon name="plus" size={11} /> ADD CONTENT
        </button>
      </div>

      {adding && (
        <AddContent
          profile={profile}
          kind={kind}
          onAdded={() => {
            void api.listProfileContent(profile.id, kind).then((list) => {
              setInstalled(list);
              setKindCounts((c) => ({ ...c, [kind]: list.length }));
            });
          }}
        />
      )}

      <div className="idetail__tablehead">
        <span className="idetail__col-name">NAME</span>
        <span className="idetail__col-type">TYPE</span>
        <span className="idetail__col-ver">STATUS</span>
        <span className="idetail__col-actions" />
      </div>
      <div className="idetail__rows scroll-y">
        {installed.length === 0 && (
          <p className="text-3 idetail__empty">
            Nothing installed — hit ADD CONTENT to browse Modrinth.
          </p>
        )}
        {installed.map((m) => (
          <div key={m.filename} className={`idetail__row ${m.enabled ? '' : 'is-off'}`}>
            <span className="idetail__name" title={m.filename}>
              {prettyName(m.filename)}
              <span className="idetail__file">{m.filename}</span>
            </span>
            <span className="idetail__col-type">
              <span className="pill">{KIND_LABEL[kind].slice(0, -1)}</span>
            </span>
            <span className="idetail__col-ver">
              <span className={`pill ${m.enabled ? 'pill--accent' : ''}`}>
                {m.enabled ? 'ENABLED' : 'OFF'}
              </span>
            </span>
            <span className="idetail__col-actions">
              <button
                className="pbtn pbtn--sm"
                title="Open on Modrinth"
                onClick={() => void viewOnModrinth(m)}
              >
                <PixelIcon name="mods" size={11} />
              </button>
              <button
                className={`pbtn pbtn--sm ${m.enabled ? 'pbtn--install' : ''}`}
                title={m.enabled ? 'Disable' : 'Enable'}
                onClick={() => void toggle(m)}
              >
                <PixelIcon name={m.enabled ? 'check' : 'plus'} size={10} />
              </button>
              <button
                className="pbtn pbtn--sm pbtn--danger"
                title="Delete"
                onClick={() => void remove(m)}
              >
                <PixelIcon name="close" size={10} />
              </button>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

/** jar/zip filename → readable title (first segment, de-snaked) */
function prettyName(filename: string): string {
  const stem = filename.replace(/\.disabled$/, '').replace(/\.(jar|zip)$/i, '');
  const first = stem.split('-')[0] ?? stem;
  return first.replace(/[_+]/g, ' ').trim() || stem;
}

function AddContent({
  profile,
  kind,
  onAdded,
}: {
  profile: ProfileDto;
  kind: ContentKind;
  onAdded: () => void;
}) {
  const toast = useUi((s) => s.toast);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<ModHit[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setResults(null);
      return;
    }
    setSearching(true);
    const t = setTimeout(() => {
      void api
        .searchContent(q, profile.gameVersion, profile.loader, 10, kind)
        .then(setResults)
        .catch(() => setResults([]))
        .finally(() => setSearching(false));
    }, 350);
    return () => clearTimeout(t);
  }, [query, profile.gameVersion, profile.loader, kind]);

  async function install(r: ModHit) {
    setBusy(r.id);
    try {
      await api.installContentToProfile(profile.id, r.id, kind);
      playSfx('success');
      toast(`${r.title} added`, 'success');
      onAdded();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="idetail__add">
      <div className="psearch idetail__addsearch">
        <PixelIcon name="search" size={11} />
        <input
          className="psearch__input"
          autoFocus
          placeholder={`SEARCH ${KIND_LABEL[kind]} ON MODRINTH`}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      {searching && <p className="font-pixel text-3 idetail__empty">SEARCHING…</p>}
      {results !== null && (
        <div className="idetail__addrows">
          {results.length === 0 && !searching && (
            <p className="text-3 idetail__empty">No matches on Modrinth.</p>
          )}
          {results.map((r) => (
            <div key={r.id} className="idetail__addrow">
              {r.iconUrl ? (
                <img src={r.iconUrl} alt="" draggable={false} />
              ) : (
                <span className="idetail__addicon">
                  <PixelIcon name="mods" size={14} />
                </span>
              )}
              <span className="idetail__addmeta">
                <span className="font-pixel-bold idetail__addtitle">{r.title}</span>
                <span className="idetail__adddesc">
                  by {r.author} · {r.description}
                </span>
              </span>
              <button
                className="pbtn pbtn--sm"
                title="Open project page on Modrinth"
                onClick={() =>
                  window.open(
                    `https://modrinth.com/${MODRINTH_KIND_PATH[kind]}/${r.slug}`,
                    '_blank',
                    'noopener',
                  )
                }
              >
                <PixelIcon name="mods" size={10} />
              </button>
              <button
                className="pbtn pbtn--sm pbtn--install"
                disabled={busy !== null}
                onClick={() => void install(r)}
              >
                {busy === r.id ? 'ADDING…' : 'ADD'}
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── worlds ─────────────────────────────────────────────────────────────────

function WorldsTab({ profile }: { profile: ProfileDto }) {
  const toast = useUi((s) => s.toast);
  const [worlds, setWorlds] = useState<WorldDto[] | null>(null);

  useEffect(() => {
    void api
      .listWorlds(profile.id)
      .then(setWorlds)
      .catch((e) => {
        toast(e instanceof Error ? e.message : String(e), 'error');
        setWorlds([]);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile.id]);

  return (
    <div className="idetail__content">
      <div className="idetail__contentbar">
        <span className="font-pixel-bold idetail__count">
          {worlds === null ? '…' : `${worlds.length} WORLD${worlds.length === 1 ? '' : 'S'}`}
        </span>
        <div className="page__spacer" />
        <button
          className="pbtn"
          onClick={() =>
            api.showInFolder(profile.id, 'saves').catch((e) => toast(String(e), 'error'))
          }
        >
          <PixelIcon name="folder" size={11} /> OPEN SAVES FOLDER
        </button>
      </div>
      <div className="idetail__rows scroll-y">
        {worlds === null && <p className="font-pixel text-3 idetail__empty">LOADING…</p>}
        {worlds !== null && worlds.length === 0 && (
          <p className="text-3 idetail__empty">
            No worlds yet — they appear here after you play this instance.
          </p>
        )}
        {(worlds ?? []).map((w) => (
          <div key={w.name} className="idetail__row">
            <span className="idetail__name" title={w.name}>
              {w.name}
              <span className="idetail__file">
                {formatBytes(w.size)} · {timeAgo(w.modified)}
              </span>
            </span>
            <span className="idetail__col-type">
              <span className="pill">WORLD</span>
            </span>
            <span className="idetail__col-ver" />
            <span className="idetail__col-actions" />
          </div>
        ))}
      </div>
    </div>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

// ── logs ───────────────────────────────────────────────────────────────────

function LogsTab({ profile }: { profile: ProfileDto }) {
  const toast = useUi((s) => s.toast);
  const log = useLaunch((s) => s.log);
  const lines = useMemo(() => log.slice(-300), [log]);

  return (
    <div className="idetail__content">
      <div className="idetail__contentbar">
        <span className="font-pixel-bold idetail__count">GAME OUTPUT</span>
        <div className="page__spacer" />
        <button
          className="pbtn"
          onClick={() =>
            api.showInFolder(profile.id, 'logs').catch((e) => toast(String(e), 'error'))
          }
        >
          <PixelIcon name="folder" size={11} /> OPEN LOGS FOLDER
        </button>
      </div>
      <div className="drawer__log idetail__log scroll-y">
        {lines.length === 0 && (
          <div className="text-3">No output yet — launch this instance to see logs.</div>
        )}
        {lines.map((l, i) => (
          <div key={i} className={`line ${l.stream === 'err' ? 'err' : ''}`}>
            {l.line}
          </div>
        ))}
      </div>
    </div>
  );
}

// ── settings (full-page form; was the edit popup) ──────────────────────────

function SettingsTab({ profile, onDeleted }: { profile: ProfileDto; onDeleted: () => void }) {
  const update = useProfiles((s) => s.update);
  const remove = useProfiles((s) => s.remove);
  const toast = useUi((s) => s.toast);
  const { settings } = useSettings();
  const [name, setName] = useState(profile.name);
  const [server, setServer] = useState(profile.server ?? '');
  const [width, setWidth] = useState(String(profile.resolution[0]));
  const [height, setHeight] = useState(String(profile.resolution[1]));
  const [jvmArgs, setJvmArgs] = useState(profile.jvmArgs.join(' '));
  const [gameVersion, setGameVersion] = useState(profile.gameVersion);
  const [versions, setVersions] = useState<string[]>([]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void api
      .listVersions()
      .then((vs) => {
        const releases = vs.filter((v) => v.type === 'release').map((v) => v.id);
        if (releases.length) setVersions(releases.slice(0, 60));
      })
      .catch(() => {});
  }, []);

  async function save() {
    if (!name.trim()) {
      toast('Give the profile a name', 'error');
      return;
    }
    setBusy(true);
    try {
      await update(profile.id, {
        name: name.trim(),
        gameVersion,
        server: server.trim() || null,
        resolution: [Number(width) || 1280, Number(height) || 720],
        jvmArgs: jvmArgs.trim().split(/\s+/).filter(Boolean),
      });
      playSfx('success');
      toast('Instance saved', 'success');
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
    <div className="idetail__settings scroll-y">
      <div className="form-col" style={{ maxWidth: 640 }}>
        <div className="form-row">
          <Field label="NAME">
            <input className="pinput" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label={`${profile.loader.toUpperCase()}${profile.loaderVersion ? ` · LOADER ${profile.loaderVersion}` : ''}`}>
            {versions.length ? (
              <Dropdown
                value={gameVersion}
                onChange={setGameVersion}
                options={versions.map((v) => ({ value: v, label: v }))}
              />
            ) : (
              <input className="pinput" value={gameVersion} onChange={(e) => setGameVersion(e.target.value)} />
            )}
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
        <div className="form-row form-row--start">
          <span className="font-pixel-bold text-3" style={{ fontSize: 11 }}>
            MEMORY:
          </span>
          {[2, 4, 6, 8, 12, 16].map((g) => (
            <button key={g} className="pbtn pbtn--sm" onClick={() => setMemory(g)}>
              {g}G
            </button>
          ))}
        </div>
        <p className="text-3 form-note">
          JVM args default to your settings: <span className="mono">{settings.defaultJvmArgs}</span>
        </p>
        <div className="form-row form-row--end">
          <button className="pbtn pbtn--install" disabled={busy} onClick={() => void save()}>
            SAVE
          </button>
        </div>
        <div className="idetail__danger">
          <span className="font-pixel-bold idetail__danger-title">DANGER ZONE</span>
          {confirmDelete ? (
            <button
              className="pbtn pbtn--danger"
              onClick={() => {
                void remove(profile.id).then(onDeleted);
              }}
            >
              <PixelIcon name="trash" size={11} /> REALLY DELETE {profile.name.toUpperCase()}
            </button>
          ) : (
            <button className="pbtn pbtn--danger" onClick={() => setConfirmDelete(true)}>
              <PixelIcon name="trash" size={11} /> DELETE INSTANCE
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
