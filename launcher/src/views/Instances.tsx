import { useEffect, useMemo, useState } from 'react';
import { NavCell, PxBox, PxButton, TT } from '../components/px/Px';
import { Choice, Combobox } from '../components/px/Form';
import InstanceEditor from './InstanceEditor';
import InstanceArt from '../components/InstanceArt';
import BrowseProjects from './Browse';
import Project from './Project';
import InstallModpack, { type InstallTarget } from './InstallModpack';
import Screenshots from './Screenshots';
/* Figma 96:2 — the card's picture ("dawnbright 2", the scene's middle band)
   and the 16×16 pixel gear on its square, both exact exports */
import instanceBanner from '../assets/brand/instance-banner.webp';
import gear16 from '../assets/brand/gear-16.png';
import {
  ago,
  api,
  clientModSupports,
  DUSK_PROFILE,
  featuredVersions,
  isTauri,
  loaderLabel,
  type ExternalInstance,
  type GameState,
  type LaunchTarget,
  type Profile,
  type ReleaseArt,
  type Version,
} from '../lib/api';

/* the loader tabs, then one per group (keyed `g:<name>` so a group called
   "Fabric" doesn't collide with the loader) */
const FILTERS = ['ALL', 'VANILLA', 'FABRIC', 'NEOFORGE'] as const;

export default function Instances({
  profiles,
  selected,
  game,
  onRefresh,
  onLaunch,
  onSelect,
  onStop,
  onWatch,
  clock24h,
}: {
  profiles: Profile[];
  selected: Profile | null;
  game: GameState | null;
  onRefresh: () => Promise<void> | void;
  onLaunch: (id: string, to?: LaunchTarget) => void;
  onSelect: (id: string) => void;
  onStop: () => void;
  /** WATCH on the media page: launch that instance straight into the recording */
  onWatch: (profileId: string, path: string) => void;
  clock24h: boolean;
}) {
  const [filter, setFilter] = useState<string>('ALL');
  /* every instance's screenshots, one page (the instances layout) */
  const [gallery, setGallery] = useState(false);
  const [creating, setCreating] = useState(false);
  const [browsing, setBrowsing] = useState(false);
  const [projectId, setProjectId] = useState<string | null>(null);
  /* the INSTALL MODPACK dialog (frame 6) and who is waiting on it: the
     browse row / project button that opened it learns whether an instance
     came out of it */
  const [installing, setInstalling] = useState<{ target: InstallTarget; done: (ok: boolean) => void } | null>(null);
  const openInstall = (target: InstallTarget) =>
    new Promise<boolean>((done) => setInstalling({ target, done }));
  const installModal = installing && (
    <InstallModpack
      target={installing.target}
      onClose={() => {
        installing.done(false);
        setInstalling(null);
      }}
      onInstall={async (versionId, name) => {
        const p = await api.installModpackVersion(installing.target.id, versionId, name);
        installing.done(true);
        setInstalling(null);
        setProjectId(null);
        setBrowsing(false);
        await onRefresh();
        onSelect(p.id);
      }}
    />
  );
  const [confirm, setConfirm] = useState<Profile | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteErr, setDeleteErr] = useState<string | null>(null);
  /* the card's gear opens the editor; it is keyed by id so a save (which
     refreshes the list) re-renders it with the fresh profile */
  const [editingId, setEditingId] = useState<string | null>(null);
  const editing = editingId ? profiles.find((p) => p.id === editingId) ?? null : null;

  /* the backend runs one game at a time: the card that owns it gets STOP,
     every other card's PLAY waits */
  const live = game && game.state !== 'exited' ? game : null;

  const groups = useMemo(
    () => [...new Set(profiles.flatMap((p) => (p.group ? [p.group] : [])))].sort((a, b) => a.localeCompare(b)),
    [profiles],
  );
  // the last instance left a group: its tab goes, so does the filter
  useEffect(() => {
    if (filter.startsWith('g:') && !groups.includes(filter.slice(2))) setFilter('ALL');
  }, [groups, filter]);

  const shown = useMemo(
    () =>
      profiles.filter((p) =>
        filter.startsWith('g:') ? p.group === filter.slice(2) : filter === 'ALL' || p.loader.toUpperCase() === filter,
      ),
    [profiles, filter],
  );

  const deleteModal = confirm && (
    <div className="modal-scrim" onClick={() => !deleting && (setConfirm(null), setDeleteErr(null))}>
      <PxBox family="red" className="px--window modal" onClick={(e) => e.stopPropagation()}>
        <TT size={22} tone="red">
          DELETE INSTANCE?
        </TT>
        <span className="meta">
          “{confirm.name}” leaves the list, and its folder — worlds, mods, configs — goes to the
          trash.
        </span>
        {deleteErr && <span className="meta">{deleteErr}</span>}
        <div className="modal__row modal__row--tall">
          <PxButton family="grey" height="md" disabled={deleting} onClick={() => (setConfirm(null), setDeleteErr(null))}>
            <TT size={20}>CANCEL</TT>
          </PxButton>
          <PxButton
            family="red"
            height="md"
            disabled={deleting}
            onClick={async () => {
              setDeleting(true);
              setDeleteErr(null);
              try {
                await api.deleteProfile(confirm.id);
                setConfirm(null);
                setEditingId(null);
                await onRefresh();
              } catch (e) {
                setDeleteErr(String(e));
              } finally {
                setDeleting(false);
              }
            }}
          >
            <TT size={20} tone="red">
              DELETE
            </TT>
          </PxButton>
        </div>
      </PxBox>
    </div>
  );

  if (gallery)
    return (
      <Screenshots
        clock24h={clock24h}
        gameBusy={game !== null}
        onWatch={onWatch}
        onBack={() => setGallery(false)}
      />
    );

  if (editing) {
    return (
      <>
        <InstanceEditor
          profile={editing}
          game={game}
          onBack={() => setEditingId(null)}
          onLaunch={(id, to) => {
            onSelect(id);
            onLaunch(id, to);
          }}
          onStop={onStop}
          onRefresh={onRefresh}
          onDelete={setConfirm}
          groups={groups}
        />
        {deleteModal}
      </>
    );
  }

  /* a modpack page (frame 5) on top of the browse page: INSTALL opens the
     dialog on the newest version, a VERSIONS row opens it on that one */
  if (browsing && projectId) {
    return (
      <>
        <Project
          kind="modpack"
          id={projectId}
          install={(versionId, project) =>
            openInstall({ id: projectId, title: project.title, iconUrl: project.iconUrl, versionId: versionId ?? undefined })
          }
          onBack={() => setProjectId(null)}
        />
        {installModal}
      </>
    );
  }

  if (browsing) {
    return (
      <>
        <BrowseProjects
          kind="modpack"
          noun="modpacks"
          search={(q, f, page, size, sort) => api.searchModpacks(q, f, page, size, sort)}
          install={(hit) => openInstall({ id: hit.id, title: hit.title, iconUrl: hit.iconUrl })}
          onOpen={(hit) => setProjectId(hit.id)}
          onBack={() => setBrowsing(false)}
        />
        {installModal}
      </>
    );
  }

  return (
    <div className="page">
      <div className="page__head">
        <h1 className="page__title">Instances</h1>
        <PxButton family="grey" height="fill" className="page__cta" onClick={() => setGallery(true)}>
          <TT size={16}>MEDIA</TT>
        </PxButton>
        {/* Figma 40:20 — the CTA construction at 142×42, a 16px label */}
        <PxButton family="accent" height="fill" className="page__cta" onClick={() => setCreating(true)}>
          <TT size={16} tone="accent">
            NEW INSTANCE
          </TT>
        </PxButton>
      </div>

      {/* one rectangle: a navbar attached to a translucent body pane */}
      <div className="win">
        <div className="win__bar">
          {FILTERS.map((f) => (
            <NavCell key={f} label={f} active={filter === f} onClick={() => setFilter(f)} />
          ))}
          {groups.map((g) => (
            <NavCell key={g} label={g.toUpperCase()} active={filter === `g:${g}`} onClick={() => setFilter(`g:${g}`)} />
          ))}
          <div className="win__fill" />
        </div>

        <div className="win__body win__body--tiles">
          {shown.length === 0 ? (
            <PxBox family="panel" className="empty">
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
            <div className="grid scroll">
              {shown.map((p) => (
                <PxBox
                  key={p.id}
                  family="grey"
                  className={[
                    'card',
                    p.id === selected?.id ? 'is-selected' : '',
                    live?.profileId === p.id ? 'is-live' : '',
                  ].join(' ')}
                >
                  {/* 30:374 — a 2px black + 3px band frame around the picture */}
                  <span className="card__banner-frame">
                    <InstanceArt profile={p} className="card__banner" />
                  </span>
                  <span className="card__name">{p.name}</span>
                  <span className="card__info">
                    {loaderLabel(p)} · {p.gameVersion} ·{' '}
                    {live?.profileId === p.id ? <span className="is-live">RUNNING</span> : ago(p.lastPlayed)}
                    {p.modCount > 0 ? ` · ${p.modCount} mods` : ''}
                  </span>
                  <div className="card__row">
                    {live?.profileId === p.id ? (
                      <PxButton
                        family="red"
                        height="fill"
                        className="card__play"
                        disabled={live.state !== 'running'}
                        onClick={onStop}
                      >
                        <TT size={22} tone="red" sx={1.15}>
                          {live.state === 'running' ? 'STOP GAME' : live.state === 'stopping' ? 'STOPPING…' : 'STARTING…'}
                        </TT>
                      </PxButton>
                    ) : (
                      <PxButton
                        family="grey"
                        height="fill"
                        className="card__play"
                        disabled={live !== null}
                        onClick={() => {
                          onSelect(p.id);
                          onLaunch(p.id);
                        }}
                      >
                        <TT size={22} tone="accent" sx={1.15}>
                          PLAY NOW
                        </TT>
                      </PxButton>
                    )}
                    {/* 38:9 — the 40px square, the 93:132 gear centred on it */}
                    <PxButton
                      family="grey"
                      height="fill"
                      className="card__gear"
                      title={`${p.name} settings`}
                      onClick={() => setEditingId(p.id)}
                    >
                      <img className="card__gear-glyph" src={gear16} alt="" draggable={false} />
                    </PxButton>
                  </div>
                </PxBox>
              ))}
            </div>
          )}
        </div>
      </div>

      {creating && (
        <NewInstance
          onClose={() => setCreating(false)}
          onBrowse={() => {
            setCreating(false);
            setBrowsing(true);
          }}
          onCreated={async (p) => {
            setCreating(false);
            await onRefresh();
            onSelect(p.id);
          }}
        />
      )}

      {deleteModal}
    </div>
  );
}

/* ── NEW INSTANCE ───────────────────────────────────────────────────────────
   The chooser leads with the game itself: the newest release of each recent
   line as a big card wearing the art Mojang publishes for it (the square the
   official launcher shows beside its patch notes), plus the newest snapshot
   when it's ahead of every release. Every card is a DUSK PROFILE: Fabric
   with Dusk Essentials resolved for exactly that version, and DuskClient at
   launch wherever a build exists. The VERSION field under the cards takes any
   other release. Underneath, the other ways in: BROWSE MODPACKS, CUSTOM
   PROFILE (a bare vanilla / fabric / neoforge instance — name + Minecraft
   version + LOADER; the fabric loader resolves itself, NeoForge picks the
   newest build at first launch) and IMPORT .MRPACK off disk. */

/* what each update was called; the patch-notes blurb covers the rest */
const DROP_NAMES: Record<string, string> = {
  '26.3': 'WILDERNESS BOUND',
  '26.2': 'CHAOS CUBED',
  '26.1': 'TINY TAKEOVER',
  '1.21.11': 'MOUNTS OF MAYHEM',
  '1.21.9': 'THE COPPER AGE',
  '1.21.6': 'CHASE THE SKIES',
  '1.21.5': 'SPRING TO LIFE',
};

/** "26.1.2" → "26.1"; 1.x keeps its patch ("1.21.11") — those were drops */
const lineOf = (id: string) => {
  const m = /^(\d+)\.(\d+)(?:\.(\d+))?/.exec(id);
  if (!m) return id;
  return m[1] === '1' ? id.split('-')[0] : `${m[1]}.${m[2]}`;
};

const artFor = (art: ReleaseArt[], id: string) =>
  art.find((a) => a.version === id) ??
  art.find((a) => a.version === lineOf(id)) ??
  art.find((a) => a.version.startsWith(`${lineOf(id)}-`) || a.version.startsWith(`${lineOf(id)}.`));

function NewInstance({
  onClose,
  onBrowse,
  onCreated,
}: {
  onClose: () => void;
  onBrowse: () => void;
  onCreated: (p: Profile) => void;
}) {
  const [step, setStep] = useState<'pick' | 'custom' | 'import'>('pick');
  const [versions, setVersions] = useState<Version[]>([]);
  const [art, setArt] = useState<ReleaseArt[]>([]);
  /* the DUSK PROFILE's version and name; the name follows the version until
     the user types their own */
  const [duskVer, setDuskVer] = useState('');
  const [duskName, setDuskName] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [version, setVersion] = useState('');
  const [loader, setLoader] = useState<'fabric' | 'neoforge' | 'vanilla'>('fabric');
  const [perf, setPerf] = useState<'on' | 'off'>('on');
  const [fabricVer, setFabricVer] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    void api
      .listVersions()
      .then((v) => {
        setVersions(v);
        const latest = v.find((x) => x.type === 'release');
        if (latest) setVersion((cur) => cur || latest.id);
        const lead = featuredVersions(v).find((x) => x.type === 'release');
        if (lead) setDuskVer((cur) => cur || lead.id);
      })
      .catch((e) => setErr(String(e)));
    void api
      .releaseArt()
      .then(setArt)
      .catch(() => setArt([]));
    void api
      .fabricLoaderVersion()
      .then(setFabricVer)
      .catch(() => setFabricVer(null));
  }, []);

  const featured = useMemo(() => featuredVersions(versions), [versions]);
  const releases = useMemo(
    () => versions.filter((v) => v.type === 'release').map((v) => ({ value: v.id })),
    [versions],
  );
  /* the VERSION field also offers the featured snapshot, flagged */
  const duskOptions = useMemo(
    () => [
      ...featured.filter((v) => v.type !== 'release').map((v) => ({ value: v.id, meta: 'SNAPSHOT' })),
      ...releases,
    ],
    [featured, releases],
  );
  const known = versions.some((v) => v.id === duskVer.trim());
  const finalName = (duskName ?? DUSK_PROFILE.instanceName(duskVer.trim())).trim();

  const createDusk = async () => {
    setBusy(true);
    setErr(null);
    try {
      const created = await api.createProfile(finalName, duskVer.trim(), 'fabric');
      // the essentials download in the background; the instance is usable meanwhile
      void api.installDuskEssentials(created.id).catch(() => {});
      onCreated(created);
    } catch (e) {
      setErr(String(e));
      setBusy(false);
    }
  };

  if (step === 'pick') {
    const v = duskVer.trim();
    return (
      <div className="modal-scrim" onClick={onClose}>
        <PxBox family="grey" className="px--window modal modal--versions" onClick={(e) => e.stopPropagation()}>
          <div className="vpick__head">
            <TT size={22}>NEW INSTANCE</TT>
            <span className="meta">Pick a version — every card is a Dusk profile.</span>
          </div>

          <div className="vcards">
            {featured.length === 0
              ? [0, 1, 2, 3].map((i) => <div key={i} className="vcard vcard--ghost" />)
              : featured.map((ver) => {
                  const a = artFor(art, ver.id);
                  const snap = ver.type !== 'release';
                  const drop = DROP_NAMES[lineOf(ver.id)];
                  return (
                    <button
                      key={ver.id}
                      type="button"
                      className={`vcard${v === ver.id ? ' is-picked' : ''}`}
                      aria-pressed={v === ver.id}
                      onClick={() => setDuskVer(ver.id)}
                      onDoubleClick={() => {
                        setDuskVer(ver.id);
                        if (!busy) void createDusk();
                      }}
                      title={a?.blurb || ver.id}
                    >
                      <img src={a?.imageUrl ?? instanceBanner} alt="" draggable={false} />
                      <span className={`vcard__tag${snap ? ' vcard__tag--snap' : ''}`}>
                        {snap ? 'SNAPSHOT' : 'DUSK PROFILE'}
                      </span>
                      <span className="vcard__info">
                        <TT size={36}>{ver.id.toUpperCase()}</TT>
                        <span className="vcard__drop">
                          {drop ?? (snap ? 'NEXT UPDATE PREVIEW' : a?.blurb?.toUpperCase() ?? 'RELEASE')}
                        </span>
                      </span>
                    </button>
                  );
                })}
          </div>

          <div className="modal__row vpick__form">
            <PxBox family="panel" height="md">
              <input
                className="input"
                aria-label="Instance name"
                placeholder="INSTANCE NAME"
                value={duskName ?? DUSK_PROFILE.instanceName(v)}
                onChange={(e) => setDuskName(e.target.value)}
              />
            </PxBox>
            <Combobox
              className="vpick__ver"
              value={duskVer}
              options={duskOptions}
              onChange={setDuskVer}
              placeholder="OTHER VERSION"
            />
            <PxButton
              family="dusk"
              height="md"
              className="vpick__create"
              disabled={busy || !known || !finalName}
              title={known ? undefined : 'Pick a Minecraft version'}
              onClick={() => void createDusk()}
            >
              <TT size={20} tone="purple">
                {busy ? 'CREATING…' : 'CREATE'}
              </TT>
            </PxButton>
          </div>

          <span className="meta">
            {!v
              ? ' '
              : clientModSupports(v)
                ? `Fabric with Dusk Essentials (Sodium, Iris, Lithium and friends, the newest builds for ${v}), plus DuskClient and your cosmetics at every launch.`
                : `Fabric with Dusk Essentials for ${v}. DuskClient isn't out for ${v} yet (it runs on 1.21 – 26.2), so no client modules or cosmetics there.`}
          </span>
          {err && <span className="meta">{err}</span>}

          <div className="modal__row vpick__more">
            <PxButton family="moss" height="md" onClick={onBrowse}>
              <TT size={16} tone="moss">
                BROWSE MODPACKS
              </TT>
            </PxButton>
            <PxButton family="grey" height="md" onClick={() => setStep('custom')} title="A bare vanilla, Fabric or NeoForge instance">
              <TT size={16}>CUSTOM PROFILE</TT>
            </PxButton>
            <PxButton
              family="grey"
              height="md"
              disabled={busy}
              title="An instance from Prism, MultiMC, CurseForge, the Modrinth App or Mojang's launcher, or a .mrpack file"
              onClick={() => setStep('import')}
            >
              <TT size={16}>IMPORT</TT>
            </PxButton>
            <span className="modal__spacer" />
            <PxButton family="grey" height="md" onClick={onClose}>
              <TT size={16}>CANCEL</TT>
            </PxButton>
          </div>
        </PxBox>
      </div>
    );
  }

  if (step === 'import') {
    return <ImportExternal onBack={() => setStep('pick')} onClose={onClose} onCreated={onCreated} />;
  }

  return (
    <div className="modal-scrim" onClick={onClose}>
      <PxBox family="panel" className="px--window modal newinst" onClick={(e) => e.stopPropagation()}>
        <TT size={22}>CUSTOM PROFILE</TT>

        <div className="modal__row">
          <span className="modal__label">
            <TT size={16} tone="dim">
              PROFILE NAME
            </TT>
          </span>
          <PxBox family="panel" height="md">
            <input
              className="input"
              placeholder="MY INSTANCE"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </PxBox>
        </div>

        <div className="modal__row">
          <span className="modal__label">
            <TT size={16} tone="dim">
              MINECRAFT VERSION
            </TT>
          </span>
          <Combobox
            value={version}
            options={releases}
            onChange={setVersion}
            placeholder={releases[0]?.value ?? '1.21.11'}
          />
        </div>

        <div className="modal__row">
          <span className="modal__label">
            <TT size={16} tone="dim">
              LOADER
            </TT>
          </span>
          <div className="newinst__choice">
            <Choice
              value={loader}
              options={[
                { value: 'fabric', label: 'FABRIC' },
                { value: 'neoforge', label: 'NEOFORGE' },
                { value: 'vanilla', label: 'VANILLA' },
              ]}
              onPick={setLoader}
            />
          </div>
        </div>

        {loader === 'fabric' && (
          <div className="modal__row">
            <span className="modal__label">
              <TT size={16} tone="dim">
                FABRIC VERSION
              </TT>
            </span>
            <PxBox family="panel" height="md" className="newinst__resolved">
              <TT size={16} tone="green">
                {fabricVer ?? '…'}
              </TT>
              <span className="meta">— resolved automatically</span>
            </PxBox>
          </div>
        )}

        {loader === 'fabric' && (
          <div className="modal__row">
            <span className="modal__label">
              <TT size={16} tone="dim">
                PERFORMANCE MODS
              </TT>
            </span>
            <div className="newinst__choice">
              <Choice
                value={perf}
                options={[
                  { value: 'on', label: 'ADD' },
                  { value: 'off', label: 'NONE' },
                ]}
                onPick={setPerf}
              />
            </div>
          </div>
        )}

        <span className="meta">
          {loader === 'fabric' && perf === 'on'
            ? 'Sodium, Lithium, FerriteCore, ImmediatelyFast, Entity Culling and friends, the newest builds for that version. DuskClient rides along.'
            : loader === 'fabric'
            ? 'Pinned automatically for that version; DuskClient rides along.'
            : loader === 'neoforge'
              ? 'The newest NeoForge build for that version, installed at first launch. DuskClient is Fabric-only.'
              : 'Plain vanilla, no modloader.'}
        </span>

        {err && <span className="meta">{err}</span>}

        <div className="modal__row">
          <PxButton family="grey" height="md" onClick={() => setStep('pick')}>
            <TT size={20}>BACK</TT>
          </PxButton>
          <span className="modal__spacer" />
          <PxButton family="grey" height="md" onClick={onClose}>
            <TT size={20}>CANCEL</TT>
          </PxButton>
          <PxButton
            family="green"
            height="md"
            disabled={busy || !name.trim() || !version.trim()}
            onClick={async () => {
              setBusy(true);
              setErr(null);
              try {
                const created = await api.createProfile(name.trim(), version.trim(), loader);
                // downloads in the background; the instance is usable meanwhile
                if (loader === 'fabric' && perf === 'on') void api.installPerformanceMods(created.id).catch(() => {});
                onCreated(created);
              } catch (e) {
                setErr(String(e));
                setBusy(false);
              }
            }}
          >
            <TT size={20} tone="green">
              CREATE
            </TT>
          </PxButton>
        </div>
      </PxBox>
    </div>
  );
}

/** NEW INSTANCE → IMPORT: a .mrpack from disk, or any instance the other
 *  launchers on this machine keep, each copied in (mods, config, worlds, packs, options)
 *  as a new Dusk instance on the same version and loader. The original is
 *  left as it was. */
function ImportExternal({
  onBack,
  onClose,
  onCreated,
}: {
  onBack: () => void;
  onClose: () => void;
  onCreated: (p: Profile) => void;
}) {
  const [found, setFound] = useState<ExternalInstance[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    void api
      .scanExternalInstances()
      .then(setFound)
      .catch((e) => {
        setErr(String(e));
        setFound([]);
      });
  }, []);

  const importFile = async () => {
    setBusy('.mrpack');
    setErr(null);
    try {
      const p = await api.importMrpack();
      if (p) onCreated(p);
      else setBusy(null);
    } catch (e) {
      setErr(String(e));
      setBusy(null);
    }
  };

  const [code, setCode] = useState('');
  const importCode = async () => {
    setBusy('code');
    setErr(null);
    try {
      onCreated(await api.importSharedInstance(code));
    } catch (e) {
      setErr(String(e));
      setBusy(null);
    }
  };

  const bring = async (e: ExternalInstance) => {
    setBusy(e.path);
    setErr(null);
    try {
      onCreated(await api.importExternalInstance(e.path));
    } catch (x) {
      setErr(String(x));
      setBusy(null);
    }
  };

  return (
    <div className="modal-scrim" onClick={busy ? undefined : onClose}>
      <PxBox family="panel" className="px--window modal extimport" onClick={(e) => e.stopPropagation()}>
        <TT size={22}>IMPORT</TT>
        <span className="meta">
          Copies the instance's mods, config, worlds, packs and options into a new instance here. The original stays as it is.
        </span>

        <div className="extimport__list">
          {found === null ? (
            <span className="meta">Looking for other launchers…</span>
          ) : found.length === 0 ? (
            <span className="meta">
              No instances found from Prism, MultiMC, CurseForge, the Modrinth App or Mojang's launcher.
            </span>
          ) : (
            found.map((e) => (
              <div key={e.path} className="extimport__row" title={e.path}>
                <span className="extimport__info">
                  <TT size={16}>{e.name}</TT>
                  <span className="meta">
                    {[
                      e.group ? `${e.source} › ${e.group}` : e.source,
                      [e.loader === 'neoforge' ? 'NeoForge' : e.loader.charAt(0).toUpperCase() + e.loader.slice(1), e.gameVersion].filter(Boolean).join(' '),
                      e.mods ? `${e.mods} mod${e.mods === 1 ? '' : 's'}` : '',
                      e.worlds ? `${e.worlds} world${e.worlds === 1 ? '' : 's'}` : '',
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  </span>
                  {e.blocked && <span className="meta extimport__blocked">{e.blocked}</span>}
                </span>
                <PxButton
                  family="green"
                  height="md"
                  disabled={!!e.blocked || busy !== null}
                  onClick={() => void bring(e)}
                >
                  <TT size={16} tone={e.blocked ? 'dim' : 'green'}>
                    {busy === e.path ? 'COPYING…' : 'IMPORT'}
                  </TT>
                </PxButton>
              </div>
            ))
          )}
        </div>

        <div className="modal__row">
          <PxBox family="panel" height="md" className="extimport__code">
            <input
              className="input"
              placeholder="A friend's share code, like K7QM-4XWP"
              value={code}
              maxLength={12}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && code.trim() && busy === null) void importCode();
              }}
            />
          </PxBox>
          <PxButton family="green" height="md" disabled={!code.trim() || busy !== null} onClick={() => void importCode()}>
            <TT size={16} tone={code.trim() ? 'green' : 'dim'}>
              {busy === 'code' ? 'INSTALLING…' : 'INSTALL'}
            </TT>
          </PxButton>
        </div>

        {err && <span className="meta">{err}</span>}

        <div className="modal__row">
          <PxButton family="grey" height="md" disabled={busy !== null} onClick={onBack}>
            <TT size={20}>BACK</TT>
          </PxButton>
          <span className="modal__spacer" />
          <PxButton
            family="grey"
            height="md"
            disabled={!isTauri || busy !== null}
            title={isTauri ? 'A Modrinth modpack file from disk' : 'Needs the desktop app'}
            onClick={() => void importFile()}
          >
            <TT size={20} tone={isTauri ? 'plain' : 'dim'}>
              {busy === '.mrpack' ? 'IMPORTING…' : '.MRPACK FILE…'}
            </TT>
          </PxButton>
          <PxButton family="grey" height="md" disabled={busy !== null} onClick={onClose}>
            <TT size={20}>CANCEL</TT>
          </PxButton>
        </div>
      </PxBox>
    </div>
  );
}
