import { useEffect, useMemo, useState } from 'react';
import { NavCell, PxBox, PxButton, TT } from '../components/px/Px';
import InstanceEditor from './InstanceEditor';
import BrowseProjects from './Browse';
import Project from './Project';
import InstallModpack, { type InstallTarget } from './InstallModpack';
/* Figma 96:2 — the card's picture ("dawnbright 2", the scene's middle band)
   and the 16×16 pixel gear on its square, both exact exports */
import instanceBanner from '../assets/brand/instance-banner.webp';
import gear16 from '../assets/brand/gear-16.png';
import {
  ago,
  api,
  clientModSupports,
  DUSK_PACK,
  isTauri,
  loaderLabel,
  type GameState,
  type Profile,
  type Version,
} from '../lib/api';

const FILTERS = ['ALL', 'VANILLA', 'FABRIC'] as const;
type Filter = (typeof FILTERS)[number];

export default function Instances({
  profiles,
  selected,
  game,
  onRefresh,
  onLaunch,
  onSelect,
  onStop,
}: {
  profiles: Profile[];
  selected: Profile | null;
  game: GameState | null;
  onRefresh: () => Promise<void> | void;
  onLaunch: (id: string) => void;
  onSelect: (id: string) => void;
  onStop: () => void;
}) {
  const [filter, setFilter] = useState<Filter>('ALL');
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
  /* the card's gear opens the editor; it is keyed by id so a save (which
     refreshes the list) re-renders it with the fresh profile */
  const [editingId, setEditingId] = useState<string | null>(null);
  const editing = editingId ? profiles.find((p) => p.id === editingId) ?? null : null;

  /* the backend runs one game at a time: the card that owns it gets STOP,
     every other card's PLAY waits */
  const live = game && game.state !== 'exited' ? game : null;

  const shown = useMemo(
    () =>
      profiles.filter((p) => filter === 'ALL' || p.loader.toUpperCase() === filter),
    [profiles, filter],
  );

  const deleteModal = confirm && (
    <div className="modal-scrim" onClick={() => setConfirm(null)}>
      <PxBox family="red" className="px--window modal" onClick={(e) => e.stopPropagation()}>
        <TT size={22} tone="red">
          DELETE INSTANCE?
        </TT>
        <span className="meta">
          “{confirm.name}” and everything in its folder — worlds, mods, configs — is removed from
          disk. This cannot be undone.
        </span>
        <div className="modal__row modal__row--tall">
          <PxButton family="grey" height="md" onClick={() => setConfirm(null)}>
            <TT size={20}>CANCEL</TT>
          </PxButton>
          <PxButton
            family="red"
            height="md"
            onClick={async () => {
              await api.deleteProfile(confirm.id);
              setConfirm(null);
              setEditingId(null);
              await onRefresh();
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

  if (editing) {
    return (
      <>
        <InstanceEditor
          profile={editing}
          game={game}
          onBack={() => setEditingId(null)}
          onLaunch={(id) => {
            onSelect(id);
            onLaunch(id);
          }}
          onStop={onStop}
          onRefresh={onRefresh}
          onDelete={setConfirm}
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
                  className={['card', p.id === selected?.id ? 'is-selected' : ''].join(' ')}
                >
                  {/* 30:374 — a 2px black + 3px band frame around the picture */}
                  <span className="card__banner-frame">
                    <img className="card__banner" src={instanceBanner} alt="" draggable={false} />
                  </span>
                  <span className="card__name">{p.name}</span>
                  <span className="card__info">
                    {loaderLabel(p)} · {p.gameVersion} · {ago(p.lastPlayed)}
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
                          {live.state === 'running' ? 'STOP GAME' : 'STARTING…'}
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
   Figma 98:88 — a 307×223 chooser: CREATE JAVA PROFILE in the accent, then
   DUSK PROFILE (purple) and BROWSE MODPACKS (moss), 265×46 grey-ring
   buttons with tinted surfaces. Two more grey rows: CUSTOM PROFILE (a bare
   vanilla / fabric instance) and IMPORT .MRPACK off disk (desktop only).
   DUSK PROFILE is the frame-6 install popup pointed at DUSK_PACK: pick the
   pack release + game version exactly as for any modpack, and the instance
   is the whole pack plus everything the launcher forces in at launch. The
   custom form is name + Minecraft version; the fabric loader resolves
   itself and shows up read-only, and the FABRIC LOADER toggle is the loader
   switch: on → fabric, off → plain vanilla with no modloader. */
function NewInstance({
  onClose,
  onBrowse,
  onCreated,
}: {
  onClose: () => void;
  onBrowse: () => void;
  onCreated: (p: Profile) => void;
}) {
  const [step, setStep] = useState<'pick' | 'dusk' | 'custom'>('pick');
  const [versions, setVersions] = useState<Version[]>([]);
  const [name, setName] = useState('');
  const [version, setVersion] = useState('');
  const [optimized, setOptimized] = useState(true);
  const [fabricVer, setFabricVer] = useState<string | null>(null);
  /* the pack's icon for the DUSK PROFILE popup; the box glyph until it lands */
  const [duskIcon, setDuskIcon] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    void api
      .getProject(DUSK_PACK.id)
      .then((p) => setDuskIcon(p.iconUrl))
      .catch(() => setDuskIcon(null));
  }, []);

  useEffect(() => {
    void api
      .listVersions()
      .then((v) => {
        setVersions(v);
        const latest = v.find((x) => x.type === 'release');
        if (latest) setVersion((cur) => cur || latest.id);
      })
      .catch((e) => setErr(String(e)));
    void api
      .fabricLoaderVersion()
      .then(setFabricVer)
      .catch(() => setFabricVer(null));
  }, []);

  const releases = versions.filter((v) => v.type === 'release').slice(0, 60);

  const importFile = async () => {
    setBusy(true);
    setErr(null);
    try {
      const p = await api.importMrpack();
      if (p) onCreated(p);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  };

  if (step === 'pick') {
    return (
      <div className="modal-scrim" onClick={onClose}>
        <PxBox family="grey" className="px--window modal modal--pick" onClick={(e) => e.stopPropagation()}>
          <TT size={16} tone="accent" className="modal--pick__title">
            CREATE JAVA PROFILE
          </TT>
          <PxButton family="dusk" height="md" onClick={() => setStep('dusk')}>
            <TT size={20} tone="purple">
              DUSK PROFILE
            </TT>
          </PxButton>
          <PxButton family="moss" height="md" onClick={onBrowse}>
            <TT size={20} tone="moss">
              BROWSE MODPACKS
            </TT>
          </PxButton>
          <PxButton family="grey" height="md" onClick={() => setStep('custom')} title="A bare vanilla or Fabric instance">
            <TT size={20}>CUSTOM PROFILE</TT>
          </PxButton>
          <PxButton
            family="grey"
            height="md"
            disabled={!isTauri || busy}
            title={isTauri ? 'Import a .mrpack from disk' : 'Needs the desktop app'}
            onClick={() => void importFile()}
          >
            <TT size={20} tone={isTauri ? 'plain' : 'dim'}>
              {busy ? 'IMPORTING…' : 'IMPORT .MRPACK'}
            </TT>
          </PxButton>
          {err && <span className="meta">{err}</span>}
        </PxBox>
      </div>
    );
  }

  if (step === 'dusk') {
    return (
      <InstallModpack
        target={{ id: DUSK_PACK.id, title: DUSK_PACK.title, iconUrl: duskIcon }}
        heading="DUSK PROFILE"
        action="CREATE"
        defaultName={DUSK_PACK.instanceName}
        note={(chosen) =>
          !chosen || clientModSupports(chosen.gameVersions[0] ?? '')
            ? `All of ${DUSK_PACK.title} at that release, plus FasterClient and your cosmetics at every launch.`
            : `All of ${DUSK_PACK.title} at that release. FasterClient and cosmetics need a 1.21.x release for now.`
        }
        onBack={() => setStep('pick')}
        onClose={onClose}
        onInstall={async (versionId, instName) => {
          onCreated(await api.installModpackVersion(DUSK_PACK.id, versionId, instName));
        }}
      />
    );
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
          <PxBox family="panel" height="md">
            <input
              className="input"
              list="mc-versions"
              placeholder={releases[0]?.id ?? '1.21.11'}
              value={version}
              onChange={(e) => setVersion(e.target.value)}
            />
            <datalist id="mc-versions">
              {releases.map((v) => (
                <option key={v.id} value={v.id} />
              ))}
            </datalist>
          </PxBox>
        </div>

        {optimized && (
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

        <button
          className="check"
          onClick={() => setOptimized((v) => !v)}
          title="Fabric loader; FasterClient rides along"
        >
          <span className={['px px--grey check__box', optimized ? 'is-on' : ''].join(' ')}>
            <span className="check__tick" />
          </span>
          <span className="newinst__option-text">
            <TT size={16}>FABRIC LOADER</TT>
            <span className="meta">
              {optimized
                ? 'Pinned automatically for that version; FasterClient rides along.'
                : 'Off — plain vanilla, no modloader.'}
            </span>
          </span>
        </button>

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
                onCreated(
                  await api.createProfile(name.trim(), version.trim(), optimized ? 'fabric' : 'vanilla'),
                );
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
