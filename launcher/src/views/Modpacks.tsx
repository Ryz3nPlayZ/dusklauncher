import { useCallback, useEffect, useMemo, useState } from 'react';
import { PixelIcon } from '../components/PixelIcon';
import { Dropdown, Modal, StatChip } from '../components/ui';
import {
  api,
  type ModpackFacets,
  type ModpackHit,
  type ModpackSearchResponse,
  type ModpackVersionDto,
} from '../lib/tauri';
import { useProfiles } from '../stores/profiles';
import { useSettings } from '../stores/settings';
import { useUi } from '../stores/ui';
import { compactNumber, dateShort } from '../lib/format';
import { playSfx } from '../sfx/sfx';

const CATEGORIES = [
  'adventure',
  'challenging',
  'combat',
  'kitchen sink',
  'lightweight',
  'magic',
  'multiplayer',
  'quests',
  'technology',
  'utility',
];

const SORTS = [
  { value: 'relevance', label: 'Relevance' },
  { value: 'downloads', label: 'Most downloaded' },
  { value: 'follows', label: 'Most followed' },
  { value: 'newest', label: 'Newest' },
  { value: 'updated', label: 'Recently updated' },
];

const PAGE_SIZES = [
  { value: '10', label: '10 per page' },
  { value: '20', label: '20 per page' },
  { value: '40', label: '40 per page' },
];

/**
 * Modpack browser — no longer a top-level tab. It lives inside instance
 * creation (Instances → NEW INSTANCE → BROWSE MODPACKS): modpacks ARE
 * instances, so browsing only makes sense where instances are made.
 */
export function ModpackBrowser({ onInstalled }: { onInstalled?: () => void }) {
  const [query, setQuery] = useState('');
  const [cats, setCats] = useState<string[]>([]);
  const [versions, setVersions] = useState<string[]>([]);
  const [loaders, setLoaders] = useState<string[]>([]);
  const [availableVersions, setAvailableVersions] = useState<string[]>([]);
  const [sort, setSort] = useState('relevance');
  const [pageSize, setPageSize] = useState(10);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<ModpackSearchResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [offline, setOffline] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [picking, setPicking] = useState<ModpackHit | null>(null);
  const [openGroups, setOpenGroups] = useState<Record<string, boolean>>({
    category: true,
    version: true,
    loader: true,
  });

  useEffect(() => {
    void api
      .listVersions()
      .then((vs) => setAvailableVersions(vs.filter((v) => v.type === 'release').slice(0, 8).map((v) => v.id)))
      .catch(() => {});
  }, []);

  const facets: ModpackFacets = useMemo(
    () => ({ categories: cats, versions, loaders }),
    [cats, versions, loaders],
  );

  const runSearch = useCallback(async () => {
    setLoading(true);
    setOffline(false);
    setErrorMsg(null);
    try {
      const r = await api.searchModpacks(query.trim(), facets, page, pageSize, sort);
      setResult(r);
    } catch (e) {
      setOffline(true);
      setErrorMsg(e instanceof Error ? e.message : String(e));
      setResult(null);
    } finally {
      setLoading(false);
    }
  }, [query, facets, page, pageSize, sort]);

  useEffect(() => {
    void runSearch();
  }, [runSearch]);

  const totalPages = result ? Math.max(1, Math.ceil(result.total / result.pageSize)) : 1;

  function toggle(list: string[], setList: (v: string[]) => void, value: string) {
    setPage(0);
    playSfx('click');
    setList(list.includes(value) ? list.filter((v) => v !== value) : [...list, value]);
  }

  return (
    <>
      <div className="modpacks modpacks--modal">
        <aside className="modpacks__filters">
          <h2 className="font-pixel modpacks__filters-title">FILTERS</h2>

          <FilterGroup
            title="CATEGORY"
            open={openGroups.category}
            onToggle={() => setOpenGroups((g) => ({ ...g, category: !g.category }))}
          >
            {CATEGORIES.map((c) => (
              <label key={c} className="pfilter">
                <span className={`pcheck ${cats.includes(c) ? 'is-on' : ''}`}>
                  {cats.includes(c) && <PixelIcon name="check" size={9} />}
                </span>
                <input
                  type="checkbox"
                  checked={cats.includes(c)}
                  onChange={() => toggle(cats, setCats, c)}
                />
                {c.replace(/\b\w/g, (m) => m.toUpperCase())}
              </label>
            ))}
          </FilterGroup>

          <FilterGroup
            title="GAME VERSION"
            open={openGroups.version}
            onToggle={() => setOpenGroups((g) => ({ ...g, version: !g.version }))}
          >
            {(availableVersions.length ? availableVersions : ['26.2', '1.21.11']).map((v) => (
              <label key={v} className="pfilter">
                <span className={`pcheck ${versions.includes(v) ? 'is-on' : ''}`}>
                  {versions.includes(v) && <PixelIcon name="check" size={9} />}
                </span>
                <input
                  type="checkbox"
                  checked={versions.includes(v)}
                  onChange={() => toggle(versions, setVersions, v)}
                />
                {v}
              </label>
            ))}
          </FilterGroup>

          <FilterGroup
            title="LOADER"
            open={openGroups.loader}
            onToggle={() => setOpenGroups((g) => ({ ...g, loader: !g.loader }))}
          >
            {['fabric', 'forge'].map((l) => (
              <label key={l} className="pfilter">
                <span className={`pcheck ${loaders.includes(l) ? 'is-on' : ''}`}>
                  {loaders.includes(l) && <PixelIcon name="check" size={9} />}
                </span>
                <input
                  type="checkbox"
                  checked={loaders.includes(l)}
                  onChange={() => toggle(loaders, setLoaders, l)}
                />
                {l.replace(/\b\w/g, (m) => m.toUpperCase())}
              </label>
            ))}
          </FilterGroup>
        </aside>

        <div className="modpacks__main">
          <div className="modpacks__bar">
            <div className="psearch modpacks__search">
              <PixelIcon name="search" size={11} />
              <input
                className="psearch__input"
                placeholder="SEARCH MODPACKS"
                value={query}
                onChange={(e) => {
                  setQuery(e.target.value);
                  setPage(0);
                }}
              />
            </div>
            <Dropdown value={sort} onChange={setSort} options={SORTS} width={180} />
            <Dropdown
              value={String(pageSize)}
              onChange={(v) => {
                setPageSize(Number(v));
                setPage(0);
              }}
              options={PAGE_SIZES}
              width={130}
            />
            <span className="text-3 font-pixel modpacks__count">
              {loading ? 'SEARCHING…' : `${result?.total ?? 0} RESULTS`}
            </span>
            <div className="modpacks__pager">
              <button className="pbtn pbtn--sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
                &lt;
              </button>
              <span className="font-pixel text-2 modpacks__page">
                {page + 1}/{totalPages}
              </span>
              <button
                className="pbtn pbtn--sm"
                disabled={page + 1 >= totalPages}
                onClick={() => setPage(page + 1)}
              >
                &gt;
              </button>
            </div>
          </div>

          {offline && (
            <div className="modpacks__empty">
              <PixelIcon name="refresh" size={22} className="text-3" />
              <p className="text-3">
                Couldn't reach Modrinth. Check your connection and try again.
              </p>
              {errorMsg && <p className="modpacks__err mono">{errorMsg}</p>}
              <button className="pbtn" onClick={() => void runSearch()}>
                <PixelIcon name="refresh" size={11} /> RETRY
              </button>
            </div>
          )}

          <div className="modpacks__list scroll-y">
            {!offline && result?.hits.length === 0 && !loading && (
              <div className="modpacks__empty">
                <p className="text-3">No modpacks match these filters.</p>
              </div>
            )}
            {result?.hits.map((hit) => (
              <article key={hit.id} className="pcard modpack-card">
                {hit.iconUrl ? (
                  <img className="modpack-card__icon" src={hit.iconUrl} alt="" draggable={false} />
                ) : (
                  <div className="modpack-card__icon modpack-card__icon--ph">
                    <PixelIcon name="mods" size={20} />
                  </div>
                )}
                <div className="modpack-card__body">
                  <h3 className="font-pixel-bold">
                    {hit.title.toUpperCase()}
                    <span className="text-3 modpack-card__author"> by {hit.author}</span>
                  </h3>
                  <p className="modpack-card__desc text-2">{hit.description}</p>
                  <div className="modpack-card__stats">
                    <StatChip icon="download">{compactNumber(hit.downloads)}</StatChip>
                    <StatChip icon="heart">{compactNumber(hit.follows)}</StatChip>
                    {hit.updatedAt && <StatChip icon="refresh">{dateShort(hit.updatedAt)}</StatChip>
                    }
                    {hit.loaders.slice(0, 2).map((l) => (
                      <span key={l} className={`pill ${l === 'fabric' ? 'pill--accent' : ''}`}>
                        {l}
                      </span>
                    ))}
                  </div>
                </div>
                <div className="modpack-card__actions">
                  <button
                    className="pbtn pbtn--install modpack-card__install"
                    onClick={() => {
                      playSfx('click');
                      setPicking(hit);
                    }}
                  >
                    <PixelIcon name="download" size={11} /> INSTALL
                  </button>
                </div>
              </article>
            ))}
          </div>
        </div>
      </div>

      {picking && (
        <InstallModal hit={picking} onClose={() => setPicking(null)} onInstalled={onInstalled} />
      )}
    </>
  );
}

// ── themed install picker: version + game version + loader ────────────────

function InstallModal({
  hit,
  onClose,
  onInstalled,
}: {
  hit: ModpackHit;
  onClose: () => void;
  onInstalled?: () => void;
}) {
  const toast = useUi((s) => s.toast);
  const reloadProfiles = useProfiles((s) => s.load);
  const selectProfile = useSettings((s) => s.update);
  const [versions, setVersions] = useState<ModpackVersionDto[] | null>(null);
  const [versionId, setVersionId] = useState<string>('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setVersions(null);
    void api
      .listModpackVersions(hit.id)
      .then((vs) => {
        setVersions(vs);
        if (vs.length) setVersionId(vs[0].id);
      })
      .catch((e) => toast(e instanceof Error ? e.message : String(e), 'error'));
  }, [hit.id, toast]);

  const current = versions?.find((v) => v.id === versionId) ?? null;

  async function install() {
    if (!versionId) return;
    setBusy(true);
    try {
      const profile = await api.installModpackVersion(hit.id, versionId);
      await reloadProfiles();
      selectProfile({ selectedProfileId: profile.id });
      playSfx('success');
      toast(`${hit.title} installed — ready to play`, 'success');
      onClose();
      onInstalled?.();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={`INSTALL — ${hit.title.toUpperCase().slice(0, 28)}`} onClose={onClose} width={520}>
      <div className="form-col">
        <p className="text-2" style={{ fontSize: 13 }}>
          Pick a version. Game version and loader come straight from Modrinth —
          the instance is created with exactly what the pack declares.
        </p>
        {versions === null ? (
          <p className="text-3 font-pixel" style={{ fontSize: 11 }}>LOADING VERSIONS…</p>
        ) : versions.length === 0 ? (
          <p className="text-3" style={{ fontSize: 13 }}>This pack has no published versions.</p>
        ) : (
          <>
            <Dropdown
              value={versionId}
              onChange={setVersionId}
              width={440}
              options={versions.map((v) => ({
                value: v.id,
                label: `${v.name} · ${(v.gameVersions[0] ?? '?')} · ${(v.loaders[0] ?? '?')}`,
              }))}
            />
            {current && (
              <p className="text-3 form-note">
                MC {(current.gameVersions.join(', ') || '—')} · {(current.loaders.join(', ') || '—')}
                {current.published ? ` · ${dateShort(current.published)}` : ''}
              </p>
            )}
          </>
        )}
        <div className="form-row form-row--end">
          <button className="pbtn" onClick={onClose}>
            CANCEL
          </button>
          <button
            className="pbtn pbtn--install"
            disabled={busy || !versionId}
            onClick={() => void install()}
          >
            <PixelIcon name="download" size={11} /> {busy ? 'INSTALLING…' : 'INSTALL'}
          </button>
        </div>
      </div>
    </Modal>
  );
}

function FilterGroup({
  title,
  open,
  onToggle,
  children,
}: {
  title: string;
  open: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}) {
  return (
    <section className="mgroup">
      <button className="mgroup__head" onClick={onToggle}>
        <span className="font-pixel">{title}</span>
        <PixelIcon name={open ? 'chevronUp' : 'chevronDown'} size={9} />
      </button>
      {open && <div className="mgroup__body scroll-y">{children}</div>}
    </section>
  );
}
