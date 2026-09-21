import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import PixelGlyph from '../components/px/PixelGlyph';
import { PxBox, PxButton, TT } from '../components/px/Px';
import { api, type ModpackFacets, type ModpackHit, type ModpackSearch, type ProjectKind, type ProjectTags } from '../lib/api';

/* ── BROWSE (Figma frame 3) ───────────────────────────────────────────────
   One page for everything that comes off Modrinth: modpacks from the
   instances page, and mods / resource packs / shaders from an instance's
   CONTENT tab. A FILTERS sidebar built from Modrinth's own tag lists
   (categories by header, every release, the loaders that apply), and a
   results pane whose rows carry icon, name, author, description and the
   moss INSTALL. A row opens the project page (frame 5); INSTALL on the row
   is the quick path — latest compatible version. */

export const EMPTY_FACETS: ModpackFacets = { categories: [], versions: [], loaders: [] };

const SORTS = ['relevance', 'downloads', 'follows', 'newest', 'updated'] as const;

/** Modrinth files server platforms under loaders too; a launcher has no
 *  use for them, and they'd triple the LOADER list */
const SERVER_LOADERS = new Set([
  'bukkit', 'spigot', 'paper', 'purpur', 'folia', 'sponge', 'bungeecord', 'waterfall', 'velocity', 'datapack',
]);

/** 4213456 → "4.2m", 703456 → "703k" — the way the reference reads */
export function fmtCount(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}m`;
  if (n >= 1_000) return `${Math.round(n / 1_000)}k`;
  return String(n);
}

const HEADER_LABEL: Record<string, string> = {
  categories: 'CATEGORY',
  features: 'FEATURES',
  resolutions: 'RESOLUTION',
  'performance impact': 'PERFORMANCE',
};

export default function BrowseProjects({
  kind,
  noun,
  initialFacets = EMPTY_FACETS,
  search,
  install,
  alreadyInstalled,
  onOpen,
  onBack,
}: {
  /** which Modrinth project type — picks the filter vocabularies */
  kind: ProjectKind;
  /** plural, lower-case — "modpacks", "resource packs" */
  noun: string;
  /** ticked before the first search — an instance pins its version + loader */
  initialFacets?: ModpackFacets;
  search: (
    query: string,
    facets: ModpackFacets,
    page: number,
    pageSize: number,
    sort: string,
  ) => Promise<ModpackSearch>;
  /** resolves true once the hit is installed (the row then reads
   *  INSTALLED); false when the user backed out of a dialog */
  install: (hit: ModpackHit) => Promise<boolean>;
  /** project ids the target instance already holds — their rows read
   *  INSTALLED from the start, so a modpack's own mods aren't offered twice */
  alreadyInstalled?: ReadonlySet<string>;
  /** a row was clicked — show its project page */
  onOpen: (hit: ModpackHit) => void;
  onBack: () => void;
}) {
  const [query, setQuery] = useState('');
  const [facets, setFacets] = useState<ModpackFacets>(initialFacets);
  const [sort, setSort] = useState<string>('relevance');
  const [pageSize, setPageSize] = useState(20);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<ModpackSearch | null>(null);
  const [tags, setTags] = useState<ProjectTags | null>(null);
  const [snapshots, setSnapshots] = useState(false);
  const [loading, setLoading] = useState(true);
  const [note, setNote] = useState<string | null>(null);
  const [installing, setInstalling] = useState<string | null>(null);
  const [installed, setInstalled] = useState<Set<string>>(() => new Set());
  const reqId = useRef(0);

  useEffect(() => {
    api
      .projectTags(kind)
      .then(setTags)
      .catch((e) => setNote(String(e)));
  }, [kind]);

  useEffect(() => {
    const id = ++reqId.current;
    setLoading(true);
    const t = setTimeout(() => {
      void search(query.trim(), facets, page, pageSize, sort)
        .then((r) => {
          if (reqId.current === id) {
            setResult(r);
            setLoading(false);
          }
        })
        .catch((e) => {
          if (reqId.current === id) {
            setNote(String(e));
            setLoading(false);
          }
        });
    }, 300);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, facets, sort, page, pageSize]);

  /* Modrinth groups categories under headers ("categories", "features",
     "resolutions", "performance impact"); each becomes its own group */
  const categoryGroups = useMemo(() => {
    const groups = new Map<string, string[]>();
    tags?.categories.forEach((c) => {
      const list = groups.get(c.header) ?? [];
      list.push(c.name);
      groups.set(c.header, list);
    });
    return [...groups.entries()];
  }, [tags]);

  /* newest first as Modrinth lists them; releases only unless asked, plus
     anything already ticked so a pinned snapshot never loses its checkbox */
  const versionOptions = useMemo(() => {
    const list = (tags?.gameVersions ?? [])
      .filter((v) => snapshots || v.versionType === 'release' || facets.versions.includes(v.version))
      .map((v) => v.version);
    facets.versions.forEach((v) => {
      if (!list.includes(v)) list.unshift(v);
    });
    return list;
  }, [tags, snapshots, facets.versions]);

  const loaderOptions = useMemo(() => {
    const list = (tags?.loaders ?? []).filter((l) => !SERVER_LOADERS.has(l));
    facets.loaders.forEach((l) => {
      if (!list.includes(l)) list.unshift(l);
    });
    // "minecraft" is the only loader a resource pack has — nothing to filter
    return list.length > 1 ? list : [];
  }, [tags, facets.loaders]);

  const toggle = (group: keyof ModpackFacets, value: string) => {
    setPage(0);
    setFacets((f) => ({
      ...f,
      [group]: f[group].includes(value) ? f[group].filter((x) => x !== value) : [...f[group], value],
    }));
  };

  const pages = Math.max(1, Math.ceil((result?.total ?? 0) / pageSize));
  const pageButtons = useMemo(() => {
    const list: (number | '…')[] = [];
    for (let i = 0; i < pages; i++) {
      if (i === 0 || i === pages - 1 || Math.abs(i - page) <= 1) list.push(i);
      else if (list[list.length - 1] !== '…') list.push('…');
    }
    return list;
  }, [pages, page]);

  const run = async (hit: ModpackHit) => {
    setInstalling(hit.id);
    setNote(null);
    try {
      if (await install(hit)) setInstalled((s) => new Set(s).add(hit.id));
    } catch (e) {
      setNote(String(e));
    }
    setInstalling(null);
  };

  const activeCount = facets.categories.length + facets.versions.length + facets.loaders.length;

  return (
    <div className="page">
      {/* BACK sits where the title would; the search takes the rest of the row */}
      <div className="page__head">
        <PxButton family="red" height="md" className="browse__back" onClick={onBack}>
          <PixelGlyph glyph="left" size={22} color="var(--r-co)" />
          <TT size={20} tone="red">
            BACK
          </TT>
        </PxButton>
        <PxBox family="panel" height="md" className="search browse__search">
          <PixelGlyph glyph="search" size={26} color="var(--text-3)" />
          <input
            className="input browse__input"
            placeholder={`Search ${noun}…`}
            value={query}
            autoFocus
            onChange={(e) => {
              setPage(0);
              setQuery(e.target.value);
            }}
          />
        </PxBox>
      </div>

      <div className="browse">
        <PxBox family="panel" className="browse__filters scroll">
          <div className="browse__filters-head">
            <TT size={20} tone="dim">
              FILTERS
            </TT>
            {activeCount > 0 && (
              <button
                className="browse__clear"
                onClick={() => {
                  setPage(0);
                  setFacets(EMPTY_FACETS);
                }}
              >
                <TT size={13} tone="red">
                  CLEAR
                </TT>
              </button>
            )}
          </div>
          {!tags && (
            <TT size={14} tone="dim">
              LOADING…
            </TT>
          )}
          {categoryGroups.map(([header, options]) => (
            <FilterGroup
              key={header}
              label={HEADER_LABEL[header] ?? header.toUpperCase()}
              options={options}
              selected={facets.categories}
              onToggle={(v) => toggle('categories', v)}
            />
          ))}
          {loaderOptions.length > 0 && (
            <FilterGroup
              label="LOADER"
              options={loaderOptions}
              selected={facets.loaders}
              onToggle={(v) => toggle('loaders', v)}
            />
          )}
          {versionOptions.length > 0 && (
            <FilterGroup
              label="GAME VERSION"
              options={versionOptions}
              selected={facets.versions}
              onToggle={(v) => toggle('versions', v)}
              scroll
              extra={
                <Check on={snapshots} label="SHOW SNAPSHOTS" onClick={() => setSnapshots((v) => !v)} />
              }
            />
          )}
        </PxBox>

        <PxBox family="panel" className="browse__results">
          <div className="browse__toolbar">
            <PxBox family="panel" height="sm" className="browse__select">
              <select className="input select" value={sort} onChange={(e) => setSort(e.target.value)}>
                {SORTS.map((s) => (
                  <option key={s} value={s}>
                    {s.charAt(0).toUpperCase() + s.slice(1)}
                  </option>
                ))}
              </select>
            </PxBox>
            <PxBox family="panel" height="sm" className="browse__select">
              <select
                className="input select"
                value={pageSize}
                onChange={(e) => {
                  setPage(0);
                  setPageSize(Number(e.target.value));
                }}
              >
                {[10, 20, 40].map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
            </PxBox>
            <span className="meta browse__count">
              {result ? `${fmtCount(result.total)} RESULTS` : '…'}
            </span>
            {result && pages > 1 && (
              <div className="browse__pager">
                <PxButton
                  family="grey"
                  height="sm"
                  className="browse__page"
                  disabled={page === 0}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                >
                  <TT size={16}>{'<'}</TT>
                </PxButton>
                {pageButtons.map((b, i) =>
                  b === '…' ? (
                    <span key={`e${i}`} className="meta">
                      …
                    </span>
                  ) : (
                    <PxButton
                      key={b}
                      family={b === page ? 'accent' : 'grey'}
                      height="sm"
                      className="browse__page"
                      onClick={() => setPage(b)}
                    >
                      <TT size={16} tone={b === page ? 'accent' : undefined}>
                        {String(b + 1)}
                      </TT>
                    </PxButton>
                  ),
                )}
                <PxButton
                  family="grey"
                  height="sm"
                  className="browse__page"
                  disabled={page >= pages - 1}
                  onClick={() => setPage((p) => Math.min(pages - 1, p + 1))}
                >
                  <TT size={16}>{'>'}</TT>
                </PxButton>
              </div>
            )}
          </div>

          {note && <span className="meta">{note}</span>}

          <div className="browse__list scroll">
            {loading && !result && (
              <TT size={16} tone="dim">
                LOADING…
              </TT>
            )}
            {result?.hits.map((h) => {
              const done = installed.has(h.id) || !!alreadyInstalled?.has(h.id);
              return (
                <PxBox
                  key={h.id}
                  family="panel"
                  listing
                  className={['browse__row', done && 'browse__row--installed'].filter(Boolean).join(' ')}
                  role="button"
                  tabIndex={0}
                  onClick={() => onOpen(h)}
                  onKeyDown={(e) => e.key === 'Enter' && onOpen(h)}
                >
                  <span className="browse__icon">
                    {h.iconUrl ? (
                      <img src={h.iconUrl} alt="" draggable={false} />
                    ) : (
                      <PixelGlyph glyph="box" size={56} color="var(--text-3)" />
                    )}
                  </span>
                  <span className="browse__text">
                    <span className="browse__name">
                      <TT size={22}>{h.title}</TT>
                      <span className="meta browse__by">
                        by {h.author} · {fmtCount(h.downloads)} downloads · {fmtCount(h.follows)} followers
                      </span>
                    </span>
                    <span className="meta browse__desc">{h.description}</span>
                    <span className="browse__chips">
                      {[...h.loaders, ...h.versions.slice(0, 3)].map((c) => (
                        <span key={c} className="browse__chip">
                          {c}
                        </span>
                      ))}
                    </span>
                  </span>
                  {/* 172:74 — the moss plate with the green two-tone label */}
                  <PxButton
                    family="moss"
                    height="md"
                    className="browse__install"
                    disabled={installing !== null || done}
                    onClick={(e) => {
                      e.stopPropagation();
                      void run(h);
                    }}
                  >
                    <TT size={20} tone="moss">
                      {installing === h.id ? 'INSTALLING…' : done ? 'INSTALLED' : 'INSTALL'}
                    </TT>
                  </PxButton>
                </PxBox>
              );
            })}
            {result && result.hits.length === 0 && (
              <TT size={16} tone="dim">
                {`NO ${noun.toUpperCase()} MATCH`}
              </TT>
            )}
          </div>
        </PxBox>
      </div>
    </div>
  );
}

function Check({ on, label, onClick }: { on: boolean; label: string; onClick: () => void }) {
  return (
    <button className="check check--row" onClick={onClick}>
      <span className={['px px--grey check__box', on ? 'is-on' : ''].join(' ')}>
        <span className="check__tick" />
      </span>
      <TT size={14} tone={on ? undefined : 'dim'}>
        {label}
      </TT>
    </button>
  );
}

function FilterGroup({
  label,
  options,
  selected,
  onToggle,
  scroll = false,
  extra,
}: {
  label: string;
  options: string[];
  selected: string[];
  onToggle: (v: string) => void;
  /** long lists (every game version) scroll inside their own box */
  scroll?: boolean;
  /** a control that sits under the heading — the snapshots toggle */
  extra?: ReactNode;
}) {
  return (
    <div className="browse__group">
      <TT size={16} tone="dim">
        {label}
      </TT>
      {extra}
      <div className={['browse__options', scroll ? 'browse__options--scroll scroll' : ''].join(' ')}>
        {options.map((o) => (
          <Check key={o} on={selected.includes(o)} label={o.toUpperCase()} onClick={() => onToggle(o)} />
        ))}
      </div>
    </div>
  );
}
