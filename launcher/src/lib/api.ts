/**
 * Tauri bridge. Every view talks to the backend through here.
 *
 * Outside Tauri (plain `vite dev` in a browser, used for visual work on the
 * UI) there is no IPC, so a small honest fixture set answers instead — the
 * views can't tell the difference, and nothing silently pretends a real
 * launch happened.
 */
import { invoke as tauriInvoke } from '@tauri-apps/api/core';
import { listen as tauriListen } from '@tauri-apps/api/event';
import steveSkin from '../assets/skins/steve.png';

export const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

// ── DTOs (mirror src-tauri/src/commands.rs) ───────────────────────────────

export interface Profile {
  id: string;
  name: string;
  gameVersion: string;
  loader: string;
  loaderVersion: string | null;
  createdAt: number;
  lastPlayed: number | null;
  jvmArgs: string[];
  resolution: [number, number];
  server: string | null;
  modCount: number;
  art: number;
  /** JVM heap override in MB; null = the launcher-wide setting */
  memoryMb: number | null;
  /** java executable override; null = auto (launcher override → provisioned) */
  javaPath: string | null;
}

/** mirrors commands.rs ProfilePatch — every field optional, only set ones apply */
export interface ProfilePatch {
  name?: string;
  gameVersion?: string;
  loader?: string;
  loaderVersion?: string | null;
  jvmArgs?: string[];
  resolution?: [number, number];
  server?: string | null;
  /** 0 clears (JSON null can't clear an Option<Option<_>> on the Rust side) */
  memoryMb?: number;
  /** "" clears */
  javaPath?: string;
}

export interface Account {
  username: string;
  uuid: string;
  authenticated: boolean;
  /** the arm model Mojang reports for the active skin; '' when unknown */
  skinVariant: SkinModel | '';
}

/** Minecraft's two arm widths: classic (4px) and slim (3px) */
export type SkinModel = 'classic' | 'slim';

export interface Skin {
  name: string;
  addedAt: number;
  selected: boolean;
}

/** One bundled cape as the client-mod registry describes it. */
export interface CapeEntry {
  id: number;
  name: string;
  glint: boolean;
  upsideDown: boolean;
  ears: boolean;
  /** ms per animation frame (100 = the MinecraftCapes default) */
  frameMs: number;
}

export type AccessoryAttachment = 'head' | 'body' | 'left_arm' | 'right_arm' | 'left_leg' | 'right_leg';

/** A Cosmetica-style model accessory (docs/COSMETICS.md §5). */
export interface AccessoryEntry {
  id: number;
  name: string;
  attachment: AccessoryAttachment;
  /** Cosmetica's pixel offset (before the per-attachment shift) */
  offset: [number, number, number];
  mirrored: boolean;
  frames: number;
  ticksPerFrame: number;
  flags: number;
  source?: string;
}

export interface CosmeticsCatalog {
  version: number;
  capes: CapeEntry[];
  accessories: AccessoryEntry[];
}

/** A Blockbench "Java block" model as Cosmetica hosts it; only what we draw. */
export interface AccessoryModelJson {
  elements: {
    from: [number, number, number];
    to: [number, number, number];
    rotation?: { origin?: [number, number, number]; angle?: number; axis?: 'x' | 'y' | 'z'; x?: number; y?: number; z?: number };
    faces: Partial<
      Record<'north' | 'south' | 'east' | 'west' | 'up' | 'down', { uv?: [number, number, number, number]; rotation?: number }>
    >;
  }[];
}

/** slot → id (or ids for multi-slots like `accessories`), plus an optional
 *  `settings` object (docs/COSMETICS.md §1.3). */
export type Loadout = Record<string, number | number[] | Record<string, unknown>>;

/** What the account owns (registry ids); the store buys into it, the
 *  wardrobe lists it. Whatever the loadout wears is always included. */
export interface Inventory {
  owned: number[];
}

/** One line of the Dusk store: a registry id with its server-side price.
 *  Animated capes are 750, everything static is 500. */
export interface StoreItem {
  id: number;
  kind: 'cape' | 'accessory';
  name: string;
  animated: boolean;
  price: number;
}

/** The store as the Dusk API sees this account. `signedIn` is false (and
 *  `error` says why) when the Mojang-join sign-in did not go through —
 *  the catalog still lists, nothing can be bought. */
export interface Store {
  items: StoreItem[];
  coins: number;
  owned: number[];
  signedIn: boolean;
  error: string | null;
}

/** The account's Dusk wallet + inventory (GET /v1/me). */
export interface Wallet {
  uuid: string;
  username: string;
  coins: number;
  owned: number[];
  loadout: Loadout;
}

export interface Redeemed {
  granted: number;
  coins: number;
}

export interface Version {
  id: string;
  type: string;
  releaseAt: string;
}

export interface ModpackFacets {
  categories: string[];
  versions: string[];
  loaders: string[];
}

export interface ModpackHit {
  id: string;
  slug: string;
  title: string;
  description: string;
  author: string;
  downloads: number;
  follows: number;
  iconUrl: string | null;
  updatedAt: string | null;
  categories: string[];
  versions: string[];
  loaders: string[];
}

export interface ModpackSearch {
  hits: ModpackHit[];
  total: number;
  page: number;
  pageSize: number;
}

/** everything Modrinth can search over: modpacks plus the three content kinds */
export type ProjectKind = 'modpack' | ContentKind;

/** the filter vocabularies Modrinth publishes under /tag — the browse
 *  sidebar is built from these, not from whatever the first page returned */
export interface ProjectTags {
  categories: { name: string; header: string }[];
  loaders: string[];
  gameVersions: { version: string; versionType: 'release' | 'snapshot' | 'alpha' | 'beta'; major: boolean }[];
}

/** a project page (frame 5): the search hit plus body, gallery and links */
export interface ProjectDetails {
  id: string;
  slug: string;
  title: string;
  description: string;
  /** Modrinth markdown */
  body: string;
  iconUrl: string | null;
  downloads: number;
  follows: number;
  categories: string[];
  loaders: string[];
  gameVersions: string[];
  gallery: { url: string; title: string | null }[];
  discordUrl: string | null;
  issuesUrl: string | null;
  sourceUrl: string | null;
  wikiUrl: string | null;
  clientSide: string;
  serverSide: string;
  published: string | null;
  updated: string | null;
}

export interface ProjectVersion {
  id: string;
  name: string;
  versionNumber: string;
  changelog: string | null;
  gameVersions: string[];
  loaders: string[];
  published: string | null;
  versionType: 'release' | 'beta' | 'alpha' | string;
  downloads: number;
}

/** The pack a DUSK PROFILE wraps. For now that is Performium (Modrinth
 *  IDrxZk6D) as-is — a Dusk instance is the whole pack at whichever version
 *  the user picks, plus what the launcher forces into every Fabric instance
 *  at launch (DuskClient + the cosmetics loadout; see commands.rs). The
 *  launcher's own pack takes this slot later. */
export const DUSK_PACK = {
  id: 'IDrxZk6D',
  slug: 'performium-was-taken',
  title: 'Performium',
  /** what the instance is called unless the user renames it */
  instanceName: 'DUSK OPTIMIZED',
} as const;

/** Whether a bundled DuskClient jar loads on a game version — mirrors
 *  cosmetics::client_mod_jar_for (one build per game line: 1.21.x and
 *  26.2; launch skips the mod elsewhere). */
export const clientModSupports = (gameVersion: string) =>
  /^1[.-]21([.-]|$)/.test(gameVersion) || /^26[.-]2([.-]|$)/.test(gameVersion);

/** One file in a profile's mods/ (or resourcepacks/, shaderpacks/) folder */
export interface ProfileMod {
  filename: string;
  size: number;
  enabled: boolean;
  /** display name from the archive's metadata, when it has one */
  name?: string | null;
  /** the archive's own icon as a data: URL, when it has one */
  icon?: string | null;
}

/** One installed file matched back to the Modrinth project it came from
 *  (by file hash — so modpack-bundled and hand-copied jars count too) */
export interface InstalledProject {
  filename: string;
  projectId: string;
  versionId: string;
  versionNumber: string;
}

/** what a profile folder holds — `mod` → mods/, `resourcepack`, `shader` */
export type ContentKind = 'mod' | 'resourcepack' | 'shader';

export interface ModHit {
  id: string;
  slug: string;
  title: string;
  description: string;
  author: string;
  downloads: number;
  iconUrl: string | null;
  versions: string[];
  loaders: string[];
}

export interface World {
  name: string;
  modified: number;
  size: number;
}

/** subfolders `show_in_folder` will open (mirrors the Rust allow-list) */
export type ProfileFolder =
  | ''
  | 'mods'
  | 'resourcepacks'
  | 'shaderpacks'
  | 'saves'
  | 'logs'
  | 'screenshots';

export interface AppInfo {
  launcherVersion: string;
  os: string;
  dataDir: string;
}

export interface Wallpaper {
  /** file name inside <data>/wallpapers/ — also the settings value */
  name: string;
  /** absolute path, fed to `convertFileSrc` */
  path: string;
  kind: 'video' | 'image';
}

export interface Settings {
  theme: string;
  volume: number;
  muted: boolean;
  reduceMotion: boolean;
  fpsCap: number;
  selectedProfileId: string | null;
  memoryMb: number;
  defaultJvmArgs: string;
  javaPaths: Record<string, string>;
  envVars: string;
  prelaunchHook: string;
  wrapperHook: string;
  postExitHook: string;
  width: number;
  height: number;
  authClientId: string;
  authMode: string;
  customBackground: string;
}

export interface Progress {
  profileId: string;
  stage: string;
  done: number;
  total: number;
  doneBytes: number;
  totalBytes: number;
}

export interface GameState {
  profileId: string;
  state: 'starting' | 'running' | 'exited';
  code: number | null;
}

/** one line of the running game's stdout/stderr (event `game-log`, batched) */
export interface GameLogLine {
  line: string;
  stream: 'out' | 'err';
}
export interface GameLogBatch {
  lines: GameLogLine[];
}

// ── browser fixtures ───────────────────────────────────────────────────────

const HOUR = 3600_000;
/** a flat placeholder image of a given shape, for the preview's gallery */
const shot = (w: number, h: number, fill: string, label: string) =>
  `data:image/svg+xml;utf8,${encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><rect width="100%" height="100%" fill="${fill}"/><text x="50%" y="50%" fill="#fff" font-size="${Math.round(w / 12)}" text-anchor="middle" dominant-baseline="middle">${label} ${w}×${h}</text></svg>`,
  )}`;

/** the DUSK PROFILE popup in the preview: Performium's real shape — the
 *  bracketed names, a release + betas per game version */
const duskPackVersions: ProjectVersion[] = [
  { id: 'pf-1', name: '[26.2] Performium v2.0.0-Release+1', versionNumber: 'v2.0.0-Release+1', changelog: null, gameVersions: ['26.2'], loaders: ['fabric'], published: '2026-09-10T00:00:00Z', versionType: 'release', downloads: 16_726 },
  { id: 'pf-2', name: '[26.2] Performium v2.0.0-Beta+2', versionNumber: 'v2.0.0-Beta+2', changelog: null, gameVersions: ['26.2'], loaders: ['fabric'], published: '2026-09-02T00:00:00Z', versionType: 'beta', downloads: 11_696 },
  { id: 'pf-3', name: '[26.1.1] Performium v1.6.8-Release+1', versionNumber: 'v1.6.8-Release+1', changelog: null, gameVersions: ['26.1.1'], loaders: ['fabric'], published: '2026-08-14T00:00:00Z', versionType: 'release', downloads: 4_877 },
  { id: 'pf-4', name: '[1.21.11] Performium v1.6.2-Release+1', versionNumber: 'v1.6.2-Release+1', changelog: null, gameVersions: ['1.21.11'], loaders: ['fabric'], published: '2026-07-01T00:00:00Z', versionType: 'release', downloads: 61_200 },
  { id: 'pf-5', name: '[1.21.10] Performium v1.5.9-Release+1', versionNumber: 'v1.5.9-Release+1', changelog: null, gameVersions: ['1.21.10'], loaders: ['fabric'], published: '2026-05-20T00:00:00Z', versionType: 'release', downloads: 98_400 },
];

const fixtures: Record<string, unknown> = {
  list_profiles: [
    {
      id: 'p-performium',
      name: 'PERFORMIUM',
      gameVersion: '1.21.11',
      loader: 'fabric',
      loaderVersion: '0.16.10',
      createdAt: Date.now() - 40 * HOUR,
      lastPlayed: Date.now() - 2 * HOUR,
      jvmArgs: [],
      resolution: [1280, 720],
      server: 'play.dusk.gg',
      modCount: 38,
      art: 0x9e3779b9,
      memoryMb: null,
      javaPath: null,
    },
    {
      id: 'p-vanilla',
      name: 'VANILLA 1.21',
      gameVersion: '1.21.4',
      loader: 'vanilla',
      loaderVersion: null,
      createdAt: Date.now() - 400 * HOUR,
      lastPlayed: Date.now() - 26 * HOUR,
      jvmArgs: [],
      resolution: [1280, 720],
      server: null,
      modCount: 0,
      art: 0x1b873593,
      memoryMb: null,
      javaPath: null,
    },
  ] satisfies Profile[],
  get_current_account: null,
  modrinth_tags: {
    categories: [
      { name: 'adventure', header: 'categories' },
      { name: 'combat', header: 'categories' },
      { name: 'kitchen-sink', header: 'categories' },
      { name: 'lightweight', header: 'categories' },
      { name: 'magic', header: 'categories' },
      { name: 'multiplayer', header: 'categories' },
      { name: 'optimization', header: 'categories' },
      { name: 'quests', header: 'categories' },
      { name: 'technology', header: 'categories' },
    ],
    loaders: ['fabric', 'forge', 'neoforge', 'quilt'],
    gameVersions: [
      { version: '26.2', versionType: 'release', major: true },
      { version: '26w14a', versionType: 'snapshot', major: false },
      { version: '26.1', versionType: 'release', major: true },
      { version: '1.21.11', versionType: 'release', major: false },
      { version: '1.21.10', versionType: 'release', major: false },
      { version: '1.21.9', versionType: 'release', major: false },
      { version: '1.21.8', versionType: 'release', major: false },
      { version: '1.21.1', versionType: 'release', major: false },
      { version: '1.21', versionType: 'release', major: true },
      { version: '1.20.1', versionType: 'release', major: false },
      { version: '1.20', versionType: 'release', major: true },
      { version: '1.19.2', versionType: 'release', major: false },
      { version: '1.18.2', versionType: 'release', major: false },
      { version: '1.16.5', versionType: 'release', major: false },
      { version: '1.12.2', versionType: 'release', major: false },
      { version: '1.8.9', versionType: 'release', major: false },
    ],
  } satisfies ProjectTags,
  get_modpack_project: {
    id: 'm-fabu', slug: 'fabulously-optimized', title: 'Fabulously Optimized',
    description: 'A feature-packed, simple-to-use Minecraft modpack — better FPS, lower latency, fixed bugs.',
    body: [
      '# Fabulously Optimized',
      '',
      'A **simple, feature-packed** modpack that improves your FPS, fixes bugs and adds *quality of life* features — without changing gameplay.',
      '',
      '## What it does',
      '',
      '- Better FPS with Sodium, Lithium and friends',
      '- Fixes dozens of vanilla bugs',
      '- Zoom, better controls, custom skins',
      '',
      '## Links',
      '',
      'Read the [wiki](https://example.com/wiki) or see the `config/` folder for tweaks.',
      '',
      '> Works out of the box — install and play.',
    ].join('\n'),
    iconUrl: null, downloads: 4213456, follows: 39012,
    categories: ['optimization', 'lightweight'], loaders: ['fabric'],
    gameVersions: ['1.21.11', '1.21.10', '1.21.9', '1.21.1', '1.20.1'],
    /* two shapes, so the preview shows the gallery keeps proportions */
    gallery: [
      { url: shot(1920, 1080, '#2d5a3d', 'wide'), title: 'A 16:9 screenshot' },
      { url: shot(1080, 1350, '#3d2d5a', 'tall'), title: 'A 4:5 screenshot' },
      { url: shot(1600, 1600, '#5a3d2d', 'square'), title: null },
    ],
    discordUrl: 'https://discord.gg/example', issuesUrl: 'https://example.com/issues',
    sourceUrl: 'https://example.com/source', wikiUrl: 'https://example.com/wiki',
    clientSide: 'required', serverSide: 'unsupported',
    published: '2020-04-12T00:00:00Z', updated: '2026-09-08T00:00:00Z',
  } satisfies ProjectDetails,
  list_modpack_versions: [
    { id: 'v-1', name: '7.2.0', versionNumber: '7.2.0', changelog: '- Updated Sodium\n- Fixed a crash', gameVersions: ['1.21.11'], loaders: ['fabric'], published: '2026-09-08T00:00:00Z', versionType: 'release', downloads: 120_000 },
    { id: 'v-2', name: '7.2.0-beta.1', versionNumber: '7.2.0-beta.1', changelog: null, gameVersions: ['1.21.11'], loaders: ['fabric'], published: '2026-09-01T00:00:00Z', versionType: 'beta', downloads: 4_000 },
    { id: 'v-3', name: '7.1.3', versionNumber: '7.1.3', changelog: null, gameVersions: ['1.21.10'], loaders: ['fabric'], published: '2026-08-10T00:00:00Z', versionType: 'release', downloads: 900_000 },
    { id: 'v-4', name: '6.5.0', versionNumber: '6.5.0', changelog: null, gameVersions: ['1.20.1'], loaders: ['fabric'], published: '2024-02-10T00:00:00Z', versionType: 'release', downloads: 2_100_000 },
  ] satisfies ProjectVersion[],
  list_skins: [
    { name: 'steve', addedAt: Date.now() - 3 * HOUR, selected: true },
    { name: 'steve-alt', addedAt: Date.now() - HOUR, selected: false },
  ] satisfies Skin[],
  read_skin: steveSkin,
  get_account_skin: null,
  list_wallpapers: [
    { name: 'aurora-cabin.mp4', path: '/tmp/aurora-cabin.mp4', kind: 'video' },
    { name: 'night-sky.mp4', path: '/tmp/night-sky.mp4', kind: 'video' },
    { name: 'overgrown-cabin.mp4', path: '/tmp/overgrown-cabin.mp4', kind: 'video' },
    { name: 'sunset.mp4', path: '/tmp/sunset.mp4', kind: 'video' },
  ] satisfies Wallpaper[],
  list_versions: [
    { id: '1.21.11', type: 'release', releaseAt: '2026-08-20' },
    { id: '1.21.10', type: 'release', releaseAt: '2026-07-15' },
    { id: '1.21.9', type: 'release', releaseAt: '2026-06-30' },
    { id: '1.21.8', type: 'release', releaseAt: '2026-06-02' },
    { id: '1.21.7', type: 'release', releaseAt: '2026-05-11' },
    { id: '1.21.6', type: 'release', releaseAt: '2026-04-14' },
    { id: '1.21.5', type: 'release', releaseAt: '2026-03-19' },
    { id: '1.21.4', type: 'release', releaseAt: '2026-02-16' },
    { id: '1.21.3', type: 'release', releaseAt: '2026-01-20' },
    { id: '1.21.1', type: 'release', releaseAt: '2025-12-08' },
    { id: '1.21', type: 'release', releaseAt: '2025-11-24' },
    { id: '1.20.6', type: 'release', releaseAt: '2025-04-29' },
    { id: '1.20.4', type: 'release', releaseAt: '2025-02-06' },
    { id: '1.20.1', type: 'release', releaseAt: '2024-11-26' },
    { id: '1.19.4', type: 'release', releaseAt: '2024-05-22' },
    { id: '1.18.2', type: 'release', releaseAt: '2024-02-28' },
    { id: '1.16.5', type: 'release', releaseAt: '2023-01-14' },
    { id: '1.12.2', type: 'release', releaseAt: '2022-09-18' },
    { id: '1.8.9', type: 'release', releaseAt: '2015-12-09' },
    { id: '26w14a', type: 'snapshot', releaseAt: '2026-09-02' },
    { id: 'b1.7.3', type: 'old_beta', releaseAt: '2011-07-07' },
  ] satisfies Version[],
  fabric_loader_version: '0.16.14',
  search_modpacks: {
    hits: [
      {
        id: 'm-fabu', slug: 'fabulously-optimized', title: 'FABULOUSLY OPTIMIZED',
        description: 'A feature-packed, simple-to-use Minecraft modpack — better FPS, lower latency, fixed bugs.',
        author: 'robotkoer', downloads: 4213456, follows: 39012, iconUrl: null, updatedAt: '2026-09-08',
        categories: ['optimization'], versions: ['1.21.11', '1.21.10', '1.21.9'], loaders: ['fabric'],
      },
      {
        id: 'm-simply', slug: 'simply-optimized', title: 'SIMPLY OPTIMIZED',
        description: 'Performance and quality-of-life improvements with a minimal footprint. Vanilla-friendly.',
        author: 'RaptorClaws', downloads: 1842301, follows: 9844, iconUrl: null, updatedAt: '2026-09-01',
        categories: ['optimization'], versions: ['1.21.11', '1.21.4'], loaders: ['fabric'],
      },
      {
        id: 'm-adren', slug: 'adrenaline', title: 'ADRENALINE',
        description: 'A lightweight performance pack built for speed without stripping the game apart.',
        author: 'jah', downloads: 934502, follows: 5120, iconUrl: null, updatedAt: '2026-08-25',
        categories: ['optimization'], versions: ['1.21.9'], loaders: ['fabric', 'quilt'],
      },
      {
        id: 'm-aof', slug: 'all-of-fabric-7', title: 'ALL OF FABRIC 7',
        description: 'A general-purpose kitchen-sink modpack built on Fabric for Minecraft 1.21.',
        author: 'th.em.is.hard', downloads: 512004, follows: 2311, iconUrl: null, updatedAt: '2026-07-30',
        categories: ['adventure', 'technology'], versions: ['1.21.4'], loaders: ['fabric'],
      },
      {
        id: 'm-bmc', slug: 'better-mc', title: 'BETTER MC',
        description: 'New biomes, bosses, dungeons and gear — a full adventure overhaul.',
        author: 'Luna', downloads: 2930111, follows: 18455, iconUrl: null, updatedAt: '2026-08-12',
        categories: ['adventure', 'magic'], versions: ['1.21.5'], loaders: ['forge', 'neoforge'],
      },
    ],
    total: 703,
    page: 0,
    pageSize: 20,
  } satisfies ModpackSearch,
  list_worlds: [
    { name: 'New World', modified: Date.now() - 2 * HOUR, size: 184_320_000 },
    { name: 'Skyblock', modified: Date.now() - 90 * HOUR, size: 41_900_000 },
  ] satisfies World[],
  search_projects: {
    hits: [
      {
        id: 'AANobbMI', slug: 'sodium', title: 'Sodium', author: 'jellysquid3', downloads: 68_000_000, follows: 12_000,
        description: 'The fastest and most compatible rendering optimization mod for Minecraft.',
        iconUrl: null, updatedAt: '2026-09-10', categories: ['optimization'], versions: ['1.21.11'], loaders: ['fabric'],
      },
      {
        id: 'gvQqBUqZ', slug: 'lithium', title: 'Lithium', author: 'jellysquid3', downloads: 30_000_000, follows: 6_400,
        description: 'No-compromises game logic optimization mod.',
        iconUrl: null, updatedAt: '2026-09-04', categories: ['optimization'], versions: ['1.21.11'], loaders: ['fabric'],
      },
      {
        id: 'P7dR8mSH', slug: 'fabric-api', title: 'Fabric API', author: 'modmuss50', downloads: 140_000_000, follows: 20_100,
        description: 'Core library for the Fabric toolchain.',
        iconUrl: null, updatedAt: '2026-09-12', categories: ['library'], versions: ['1.21.11'], loaders: ['fabric'],
      },
      {
        id: 'YL57xq9U', slug: 'iris', title: 'Iris Shaders', author: 'coderbot', downloads: 25_000_000, follows: 9_800,
        description: 'A modern shaders mod for Minecraft intended to be compatible with existing OptiFine shader packs.',
        iconUrl: null, updatedAt: '2026-08-30', categories: ['decoration'], versions: ['1.21.11'], loaders: ['fabric'],
      },
    ],
    total: 4,
    page: 0,
    pageSize: 20,
  } satisfies ModpackSearch,
  search_content: [
    {
      id: 'AANobbMI', slug: 'sodium', title: 'Sodium', author: 'jellysquid3', downloads: 68_000_000,
      description: 'The fastest and most compatible rendering optimization mod for Minecraft.',
      iconUrl: null, versions: ['1.21.11'], loaders: ['fabric'],
    },
    {
      id: 'gvQqBUqZ', slug: 'lithium', title: 'Lithium', author: 'jellysquid3', downloads: 30_000_000,
      description: 'No-compromises game logic optimization mod.',
      iconUrl: null, versions: ['1.21.11'], loaders: ['fabric'],
    },
    {
      id: 'P7dR8mSH', slug: 'fabric-api', title: 'Fabric API', author: 'modmuss50', downloads: 140_000_000,
      description: 'Core library for the Fabric toolchain.',
      iconUrl: null, versions: ['1.21.11'], loaders: ['fabric'],
    },
  ] satisfies ModHit[],
  get_app_info: {
    launcherVersion: '0.1.0',
    os: 'Browser (preview)',
    dataDir: '— not running under Tauri —',
  } satisfies AppInfo,
  get_settings: {
    theme: 'overworld',
    volume: 0.6,
    muted: false,
    reduceMotion: false,
    fpsCap: 30,
    selectedProfileId: 'p-performium',
    memoryMb: 4096,
    defaultJvmArgs: '-Xms2G -Xmx4G -XX:+UseZGC -XX:+AlwaysPreTouch',
    javaPaths: {},
    envVars: '',
    prelaunchHook: '',
    wrapperHook: '',
    postExitHook: '',
    width: 1280,
    height: 720,
    authClientId: '',
    authMode: 'official',
    customBackground: '',
  } satisfies Settings,
};

/** Commands that change real state: refused outright in the browser. */
const sideEffects = new Set([
  'install_and_launch',
  'install_modpack',
  'install_modpack_version',
  'install_content_version_to_profile',
  'begin_login',
  'begin_reconsent_login',
  'upload_skin',
  'import_skin',
  'show_in_folder',
  'open_data_dir',
  'import_local_mod',
  'install_content_to_profile',
  'import_mrpack',
  'export_instance',
  'install_bundled_pack',
  'import_wallpaper',
  'remove_wallpaper',
]);

/* the preview's mod folders — one list per profile+kind, so enable/remove
   round-trip in the browser the way they do on disk */
const previewContent = new Map<string, ProfileMod[]>();
/* a flat 16×16 swatch standing in for a jar's icon in the browser preview */
function previewIcon(color: string): string {
  const c = document.createElement('canvas');
  c.width = c.height = 16;
  const g = c.getContext('2d')!;
  g.fillStyle = color;
  g.fillRect(0, 0, 16, 16);
  g.fillStyle = 'rgba(0,0,0,.35)';
  g.fillRect(3, 3, 10, 10);
  g.fillStyle = color;
  g.fillRect(5, 5, 6, 6);
  return c.toDataURL();
}

function previewFolder(profileId: string, kind: string): ProfileMod[] {
  const key = `${profileId}/${kind}`;
  let list = previewContent.get(key);
  if (!list) {
    list =
      kind === 'mod' && profileId === 'p-performium'
        ? [
            { filename: 'fabric-api-0.116.0+1.21.11.jar', size: 2_310_000, enabled: true, name: 'Fabric API', icon: previewIcon('#c9a24a') },
            { filename: 'sodium-fabric-0.6.13+mc1.21.11.jar', size: 1_180_000, enabled: true, name: 'Sodium', icon: previewIcon('#3a86ff') },
            { filename: 'lithium-fabric-0.15.0+mc1.21.11.jar', size: 640_000, enabled: true, name: 'Lithium', icon: previewIcon('#7ec850') },
            { filename: 'iris-fabric-1.8.8+mc1.21.11.jar', size: 3_950_000, enabled: false, name: 'Iris Shaders', icon: null },
          ]
        : [];
    previewContent.set(key, list);
  }
  return list;
}

/* the preview's wardrobe: the real catalog. Vite serves the client mod's
   cosmetics folder at /__cosmetics (see vite.config.ts), so the browser shows
   exactly the capes and accessories the jar ships — nothing synthesized. */
let previewLoadout: Loadout = { cape: 5, accessories: [16] };
let previewOwned = new Set<number>([5, 16]);
let previewCoins = 0;
let previewRedeemed = false;
/* the server's pricing rule (server/src/main.rs): animated capes 750, else 500 */
const PREVIEW_ANIMATED_CAPES = new Set([5, 6, 7, 14, 15]);
async function previewStore(): Promise<Store> {
  const cat = await previewCosmetics();
  const items: StoreItem[] = [
    ...cat.capes.map((c) => {
      const animated = PREVIEW_ANIMATED_CAPES.has(c.id);
      return { id: c.id, kind: 'cape' as const, name: c.name, animated, price: animated ? 750 : 500 };
    }),
    ...cat.accessories.map((a) => ({ id: a.id, kind: 'accessory' as const, name: a.name, animated: false, price: 500 })),
  ];
  return { items, coins: previewCoins, owned: [...previewOwned].sort((a, b) => a - b), signedIn: true, error: null };
}
let previewCatalog: Promise<CosmeticsCatalog> | null = null;
function previewCosmetics(): Promise<CosmeticsCatalog> {
  previewCatalog ??= fetch('/__cosmetics/registry.json')
    .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`registry.json: HTTP ${r.status}`))))
    .then((reg: { version: number; capes: Partial<CapeEntry>[]; accessories: AccessoryEntry[] }) => ({
      version: reg.version,
      capes: reg.capes.map((c) => ({ ...c, frameMs: c.frameMs ?? 100 }) as CapeEntry),
      accessories: reg.accessories,
    }));
  return previewCatalog;
}
async function previewFile(path: string): Promise<string> {
  const r = await fetch(`/__cosmetics/${path}`);
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  const blob = await r.blob();
  return new Promise((resolve, reject) => {
    const fr = new FileReader();
    fr.onload = () => resolve(String(fr.result));
    fr.onerror = () => reject(fr.error);
    fr.readAsDataURL(blob);
  });
}

export async function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  if (isTauri) return tauriInvoke<T>(cmd, args);
  if (sideEffects.has(cmd)) {
    throw new Error(`"${cmd}" needs the desktop app — this is the browser preview.`);
  }
  if (cmd === 'list_modpack_versions' && args?.id === DUSK_PACK.id) return structuredClone(duskPackVersions) as T;
  if (cmd === 'get_modpack_project' && args?.id === DUSK_PACK.id) {
    const base = structuredClone(fixtures.get_modpack_project) as ProjectDetails;
    return { ...base, id: DUSK_PACK.id, slug: DUSK_PACK.slug, title: DUSK_PACK.title, iconUrl: shot(96, 96, '#3a2f5a', 'P') } as T;
  }
  if (cmd in fixtures) return structuredClone(fixtures[cmd]) as T;
  if (cmd === 'list_cosmetics') return structuredClone(await previewCosmetics()) as T;
  if (cmd === 'read_cosmetic_texture') {
    const id = Number(args?.id);
    const path =
      args?.kind === 'accessory' ? `accessories/${id}/texture.png` : `capes/${id}/${args?.kind === 'ears' ? 'ears' : 'cape'}.png`;
    return (await previewFile(path)) as T;
  }
  if (cmd === 'read_cosmetic_model') {
    return JSON.parse(atob((await previewFile(`accessories/${Number(args?.id)}/model.json`)).split(',')[1])) as T;
  }
  if (cmd === 'get_loadout') return structuredClone(previewLoadout) as T;
  if (cmd === 'get_inventory') return { owned: [...previewOwned].sort((a, b) => a - b) } as T;
  if (cmd === 'get_store') return (await previewStore()) as T;
  if (cmd === 'get_wallet') {
    return { uuid: '', username: 'Preview', coins: previewCoins, owned: [...previewOwned], loadout: previewLoadout } as T;
  }
  if (cmd === 'redeem_code') {
    if (String(args?.code).trim().toLowerCase() !== 'yourewelcome') throw new Error('unknown code');
    if (previewRedeemed) throw new Error('code already redeemed');
    previewRedeemed = true;
    previewCoins += 1000;
    return { granted: 1000, coins: previewCoins } as T;
  }
  if (cmd === 'buy_cosmetic') {
    const store = await previewStore();
    const item = store.items.find((i) => i.id === args?.id);
    if (!item) throw new Error('no such item');
    if (!previewOwned.has(item.id)) {
      if (previewCoins < item.price) throw new Error('not enough coins');
      previewCoins -= item.price;
      previewOwned = new Set(previewOwned).add(item.id);
    }
    return (await previewStore()) as T;
  }
  if (cmd === 'export_cosmetic_texture') return null as T;
  if (cmd === 'set_loadout') {
    previewLoadout = structuredClone(args?.loadout as Loadout);
    return structuredClone(previewLoadout) as T;
  }
  if (cmd === 'set_settings') return (args?.settings as T) ?? (fixtures.get_settings as T);
  if (cmd === 'list_profile_content') {
    return structuredClone(previewFolder(String(args?.profileId), String(args?.kind))) as T;
  }
  if (cmd === 'lookup_profile_content') {
    // the preview's jars map onto the search fixture's projects
    const ids: Record<string, [string, string]> = {
      'fabric-api-0.116.0+1.21.11.jar': ['P7dR8mSH', '0.116.0+1.21.11'],
      'sodium-fabric-0.6.13+mc1.21.11.jar': ['AANobbMI', 'mc1.21.11-0.6.13'],
      'lithium-fabric-0.15.0+mc1.21.11.jar': ['gvQqBUqZ', 'mc1.21.11-0.15.0'],
      'iris-fabric-1.8.8+mc1.21.11.jar': ['YL57xq9U', '1.8.8+1.21.11'],
    };
    return previewFolder(String(args?.profileId), String(args?.kind)).flatMap((m) => {
      const hit = ids[m.filename];
      return hit
        ? [{ filename: m.filename, projectId: hit[0], versionId: `v-${hit[0]}`, versionNumber: hit[1] }]
        : [];
    }) as T;
  }
  if (cmd === 'set_content_enabled') {
    const list = previewFolder(String(args?.profileId), String(args?.kind));
    const row = list.find((m) => m.filename === args?.filename);
    if (!row) throw new Error('no such file');
    row.enabled = Boolean(args?.enabled);
    return structuredClone(row) as T;
  }
  if (cmd === 'remove_profile_content') {
    const list = previewFolder(String(args?.profileId), String(args?.kind));
    const i = list.findIndex((m) => m.filename === args?.filename);
    if (i >= 0) list.splice(i, 1);
    return undefined as T;
  }
  if (cmd === 'update_profile') {
    // the preview's profiles live in the fixture list, so a save shows up on
    // the next list_profiles like it would from the store
    const list = fixtures.list_profiles as Profile[];
    const p = list.find((x) => x.id === args?.id);
    if (!p) throw new Error('profile not found');
    const patch = (args?.patch ?? {}) as ProfilePatch;
    if (patch.name?.trim()) p.name = patch.name.trim();
    if (patch.gameVersion) p.gameVersion = patch.gameVersion;
    if (patch.loader) {
      p.loader = patch.loader;
      p.loaderVersion = patch.loader === 'fabric' ? String(fixtures.fabric_loader_version) : null;
    }
    if (patch.jvmArgs) p.jvmArgs = patch.jvmArgs;
    if (patch.resolution) p.resolution = patch.resolution;
    if (patch.server !== undefined) p.server = patch.server?.trim() ? patch.server : null;
    if (patch.memoryMb !== undefined) p.memoryMb = patch.memoryMb > 0 ? patch.memoryMb : null;
    if (patch.javaPath !== undefined) p.javaPath = patch.javaPath.trim() || null;
    return structuredClone(p) as T;
  }
  if (cmd === 'delete_profile') {
    const list = fixtures.list_profiles as Profile[];
    const i = list.findIndex((x) => x.id === args?.id);
    if (i >= 0) list.splice(i, 1);
    return undefined as T;
  }
  if (cmd === 'create_profile') {
    // the preview has no store to write — echo a plausible DTO so the flow
    // can be walked end to end in the browser
    const a = (args ?? {}) as { name?: string; gameVersion?: string; loader?: string };
    return {
      id: `p${Date.now()}`,
      name: a.name ?? 'Instance',
      gameVersion: a.gameVersion ?? '1.21.11',
      loader: a.loader ?? 'vanilla',
      loaderVersion: a.loader === 'fabric' ? fixtures.fabric_loader_version : null,
      jvmArgs: [],
      resolution: [1280, 720],
      server: null,
      modCount: 0,
      art: 0x9e3779b9,
      createdAt: Date.now(),
      lastPlayed: null,
      memoryMb: null,
      javaPath: null,
    } as T;
  }
  return undefined as T;
}

export async function listen<T>(event: string, handler: (payload: T) => void) {
  if (!isTauri) return () => {};
  return tauriListen<T>(event, (e) => handler(e.payload));
}

// ── typed calls ────────────────────────────────────────────────────────────

export const api = {
  listProfiles: () => invoke<Profile[]>('list_profiles'),
  createProfile: (name: string, gameVersion: string, loader: string, server?: string | null) =>
    invoke<Profile>('create_profile', { name, gameVersion, loader, server: server ?? null }),
  updateProfile: (id: string, patch: ProfilePatch) =>
    invoke<Profile>('update_profile', { id, patch }),
  deleteProfile: (id: string) => invoke<void>('delete_profile', { id }),
  launch: (profileId: string) => invoke<void>('install_and_launch', { profileId }),
  stopGame: () => invoke<void>('stop_game'),
  listVersions: () => invoke<Version[]>('list_versions'),
  fabricLoaderVersion: () => invoke<string>('fabric_loader_version'),
  searchModpacks: (
    query: string,
    facets: ModpackFacets,
    page = 0,
    pageSize = 20,
    sort = 'relevance',
  ) => invoke<ModpackSearch>('search_modpacks', { query, facets, page, pageSize, sort }),
  /** the same paged search for mods / resource packs / shaders */
  searchProjects: (
    kind: ContentKind,
    query: string,
    facets: ModpackFacets,
    page = 0,
    pageSize = 20,
    sort = 'relevance',
  ) => invoke<ModpackSearch>('search_projects', { kind, query, facets, page, pageSize, sort }),
  installModpack: (id: string) => invoke<Profile>('install_modpack', { id }),
  /** install one specific version of a modpack as a new instance */
  installModpackVersion: (id: string, versionId: string, name?: string) =>
    invoke<Profile>('install_modpack_version', { id, versionId, name: name ?? null }),
  getProject: (id: string) => invoke<ProjectDetails>('get_modpack_project', { id }),
  listProjectVersions: (id: string) => invoke<ProjectVersion[]>('list_modpack_versions', { id }),
  /** Modrinth's own filter lists for one project kind (cached per process) */
  projectTags: (kind: ProjectKind) => invoke<ProjectTags>('modrinth_tags', { kind }),
  /** install a pack shipped inside the app bundle (e.g. 'dusk-essentials') */
  installBundledPack: (pack: string) => invoke<Profile>('install_bundled_pack', { pack }),
  /** native picker → installs a .mrpack as a new instance (null if cancelled) */
  importMrpack: () => invoke<Profile | null>('import_mrpack'),
  /** save dialog → writes the instance as a .mrpack; resolves to a short
   *  summary ("12 files"), or null if cancelled */
  exportInstance: (profileId: string) =>
    invoke<string | null>('export_instance', { profileId }),
  /** open one of the profile's folders in Finder / Explorer */
  openProfileFolder: (profileId: string, subdir: ProfileFolder = '') =>
    invoke<void>('show_in_folder', { profileId, subdir }),
  openDataDir: () => invoke<void>('open_data_dir'),

  // ── what an instance holds ──
  listWorlds: (profileId: string) => invoke<World[]>('list_worlds', { profileId }),
  listContent: (profileId: string, kind: ContentKind) =>
    invoke<ProfileMod[]>('list_profile_content', { profileId, kind }),
  /** which Modrinth projects the folder already holds, by file hash */
  lookupContent: (profileId: string, kind: ContentKind) =>
    invoke<InstalledProject[]>('lookup_profile_content', { profileId, kind }),
  setContentEnabled: (profileId: string, kind: ContentKind, filename: string, enabled: boolean) =>
    invoke<ProfileMod>('set_content_enabled', { profileId, kind, filename, enabled }),
  removeContent: (profileId: string, kind: ContentKind, filename: string) =>
    invoke<void>('remove_profile_content', { profileId, kind, filename }),
  /** native file picker → copies the .jar into mods/ (null if cancelled) */
  importLocalMod: (profileId: string) =>
    invoke<ProfileMod | null>('import_local_mod', { profileId }),
  searchContent: (kind: ContentKind, query: string, gameVersion: string, loader: string, limit = 20) =>
    invoke<ModHit[]>('search_content', { kind, query, gameVersion, loader, limit }),
  installContent: (profileId: string, kind: ContentKind, projectId: string) =>
    invoke<ProfileMod>('install_content_to_profile', { profileId, kind, projectId }),
  /** install the exact version the user picked on the project page */
  installContentVersion: (profileId: string, kind: ContentKind, versionId: string) =>
    invoke<ProfileMod>('install_content_version_to_profile', { profileId, kind, versionId }),

  getSettings: () => invoke<Settings>('get_settings'),
  setSettings: (settings: Settings) => invoke<Settings>('set_settings', { settings }),

  listWallpapers: () => invoke<Wallpaper[]>('list_wallpapers'),
  /** native picker → copies the file into <data>/wallpapers/ (null if cancelled) */
  importWallpaper: () => invoke<Wallpaper | null>('import_wallpaper'),
  removeWallpaper: (name: string) => invoke<void>('remove_wallpaper', { name }),

  getAccount: () => invoke<Account | null>('get_current_account'),
  login: () => invoke<Account>('begin_login'),
  logout: () => invoke<void>('logout'),
  getAppInfo: () => invoke<AppInfo>('get_app_info'),

  listSkins: () => invoke<Skin[]>('list_skins'),
  readSkin: (name: string) => invoke<string>('read_skin', { name }),
  importSkin: () => invoke<Skin | null>('import_skin'),
  deleteSkin: (name: string) => invoke<void>('delete_skin', { name }),
  selectSkin: (name: string) => invoke<void>('set_selected_skin', { name }),
  uploadSkin: (name: string, variant: SkinModel) =>
    invoke<void>('upload_skin', { name, variant }),
  /** pin a wardrobe skin's arm model; null goes back to auto-detect */
  accountSkin: () => invoke<string | null>('get_account_skin'),

  listCosmetics: () => invoke<CosmeticsCatalog>('list_cosmetics'),
  readCosmeticTexture: (kind: 'cape' | 'ears' | 'accessory', id: number) =>
    invoke<string>('read_cosmetic_texture', { kind, id }),
  readCosmeticModel: (id: number) => invoke<AccessoryModelJson>('read_cosmetic_model', { id }),
  getLoadout: () => invoke<Loadout>('get_loadout'),
  setLoadout: (loadout: Loadout) => invoke<Loadout>('set_loadout', { loadout }),
  getInventory: () => invoke<Inventory>('get_inventory'),
  /** the Dusk store: catalog with prices, this account's coins and inventory */
  getStore: () => invoke<Store>('get_store'),
  getWallet: () => invoke<Wallet>('get_wallet'),
  /** turn a code into coins (server-side; each code once per account) */
  redeemCode: (code: string) => invoke<Redeemed>('redeem_code', { code }),
  /** spend coins on a catalog item; resolves to the refreshed store */
  buyCosmetic: (id: number) => invoke<Store>('buy_cosmetic', { id }),
  /** save dialog → the PNG out of the jar (for uploading to minecraftcapes.net);
   *  resolves to the written path, or null if cancelled */
  exportCosmeticTexture: (kind: 'cape' | 'ears' | 'accessory', id: number) =>
    invoke<string | null>('export_cosmetic_texture', { kind, id }),
};

// ── small formatters ───────────────────────────────────────────────────────

export function ago(ms: number | null): string {
  if (!ms) return 'never played';
  const s = Math.max(1, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return `${s} seconds ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} minute${m === 1 ? '' : 's'} ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} hour${h === 1 ? '' : 's'} ago`;
  const d = Math.round(h / 24);
  return `${d} day${d === 1 ? '' : 's'} ago`;
}

export function loaderLabel(p: Profile) {
  return p.loader.charAt(0).toUpperCase() + p.loader.slice(1);
}

export function fmtBytes(n: number): string {
  if (n >= 1_073_741_824) return `${(n / 1_073_741_824).toFixed(1)} GB`;
  if (n >= 1_048_576) return `${(n / 1_048_576).toFixed(1)} MB`;
  if (n >= 1024) return `${Math.round(n / 1024)} KB`;
  return `${n} B`;
}
