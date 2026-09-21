import { useEffect, useMemo, useState } from 'react';
import PixelGlyph from '../components/px/PixelGlyph';
import { PxBox, PxButton, TT } from '../components/px/Px';
import { api, type ProjectVersion } from '../lib/api';
import { fmtCount } from './Browse';

/** what the dialog needs to know about the pack — a browse hit and a
 *  project page both have it */
export interface InstallTarget {
  id: string;
  title: string;
  iconUrl: string | null;
  /** preselect this version (a VERSIONS-tab row); else the newest */
  versionId?: string;
}

const fmtDate = (iso: string | null) =>
  iso ? new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '';

/** Figma 187:222 — INSTALL MODPACK: the pack's icon beside an editable
 *  instance name, every version of the pack as a pickable list, CANCEL /
 *  INSTALL. A popup over whatever page opened it. */
export default function InstallModpack({
  target,
  onClose,
  onInstall,
  onBack,
  heading = 'INSTALL MODPACK',
  action = 'INSTALL',
  defaultName = target.title.toUpperCase(),
  note,
}: {
  target: InstallTarget;
  onClose: () => void;
  /** resolves once the instance exists; the dialog closes itself after */
  onInstall: (versionId: string, name: string) => Promise<void>;
  /** the DUSK PROFILE flow arrives from the chooser: a BACK button returns
   *  to it, CANCEL still dismisses everything */
  onBack?: () => void;
  heading?: string;
  action?: string;
  defaultName?: string;
  /** one line under the name field — what else the instance ships with;
   *  a function sees the picked version */
  note?: string | ((chosen: ProjectVersion | null) => string);
}) {
  const [name, setName] = useState(defaultName);
  const [versions, setVersions] = useState<ProjectVersion[] | null>(null);
  const [picked, setPicked] = useState<string | null>(target.versionId ?? null);
  const [gameFilter, setGameFilter] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .listProjectVersions(target.id)
      .then((list) => {
        if (cancelled) return;
        setVersions(list);
        /* the preselected row's game version keeps the list focused; else
           the newest version, unfiltered */
        const pre = target.versionId ? list.find((v) => v.id === target.versionId) : undefined;
        if (pre) setGameFilter(pre.gameVersions[0] ?? '');
        setPicked((cur) => cur ?? list[0]?.id ?? null);
      })
      .catch((e) => !cancelled && setErr(String(e)));
    return () => {
      cancelled = true;
    };
  }, [target.id, target.versionId]);

  const gameOptions = useMemo(() => {
    const seen: string[] = [];
    for (const v of versions ?? []) for (const g of v.gameVersions) if (!seen.includes(g)) seen.push(g);
    return seen;
  }, [versions]);

  const shown = useMemo(
    () => (versions ?? []).filter((v) => !gameFilter || v.gameVersions.includes(gameFilter)),
    [versions, gameFilter],
  );

  const chosen = versions?.find((v) => v.id === picked) ?? null;

  const run = async () => {
    if (!chosen) return;
    setBusy(true);
    setErr(null);
    try {
      await onInstall(chosen.id, name.trim());
    } catch (e) {
      setErr(String(e));
      setBusy(false);
    }
  };

  return (
    <div className="modal-scrim" onClick={busy ? undefined : onClose}>
      <PxBox family="panel" className="px--window modal install" onClick={(e) => e.stopPropagation()}>
        <div className="install__head">
          <TT size={22} tone="yellow">
            {heading}
          </TT>
          <PxButton family="grey" height="sm" className="install__close" disabled={busy} onClick={onClose} title="Close">
            <PixelGlyph glyph="close" size={18} color="var(--text-2)" />
          </PxButton>
        </div>

        {/* 187:214 + 187:216 — the icon beside the name field */}
        <div className="install__ident">
          <span className="install__icon">
            {target.iconUrl ? (
              <img src={target.iconUrl} alt="" draggable={false} />
            ) : (
              <PixelGlyph glyph="box" size={56} color="var(--text-3)" />
            )}
          </span>
          <span className="install__field">
            <TT size={13} tone="dim">
              INSTANCE NAME
            </TT>
            <PxBox family="panel" height="md">
              <input
                className="input"
                value={name}
                placeholder={defaultName}
                disabled={busy}
                onChange={(e) => setName(e.target.value)}
              />
            </PxBox>
            {note && <span className="meta install__note">{typeof note === 'function' ? note(chosen) : note}</span>}
          </span>
        </div>

        {/* 187:218 — the pack's versions, the list construction of frame 3/4 */}
        <div className="install__toolbar">
          <TT size={13} tone="dim">
            VERSION
          </TT>
          <PxBox family="panel" height="sm" className="browse__select">
            <select
              className="input select"
              value={gameFilter}
              disabled={busy}
              onChange={(e) => {
                const g = e.target.value;
                setGameFilter(g);
                /* keep the pick visible: the newest version for that game */
                const first = (versions ?? []).find((v) => !g || v.gameVersions.includes(g));
                if (!chosen || (g && !chosen.gameVersions.includes(g))) setPicked(first?.id ?? null);
              }}
            >
              <option value="">All game versions</option>
              {gameOptions.map((g) => (
                <option key={g} value={g}>
                  {g}
                </option>
              ))}
            </select>
          </PxBox>
          <span className="meta install__count">
            {versions ? `${shown.length} of ${versions.length}` : ''}
          </span>
        </div>
        <div className="install__list scroll">
          {!versions && !err && (
            <TT size={16} tone="dim">
              LOADING…
            </TT>
          )}
          {shown.map((v) => {
            const on = v.id === picked;
            return (
              <PxBox
                key={v.id}
                family={on ? 'moss' : 'panel'}
                listing
                className={['project__version', 'install__version', on ? 'is-on' : ''].join(' ')}
                role="radio"
                aria-checked={on}
                onClick={() => !busy && setPicked(v.id)}
              >
                <span className={['project__vtype', `project__vtype--${v.versionType}`].join(' ')}>
                  <TT size={11} tone={v.versionType === 'release' ? 'green' : v.versionType === 'beta' ? 'yellow' : 'red'}>
                    {v.versionType.toUpperCase()}
                  </TT>
                </span>
                <span className="project__vtext">
                  <span className="browse__name">
                    <TT size={16}>{v.name || v.versionNumber}</TT>
                    {v.name && v.name !== v.versionNumber && <span className="meta">{v.versionNumber}</span>}
                  </span>
                  <span className="meta">
                    {[
                      v.loaders.join(' / '),
                      v.gameVersions.slice(0, 6).join(', ') + (v.gameVersions.length > 6 ? '…' : ''),
                      `${fmtCount(v.downloads)} downloads`,
                      fmtDate(v.published),
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  </span>
                </span>
              </PxBox>
            );
          })}
          {versions && shown.length === 0 && (
            <TT size={16} tone="dim">
              {`NO VERSIONS FOR ${gameFilter}`}
            </TT>
          )}
        </div>

        {err && <span className="meta install__err">{err}</span>}

        {/* 187:221 — grey CANCEL, the install plate */}
        <div className="modal__row install__foot">
          {onBack && (
            <PxButton family="grey" height="md" className="install__btn install__back" disabled={busy} onClick={onBack}>
              <TT size={20}>BACK</TT>
            </PxButton>
          )}
          <span className="meta install__picked">
            {chosen ? `${chosen.versionNumber || chosen.name} · ${chosen.gameVersions[0] ?? ''}` : ''}
          </span>
          <PxButton family="grey" height="md" className="install__btn" disabled={busy} onClick={onClose}>
            <TT size={20}>CANCEL</TT>
          </PxButton>
          <PxButton
            family="moss"
            height="md"
            className="install__btn"
            disabled={busy || !chosen || !name.trim()}
            onClick={() => void run()}
          >
            <TT size={20} tone="moss">
              {busy ? 'INSTALLING…' : action}
            </TT>
          </PxButton>
        </div>
      </PxBox>
    </div>
  );
}
