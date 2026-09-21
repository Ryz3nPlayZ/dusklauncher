import { useEffect, useMemo, useRef, useState } from 'react';
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
  loaderLabel,
  type ContentKind,
  type GameState,
  type InstalledProject,
  type Profile,
  type ProfileFolder,
  type ProfileMod,
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
  onLaunch: (id: string) => void;
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
        onBack={() => setProjectId(null)}
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
        onOpen={(hit) => setProjectId(hit.id)}
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
              {live.state === 'running' ? 'STOP GAME' : 'STARTING…'}
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
          />
        )}
        {tab === 'SETTINGS' && (
          <SettingsTab profile={profile} onSaved={onRefresh} onDelete={() => onDelete(profile)} />
        )}
        {tab === 'WORLDS' && <WorldsTab profile={profile} />}
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
            : 'Plain vanilla, no modloader. Mods in the folder are ignored.'
        }
      >
        <Choice
          value={loader}
          options={[
            { value: 'vanilla', label: 'VANILLA' },
            { value: 'fabric', label: 'FABRIC' },
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
      </Row>
      <Row label="JVM ARGUMENTS" hint="Space-separated extras; the heap comes from MEMORY.">
        <PxBox family="panel" height="md" className="px--wide">
          <input
            className="input"
            placeholder="-XX:+UseZGC"
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
}: {
  profile: Profile;
  kindTab: KindTab;
  onKindTab: (k: KindTab) => void;
  content: ContentInfo | null;
  onBrowse: (c: ContentInfo) => void;
}) {
  const [rows, setRows] = useState<{ file: ProfileMod; kind: ContentKind; label: string }[] | null>(null);
  const [note, setNote] = useState<string | null>(null);

  const kind = content?.kind ?? null;
  const noun = content?.noun ?? 'files';

  const reload = () => {
    const targets = content ? [content] : KIND_INFO;
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [profile.id, kindTab]);

  const act = async (fn: () => Promise<unknown>) => {
    setNote(null);
    try {
      await fn();
      await reload();
    } catch (e) {
      setNote(String(e));
    }
  };

  const enabled = rows?.filter((r) => r.file.enabled).length ?? 0;
  const vanilla = kindTab === 'MODS' && profile.loader === 'vanilla';

  return (
    <div className="win__body editor__body">
      {/* 172:626 — the kind strip, with the actions pinned to its right */}
      <div className="win__bar editor__kinds">
        {KINDS.map((k) => (
          <NavCell key={k} label={k} active={kindTab === k} onClick={() => onKindTab(k)} />
        ))}
        <div className="win__fill">
          <span className="meta editor__count">
            {rows ? `${rows.length} ${noun.toUpperCase()} · ${enabled} ON` : '…'}
          </span>
        </div>
        {kindTab === 'MODS' && isTauri && (
          <NavCell label="ADD FILE" onClick={() => void act(() => api.importLocalMod(profile.id))} />
        )}
        {content && <NavCell label="GET FROM MODRINTH" onClick={() => onBrowse(content)} />}
      </div>

      {vanilla && (
        <span className="meta">
          This instance runs vanilla — switch the loader to Fabric in SETTINGS for mods to load.
        </span>
      )}
      {note && <span className="meta">{note}</span>}

      <div className="editor__list scroll">
        {rows && rows.length === 0 && (
          <PxBox family="panel" className="empty">
            <TT size={20} tone="dim">
              {`NO ${noun.toUpperCase()} YET`}
            </TT>
            <span className="meta">
              {kind === 'mod'
                ? 'Add a .jar from disk or install one from Modrinth.'
                : `Install one from Modrinth, or drop files into the ${noun} folder.`}
            </span>
          </PxBox>
        )}
        {rows?.map(({ file: m, kind: fileKind, label }) => (
          <div key={`${fileKind}/${m.filename}`} className={['editor__row', m.enabled ? '' : 'is-off'].join(' ')}>
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
            <PxButton
              family="red"
              height="sm"
              className="editor__remove"
              onClick={() => void act(() => api.removeContent(profile.id, fileKind, m.filename))}
            >
              <TT size={16} tone="red">
                REMOVE
              </TT>
            </PxButton>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ── WORLDS: what's in saves/ ───────────────────────────────────────────── */
function WorldsTab({ profile }: { profile: Profile }) {
  const [worlds, setWorlds] = useState<World[] | null>(null);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    void api
      .listWorlds(profile.id)
      .then(setWorlds)
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
        <span className="meta browse__count">{worlds ? `${worlds.length} WORLDS` : '…'}</span>
      </div>
      {note && <span className="meta">{note}</span>}
      <div className="editor__list scroll">
        {worlds && worlds.length === 0 && (
          <PxBox family="panel" className="empty">
            <TT size={20} tone="dim">
              NO WORLDS YET
            </TT>
            <span className="meta">Worlds you create in this instance show up here.</span>
          </PxBox>
        )}
        {worlds?.map((w) => (
          <div key={w.name} className="editor__row">
            <span className="editor__file">
              <TT size={16}>{w.name}</TT>
              <span className="meta">
                {fmtBytes(w.size)} · played {ago(w.modified)}
              </span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
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
  const lines = useGameLog(profile.id);
  const [follow, setFollow] = useState(true);
  const [note, setNote] = useState<string | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);

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

  const errors = useMemo(() => lines.filter((l) => l.stream === 'err').length, [lines]);

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
      : 'STARTING…'
    : lines.length
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
        <PxButton family="grey" height="sm" disabled={lines.length === 0} onClick={() => void copy()}>
          <TT size={16}>COPY LOG</TT>
        </PxButton>
        <PxButton family="grey" height="sm" disabled={lines.length === 0} onClick={clearGameLog}>
          <TT size={16}>CLEAR</TT>
        </PxButton>
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
          {note ?? `${status} · ${lines.length} LINES${errors ? ` · ${errors} ERR` : ''}`}
        </span>
      </div>

      <PxBox family="panel" className="editor__log">
        <div ref={bodyRef} className="editor__log-body scroll" onScroll={onScroll}>
          {lines.length === 0 ? (
            <span className="meta">
              {game
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
    </div>
  );
}
