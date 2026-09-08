import { useCallback, useEffect, useMemo, useState } from 'react';
import { PixelIcon } from '../components/PixelIcon';
import { Dropdown, StatChip } from '../components/ui';
import {
  api,
  type ModpackFacets,
  type ModpackHit,
  type ModpackProjectDto,
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
  { value: '10', label: '10' },
  { value: '20', label: '20' },
  { value: '40', label: '40' },
];

/**
 * Browse Modpacks — a full-view step inside the add-instance flow
 * (Instances → NEW INSTANCE → BROWSE MODPACKS), never a popup and never a
 * top-level tab: modpacks ARE instances, so browsing only makes sense where
 * instances are made. Dawn layout: title + search up top, filter rail on the
 * left, result rows with a green INSTALL tile and stat column on the right.
 * Clicking a row opens the detail view (DESCRIPTION / VERSIONS / GALLERY +
 * DETAILS rail); `onBack` returns to the instance list.
 */

// ── browse ────────────────────────────────────────────────────────────────

export function ModpackBrowse({ onOpen, onBack }: { onOpen: (hit: ModpackHit) => void; onBack: () => void }) {
  const [query, setQuery] = useState('');
  const [cats, setCats] = useState<string[]>([]);
  const [versions, setVersions] = useState<string[]>([]);
  const [loaders, setLoaders] = useState<string[]>([]);
  const [availableVersions, setAvailableVersions] = useState<string[]>([]);
  const [sort, setSort] = useState('relevance');
  const [pageSize, setPageSize] = useState(20);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<ModpackSearchResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [offline, setOffline] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
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
    <div className="page page--split">
      <div className="page__head">
        <h1 className="page__title">BROWSE MODPACKS</h1>
        <div className="page__spacer" />
        <button className="pbtn" onClick={onBack} title="Back to instances">
          <PixelIcon name="back" size={11} />
        </button>
        <div className="psearch">
          <PixelIcon name="search" size={11} />
          <input
            className="psearch__input"
            placeholder="SEARCH"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setPage(0);
            }}
          />
        </div>
      </div>

      <div className="modpacks">
        <aside className="modpacks__filters scroll-y">
          <h2 className="font-pixel-bold modpacks__filters-title">FILTERS</h2>

          <FilterGroup
            title="CATEGORY"
            open={openGroups.category}
            onToggle={() => setOpenGroups((g) => ({ ...g, category: !g.category }))}
          >
            {CATEGORIES.map((c) => (
              <label key={c} className={`pfilter ${cats.includes(c) ? 'is-on' : ''}`}>
                <span className={`pcheck pcheck--gold ${cats.includes(c) ? 'is-on' : ''}`}>
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
              <label key={v} className={`pfilter ${versions.includes(v) ? 'is-on' : ''}`}>
                <span className={`pcheck pcheck--gold ${versions.includes(v) ? 'is-on' : ''}`}>
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
              <label key={l} className={`pfilter ${loaders.includes(l) ? 'is-on' : ''}`}>
                <span className={`pcheck pcheck--gold ${loaders.includes(l) ? 'is-on' : ''}`}>
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
            <Dropdown value={sort} onChange={(v) => { setSort(v); setPage(0); }} options={SORTS} width={170} />
            <Dropdown
              value={String(pageSize)}
              onChange={(v) => {
                setPageSize(Number(v));
                setPage(0);
              }}
              options={PAGE_SIZES}
              width={80}
            />
            <span className="font-pixel-bold modpacks__count">
              {loading ? 'SEARCHING…' : `${result?.total ?? 0} RESULTS`}
            </span>
            <Pager page={page} totalPages={totalPages} onPage={(p) => { setPage(p); playSfx('tab'); }} />
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
              <ModpackRow key={hit.id} hit={hit} onOpen={() => onOpen(hit)} onInstalled={onBack} />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

function ModpackRow({ hit, onOpen, onInstalled }: { hit: ModpackHit; onOpen: () => void; onInstalled: () => void }) {
  const [busy, setBusy] = useState(false);
  const toast = useUi((s) => s.toast);
  const reloadProfiles = useProfiles((s) => s.load);
  const selectProfile = useSettings((s) => s.update);

  async function quickInstall(e: React.MouseEvent) {
    e.stopPropagation();
    if (busy) return;
    setBusy(true);
    try {
      const profile = await api.installModpack(hit.id);
      await reloadProfiles();
      selectProfile({ selectedProfileId: profile.id });
      playSfx('success');
      toast(`${hit.title} installed — ready to play`, 'success');
      onInstalled();
    } catch (err) {
      toast(err instanceof Error ? err.message : String(err), 'error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <article className="modpack-card" onClick={onOpen}>
      {hit.iconUrl ? (
        <img className="modpack-card__icon" src={hit.iconUrl} alt="" draggable={false} />
      ) : (
        <div className="modpack-card__icon modpack-card__icon--ph">
          <PixelIcon name="mods" size={22} />
        </div>
      )}
      <div className="modpack-card__body">
        <h3 className="font-pixel-bold">
          {hit.title.toUpperCase()}
          <span className="modpack-card__author"> by {hit.author}</span>
        </h3>
        <p className="modpack-card__desc">{hit.description}</p>
      </div>
      <div className="modpack-card__side">
        <button
          className="pbtn pbtn--install modpack-card__install"
          disabled={busy}
          onClick={quickInstall}
        >
          {busy ? 'WORKING…' : 'INSTALL'}
        </button>
        <div className="modpack-card__stats">
          <StatChip icon="download">{compactNumber(hit.downloads)}</StatChip>
          <StatChip icon="heart">{compactNumber(hit.follows)}</StatChip>
        </div>
        {hit.updatedAt && <span className="modpack-card__date">{dateShort(hit.updatedAt)}</span>}
      </div>
    </article>
  );
}

function Pager({ page, totalPages, onPage }: { page: number; totalPages: number; onPage: (p: number) => void }) {
  if (totalPages <= 1) return null;
  const nums: (number | '…')[] = [];
  const push = (n: number | '…') => {
    if (nums[nums.length - 1] !== n) nums.push(n);
  };
  push(0);
  for (let n = page - 1; n <= page + 1; n++) if (n > 0 && n < totalPages - 1) push(n);
  if (totalPages > 1) push('…');
  if (totalPages > 1) push(totalPages - 1);
  return (
    <div className="modpacks__pager">
      <button className="pbtn pbtn--sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
        &lt;
      </button>
      {nums.map((n, i) =>
        n === '…' ? (
          <span key={`e${i}`} className="font-pixel text-3 modpacks__ellipsis">…</span>
        ) : (
          <button
            key={n}
            className={`pbtn pbtn--sm ${n === page ? 'is-cur' : ''}`}
            onClick={() => onPage(n)}
          >
            {n + 1}
          </button>
        ),
      )}
      <button
        className="pbtn pbtn--sm"
        disabled={page + 1 >= totalPages}
        onClick={() => onPage(page + 1)}
      >
        &gt;
      </button>
    </div>
  );
}

// ── detail ────────────────────────────────────────────────────────────────

type DetailTab = 'description' | 'versions' | 'gallery';

export function ModpackDetail({ hit, onBack, onInstalled }: { hit: ModpackHit; onBack: () => void; onInstalled: () => void }) {
  const [tab, setTab] = useState<DetailTab>('description');
  const [project, setProject] = useState<ModpackProjectDto | null>(null);
  const [versions, setVersions] = useState<ModpackVersionDto[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [installing, setInstalling] = useState(false);
  const toast = useUi((s) => s.toast);
  const reloadProfiles = useProfiles((s) => s.load);
  const selectProfile = useSettings((s) => s.update);

  useEffect(() => {
    setProject(null);
    setVersions(null);
    setFailed(false);
    void api.getModpackProject(hit.id).then(setProject).catch(() => setFailed(true));
    void api.listModpackVersions(hit.id).then(setVersions).catch(() => {});
  }, [hit.id]);

  async function installVersion(versionId: string | null) {
    if (installing) return;
    setInstalling(true);
    try {
      const profile = versionId
        ? await api.installModpackVersion(hit.id, versionId)
        : await api.installModpack(hit.id);
      await reloadProfiles();
      selectProfile({ selectedProfileId: profile.id });
      playSfx('success');
      toast(`${hit.title} installed — ready to play`, 'success');
      onInstalled();
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    } finally {
      setInstalling(false);
    }
  }

  const p = project;
  const downloads = p?.downloads ?? hit.downloads;
  const follows = p?.follows ?? hit.follows;

  return (
    <div className="page page--split">
      <div className="mpdetail__head">
        {hit.iconUrl ? (
          <img className="mpdetail__icon" src={hit.iconUrl} alt="" draggable={false} />
        ) : (
          <div className="mpdetail__icon mpdetail__icon--ph">
            <PixelIcon name="mods" size={24} />
          </div>
        )}
        <div className="mpdetail__titlewrap">
          <h1 className="page__title">{hit.title.toUpperCase()}</h1>
          <p className="mpdetail__desc">{hit.description}</p>
        </div>
        <div className="mpdetail__headstats">
          <StatChip icon="download">{compactNumber(downloads)}</StatChip>
          <StatChip icon="heart">{compactNumber(follows)}</StatChip>
        </div>
        <button className="pbtn" onClick={onBack} title="Back to results">
          <PixelIcon name="back" size={11} />
        </button>
        <button
          className="pbtn pbtn--install"
          disabled={installing}
          onClick={() => void installVersion(null)}
        >
          {installing ? 'WORKING…' : 'INSTALL'}
        </button>
      </div>

      <div className="mpdetail__layout">
        <section className="mpdetail__main">
          <nav className="ptabs mpdetail__tabs">
            {(['description', 'versions', 'gallery'] as DetailTab[]).map((t) => (
              <button
                key={t}
                className={`ptab ${tab === t ? 'ptab--active' : ''}`}
                onClick={() => { setTab(t); playSfx('tab'); }}
              >
                {t.toUpperCase()}
              </button>
            ))}
          </nav>
          <div className="mpdetail__body scroll-y">
            {tab === 'description' && (
              failed ? (
                <p className="text-3">Couldn't load the project page. Try again later.</p>
              ) : !p ? (
                <p className="font-pixel text-3" style={{ fontSize: 11 }}>LOADING…</p>
              ) : (
                <Markdown text={p.body || p.description} />
              )
            )}
            {tab === 'versions' && (
              versions === null ? (
                <p className="font-pixel text-3" style={{ fontSize: 11 }}>LOADING VERSIONS…</p>
              ) : versions.length === 0 ? (
                <p className="text-3">This pack has no published versions.</p>
              ) : (
                versions.map((v) => (
                  <div key={v.id} className="mpversion">
                    <div className="mpversion__meta">
                      <span className="font-pixel-bold mpversion__name">{v.name}</span>
                      <span className="mpversion__sub">
                        {(v.gameVersions.join(', ') || '—')} · {(v.loaders.join(', ') || '—')}
                        {v.published ? ` · ${dateShort(v.published)}` : ''}
                      </span>
                    </div>
                    <button
                      className="pbtn pbtn--sm pbtn--install"
                      disabled={installing}
                      onClick={() => void installVersion(v.id)}
                    >
                      INSTALL
                    </button>
                  </div>
                ))
              )
            )}
            {tab === 'gallery' && (
              !p ? (
                <p className="font-pixel text-3" style={{ fontSize: 11 }}>LOADING…</p>
              ) : p.gallery.length === 0 ? (
                <p className="text-3">No gallery images for this pack.</p>
              ) : (
                <div className="mpgallery">
                  {p.gallery.map((g, i) => (
                    <figure key={i} className="mpgallery__item">
                      <img src={g.url} alt={g.title ?? ''} loading="lazy" draggable={false} />
                      {g.title && <figcaption>{g.title}</figcaption>}
                    </figure>
                  ))}
                </div>
              )
            )}
          </div>
        </section>

        <aside className="mpdetail__side scroll-y">
          <h2 className="font-pixel-bold mpdetail__sidetitle">DETAILS</h2>
          <section className="mgroup">
            <div className="mgroup__head"><span className="font-pixel">COMPATIBILITY</span></div>
            <div className="mgroup__body">
              <span className="mpdetail__label">Versions</span>
              <div className="mpdetail__chips">
                {(p?.gameVersions ?? hit.versions).slice(0, 12).map((v) => (
                  <span key={v} className="pill">{v}</span>
                ))}
              </div>
              <span className="mpdetail__label">Loaders</span>
              <div className="mpdetail__chips">
                {(p?.loaders ?? hit.loaders).map((l) => (
                  <span key={l} className="pill pill--accent">{l}</span>
                ))}
              </div>
              {p && (
                <>
                  <span className="mpdetail__label">Environment</span>
                  <div className="mpdetail__chips">
                    <span className="pill">CLIENT {p.clientSide.toUpperCase()}</span>
                    <span className="pill">SERVER {p.serverSide.toUpperCase()}</span>
                  </div>
                </>
              )}
            </div>
          </section>
          {p && (p.sourceUrl || p.issuesUrl || p.wikiUrl || p.discordUrl) && (
            <section className="mgroup">
              <div className="mgroup__head"><span className="font-pixel">LINKS</span></div>
              <div className="mgroup__body">
                {[
                  ['Source', p.sourceUrl],
                  ['Issues', p.issuesUrl],
                  ['Wiki', p.wikiUrl],
                  ['Discord', p.discordUrl],
                ].map(([label, url]) =>
                  url ? (
                    <button
                      key={label}
                      className="mpdetail__link"
                      onClick={() => window.open(url, '_blank', 'noopener')}
                    >
                      {label}
                    </button>
                  ) : null,
                )}
              </div>
            </section>
          )}
          {p && p.categories.length > 0 && (
            <section className="mgroup">
              <div className="mgroup__head"><span className="font-pixel">TAGS</span></div>
              <div className="mgroup__body">
                <div className="mpdetail__chips">
                  {p.categories.map((c) => (
                    <span key={c} className="pill">{c.toUpperCase()}</span>
                  ))}
                </div>
              </div>
            </section>
          )}
        </aside>
      </div>
    </div>
  );
}

// ── minimal markdown (Modrinth bodies): headings, bold/italic/code, links,
// images, lists, rules. Builds nodes directly — no raw HTML injection. ─────

function Markdown({ text }: { text: string }) {
  const blocks = useMemo(() => {
    const lines = text.replace(/\r\n/g, '\n').split('\n');
    const out: React.ReactNode[] = [];
    let list: string[] | null = null;
    let key = 0;
    const flushList = () => {
      if (list) {
        out.push(
          <ul key={`ul${key++}`} className="md__ul">
            {list.map((li, i) => <li key={i}>{inline(li, `li${i}`)}</li>)}
          </ul>,
        );
        list = null;
      }
    };
    for (const raw of lines) {
      const line = raw.trimEnd();
      if (/^\s*([-*])\s+/.test(line)) {
        (list ??= []).push(line.replace(/^\s*([-*])\s+/, ''));
        continue;
      }
      flushList();
      if (!line.trim()) continue;
      if (/^---+$/.test(line.trim())) {
        out.push(<hr key={`hr${key++}`} className="md__hr" />);
        continue;
      }
      const h = /^(#{1,4})\s+(.*)/.exec(line);
      if (h) {
        const level = h[1].length;
        const Tag = `h${Math.min(4, level + 1)}` as 'h2' | 'h3' | 'h4' | 'h5';
        out.push(<Tag key={`h${key++}`} className="md__h">{inline(h[2], 'h')}</Tag>);
        continue;
      }
      const img = /^!\[([^\]]*)\]\(([^)]+)\)/.exec(line.trim());
      if (img) {
        out.push(<img key={`im${key++}`} className="md__img" src={img[2]} alt={img[1]} loading="lazy" draggable={false} />);
        continue;
      }
      out.push(<p key={`p${key++}`} className="md__p">{inline(line, 'p')}</p>);
    }
    flushList();
    return out;
  }, [text]);
  return <div className="md">{blocks}</div>;
}

function inline(src: string, scope: string): React.ReactNode[] {
  // images, links, bold, italic, inline code — single pass, left to right
  const re = /!\[([^\]]*)\]\(([^)]+)\)|\[([^\]]+)\]\(([^)]+)\)|\*\*([^*]+)\*\*|\*([^*]+)\*|`([^`]+)`/g;
  const out: React.ReactNode[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  let k = 0;
  while ((m = re.exec(src))) {
    if (m.index > last) out.push(src.slice(last, m.index));
    const g = (i: number) => m?.[i] ?? '';
    if (m[1] !== undefined) {
      out.push(<img key={`${scope}${k++}`} className="md__img" src={g(2)} alt={g(1)} loading="lazy" draggable={false} />);
    } else if (m[3] !== undefined) {
      const url = g(4);
      out.push(
        <button
          key={`${scope}${k++}`}
          className="md__link"
          onClick={() => window.open(url, '_blank', 'noopener')}
        >
          {m[3]}
        </button>,
      );
    } else if (m[5] !== undefined) {
      out.push(<strong key={`${scope}${k++}`}>{m[5]}</strong>);
    } else if (m[6] !== undefined) {
      out.push(<em key={`${scope}${k++}`}>{m[6]}</em>);
    } else if (m[7] !== undefined) {
      out.push(<code key={`${scope}${k++}`} className="md__code">{m[7]}</code>);
    }
    last = m.index + m[0].length;
  }
  if (last < src.length) out.push(src.slice(last));
  return out;
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
