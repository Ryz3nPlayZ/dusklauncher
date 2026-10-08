import { useEffect, useMemo, useRef, useState } from 'react';
import { getCurrentWebview } from '@tauri-apps/api/webview';
import PixelGlyph from '../components/px/PixelGlyph';
import { Choice, Row } from '../components/px/Form';
import { NavCell, PxBox, PxButton, TT } from '../components/px/Px';
import instanceBanner from '../assets/brand/instance-banner.webp';
import BrowseProjects from './Browse';
import Project from './Project';
import {
  ago,
  api,
  fmtBytes,
  isTauri,
  javaFor,
  loaderLabel,
  playtime,
  type ContentKind,
  type ContentUpdate,
  type Datapack,
  type GameState,
  type InstalledProject,
  type JavaInstall,
  type LatestLog,
  type ImportedWorlds,
  type ModProblems,
  releaseNewer,
  type LaunchTarget,
  type Profile,
  type ProfileFolder,
  type ProfileMod,
  type SavedServer,
  type ServerStatus,
  type Version,
  type World,
} from '../lib/api';
import { clearGameLog, useGameLog } from '../lib/gamelog';

/* ── the instance editor ──────────────────────────────────────────────────
   What the gear on an instance card opens — Figma Frame 4 (172:297): the
   instance's icon, name and a line of metadata up top, then one window whose
   bar carries CONTENT / WORLDS / LOG / SETTINGS. CONTENT nests a second bar
   (ALL / MODS / RESOURCE PACKS / SHADERS) over the file rows. DELETE lives at
   the bottom of SETTINGS where it can't be hit by accident. */

const TABS = ['CONTENT', 'WORLDS', 'LOG', 'SETTINGS'] as const;
type Tab = (typeof TABS)[number];

/* the nested strip under CONTENT (172:626) */
const KINDS = ['ALL', 'MODS', 'RESOURCE PACKS', 'SHADERS'] as const;
type KindTab = (typeof KINDS)[number];

type ContentInfo = {
  kind: ContentKind;
  folder: ProfileFolder;
  noun: string;
  label: string;
  /** the browse page's CATEGORY checkboxes (Modrinth's tags for the type) */
};
const CONTENT: Record<Exclude<KindTab, 'ALL'>, ContentInfo> = {
  MODS: {
    kind: 'mod',
    folder: 'mods',
    noun: 'mods',
    label: 'MOD',
  },
  'RESOURCE PACKS': {
    kind: 'resourcepack',
    folder: 'resourcepacks',
    noun: 'resource packs',
    label: 'RESOURCE PACK',
  },
  SHADERS: {
    kind: 'shader',
    folder: 'shaderpacks',
    noun: 'shader packs',
    label: 'SHADER',
  },
};
const KIND_INFO = Object.values(CONTENT);
const CONTENT_BY_KIND: Record<ContentKind, ContentInfo> = Object.fromEntries(
  KIND_INFO.map((c) => [c.kind, c]),
) as Record<ContentKind, ContentInfo>;

export default function InstanceEditor({
  profile,
  game,
  onBack,
  onLaunch,
  onStop,
  onRefresh,
  onDelete,
}: {
  profile: Profile;
  game: GameState | null;
  onBack: () => void;
  onLaunch: (id: string, to?: LaunchTarget) => void;
  onStop: () => void;
  onRefresh: () => Promise<void> | void;
  onDelete: (p: Profile) => void;
}) {
  const [tab, setTab] = useState<Tab>('CONTENT');
  const [kindTab, setKindTab] = useState<KindTab>('ALL');
  /* GET FROM MODRINTH swaps the whole page for the browse page (frame 3)
     for this kind; BACK lands on the CONTENT tab, which reloads */
  const [browsing, setBrowsing] = useState<ContentInfo | null>(null);
  const [projectId, setProjectId] = useState<string | null>(null);
  /* the project page was opened from an installed row, not from the browse
     list — BACK then returns to the content list instead of the browse page */
  const [fromList, setFromList] = useState(false);
  /* what the folder already holds, keyed by Modrinth project id — the
     browse rows read INSTALLED off it, the project page marks the version */
  const [held, setHeld] = useState<Map<string, InstalledProject>>(() => new Map());
  const heldIds = useMemo(() => new Set(held.keys()), [held]);

  const lookupHeld = async (kind: ContentKind) => {
    try {
      const list = await api.lookupContent(profile.id, kind);
      setHeld(new Map(list.map((m) => [m.projectId, m])));
    } catch {
      /* Modrinth unreachable — the rows just don't pre-mark */
    }
  };

  useEffect(() => {
    setHeld(new Map());
    if (browsing) void lookupHeld(browsing.kind);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [browsing?.kind, profile.id]);
  const content = kindTab === 'ALL' ? null : CONTENT[kindTab];
  /* one game at a time: this instance's own run turns PLAY into STOP,
     another instance's blocks it */
  const live = game && game.state !== 'exited' ? game : null;
  const mine = live?.profileId === profile.id;

  const folder: ProfileFolder =
    tab === 'LOG' ? 'logs' : tab === 'WORLDS' ? 'saves' : tab === 'CONTENT' ? content?.folder ?? '' : '';

  if (browsing && projectId) {
    const b = browsing;
    return (
      <Project
        kind={b.kind}
        id={projectId}
        installTitle="ADD TO INSTANCE"
        prefer={{ gameVersion: profile.gameVersion, loader: profile.loader }}
        current={held.get(projectId) ?? null}
        install={async (versionId) => {
          const old = held.get(projectId);
          const added = versionId
            ? await api.installContentVersion(profile.id, b.kind, versionId)
            : await api.installContent(profile.id, b.kind, projectId);
          /* SWITCH: the folder must never hold two versions of one project */
          if (old && old.filename !== added.filename) {
            await api.removeContent(profile.id, b.kind, old.filename);
          }
          await onRefresh();
          await lookupHeld(b.kind);
          return true;
        }}
        onBack={() => {
          setProjectId(null);
          if (fromList) {
            setBrowsing(null);
            setFromList(false);
          }
        }}
      />
    );
  }

  if (browsing) {
    const b = browsing;
    return (
      <BrowseProjects
        kind={b.kind}
        noun={b.noun}
        initialFacets={{
          categories: [],
          versions: [profile.gameVersion],
          loaders: b.kind === 'mod' && profile.loader !== 'vanilla' ? [profile.loader] : [],
        }}
        search={(q, f, page, size, sort) => api.searchProjects(b.kind, q, f, page, size, sort)}
        install={async (hit) => {
          await api.installContent(profile.id, b.kind, hit.id);
          await onRefresh();
          await lookupHeld(b.kind);
          return true;
        }}
        alreadyInstalled={heldIds}
        onOpen={(hit) => {
          setFromList(false);
          setProjectId(hit.id);
        }}
        onBack={() => setBrowsing(null)}
      />
    );
  }

  return (
    <div className="page">
      {/* 172:580 / 172:352 / 172:602 — icon, name + metadata, the type plate */}
      <div className="page__head editor__head">
        <span className="editor__icon">
          <img src={instanceBanner} alt="" draggable={false} />
        </span>
        <div className="editor__ident">
          <div className="editor__ident-row">
            <h1 className="page__title editor__title">{profile.name}</h1>
            <PxBox family="moss" height="sm" className="editor__badge">
              <TT size={14} tone="plain">
                {profile.loader.toUpperCase()}
              </TT>
            </PxBox>
          </div>
          <TT size={14} tone="plain" className="editor__meta">
            {`${loaderLabel(profile)} · ${profile.gameVersion}${
              profile.loaderVersion ? ` (${profile.loaderVersion})` : ''
            }${profile.modCount > 0 ? ` · ${profile.modCount} mods` : ''} · last played ${ago(
              profile.lastPlayed,
            )}`}
          </TT>
          {profile.playSecs > 0 && (
            <TT size={14} tone="plain" className="editor__meta">
              {`${playtime(profile.playSecs)} played`}
            </TT>
          )}
        </div>
        <PxButton family="grey" height="md" className="browse__back" onClick={onBack}>
          <PixelGlyph glyph="left" size={22} color="var(--text-2)" />
          <TT size={16}>BACK</TT>
        </PxButton>
        {mine ? (
          <PxButton
            family="red"
            height="md"
            className="page__cta"
            disabled={live.state !== 'running'}
            onClick={onStop}
          >
            <TT size={16} tone="red">
              {live.state === 'running' ? 'STOP GAME' : live.state === 'stopping' ? 'STOPPING…' : 'STARTING…'}
            </TT>
          </PxButton>
        ) : (
          <PxButton
            family="accent"
            height="md"
            className="page__cta"
            disabled={live !== null}
            title={live ? 'Another instance is running' : undefined}
            onClick={() => onLaunch(profile.id)}
          >
            <TT size={16} tone="accent">
              PLAY NOW
            </TT>
          </PxButton>
        )}
      </div>

      <div className="win win--solid">
        <div className="win__bar">
          {TABS.map((t) => (
            <NavCell key={t} label={t} active={tab === t} onClick={() => setTab(t)} />
          ))}
          <div className="win__fill" />
          <NavCell label="OPEN FOLDER" onClick={() => void api.openProfileFolder(profile.id, folder)} />
        </div>

        {tab === 'CONTENT' && (
          <ContentTab
            key={kindTab}
            profile={profile}
            kindTab={kindTab}
            onKindTab={setKindTab}
            content={content}
            onBrowse={setBrowsing}
            onOpenProject={(c, id) => {
              setFromList(true);
              setBrowsing(c);
              setProjectId(id);
            }}
          />
        )}
        {tab === 'SETTINGS' && (
          <SettingsTab profile={profile} onSaved={onRefresh} onDelete={() => onDelete(profile)} />
        )}
        {tab === 'WORLDS' && (
          <WorldsTab profile={profile} busy={live !== null} onLaunch={(to) => onLaunch(profile.id, to)} />
        )}
        {tab === 'LOG' && <LogTab profile={profile} game={mine ? live : null} onStop={onStop} />}
      </div>
    </div>
  );
}

/* the heap is its own field (and the launcher strips these at launch), so
   the JVM box never shows them */
/* "sodium-0.5.8.jar" → "sodium-0.5.8"; a folder pack keeps its name */
const displayName = (f: string) => f.replace(/\.(jar|zip)$/i, '');

const isHeapFlag = (a: string) => a.startsWith('-Xmx') || a.startsWith('-Xms');
const jvmText = (p: Profile) => p.jvmArgs.filter((a) => !isHeapFlag(a)).join(' ');

/* one-click JVM tuning; the launcher drops flags a given Java can't parse
   (core launch.rs), so these are safe on every version */
const TUNING = {
  /* Mojang's own G1 set (core profile.rs G1_TUNING), the default */
  balanced:
    '-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M',
  /* generational ZGC: near-pauseless collections for a steadier frame time,
     for a little throughput and memory */
  latency: '-XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:+ZGenerational',
} as const;
type Tuning = keyof typeof TUNING | 'custom';
const tuningOf = (jvm: string): Tuning => {
  const t = jvm.trim().split(/\s+/).join(' ');
  return (Object.keys(TUNING) as (keyof typeof TUNING)[]).find((k) => TUNING[k] === t) ?? 'custom';
};

/* ── SETTINGS: the profile's own fields, saved as one patch ─────────────── */
function SettingsTab({
  profile,
  onSaved,
  onDelete,
}: {
  profile: Profile;
  onSaved: () => Promise<void> | void;
  onDelete: () => void;
}) {
  const [name, setName] = useState(profile.name);
  const [version, setVersion] = useState(profile.gameVersion);
  const [loader, setLoader] = useState(profile.loader);
  const [width, setWidth] = useState(String(profile.resolution[0]));
  const [height, setHeight] = useState(String(profile.resolution[1]));
  const [jvm, setJvm] = useState(jvmText(profile));
  const [memory, setMemory] = useState(profile.memoryMb ? String(profile.memoryMb) : '');
  const [javaPath, setJavaPath] = useState(profile.javaPath ?? '');
  /* the JAVA picker: open when set, the installs once they're found */
  const [javas, setJavas] = useState<JavaInstall[] | 'open' | null>(null);
  const [server, setServer] = useState(profile.server ?? '');
  const [versions, setVersions] = useState<Version[]>([]);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    void api.listVersions().then(setVersions).catch(() => setVersions([]));
  }, []);

  // the form is a copy of the profile; a save or an outside refresh resets it
  useEffect(() => {
    setName(profile.name);
    setVersion(profile.gameVersion);
    setLoader(profile.loader);
    setWidth(String(profile.resolution[0]));
    setHeight(String(profile.resolution[1]));
    setJvm(jvmText(profile));
    setMemory(profile.memoryMb ? String(profile.memoryMb) : '');
    setJavaPath(profile.javaPath ?? '');
    setServer(profile.server ?? '');
  }, [profile]);

  const w = Number(width);
  const h = Number(height);
  const mb = memory.trim() === '' ? 0 : Number(memory);
  const dirty =
    name.trim() !== profile.name ||
    version.trim() !== profile.gameVersion ||
    loader !== profile.loader ||
    w !== profile.resolution[0] ||
    h !== profile.resolution[1] ||
    jvm.trim() !== jvmText(profile) ||
    mb !== (profile.memoryMb ?? 0) ||
    javaPath.trim() !== (profile.javaPath ?? '') ||
    server.trim() !== (profile.server ?? '');
  const valid =
    name.trim() !== '' &&
    version.trim() !== '' &&
    w >= 320 &&
    h >= 240 &&
    Number.isInteger(mb) &&
    (mb === 0 || mb >= 512);

  const save = async () => {
    setBusy(true);
    setNote(null);
    try {
      await api.updateProfile(profile.id, {
        name: name.trim(),
        gameVersion: version.trim(),
        loader,
        // the heap lives in MEMORY now; a -Xmx typed here would be overridden
        jvmArgs: jvm.trim() ? jvm.trim().split(/\s+/).filter((a) => !isHeapFlag(a)) : [],
        resolution: [Math.round(w), Math.round(h)],
        server: server.trim(),
        memoryMb: mb,
        javaPath: javaPath.trim(),
      });
      await onSaved();
      setNote('Saved.');
    } catch (e) {
      setNote(String(e));
    } finally {
      setBusy(false);
    }
  };

  const duplicate = async () => {
    setBusy(true);
    setNote(null);
    try {
      const copy = await api.duplicateProfile(profile.id);
      setNote(`Copied as “${copy.name}” — it's in INSTANCES.`);
      await onSaved();
    } catch (e) {
      setNote(String(e));
    } finally {
      setBusy(false);
    }
  };

  const repair = async () => {
    setBusy(true);
    setNote(null);
    try {
      await api.repairProfile(profile.id);
      setNote('Every game file gets checked again on the next launch.');
    } catch (e) {
      setNote(String(e));
    } finally {
      setBusy(false);
    }
  };

  const shortcut = async () => {
    setBusy(true);
    setNote(null);
    try {
      const at = await api.createShortcut(profile.id);
      setNote(`Shortcut made: ${at.split(/[\\/]/).pop()} on the desktop starts ${profile.name} straight away.`);
    } catch (e) {
      setNote(String(e));
    } finally {
      setBusy(false);
    }
  };

  const exportPack = async () => {
    setBusy(true);
    setNote(null);
    try {
      const r = await api.exportInstance(profile.id);
      setNote(r ? `Exported ${r} to the .mrpack.` : null);
    } catch (e) {
      setNote(String(e));
    } finally {
      setBusy(false);
    }
  };

  const releases = versions.filter((v) => v.type === 'release').slice(0, 60);

  return (
    <div className="win__body settings__body scroll">
      <Row label="NAME">
        <PxBox family="panel" height="md" className="px--wide">
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
        </PxBox>
      </Row>
      <Row label="MINECRAFT VERSION" hint="Changing it re-installs the game files on the next launch.">
        <PxBox family="panel" height="md">
          <input
            className="input"
            list="editor-versions"
            value={version}
            onChange={(e) => setVersion(e.target.value)}
          />
          <datalist id="editor-versions">
            {releases.map((v) => (
              <option key={v.id} value={v.id} />
            ))}
          </datalist>
        </PxBox>
      </Row>
      <Row
        label="LOADER"
        hint={
          loader === 'fabric'
            ? `Fabric ${profile.loader === 'fabric' && profile.loaderVersion ? profile.loaderVersion : '— pinned automatically'}`
            : loader === 'neoforge'
              ? `NeoForge ${profile.loader === 'neoforge' && profile.loaderVersion ? profile.loaderVersion : '— newest build for the version'}. DuskClient is Fabric-only.`
              : 'Plain vanilla, no modloader. Mods in the folder are ignored.'
        }
      >
        <Choice
          value={loader}
          options={[
            { value: 'vanilla', label: 'VANILLA' },
            { value: 'fabric', label: 'FABRIC' },
            { value: 'neoforge', label: 'NEOFORGE' },
          ]}
          onPick={setLoader}
        />
      </Row>
      <Row label="RESOLUTION" hint="The game window when it opens.">
        <PxBox family="panel" height="md">
          <input
            className="input editor__num"
            type="number"
            min={320}
            value={width}
            onChange={(e) => setWidth(e.target.value)}
          />
        </PxBox>
        <span className="editor__x meta">×</span>
        <PxBox family="panel" height="md">
          <input
            className="input editor__num"
            type="number"
            min={240}
            value={height}
            onChange={(e) => setHeight(e.target.value)}
          />
        </PxBox>
      </Row>
      <Row
        label="MEMORY"
        hint={
          mb > 0 && mb < 512
            ? 'At least 512 MB.'
            : 'Heap for this instance, in MB. Empty = the launcher-wide setting.'
        }
      >
        <PxBox family="panel" height="md">
          <input
            className="input editor__num"
            type="number"
            min={512}
            step={256}
            placeholder="auto"
            value={memory}
            onChange={(e) => setMemory(e.target.value)}
          />
        </PxBox>
        <span className="editor__x meta">MB</span>
      </Row>
      <Row label="JAVA" hint="Path to a java executable. Empty = the launcher picks one for the version.">
        <PxBox family="panel" height="md" className="px--wide">
          <input
            className="input"
            placeholder="auto"
            value={javaPath}
            onChange={(e) => setJavaPath(e.target.value)}
          />
        </PxBox>
        <PxButton
          family="grey"
          height="md"
          title="Pick from the Java installs on this computer"
          onClick={() => {
            setJavas('open');
            void api
              .listJavas()
              .then(setJavas)
              .catch(() => setJavas([]));
          }}
        >
          <TT size={16}>PICK</TT>
        </PxButton>
      </Row>
      <Row
        label="TUNING"
        hint={
          tuningOf(jvm) === 'latency'
            ? 'Generational ZGC: steadier frame times, a little more memory. Needs Java 21+ to be generational.'
            : tuningOf(jvm) === 'balanced'
              ? "Mojang's G1 settings, the default."
              : 'Your own JVM arguments below.'
        }
      >
        <Choice
          value={tuningOf(jvm)}
          options={[
            { value: 'balanced', label: 'BALANCED' },
            { value: 'latency', label: 'LOW LATENCY' },
            { value: 'custom', label: 'CUSTOM' },
          ]}
          onPick={(t) => t !== 'custom' && setJvm(TUNING[t])}
        />
      </Row>
      <Row label="JVM ARGUMENTS" hint="Space-separated extras; the heap comes from MEMORY.">
        <PxBox family="panel" height="md" className="px--wide">
          <input
            className="input"
            placeholder="-XX:MaxGCPauseMillis=50"
            value={jvm}
            onChange={(e) => setJvm(e.target.value)}
          />
        </PxBox>
      </Row>
      <Row label="JOIN SERVER" hint="Connect straight to this address when the game starts.">
        <PxBox family="panel" height="md" className="px--wide">
          <input
            className="input"
            placeholder="play.example.net"
            value={server}
            onChange={(e) => setServer(e.target.value)}
          />
        </PxBox>
      </Row>

      <div className="srow editor__foot">
        <div className="srow__text">
          <span className="meta">
            {note ?? `Created ${ago(profile.createdAt)} · last played ${ago(profile.lastPlayed)}`}
          </span>
        </div>
        <div className="srow__control">
          <PxButton
            family="grey"
            height="md"
            disabled={busy || !isTauri}
            title={isTauri ? 'Save this instance as a .mrpack' : 'Needs the desktop app'}
            onClick={() => void exportPack()}
          >
            <TT size={16} tone={isTauri ? 'plain' : 'dim'}>
              EXPORT
            </TT>
          </PxButton>
          <PxButton
            family="grey"
            height="md"
            disabled={busy || !isTauri}
            title={isTauri ? 'A desktop shortcut that starts this instance straight away' : 'Needs the desktop app'}
            onClick={() => void shortcut()}
          >
            <TT size={16} tone={isTauri ? 'plain' : 'dim'}>
              SHORTCUT
            </TT>
          </PxButton>
          <PxButton
            family="grey"
            height="md"
            disabled={busy}
            title="Check every game file on the next launch and replace damaged ones"
            onClick={() => void repair()}
          >
            <TT size={16}>REPAIR</TT>
          </PxButton>
          <PxButton
            family="grey"
            height="md"
            disabled={busy}
            title="A new instance with these settings, mods, config and worlds"
            onClick={() => void duplicate()}
          >
            <TT size={16}>DUPLICATE</TT>
          </PxButton>
          <PxButton family="red" height="md" onClick={onDelete}>
            <TT size={16} tone="red">
              DELETE INSTANCE
            </TT>
          </PxButton>
          <PxButton family="green" height="md" disabled={!dirty || !valid || busy} onClick={() => void save()}>
            <TT size={16} tone="green">
              {busy ? 'SAVING…' : 'SAVE'}
            </TT>
          </PxButton>
        </div>
      </div>
      {javas && (
        <JavaPicker
          javas={javas === 'open' ? null : javas}
          need={javaFor(version.trim())}
          gameVersion={version.trim()}
          current={javaPath.trim()}
          onPick={(path) => {
            setJavaPath(path);
            setJavas(null);
          }}
          onClose={() => setJavas(null)}
        />
      )}
    </div>
  );
}

/** The JAVA row's picker: AUTOMATIC, then every install found on this
 * computer, newest first. */
function JavaPicker({
  javas,
  need,
  gameVersion,
  current,
  onPick,
  onClose,
}: {
  /** null while the scan runs */
  javas: JavaInstall[] | null;
  /** the major this instance's version asks for, if known */
  need: number | null;
  gameVersion: string;
  current: string;
  onPick: (path: string) => void;
  onClose: () => void;
}) {
  return (
    <div className="modal-scrim" onClick={onClose}>
      <PxBox family="red" className="px--window modal javas" onClick={(e) => e.stopPropagation()}>
        <TT size={22}>PICK JAVA</TT>
        <span className="meta">
          {need ? `Minecraft ${gameVersion} runs on Java ${need}.` : 'Pick the Java this instance runs on.'}
        </span>
        <div className="javas__list scroll">
          <div className="editor__row">
            <span className="editor__file">
              <TT size={16}>AUTOMATIC</TT>
              <span className="meta">The launcher downloads and uses the Java the version asks for.</span>
            </span>
            <span className="editor__actions">
              <PxButton family={current === '' ? 'accent' : 'grey'} height="sm" onClick={() => onPick('')}>
                <TT size={16} tone={current === '' ? 'accent' : 'plain'}>
                  {current === '' ? 'IN USE' : 'USE'}
                </TT>
              </PxButton>
            </span>
          </div>
          {javas === null && <span className="meta">Looking for Java…</span>}
          {javas?.length === 0 && <span className="meta">No other Java found on this computer.</span>}
          {javas?.map((j) => {
            const using = current === j.path;
            const old = need !== null && j.major < need;
            return (
              <div key={j.path} className="editor__row">
                <span className="editor__file">
                  <TT size={16}>{`JAVA ${j.major}`}</TT>
                  <span className="meta">
                    {[j.version, j.bundled ? "the launcher's own" : j.vendor].filter(Boolean).join(' · ')}
                    {old && <span className="javas__old"> · too old for {gameVersion}</span>}
                  </span>
                  <span className="meta editor__filename" title={j.path}>
                    {j.path}
                  </span>
                </span>
                <span className="editor__actions">
                  <PxButton family={using ? 'accent' : 'grey'} height="sm" onClick={() => onPick(j.path)}>
                    <TT size={16} tone={using ? 'accent' : 'plain'}>
                      {using ? 'IN USE' : 'USE'}
                    </TT>
                  </PxButton>
                </span>
              </div>
            );
          })}
        </div>
        <div className="modal__row modal__row--tall">
          <span className="modal__spacer" />
          <PxButton family="grey" height="md" onClick={onClose}>
            <TT size={20}>CLOSE</TT>
          </PxButton>
        </div>
      </PxBox>
    </div>
  );
}

/* ── MODS / RESOURCE PACKS / SHADERS: one list, three folders ────────────
   The nested strip (ALL / MODS / RESOURCE PACKS / SHADERS) picks what the
   list below shows: ALL merges the three folders with a kind tag per row. */
function ContentTab({
  profile,
  kindTab,
  onKindTab,
  content,
  onBrowse,
  onOpenProject,
}: {
  profile: Profile;
  kindTab: KindTab;
  onKindTab: (k: KindTab) => void;
  content: ContentInfo | null;
  onBrowse: (c: ContentInfo) => void;
  /** open an installed file's Modrinth page — its VERSIONS tab installs any version */
  onOpenProject: (c: ContentInfo, projectId: string) => void;
}) {
  const [rows, setRows] = useState<{ file: ProfileMod; kind: ContentKind; label: string }[] | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  /* newer Modrinth versions, keyed `kind/filename`; null while checking */
  const [updates, setUpdates] = useState<Map<string, ContentUpdate> | null>(null);
  /* which Modrinth project each file is, keyed `kind/filename`; files Modrinth
     doesn't know (hand-made packs) have no entry and no VERSIONS button */
  const [projects, setProjects] = useState<Map<string, string>>(() => new Map());
  /* the rows an UPDATE is running on — their buttons wait */
  const [updating, setUpdating] = useState<Set<string>>(() => new Set());
  /* files are being dragged over the window */
  const [dropping, setDropping] = useState(false);
  /* what would stop the mods loading: missing or wrong-version libraries,
     a mod twice, mods that break each other (Fabric only) */
  const [problems, setProblems] = useState<ModProblems | null>(null);
  /* the problem row whose fix is running */
  const [fixing, setFixing] = useState<string | null>(null);

  const kind = content?.kind ?? null;
  const noun = content?.noun ?? 'files';
  const targets = content ? [content] : KIND_INFO;

  /* hashes the folder against Modrinth; offline just means no UPDATE buttons */
  const checkUpdates = async () => {
    setUpdates(null);
    const found = await Promise.all(
      targets.map((t) =>
        api
          .checkContentUpdates(profile.id, t.kind)
          .then((list) => list.map((u) => [`${t.kind}/${u.filename}`, u] as const))
          .catch(() => []),
      ),
    );
    setUpdates(new Map(found.flat()));
  };

  const lookupProjects = async () => {
    const found = await Promise.all(
      targets.map((t) =>
        api
          .lookupContent(profile.id, t.kind)
          .then((list) => list.map((p) => [`${t.kind}/${p.filename}`, p.projectId] as const))
          .catch(() => []),
      ),
    );
    setProjects(new Map(found.flat()));
  };

  const reload = () => {
    if (kindTab === 'ALL' || kindTab === 'MODS') {
      void api
        .modProblems(profile.id)
        .then(setProblems)
        .catch(() => setProblems(null));
    } else {
      setProblems(null);
    }
    return Promise.all(
      targets.map(async (t) => {
        const files = await api.listContent(profile.id, t.kind);
        return files.map((file) => ({ file, kind: t.kind, label: t.label }));
      }),
    )
      .then((groups) =>
        setRows(groups.flat().sort((a, b) => a.file.filename.localeCompare(b.file.filename))),
      )
      .catch((e) => setNote(String(e)));
  };

  useEffect(() => {
    setRows(null);
    void reload();
    void checkUpdates();
    void lookupProjects();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile.id, kindTab]);

  /* drop .jar / .zip files anywhere on the window: the backend sorts each
     into mods, resource packs or shaders by what's inside */
  useEffect(() => {
    if (!isTauri) return;
    let off: (() => void) | undefined;
    let gone = false;
    void getCurrentWebview()
      .onDragDropEvent(({ payload: p }) => {
        if (p.type === 'enter' || p.type === 'over') setDropping(true);
        else if (p.type === 'leave') setDropping(false);
        else {
          setDropping(false);
          void act(async () => {
            const r = await api.importContentPaths(profile.id, p.paths);
            const added = r.added.length === 1 ? `Added ${r.added[0].filename}` : `Added ${r.added.length} files`;
            const worlds = r.worlds.length ? `added ${r.worlds.join(', ')} to WORLDS` : '';
            const skipped = r.skipped.length
              ? `skipped ${r.skipped.join(', ')} — only .jar mods, .zip packs and worlds go here`
              : '';
            setNote([r.added.length ? added : '', worlds, skipped].filter(Boolean).join(' · ') || null);
          });
        }
      })
      .then((u) => (gone ? u() : (off = u)));
    return () => {
      gone = true;
      off?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile.id, kindTab]);

  /* one at a time: two downloads racing into one folder buys nothing, and a
     failure stops the batch where it is */
  const update = async (list: [string, ContentKind, ContentUpdate][]) => {
    setNote(null);
    setUpdating((cur) => new Set([...cur, ...list.map(([key]) => key)]));
    let done = 0;
    try {
      for (const [key, k, u] of list) {
        await api.updateContent(profile.id, k, u.filename, u.versionId);
        done++;
        setUpdates((cur) => {
          const next = new Map(cur);
          next.delete(key);
          return next;
        });
      }
    } catch (e) {
      setNote(`${done ? `Updated ${done}, then: ` : ''}${String(e)}`);
    } finally {
      setUpdating(new Set());
      await reload();
    }
  };

  const pending = rows
    ? rows.flatMap(({ file, kind: k }) => {
        const key = `${k}/${file.filename}`;
        const u = updates?.get(key);
        return u ? [[key, k, u] as [string, ContentKind, ContentUpdate]] : [];
      })
    : [];
  const needle = filter.trim().toLowerCase();
  const shown = needle
    ? rows?.filter(({ file }) => `${file.name ?? ''} ${file.filename}`.toLowerCase().includes(needle))
    : rows;

  const act = async (fn: () => Promise<unknown>) => {
    setNote(null);
    try {
      await fn();
      await reload();
    } catch (e) {
      setNote(String(e));
    }
  };

  const vanilla = kindTab === 'MODS' && profile.loader === 'vanilla';

  const fix = (key: string, fn: () => Promise<unknown>) => {
    setFixing(key);
    void act(fn).finally(() => setFixing(null));
  };
  const turnOff = (files: string[]) => async () => {
    for (const f of files) await api.setContentEnabled(profile.id, 'mod', f, false);
  };
  const and = (names: string[]) =>
    names.length > 4
      ? `${names.slice(0, 3).join(', ')} and ${names.length - 3} more`
      : names.length > 1
        ? `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
        : names[0];

  /* each problem as a row: the warning, the rest of the sentence, and the fix when there's one */
  type ProblemRow = { key: string; warn: string; text: string; fix?: { label: string; title: string; run: () => Promise<unknown> } };
  const problemRows: ProblemRow[] = problems
    ? [
        ...problems.missing.map((d) => ({
          key: `missing/${d.project}`,
          warn: `${d.label} is ${d.disabledFile ? 'turned off' : 'missing'}`,
          text: ` — ${and(d.neededBy)} ${d.neededBy.length > 1 ? 'need' : 'needs'} it, and the game won’t start without it.`,
          fix: d.disabledFile
            ? { label: 'TURN ON', title: `Turn ${d.disabledFile} back on`, run: () => api.setContentEnabled(profile.id, 'mod', d.disabledFile!, true) }
            : { label: 'INSTALL', title: `Install ${d.label} from Modrinth`, run: () => api.installContent(profile.id, 'mod', d.project) },
        })),
        ...problems.mismatched.map((m): ProblemRow => {
          const files = m.neededBy.map((n) => n.file);
          if (m.file === null) {
            return {
              key: `mc/${m.version}`,
              warn: `${and(m.neededBy.map((n) => n.name))} ${m.neededBy.length > 1 ? 'are' : 'is'} for another Minecraft`,
              text: ` — this instance runs ${m.version} (${m.neededBy.map((n) => `${n.name}: ${n.wants}`).join(', ')}). Turn ${m.neededBy.length > 1 ? 'them' : 'it'} off or get a ${m.version} build.`,
              fix: { label: 'TURN OFF', title: `Turn off ${files.join(', ')}`, run: turnOff(files) },
            };
          }
          const key = `mod/${m.file}`;
          const u = updates?.get(key);
          return {
            key: `wrong/${m.file}`,
            warn: `${m.name} ${m.version} is the wrong version`,
            text: ` — ${m.neededBy.map((n) => `${n.name} needs ${n.wants}`).join(', ')}.${u ? '' : ' Get one that fits from its VERSIONS.'}`,
            fix: u ? { label: 'UPDATE', title: `Update ${m.file} to ${u.versionNumber}`, run: () => update([[key, 'mod', u]]) } : undefined,
          };
        }),
        ...problems.duplicates.map((d) => ({
          key: `twice/${d.keep}`,
          warn: `${d.name} is in mods/ ${d.extra.length > 1 ? `${d.extra.length + 1} times` : 'twice'}`,
          text: ` — the game won’t start with more than one. Keeps ${d.keepVersion || d.keep} and turns off ${d.extra.join(', ')}.`,
          fix: { label: 'TURN OFF OLD', title: `Turn off ${d.extra.join(', ')}`, run: turnOff(d.extra) },
        })),
        ...problems.clashes.map((c) => ({
          key: `breaks/${c.file}/${c.otherFile}`,
          warn: `${c.name} doesn’t work with ${c.other} ${c.otherVersion}`,
          text: ` — ${c.name} says so itself, and the game won’t start with both on. Update ${c.other} or turn one off.`,
          fix: { label: 'TURN OFF', title: `Turn off ${c.otherFile} (${c.other})`, run: turnOff([c.otherFile]) },
        })),
      ]
    : [];

  return (
    <div className="win__body editor__body">
      {/* 172:626 — the kind strip, with the actions pinned to its right */}
      <div className="win__bar editor__kinds">
        {KINDS.map((k) => (
          <NavCell key={k} label={k} active={kindTab === k} onClick={() => onKindTab(k)} />
        ))}
        <div className="win__fill" />
        {content && isTauri && (
          <NavCell label="ADD FILES" onClick={() => void act(() => api.importLocalContent(profile.id, content.kind))} />
        )}
        {content && <NavCell label="GET FROM MODRINTH" onClick={() => onBrowse(content)} />}
      </div>

      {vanilla && (
        <span className="meta">
          This instance runs vanilla — switch the loader to Fabric or NeoForge in SETTINGS for mods to load.
        </span>
      )}
      {note && <span className="meta">{note}</span>}
      {problemRows.length > 0 && (
        <div className="editor__deps">
          {problemRows.map((p) => (
            <div key={p.key} className="editor__dep">
              <span className="meta editor__dep-text">
                <span className="editor__dep-warn">{p.warn}</span>
                {p.text}
              </span>
              {p.fix && (
                <PxButton family="accent" height="sm" disabled={fixing !== null} title={p.fix.title} onClick={() => fix(p.key, p.fix!.run)}>
                  <TT size={16} tone="accent">
                    {fixing === p.key ? 'WORKING…' : p.fix.label}
                  </TT>
                </PxButton>
              )}
            </div>
          ))}
        </div>
      )}

      {rows && rows.length > 0 && (
        <div className="browse__toolbar">
          <PxBox family="panel" height="sm" className="search editor__search">
            <PixelGlyph glyph="search" size={20} color="var(--text-3)" />
            <input
              className="input editor__search-input"
              placeholder={`Filter ${noun}…`}
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
          </PxBox>
          {pending.length > 0 && (
            <PxButton
              family="accent"
              height="sm"
              disabled={updating.size > 0}
              onClick={() => void update(pending)}
            >
              <TT size={16} tone="accent">
                {pending.length === 1 ? 'UPDATE 1' : `UPDATE ALL ${pending.length}`}
              </TT>
            </PxButton>
          )}
          <span className="meta browse__count">
            {`${needle ? `${shown?.length ?? 0} OF ` : ''}${rows.length} ${noun.toUpperCase()}`}
            {updates === null
              ? ' · CHECKING FOR UPDATES…'
              : pending.length
                ? ` · ${pending.length} ${pending.length === 1 ? 'UPDATE' : 'UPDATES'}`
                : ' · UP TO DATE'}
          </span>
        </div>
      )}

      <div className="editor__list scroll">
        {rows && rows.length === 0 && (
          <PxBox family="panel" className="empty">
            <TT size={20} tone="dim">
              {`NO ${noun.toUpperCase()} YET`}
            </TT>
            <span className="meta">
              {kind === 'mod'
                ? 'Add .jar files from disk or install one from Modrinth.'
                : kind
                  ? 'Add .zip files from disk or install one from Modrinth.'
                  : 'Pick a kind above to add files from disk or install from Modrinth.'}
            </span>
          </PxBox>
        )}
        {needle && shown?.length === 0 && (
          <span className="meta">{`Nothing here matches “${filter.trim()}”.`}</span>
        )}
        {dropping && (
          <div className="editor__drop">
            <TT size={20} tone="accent">
              {`DROP TO ADD TO ${profile.name.toUpperCase()}`}
            </TT>
            <span className="meta">.jar mods · .zip resource packs and shader packs</span>
          </div>
        )}
        {shown?.map(({ file: m, kind: fileKind, label }) => {
          const key = `${fileKind}/${m.filename}`;
          const upd = updates?.get(key);
          const projectId = projects.get(key);
          return (
            <div key={key} className={['editor__row', m.enabled ? '' : 'is-off'].join(' ')}>
              {/* the on/off box leads the row — the one control every row has */}
              <button
                className="check"
                title={m.enabled ? 'Enabled — click to disable' : 'Disabled — click to enable'}
                onClick={() => void act(() => api.setContentEnabled(profile.id, fileKind, m.filename, !m.enabled))}
              >
                <span className={['px px--grey check__box', m.enabled ? 'is-on' : ''].join(' ')}>
                  <span className="check__tick" />
                </span>
              </button>
              {/* 172:640 — the icon square: the jar's own icon, else a glyph */}
              <span className="editor__row-icon">
                {m.icon ? (
                  <img src={m.icon} alt="" draggable={false} />
                ) : (
                  <PixelGlyph glyph="box" size={40} color="var(--text-3)" />
                )}
              </span>
              <span className="editor__file">
                <TT size={16} tone={m.enabled ? 'plain' : 'dim'}>
                  {m.name || displayName(m.filename)}
                </TT>
                <span className="meta editor__filename">
                  {m.filename} · {fmtBytes(m.size)}
                  {upd && ` · ${upd.currentVersion} → ${upd.versionNumber}`}
                </span>
              </span>
              {/* type / state column — the Figma note asks for type + source +
                  version; the backend only knows the type today */}
              <span className="editor__kind">
                <TT size={14} tone="sub">
                  {label}
                </TT>
                <span className="meta">{m.enabled ? 'enabled' : 'disabled'}</span>
              </span>
              {/* fixed width, so the type column lines up with or without UPDATE */}
              <span className="editor__actions">
                {projectId && (
                  <PxButton
                    family="grey"
                    height="sm"
                    title="Open on Modrinth — pick a specific version to install"
                    disabled={updating.has(key)}
                    onClick={() => onOpenProject(CONTENT_BY_KIND[fileKind], projectId)}
                  >
                    <TT size={16}>VERSIONS</TT>
                  </PxButton>
                )}
                {upd && (
                  <PxButton
                    family="accent"
                    height="sm"
                    title={`${upd.currentVersion} → ${upd.versionNumber}`}
                    disabled={updating.size > 0}
                    onClick={() => void update([[key, fileKind, upd]])}
                  >
                    <TT size={16} tone="accent">
                      {updating.has(key) ? 'UPDATING…' : 'UPDATE'}
                    </TT>
                  </PxButton>
                )}
                <PxButton
                  family="red"
                  height="sm"
                  disabled={updating.has(key)}
                  onClick={() => void act(() => api.removeContent(profile.id, fileKind, m.filename))}
                >
                  <TT size={16} tone="red">
                    REMOVE
                  </TT>
                </PxButton>
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ── WORLDS: what's in saves/ ───────────────────────────────────────────── */
/** a name without the game's § formatting codes (map makers colour theirs) */
const plain = (name: string) => name.replace(/§./g, '').trim() || name;

function WorldsTab({
  profile,
  busy,
  onLaunch,
}: {
  profile: Profile;
  /** a game is starting or running: JOIN / PLAY wait for it */
  busy: boolean;
  onLaunch: (to: LaunchTarget) => void;
}) {
  const [worlds, setWorlds] = useState<World[] | null>(null);
  const [servers, setServers] = useState<SavedServer[] | null>(null);
  /* address → its ping; 'down' when it didn't answer, absent while pinging */
  const [status, setStatus] = useState<Record<string, ServerStatus | 'down'>>({});
  const [note, setNote] = useState<string | null>(null);
  /* world being zipped right now */
  const [backing, setBacking] = useState<string | null>(null);
  const [trashing, setTrashing] = useState<World | null>(null);
  /* the ADD SERVER form, open when set */
  const [adding, setAdding] = useState<{ name: string; address: string } | null>(null);
  const [dropping, setDropping] = useState<SavedServer | null>(null);
  /* files dragged over the window */
  const [dragOver, setDragOver] = useState(false);
  const [importing, setImporting] = useState(false);
  /* the world whose DATAPACKS window is open; drops go to it meanwhile */
  const [packsOf, setPacksOf] = useState<World | null>(null);
  const packsOpen = useRef(false);
  packsOpen.current = packsOf !== null;

  const imported = async (run: () => Promise<ImportedWorlds>) => {
    setNote(null);
    setImporting(true);
    try {
      const r = await run();
      const added = r.added.length ? `Added ${r.added.join(', ')}` : '';
      const skipped = r.skipped.length ? `no world in ${r.skipped.join(', ')}` : '';
      setNote([added, skipped].filter(Boolean).join(' · ') || null);
      if (r.added.length) setWorlds(await api.listWorlds(profile.id));
    } catch (e) {
      setNote(String(e));
    } finally {
      setImporting(false);
    }
  };

  /* drop world zips or folders anywhere on the window */
  useEffect(() => {
    if (!isTauri) return;
    let off: (() => void) | undefined;
    let gone = false;
    void getCurrentWebview()
      .onDragDropEvent(({ payload: p }) => {
        if (packsOpen.current) return;
        if (p.type === 'enter' || p.type === 'over') setDragOver(true);
        else if (p.type === 'leave') setDragOver(false);
        else {
          setDragOver(false);
          void imported(() => api.importWorldPaths(profile.id, p.paths));
        }
      })
      .then((u) => (gone ? u() : (off = u)));
    return () => {
      gone = true;
      off?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile.id]);

  const backup = async (w: World) => {
    setBacking(w.name);
    setNote(null);
    try {
      const zip = await api.backupWorld(profile.id, w.name);
      setNote(`Backed up ${w.name} to backups/${zip}`);
    } catch (e) {
      setNote(String(e));
    } finally {
      setBacking(null);
    }
  };

  const remove = async (w: World) => {
    setTrashing(null);
    try {
      await api.deleteWorld(profile.id, w.name);
      setWorlds((list) => (list ?? []).filter((x) => x.name !== w.name));
      setNote(`${w.name} moved to the trash.`);
    } catch (e) {
      setNote(String(e));
    }
  };

  const addServer = async (name: string, address: string) => {
    setNote(null);
    try {
      const list = await api.addServer(profile.id, name, address);
      setAdding(null);
      setServers(list);
      const added = list[list.length - 1];
      if (added) {
        void api
          .pingServer(added.address)
          .then((st) => setStatus((m) => ({ ...m, [added.address]: st })))
          .catch(() => setStatus((m) => ({ ...m, [added.address]: 'down' })));
      }
    } catch (e) {
      setAdding(null);
      setNote(String(e));
    }
  };

  const dropServer = async (sv: SavedServer) => {
    setDropping(null);
    try {
      setServers(await api.removeServer(profile.id, sv.name, sv.address));
      setNote(`${sv.name} removed from the server list.`);
    } catch (e) {
      setNote(String(e));
    }
  };

  const ping = (list: SavedServer[]) => {
    setStatus({});
    for (const sv of list) {
      void api
        .pingServer(sv.address)
        .then((st) => setStatus((m) => ({ ...m, [sv.address]: st })))
        .catch(() => setStatus((m) => ({ ...m, [sv.address]: 'down' })));
    }
  };

  useEffect(() => {
    void api
      .listWorlds(profile.id)
      .then(setWorlds)
      .catch((e) => setNote(String(e)));
    void api
      .listServers(profile.id)
      .then((list) => {
        setServers(list);
        ping(list);
      })
      .catch((e) => setNote(String(e)));
  }, [profile.id]);

  return (
    <div className="win__body editor__body">
      <div className="browse__toolbar">
        <PxButton
          family="blue"
          height="sm"
          onClick={() => void api.openProfileFolder(profile.id, 'saves')}
        >
          <TT size={16} tone="blue">
            OPEN SAVES FOLDER
          </TT>
        </PxButton>
        <PxButton
          family="grey"
          height="sm"
          disabled={busy}
          title={busy ? 'Close the game first' : 'Add a server to this instance’s list'}
          onClick={() => setAdding({ name: '', address: '' })}
        >
          <TT size={16}>ADD SERVER</TT>
        </PxButton>
        {isTauri && (
          <PxButton
            family="grey"
            height="sm"
            disabled={importing}
            title="Add a world from a .zip — a backup or a downloaded map. Folders can be dropped here too."
            onClick={() => void imported(() => api.importWorld(profile.id))}
          >
            <TT size={16}>{importing ? 'IMPORTING…' : 'IMPORT WORLD'}</TT>
          </PxButton>
        )}
        {servers && servers.length > 0 && (
          <PxButton family="grey" height="sm" onClick={() => ping(servers)}>
            <TT size={16}>REFRESH</TT>
          </PxButton>
        )}
        {worlds && worlds.length > 0 && (
          <PxButton family="grey" height="sm" onClick={() => void api.openProfileFolder(profile.id, 'backups')}>
            <TT size={16}>BACKUPS</TT>
          </PxButton>
        )}
        <span className="meta browse__count">
          {worlds && servers ? `${servers.length} SERVERS · ${worlds.length} WORLDS` : '…'}
        </span>
      </div>
      {note && <span className="meta">{note}</span>}
      <div className="editor__list scroll">
        {dragOver && (
          <div className="editor__drop">
            <TT size={20} tone="accent">
              {`DROP TO ADD TO ${profile.name.toUpperCase()}`}
            </TT>
            <span className="meta">world .zip files and world folders</span>
          </div>
        )}
        {servers && servers.length > 0 && (
          <TT size={14} tone="sub" className="worlds__head">
            SERVERS
          </TT>
        )}
        {servers?.map((sv) => {
          const st = status[sv.address];
          const up = st && st !== 'down' ? st : null;
          const icon = up?.icon ?? sv.icon;
          return (
            <div key={`${sv.name}|${sv.address}`} className="editor__row">
              <span className="editor__row-icon">
                {icon ? (
                  <img src={icon} alt="" draggable={false} />
                ) : (
                  <PixelGlyph glyph="box" size={40} color="var(--text-3)" />
                )}
              </span>
              <span className="editor__file">
                <TT size={16}>{sv.name}</TT>
                {up && up.motd.length > 0 ? (
                  <span className="meta worlds__motd">
                    {up.motd.map((m, i) => (
                      <span key={i} style={m.color ? { color: m.color } : undefined}>
                        {m.text}
                      </span>
                    ))}
                  </span>
                ) : (
                  <span className="meta editor__filename">{sv.address}</span>
                )}
              </span>
              <span className="editor__kind">
                {st === undefined ? (
                  <span className="meta">pinging…</span>
                ) : up ? (
                  <>
                    <TT size={14} tone="sub">{`${up.online}/${up.max} ONLINE`}</TT>
                    <span className="meta" title={up.version}>
                      <span style={{ color: pingColor(up.pingMs) }}>{up.pingMs} ms</span>
                    </span>
                  </>
                ) : (
                  <>
                    <TT size={14} tone="red">
                      OFFLINE
                    </TT>
                    <span className="meta">can't reach it</span>
                  </>
                )}
              </span>
              <span className="editor__actions worlds__actions">
                <PxButton
                  family="red"
                  height="sm"
                  disabled={busy}
                  title={busy ? 'Close the game first' : 'Take it off the list'}
                  onClick={() => setDropping(sv)}
                >
                  <TT size={16} tone="red">
                    REMOVE
                  </TT>
                </PxButton>
                <PxButton
                  family="accent"
                  height="sm"
                  disabled={busy}
                  title={busy ? 'A game is already running' : `Join ${sv.address}`}
                  onClick={() => onLaunch({ server: sv.address })}
                >
                  <TT size={16} tone="accent">
                    JOIN
                  </TT>
                </PxButton>
              </span>
            </div>
          );
        })}
        {worlds && worlds.length > 0 && (
          <TT size={14} tone="sub" className="worlds__head">
            WORLDS
          </TT>
        )}
        {worlds && worlds.length === 0 && servers?.length === 0 && (
          <PxBox family="panel" className="empty">
            <TT size={20} tone="dim">
              NO WORLDS YET
            </TT>
            <span className="meta">Worlds and servers you add in this instance show up here.</span>
          </PxBox>
        )}
        {worlds?.map((w) => (
          <div key={w.name} className="editor__row">
            <span className="editor__row-icon">
              {w.icon ? (
                <img src={w.icon} alt="" draggable={false} />
              ) : (
                <PixelGlyph glyph="box" size={40} color="var(--text-3)" />
              )}
            </span>
            <span
              className="editor__file"
              title={[
                w.levelName && plain(w.levelName) !== w.name ? `Folder: saves/${w.name}` : '',
                w.seed ? `Seed: ${w.seed}` : '',
              ]
                .filter(Boolean)
                .join('\n')}
            >
              <TT size={16}>{plain(w.levelName ?? w.name)}</TT>
              <span className="meta">
                {[
                  w.hardcore ? 'Hardcore' : w.gameMode && w.gameMode[0].toUpperCase() + w.gameMode.slice(1),
                  w.cheats ? 'cheats' : '',
                  w.version,
                  fmtBytes(w.size),
                  `played ${ago(w.modified)}`,
                ]
                  .filter(Boolean)
                  .join(' · ')}
                {w.version && releaseNewer(w.version, profile.gameVersion) && (
                  <span className="worlds__newer"> · newer than this instance — back it up before playing</span>
                )}
              </span>
            </span>
            <span className="editor__actions worlds__actions">
              <PxButton
                family="grey"
                height="sm"
                disabled={busy || backing !== null}
                title={busy ? 'Close the game first' : 'Zip it into the backups folder'}
                onClick={() => void backup(w)}
              >
                <TT size={16}>{backing === w.name ? 'BACKING UP…' : 'BACKUP'}</TT>
              </PxButton>
              <PxButton
                family="grey"
                height="sm"
                title="The world's datapacks: add, turn off or remove them"
                onClick={() => setPacksOf(w)}
              >
                <TT size={16}>DATAPACKS</TT>
              </PxButton>
              <PxButton
                family="red"
                height="sm"
                disabled={busy || backing === w.name}
                title={busy ? 'Close the game first' : 'Move it to the trash'}
                onClick={() => setTrashing(w)}
              >
                <TT size={16} tone="red">
                  DELETE
                </TT>
              </PxButton>
              {profile.loader === 'fabric' && (
                <PxButton
                  family="grey"
                  height="sm"
                  disabled={busy}
                  title={
                    busy
                      ? 'A game is already running'
                      : `Open ${w.name} to friends: they join at an address shown in the friends pane, no port forwarding`
                  }
                  onClick={() => onLaunch({ world: w.name, host: true })}
                >
                  <TT size={16}>HOST</TT>
                </PxButton>
              )}
              <PxButton
                family="accent"
                height="sm"
                disabled={busy}
                title={busy ? 'A game is already running' : `Open ${w.name}`}
                onClick={() => onLaunch({ world: w.name })}
              >
                <TT size={16} tone="accent">
                  PLAY
                </TT>
              </PxButton>
            </span>
          </div>
        ))}
      </div>
      {adding && (
        <div className="modal-scrim" onClick={() => setAdding(null)}>
          <PxBox family="red" className="px--window modal" onClick={(e) => e.stopPropagation()}>
            <form
              className="worlds__form"
              onSubmit={(e) => {
                e.preventDefault();
                void addServer(adding.name, adding.address);
              }}
            >
              <TT size={22}>ADD SERVER</TT>
              <div className="modal__row">
                <span className="modal__label">
                  <TT size={16} tone="sub">
                    NAME
                  </TT>
                </span>
                <PxBox family="panel" height="md">
                  <input
                    className="input"
                    value={adding.name}
                    placeholder="Minecraft Server"
                    maxLength={64}
                    onChange={(e) => setAdding({ ...adding, name: e.target.value })}
                  />
                </PxBox>
              </div>
              <div className="modal__row">
                <span className="modal__label">
                  <TT size={16} tone="sub">
                    ADDRESS
                  </TT>
                </span>
                <PxBox family="panel" height="md">
                  <input
                    className="input"
                    value={adding.address}
                    placeholder="play.example.net"
                    autoFocus
                    spellCheck={false}
                    onChange={(e) => setAdding({ ...adding, address: e.target.value })}
                  />
                </PxBox>
              </div>
              <div className="modal__row modal__row--tall">
                <PxButton family="grey" height="md" type="button" onClick={() => setAdding(null)}>
                  <TT size={20}>CANCEL</TT>
                </PxButton>
                <PxButton family="accent" height="md" type="submit" disabled={adding.address.trim() === ''}>
                  <TT size={20} tone="accent">
                    ADD
                  </TT>
                </PxButton>
              </div>
            </form>
          </PxBox>
        </div>
      )}
      {dropping && (
        <div className="modal-scrim" onClick={() => setDropping(null)}>
          <PxBox family="red" className="px--window modal" onClick={(e) => e.stopPropagation()}>
            <TT size={22} tone="red">
              REMOVE SERVER?
            </TT>
            <span className="meta">
              “{dropping.name}” ({dropping.address}) comes off this instance’s server list. The previous list stays
              in servers.dat_old.
            </span>
            <div className="modal__row modal__row--tall">
              <PxButton family="grey" height="md" onClick={() => setDropping(null)}>
                <TT size={20}>CANCEL</TT>
              </PxButton>
              <PxButton family="red" height="md" onClick={() => void dropServer(dropping)}>
                <TT size={20} tone="red">
                  REMOVE
                </TT>
              </PxButton>
            </div>
          </PxBox>
        </div>
      )}
      {packsOf && <DatapacksWindow profile={profile} world={packsOf} busy={busy} onClose={() => setPacksOf(null)} />}
      {trashing && (
        <div className="modal-scrim" onClick={() => setTrashing(null)}>
          <PxBox family="red" className="px--window modal" onClick={(e) => e.stopPropagation()}>
            <TT size={22} tone="red">
              DELETE WORLD?
            </TT>
            <span className="meta">
              “{trashing.name}” ({fmtBytes(trashing.size)}) moves to the trash. Restore it from there if you change
              your mind.
            </span>
            <div className="modal__row modal__row--tall">
              <PxButton family="grey" height="md" onClick={() => setTrashing(null)}>
                <TT size={20}>CANCEL</TT>
              </PxButton>
              <PxButton family="red" height="md" onClick={() => void remove(trashing)}>
                <TT size={20} tone="red">
                  DELETE
                </TT>
              </PxButton>
            </div>
          </PxBox>
        </div>
      )}
    </div>
  );
}

/** One world's datapacks/: zips that can be turned off (renamed
 *  `.zip.disabled`, which the game skips) or trashed, pack folders that can
 *  be trashed, and ADD / drop to copy more in. Off and on take effect the
 *  next time the world opens; while the game runs only adding works. */
function DatapacksWindow({
  profile,
  world,
  busy,
  onClose,
}: {
  profile: Profile;
  world: World;
  busy: boolean;
  onClose: () => void;
}) {
  const [packs, setPacks] = useState<Datapack[] | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [working, setWorking] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);

  const load = () =>
    api
      .listDatapacks(profile.id, world.name)
      .then(setPacks)
      .catch((e) => setNote(String(e)));
  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile.id, world.name]);

  const run = async (key: string, task: () => Promise<unknown>) => {
    setWorking(key);
    setNote(null);
    try {
      await task();
      await load();
    } catch (e) {
      setNote(String(e));
    } finally {
      setWorking(null);
    }
  };

  const added = (r: ImportedWorlds) => {
    const a = r.added.length ? `Added ${r.added.join(', ')}` : '';
    const s = r.skipped.length ? `not a datapack: ${r.skipped.join(', ')}` : '';
    setNote([a, s].filter(Boolean).join(' · ') || null);
  };

  useEffect(() => {
    if (!isTauri) return;
    let off: (() => void) | undefined;
    let gone = false;
    void getCurrentWebview()
      .onDragDropEvent(({ payload: p }) => {
        if (p.type === 'enter' || p.type === 'over') setDragOver(true);
        else if (p.type === 'leave') setDragOver(false);
        else {
          setDragOver(false);
          void run('add', async () => added(await api.addDatapackPaths(profile.id, world.name, p.paths)));
        }
      })
      .then((u) => (gone ? u() : (off = u)));
    return () => {
      gone = true;
      off?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile.id, world.name]);

  const closed = busy ? 'Close the game first' : null;
  return (
    <div className="modal-scrim" onClick={onClose}>
      <PxBox family="red" className="px--window modal extimport" onClick={(e) => e.stopPropagation()}>
        <TT size={22}>{`DATAPACKS · ${plain(world.levelName ?? world.name).toUpperCase()}`}</TT>
        <span className="meta">
          {dragOver
            ? 'Drop to add these to the world.'
            : 'Changes take effect the next time the world opens. Drop pack zips or folders here to add them.'}
        </span>
        {note && <span className="meta">{note}</span>}
        <div className="extimport__list">
          {packs?.length === 0 && <span className="meta">No datapacks in this world yet.</span>}
          {packs === null && <span className="meta">Reading the world’s datapacks…</span>}
          {packs?.map((p) => (
            <div key={`${p.file}|${p.enabled}`} className="extimport__row">
              <span className="editor__row-icon packs__icon">
                {p.icon ? (
                  <img src={p.icon} alt="" draggable={false} />
                ) : (
                  <PixelGlyph glyph="box" size={28} color="var(--text-3)" />
                )}
              </span>
              <span className="extimport__info">
                <TT size={16} tone={p.enabled ? undefined : 'dim'}>
                  {p.file.replace(/\.zip$/i, '')}
                </TT>
                <span className="meta" title={p.description ?? undefined}>
                  {[p.enabled ? '' : 'off', p.folder ? 'folder' : '', fmtBytes(p.size), p.description]
                    .filter(Boolean)
                    .join(' · ')}
                </span>
              </span>
              {!p.folder && (
                <PxButton
                  family="grey"
                  height="sm"
                  disabled={busy || working !== null}
                  title={closed ?? (p.enabled ? 'Leave it out of the world' : 'Put it back in the world')}
                  onClick={() => void run(p.file, () => api.setDatapackEnabled(profile.id, world.name, p.file, !p.enabled))}
                >
                  <TT size={16}>{p.enabled ? 'TURN OFF' : 'TURN ON'}</TT>
                </PxButton>
              )}
              <PxButton
                family="red"
                height="sm"
                disabled={busy || working !== null}
                title={closed ?? 'Move it to the trash'}
                onClick={() => void run(p.file, () => api.removeDatapack(profile.id, world.name, p.file, p.enabled))}
              >
                <TT size={16} tone="red">
                  REMOVE
                </TT>
              </PxButton>
            </div>
          ))}
        </div>
        <div className="modal__row modal__row--tall">
          {isTauri && (
            <PxButton
              family="accent"
              height="md"
              disabled={working !== null}
              onClick={() => void run('add', async () => added(await api.addDatapacks(profile.id, world.name)))}
            >
              <TT size={20} tone="accent">
                {working === 'add' ? 'ADDING…' : 'ADD…'}
              </TT>
            </PxButton>
          )}
          <PxButton family="grey" height="md" onClick={onClose}>
            <TT size={20}>DONE</TT>
          </PxButton>
        </div>
      </PxBox>
    </div>
  );
}

/** the game's own ping bars: green under 150 ms, yellow under 300, red above */
function pingColor(ms: number) {
  return ms < 150 ? 'var(--g-up)' : ms < 300 ? 'var(--y-up)' : 'var(--r-up)';
}

/* ── LOG: the game's console, live while it runs ───────────────────────────
   Prism's "Minecraft Log" page. The backend streams stdout/stderr as
   `game-log` batches; the store in lib/gamelog keeps them for whichever
   instance last started, so the tab still reads after the game exits. */
function LogTab({
  profile,
  game,
  onStop,
}: {
  profile: Profile;
  game: GameState | null;
  onStop: () => void;
}) {
  const live = useGameLog(profile.id);
  /* latest.log, shown when this launcher didn't watch the last run */
  const [disk, setDisk] = useState<LatestLog | null>(null);
  const [filter, setFilter] = useState('');
  const [errorsOnly, setErrorsOnly] = useState(false);
  const [follow, setFollow] = useState(true);
  const [note, setNote] = useState<string | null>(null);
  const [sharing, setSharing] = useState<'ask' | 'busy' | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);

  const fromDisk = live.length === 0 && !game && disk !== null && disk.lines.length > 0;
  const all = fromDisk ? disk.lines : live;
  const needle = filter.trim().toLowerCase();
  const lines = useMemo(
    () =>
      needle || errorsOnly
        ? all.filter(
            (l) => (!errorsOnly || l.stream === 'err') && (!needle || l.line.toLowerCase().includes(needle)),
          )
        : all,
    [all, needle, errorsOnly],
  );

  useEffect(() => {
    if (live.length > 0 || game) return;
    void api
      .readLatestLog(profile.id)
      .then(setDisk)
      .catch(() => setDisk(null));
  }, [profile.id, live.length, game]);

  const share = async () => {
    setSharing('busy');
    try {
      const url = await api.uploadLog(all.map((l) => l.line).join('\n'));
      try {
        await navigator.clipboard.writeText(url);
        setNote(`Link copied: ${url}`);
      } catch {
        setNote(url);
      }
    } catch (e) {
      setNote(String(e));
    } finally {
      setSharing(null);
    }
  };

  // stick to the bottom while following; scrolling up pauses that
  useEffect(() => {
    const el = bodyRef.current;
    if (el && follow) el.scrollTop = el.scrollHeight;
  }, [lines, follow]);
  const onScroll = () => {
    const el = bodyRef.current;
    if (!el) return;
    setFollow(el.scrollHeight - el.scrollTop - el.clientHeight < 8);
  };

  const errors = useMemo(() => all.filter((l) => l.stream === 'err').length, [all]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(lines.map((l) => l.line).join('\n'));
      setNote('Copied.');
    } catch (e) {
      setNote(String(e));
    }
  };

  const status = game
    ? game.state === 'running'
      ? 'RUNNING'
      : game.state === 'stopping'
        ? 'STOPPING…'
        : 'STARTING…'
    : fromDisk
      ? `LATEST.LOG · ${ago(disk.modified).toUpperCase()}`
      : all.length
        ? 'LAST RUN'
        : 'NOT RUNNING';

  return (
    <div className="win__body editor__body">
      <div className="browse__toolbar">
        {game?.state === 'running' && (
          <PxButton family="red" height="sm" onClick={onStop}>
            <TT size={16} tone="red">
              STOP GAME
            </TT>
          </PxButton>
        )}
        <PxBox family="panel" height="sm" className="search editor__search">
          <PixelGlyph glyph="search" size={20} color="var(--text-3)" />
          <input
            className="input editor__search-input"
            placeholder="Filter lines…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </PxBox>
        <PxButton
          family={errorsOnly ? 'red' : 'grey'}
          height="sm"
          disabled={errors === 0 && !errorsOnly}
          title="Only the errors and their stack traces"
          onClick={() => setErrorsOnly((v) => !v)}
        >
          <TT size={16} tone={errorsOnly ? 'red' : 'plain'}>
            ERRORS
          </TT>
        </PxButton>
        <PxButton family="grey" height="sm" disabled={lines.length === 0} onClick={() => void copy()}>
          <TT size={16}>COPY</TT>
        </PxButton>
        <PxButton
          family="grey"
          height="sm"
          disabled={all.length === 0 || sharing !== null}
          title="Put the log on mclo.gs and copy the link"
          onClick={() => setSharing('ask')}
        >
          <TT size={16}>{sharing === 'busy' ? 'SHARING…' : 'SHARE'}</TT>
        </PxButton>
        {!fromDisk && (
          <PxButton family="grey" height="sm" disabled={live.length === 0} onClick={clearGameLog}>
            <TT size={16}>CLEAR</TT>
          </PxButton>
        )}
        <PxButton
          family="grey"
          height="sm"
          disabled={!isTauri}
          title={isTauri ? undefined : 'Needs the desktop app'}
          onClick={() => void api.openProfileFolder(profile.id, 'logs')}
        >
          <TT size={16}>LOGS FOLDER</TT>
        </PxButton>
        <span className="meta browse__count">
          {note ??
            `${status} · ${lines.length === all.length ? '' : `${lines.length}/`}${all.length} LINES${
              errors ? ` · ${errors} ERR` : ''
            }`}
        </span>
      </div>

      <PxBox family="panel" className="editor__log">
        <div ref={bodyRef} className="editor__log-body scroll" onScroll={onScroll}>
          {lines.length === 0 ? (
            <span className="meta">
              {all.length > 0
                ? 'No lines match.'
                : game
                  ? 'Waiting for the game to say something…'
                  : 'Nothing yet — PLAY NOW and the console shows up here. Older runs are in the logs folder.'}
            </span>
          ) : (
            lines.map((l, i) => (
              <span key={i} className={`editor__log-line${l.stream === 'err' ? ' is-err' : ''}`}>
                {l.line}
              </span>
            ))
          )}
        </div>
        {!follow && lines.length > 0 && (
          <button className="editor__log-follow" onClick={() => setFollow(true)}>
            <TT size={13} tone="accent">
              ↓ FOLLOW
            </TT>
          </button>
        )}
      </PxBox>
      {sharing === 'ask' && (
        <div className="modal-scrim" onClick={() => setSharing(null)}>
          <PxBox family="red" className="px--window modal" onClick={(e) => e.stopPropagation()}>
            <TT size={22}>SHARE LOG?</TT>
            <span className="meta">
              The log goes up on mclo.gs, where anyone with the link can read it — handy for asking for help.
              Sign-in tokens and your home folder are taken out first.
            </span>
            <div className="modal__row modal__row--tall">
              <PxButton family="grey" height="md" onClick={() => setSharing(null)}>
                <TT size={20}>CANCEL</TT>
              </PxButton>
              <PxButton family="accent" height="md" onClick={() => void share()}>
                <TT size={20} tone="accent">
                  SHARE
                </TT>
              </PxButton>
            </div>
          </PxBox>
        </div>
      )}
    </div>
  );
}
