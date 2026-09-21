import { useEffect, useMemo, useState, type ReactNode } from 'react';
import DOMPurify from 'dompurify';
import { marked } from 'marked';
import PixelGlyph from '../components/px/PixelGlyph';
import { NavCell, NavLabel, PxBox, PxButton, TT } from '../components/px/Px';
import { api, isTauri, type ProjectDetails, type ProjectKind, type ProjectVersion } from '../lib/api';
import { fmtCount } from './Browse';

/* ── PROJECT PAGE (Figma frame 5, 187:5) ────────────────────────────────
   One page for a modpack, a mod, a resource pack or a shader off Modrinth.
   The instance-editor head (90px icon, title, meta line, the grey type
   plate, BACK + INSTALL on the right), then the window with DESCRIPTION /
   VERSIONS / GALLERY tabs and, beside it, the 253-wide details column:
   compatibility, loaders, game versions, links, numbers. VERSIONS is where
   a particular game version or pack version gets picked. */

const KIND_LABEL: Record<ProjectKind, string> = {
  modpack: 'MODPACK',
  mod: 'MOD',
  resourcepack: 'RESOURCE PACK',
  shader: 'SHADER',
};

type Tab = 'DESCRIPTION' | 'VERSIONS' | 'GALLERY';

const fmtDate = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString() : '—');

/** open a link in the system browser (the launcher never navigates) */
async function openLink(url: string) {
  if (isTauri) {
    const { openUrl } = await import('@tauri-apps/plugin-opener');
    await openUrl(url);
  } else {
    window.open(url, '_blank', 'noopener');
  }
}

/** Modrinth bodies are markdown with a little HTML; sanitize after render
 *  and strip anything that could script or navigate the launcher */
function renderBody(md: string): string {
  const html = marked.parse(md, { async: false, gfm: true, breaks: false }) as string;
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'script', 'iframe', 'form', 'input', 'video', 'audio'],
    FORBID_ATTR: ['style', 'onerror', 'onload'],
  });
}

export default function Project({
  kind,
  id,
  installTitle = 'INSTALL',
  prefer,
  install,
  current,
  onBack,
}: {
  kind: ProjectKind;
  id: string;
  /** the instance this is going into — its game version and loader
   *  preselect the VERSIONS filters when the project ships for them */
  prefer?: { gameVersion: string; loader: string };
  /** the head button's label — INSTALL, or ADD TO INSTANCE */
  installTitle?: string;
  /** null = the latest compatible version (the head button); a version id
   *  when picked in the VERSIONS tab. Resolves true once installed, false
   *  when the user backed out of a dialog */
  install: (versionId: string | null, project: ProjectDetails) => Promise<boolean>;
  /** the version of this project the instance already holds, if any — the
   *  head button then reads INSTALLED and other versions offer SWITCH */
  current?: { versionId: string; versionNumber: string } | null;
  onBack: () => void;
}) {
  const [project, setProject] = useState<ProjectDetails | null>(null);
  const [versions, setVersions] = useState<ProjectVersion[] | null>(null);
  const [tab, setTab] = useState<Tab>('DESCRIPTION');
  const [note, setNote] = useState<string | null>(null);
  const [installing, setInstalling] = useState<string | null>(null);
  const [installed, setInstalled] = useState<Set<string>>(() => new Set());
  const [gameFilter, setGameFilter] = useState('');
  const [loaderFilter, setLoaderFilter] = useState('');
  const [lightbox, setLightbox] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    setProject(null);
    setVersions(null);
    setNote(null);
    api
      .getProject(id)
      .then((p) => !cancelled && setProject(p))
      .catch((e) => !cancelled && setNote(String(e)));
    api
      .listProjectVersions(id)
      .then((v) => {
        if (cancelled) return;
        setVersions(v);
        if (prefer && v.some((x) => x.gameVersions.includes(prefer.gameVersion))) setGameFilter(prefer.gameVersion);
        if (prefer && v.some((x) => x.loaders.includes(prefer.loader))) setLoaderFilter(prefer.loader);
      })
      .catch((e) => !cancelled && setNote(String(e)));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const body = useMemo(() => (project ? renderBody(project.body) : ''), [project]);

  /* the two selects list what this project actually ships for */
  const gameOptions = useMemo(() => {
    const seen = new Set<string>();
    versions?.forEach((v) => v.gameVersions.forEach((g) => seen.add(g)));
    return [...seen];
  }, [versions]);
  const loaderOptions = useMemo(() => {
    const seen = new Set<string>();
    versions?.forEach((v) => v.loaders.forEach((l) => seen.add(l)));
    return [...seen];
  }, [versions]);
  const shown = useMemo(
    () =>
      (versions ?? []).filter(
        (v) =>
          (!gameFilter || v.gameVersions.includes(gameFilter)) &&
          (!loaderFilter || v.loaders.includes(loaderFilter)),
      ),
    [versions, gameFilter, loaderFilter],
  );

  const run = async (versionId: string | null) => {
    if (!project) return;
    const key = versionId ?? '*';
    setInstalling(key);
    setNote(null);
    try {
      if (await install(versionId, project)) setInstalled((s) => new Set(s).add(key));
    } catch (e) {
      setNote(String(e));
    }
    setInstalling(null);
  };

  const meta = project
    ? [
        project.loaders.join(' / ') || null,
        project.gameVersions.length
          ? project.gameVersions.length > 3
            ? `${project.gameVersions[project.gameVersions.length - 1]} – ${project.gameVersions[0]}`
            : project.gameVersions.join(', ')
          : null,
        `${fmtCount(project.downloads)} downloads`,
        `updated ${fmtDate(project.updated)}`,
      ]
        .filter(Boolean)
        .join(' · ')
    : '…';

  return (
    <div className="page">
      {/* frame 5 head: the instance-editor layout with the grey type plate */}
      <div className="page__head editor__head">
        <span className="editor__icon project__icon">
          {project?.iconUrl ? (
            <img src={project.iconUrl} alt="" draggable={false} />
          ) : (
            <PixelGlyph glyph="box" size={56} color="var(--text-3)" />
          )}
        </span>
        <div className="editor__ident">
          <div className="editor__ident-row">
            <h1 className="page__title editor__title">{project?.title ?? 'LOADING…'}</h1>
            <PxBox family="grey" height="sm" className="editor__badge">
              <TT size={14} tone="sub">
                {KIND_LABEL[kind]}
              </TT>
            </PxBox>
          </div>
          <TT size={14} tone="plain" className="editor__meta">
            {meta}
          </TT>
        </div>
        <PxButton family="red" height="md" className="browse__back" onClick={onBack}>
          <PixelGlyph glyph="left" size={22} color="var(--r-co)" />
          <TT size={20} tone="red">
            BACK
          </TT>
        </PxButton>
        <PxButton
          family="moss"
          height="md"
          className="page__cta project__install"
          disabled={!project || installing !== null || installed.has('*') || !!current}
          title={current ? `${current.versionNumber} is installed in this instance` : 'The latest version that fits'}
          onClick={() => void run(null)}
        >
          <TT size={20} tone="moss">
            {installing === '*' ? 'INSTALLING…' : installed.has('*') || current ? 'INSTALLED' : installTitle}
          </TT>
        </PxButton>
      </div>

      <div className="project">
        <div className="win project__win">
          <div className="win__bar">
            <div className="project__lead" />
            {(['DESCRIPTION', 'VERSIONS', 'GALLERY'] as Tab[]).map((t) => (
              <NavCell key={t} label={t} active={tab === t} onClick={() => setTab(t)} />
            ))}
            <div className="win__fill" />
            {tab === 'VERSIONS' && versions && <NavLabel label={`${shown.length} OF ${versions.length}`} />}
            {tab === 'GALLERY' && project && <NavLabel label={`${project.gallery.length} IMAGES`} />}
          </div>

          <div className="win__body project__body">
            {note && <span className="meta">{note}</span>}

            {tab === 'DESCRIPTION' && (
              <div className="project__prose scroll">
                {project ? (
                  <div
                    className="md"
                    // sanitized by DOMPurify in renderBody
                    dangerouslySetInnerHTML={{ __html: body }}
                    onClick={(e) => {
                      const a = (e.target as HTMLElement).closest('a');
                      if (a?.href) {
                        e.preventDefault();
                        void openLink(a.href);
                      }
                    }}
                  />
                ) : (
                  <TT size={16} tone="dim">
                    LOADING…
                  </TT>
                )}
              </div>
            )}

            {tab === 'VERSIONS' && (
              <>
                <div className="browse__toolbar">
                  <PxBox family="panel" height="sm" className="browse__select">
                    <select className="input select" value={gameFilter} onChange={(e) => setGameFilter(e.target.value)}>
                      <option value="">All game versions</option>
                      {gameOptions.map((g) => (
                        <option key={g} value={g}>
                          {g}
                        </option>
                      ))}
                    </select>
                  </PxBox>
                  {loaderOptions.length > 1 && (
                    <PxBox family="panel" height="sm" className="browse__select">
                      <select className="input select" value={loaderFilter} onChange={(e) => setLoaderFilter(e.target.value)}>
                        <option value="">All loaders</option>
                        {loaderOptions.map((l) => (
                          <option key={l} value={l}>
                            {l}
                          </option>
                        ))}
                      </select>
                    </PxBox>
                  )}
                </div>
                <div className="project__versions scroll">
                  {!versions && (
                    <TT size={16} tone="dim">
                      LOADING…
                    </TT>
                  )}
                  {shown.map((v) => {
                    const done = installed.has(v.id) || current?.versionId === v.id;
                    const swap = !done && !!current;
                    return (
                      <PxBox key={v.id} family="panel" listing className="project__version">
                        <span className={['project__vtype', `project__vtype--${v.versionType}`].join(' ')}>
                          <TT size={11} tone={v.versionType === 'release' ? 'green' : v.versionType === 'beta' ? 'yellow' : 'red'}>
                            {v.versionType.toUpperCase()}
                          </TT>
                        </span>
                        <span className="project__vtext">
                          <span className="browse__name">
                            <TT size={20}>{v.name || v.versionNumber}</TT>
                            {v.name && v.name !== v.versionNumber && <span className="meta">{v.versionNumber}</span>}
                          </span>
                          <span className="meta">
                            {[v.loaders.join(' / '), v.gameVersions.slice(0, 6).join(', ') + (v.gameVersions.length > 6 ? '…' : ''), `${fmtCount(v.downloads)} downloads`, fmtDate(v.published)]
                              .filter(Boolean)
                              .join(' · ')}
                          </span>
                        </span>
                        <PxButton
                          family="moss"
                          height="sm"
                          className="project__vinstall"
                          title={swap ? `Replaces ${current!.versionNumber}` : undefined}
                          disabled={installing !== null || done}
                          onClick={() => void run(v.id)}
                        >
                          <TT size={16} tone="moss">
                            {installing === v.id ? 'INSTALLING…' : done ? 'INSTALLED' : swap ? 'SWITCH' : 'INSTALL'}
                          </TT>
                        </PxButton>
                      </PxBox>
                    );
                  })}
                  {versions && shown.length === 0 && (
                    <TT size={16} tone="dim">
                      NO VERSIONS MATCH
                    </TT>
                  )}
                </div>
              </>
            )}

            {tab === 'GALLERY' && (
              <div className="project__gallery scroll">
                {project?.gallery.map((g, i) => (
                  <button key={g.url} className="project__shot" onClick={() => setLightbox(i)} title={g.title ?? undefined}>
                    <img src={g.url} alt={g.title ?? ''} loading="lazy" draggable={false} />
                    {g.title && <span className="meta project__shot-title">{g.title}</span>}
                  </button>
                ))}
                {project && project.gallery.length === 0 && (
                  <TT size={16} tone="dim">
                    NO IMAGES
                  </TT>
                )}
              </div>
            )}
          </div>
        </div>

        {/* the details column */}
        <PxBox family="panel" className="project__side scroll">
          {project && (
            <>
              <Detail label="COMPATIBILITY">
                <Row k="client" v={project.clientSide} />
                <Row k="server" v={project.serverSide} />
              </Detail>
              {project.loaders.length > 0 && (
                <Detail label="LOADERS">
                  <span className="browse__chips project__chips">
                    {project.loaders.map((l) => (
                      <span key={l} className="browse__chip">
                        {l}
                      </span>
                    ))}
                  </span>
                </Detail>
              )}
              {project.gameVersions.length > 0 && (
                <Detail label="GAME VERSIONS">
                  <span className="browse__chips project__chips">
                    {project.gameVersions.map((g) => (
                      <span key={g} className="browse__chip">
                        {g}
                      </span>
                    ))}
                  </span>
                </Detail>
              )}
              {project.categories.length > 0 && (
                <Detail label="CATEGORIES">
                  <span className="browse__chips project__chips">
                    {project.categories.map((c) => (
                      <span key={c} className="browse__chip">
                        {c}
                      </span>
                    ))}
                  </span>
                </Detail>
              )}
              <Detail label="LINKS">
                {(
                  [
                    ['SOURCE', project.sourceUrl],
                    ['ISSUES', project.issuesUrl],
                    ['WIKI', project.wikiUrl],
                    ['DISCORD', project.discordUrl],
                    ['MODRINTH', `https://modrinth.com/${kind}/${project.slug}`],
                  ] as [string, string | null][]
                )
                  .filter((x): x is [string, string] => !!x[1])
                  .map(([label, url]) => (
                    <button key={label} className="project__link" onClick={() => void openLink(url)}>
                      <TT size={13} tone="blue">
                        {label}
                      </TT>
                    </button>
                  ))}
              </Detail>
              <Detail label="NUMBERS">
                <Row k="downloads" v={project.downloads.toLocaleString()} />
                <Row k="followers" v={project.follows.toLocaleString()} />
                <Row k="published" v={fmtDate(project.published)} />
                <Row k="updated" v={fmtDate(project.updated)} />
              </Detail>
            </>
          )}
        </PxBox>
      </div>

      {lightbox !== null && project && project.gallery[lightbox] && (
        <div className="project__lightbox" onClick={() => setLightbox(null)}>
          <img src={project.gallery[lightbox].url} alt="" draggable={false} />
        </div>
      )}
    </div>
  );
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="project__detail">
      <TT size={14} tone="dim">
        {label}
      </TT>
      {children}
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <span className="project__row">
      <span className="meta">{k}</span>
      <span className="meta project__val">{v}</span>
    </span>
  );
}
