import { useEffect, useState } from 'react';
import { PixelIcon } from '../components/PixelIcon';
import { Toggle } from '../components/ui';
import { api, type ContentKind, type ModHit, type ProfileDto, type ProfileModDto } from '../lib/tauri';
import { useUi } from '../stores/ui';
import { playSfx } from '../sfx/sfx';

const KINDS: { kind: ContentKind; label: string; hint: string }[] = [
  { kind: 'mod', label: 'MODS', hint: 'Fabric mods for this instance' },
  { kind: 'resourcepack', label: 'RESOURCE PACKS', hint: 'Visual / audio packs' },
  { kind: 'shader', label: 'SHADERS', hint: 'Iris-compatible shader packs' },
];

/** Per-instance content manager: installed list + Modrinth browser per kind. */
export default function InstanceContent({ profile }: { profile: ProfileDto }) {
  const [kind, setKind] = useState<ContentKind>('mod');
  const active = KINDS.find((k) => k.kind === kind)!;
  return (
    <div className="content">
      <nav className="ptabs content__tabs">
        {KINDS.map((k) => (
          <button
            key={k.kind}
            className={`ptab ${kind === k.kind ? 'ptab--active' : ''}`}
            onClick={() => {
              setKind(k.kind);
              playSfx('tab');
            }}
          >
            {k.label}
          </button>
        ))}
      </nav>
      <p className="text-3 content__hint">{active.hint}</p>
      <KindPanel key={kind} profile={profile} kind={kind} />
    </div>
  );
}

function KindPanel({ profile, kind }: { profile: ProfileDto; kind: ContentKind }) {
  const toast = useUi((s) => s.toast);
  const [installed, setInstalled] = useState<ProfileModDto[]>([]);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<ModHit[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  async function refresh() {
    try {
      setInstalled(await api.listProfileContent(profile.id, kind));
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    }
  }

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile.id, kind]);

  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setResults(null);
      return;
    }
    setSearching(true);
    const t = setTimeout(() => {
      void api
        .searchContent(q, profile.gameVersion, profile.loader, 8, kind)
        .then(setResults)
        .catch(() => setResults([]))
        .finally(() => setSearching(false));
    }, 350);
    return () => clearTimeout(t);
  }, [query, profile.gameVersion, profile.loader, kind]);

  async function install(projectId: string, title: string) {
    setBusy(projectId);
    try {
      await api.installContentToProfile(profile.id, projectId, kind);
      await refresh();
      playSfx('success');
      toast(`${title} added`, 'success');
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    } finally {
      setBusy(null);
    }
  }

  async function toggle(m: ProfileModDto) {
    try {
      await api.setContentEnabled(profile.id, m.filename, !m.enabled, kind);
      await refresh();
      playSfx('click');
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    }
  }

  async function remove(m: ProfileModDto) {
    try {
      await api.removeProfileContent(profile.id, m.filename, kind);
      await refresh();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    }
  }

  return (
    <div className="content__panel">
      <div className="content__installed">
        <div className="font-pixel content__sub">INSTALLED ({installed.length})</div>
        {installed.length === 0 && (
          <p className="text-3 content__empty">Nothing installed yet — search Modrinth below.</p>
        )}
        {installed.map((m) => (
          <div key={m.filename} className={`content__row ${m.enabled ? '' : 'is-off'}`}>
            <Toggle checked={m.enabled} onChange={() => void toggle(m)} />
            <span className="content__name" title={m.filename}>
              {m.filename}
            </span>
            <button
              className="content__rm"
              title="Remove"
              onClick={() => void remove(m)}
            >
              <PixelIcon name="trash" size={10} />
            </button>
          </div>
        ))}
      </div>

      <div className="psearch content__search">
        <PixelIcon name="search" size={11} />
        <input
          className="psearch__input"
          placeholder={`SEARCH ${kind === 'mod' ? 'MODS' : kind === 'shader' ? 'SHADERS' : 'RESOURCE PACKS'}`}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      {searching && (
        <p className="text-3 font-pixel content__sub">SEARCHING…</p>
      )}
      {results !== null && (
        <div className="content__results scroll-y">
          {results.length === 0 && !searching && (
            <p className="text-3 content__empty">No matches on Modrinth.</p>
          )}
          {results.map((r) => (
            <div key={r.id} className="content__row">
              <span className="content__meta">
                <span className="font-pixel-bold content__title">{r.title}</span>
                <span className="text-3 content__desc">{r.description}</span>
              </span>
              <button
                className="pbtn pbtn--sm pbtn--install"
                disabled={busy !== null}
                onClick={() => void install(r.id, r.title)}
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
