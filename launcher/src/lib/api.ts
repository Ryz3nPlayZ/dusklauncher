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
  /** seconds played, all launches together */
  playSecs: number;
  jvmArgs: string[];
  resolution: [number, number];
  server: string | null;
  modCount: number;
  art: number;
  /** JVM heap override in MB; null = the launcher-wide setting */
  memoryMb: number | null;
  /** java executable override; null = auto (launcher override → provisioned) */
  javaPath: string | null;
  /** a name instances are filed under in INSTANCES; null = none */
  group: string | null;
  /** absolute path of the instance's own picture; null = the stock banner */
  icon: string | null;
  /** the Modrinth pack it was installed from; null = not from one */
  pack: PackLink | null;
}

/** mirrors commands.rs PackDto */
export interface PackLink {
  projectId: string;
  versionId: string;
  versionNumber: string;
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
  /** "" ungroups */
  group?: string;
}

export interface Account {
  username: string;
  uuid: string;
  authenticated: boolean;
  /** the arm model Mojang reports for the active skin; '' when unknown */
  skinVariant: SkinModel | '';
  /** offline play (no Microsoft account): singleplayer and offline-mode
   *  servers only */
  offline?: boolean;
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
  /** a launcher feature the code switched on locally ('offline') */
  unlocked?: string;
}

/** this account's referral code and who brought it (server-side) */
export interface Referral {
  code: string;
  /** who invited this account, once a code was entered */
  referredBy: string | null;
  referralPaid: boolean;
  /** new accounts only, once */
  canClaim: boolean;
  /** accounts that entered this one's code / of those, how many paid out */
  invited: number;
  paid: number;
  referrerReward: number;
  refereeReward: number;
  coins: number;
}

/** a Mojang cape the account owns (Migrator, Pan, …) */
export interface AccountCape {
  id: string;
  name: string;
  active: boolean;
  /** 64×32 PNG data URL; empty if it couldn't be fetched */
  texture: string;
}

/** one entry of the friends list (GET /v1/friends) — online first, then by name */
export interface Friend {
  uuid: string;
  username: string;
  /** heartbeat within the last two minutes */
  online: boolean;
  /** the game version they're in; only while online */
  playing?: string;
  /** the multiplayer server they're on — joinable; only while playing */
  server?: string;
  /** unix seconds */
  lastSeen: number;
  /** messages from them not yet fetched */
  unread: number;
  /** a cracked (offline) account */
  offline?: boolean;
}

/** the heartbeat's answer (POST /v1/me/presence) — what the status pill badges */
export interface SocialSummary {
  /** incoming friend requests */
  requests: number;
  /** unread messages across every conversation */
  unread: number;
  /** friends online right now */
  online: number;
}

/** one pending invite, whichever direction (GET /v1/friends/requests) */
export interface FriendRequest {
  id: number;
  uuid: string;
  username: string;
  /** unix seconds */
  createdAt: number;
}

export interface FriendRequests {
  incoming: FriendRequest[];
  outgoing: FriendRequest[];
}

/** a friend's profile (GET /v1/profile/:uuid) — friends-only, never a stranger's */
export interface FriendProfile {
  uuid: string;
  username: string;
  online: boolean;
  /** unix seconds */
  lastSeen: number;
  playing?: string;
  server?: string;
  cape: number | null;
  accessories: number[];
  /** everything they own, so a gift can skip it */
  owned: number[];
  /** achievements they've claimed */
  badges: string[];
  /** a cracked (offline) account */
  offline?: boolean;
}

/** a quest, daily or weekly, or an achievement (GET /v1/me/quests) */
export interface Quest {
  id: string;
  title: string;
  progress: number;
  goal: number;
  /** "min" for minutes played, else a count */
  unit: string;
  coins: number;
  done: boolean;
  claimed: boolean;
}

export interface Streak {
  /** played days in a row, counting today once it reaches needMinutes */
  days: number;
  todayMinutes: number;
  needMinutes: number;
  /** what today pays once it counts */
  coins: number;
  done: boolean;
  claimed: boolean;
  /** the week's cycle of payouts and today's place in it (0-based) */
  cycle: number[];
  cycleDay: number;
}

export interface Quests {
  coins: number;
  daily: Quest[];
  weekly: Quest[];
  streak: Streak;
  achievements: Quest[];
  /** rewards waiting for CLAIM */
  claimable: number;
  /** seconds until the daily / weekly boards change */
  dailyReset: number;
  weeklyReset: number;
}

/** one chat line, either direction (GET/POST /v1/messages/:uuid) */
export interface ChatMessage {
  id: number;
  fromUuid: string;
  toUuid: string;
  body: string;
  /** unix seconds */
  sentAt: number;
  kind: 'text' | 'invite' | 'image' | 'gift';
  /** invite: {server, version?} · image: {image} · gift: {item, name, kind} */
  meta?: { server?: string; version?: string | null; image?: string; item?: number; name?: string; kind?: string };
}

/** who can see what (GET/PUT /v1/me/privacy) */
export interface Privacy {
  /** friends see this account offline, last seen when it went on */
  appearOffline: boolean;
  /** friends see the version and server being played */
  shareActivity: boolean;
  friendRequests: 'everyone' | 'friends_of_friends' | 'nobody';
}

export interface BlockedPlayer {
  uuid: string;
  username: string;
}

/** a saved, named loadout (GET/POST /v1/me/outfits) */
export interface Outfit {
  id: number;
  name: string;
  loadout: Loadout;
  /** unix seconds */
  createdAt: number;
}

/** a Microsoft account signed in on this machine, for the switcher */
export interface SavedAccount {
  uuid: string;
  username: string;
  active: boolean;
}

/** what the running game is doing (event `game-activity`) */
export interface GameActivity {
  profileId: string;
  profileName: string;
  gameVersion: string;
  /** the multiplayer server, from the game log; null in singleplayer / menus.
   *  While hosting, the public address friends join the world at. */
  server: string | null;
  /** `server` is this player's own world, opened to friends (HOST) */
  hosting: boolean;
  /** unix seconds */
  startedAt: number;
}

export interface Screenshot {
  /** absolute path, fed to `convertFileSrc` */
  path: string;
  name: string;
  profileId: string;
  profileName: string;
  /** unix millis */
  takenAt: number;
  size: number;
  favorite: boolean;
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

/** A DUSK PROFILE: a Fabric instance filled with Dusk Essentials (the
 *  launcher's own lineup, mods::DUSK_ESSENTIALS) for the chosen game version,
 *  plus what the launcher forces into every Fabric instance at launch
 *  (DuskClient + the cosmetics loadout; see commands.rs). */
export const DUSK_PROFILE = {
  /** what the instance is called unless the user renames it */
  instanceName: (gameVersion: string) => `DUSK ${gameVersion}`,
  /** the game version the first-run instance is created on */
  defaultGameVersion: '1.21.11',
} as const;

/** Mojang's art for one Java release (release_art.rs) */
export interface ReleaseArt {
  version: string;
  kind: 'release' | 'snapshot' | string;
  imageUrl: string;
  blurb: string;
}

/** The versions NEW INSTANCE offers as big cards: the newest release of each
 *  update line (26.3, 26.2, 26.1.x …, then 1.21.11), newest first, `max` of
 *  them. A line with no release yet adds one more card in front: its newest
 *  snapshot, and only while that snapshot is newer than every release. */
export function featuredVersions(versions: Version[], max = 4): Version[] {
  const line = (id: string) => {
    const m = /^(\d+)\.(\d+)/.exec(id);
    // 1.x lines are one card for the whole 1.21 line: its newest patch
    return m ? (m[1] === '1' ? `1.${m[2]}` : `${m[1]}.${m[2]}`) : id;
  };
  const out: Version[] = [];
  const seen = new Set<string>();
  const newestRelease = versions.find((v) => v.type === 'release');
  for (const v of versions) {
    if (v.type === 'snapshot') {
      const ahead = newestRelease && v.releaseAt > newestRelease.releaseAt;
      if (!ahead || out.length > 0 || seen.has(line(v.id))) continue;
    } else if (v.type !== 'release') continue;
    const key = line(v.id);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(v);
    if (out.filter((x) => x.type === 'release').length >= max) break;
  }
  return out;
}

/** Whether a bundled DuskClient jar loads on a game version — mirrors
 *  cosmetics::client_mod_jar_for (one build per API line: 1.21–1.21.1,
 *  1.21.2–3, 1.21.4, 1.21.5, 1.21.6–8, 1.21.9–10, 1.21.11, 26.1.x, 26.2.x,
 *  i.e. every release from 1.21 through 26.2; launch skips the mod
 *  elsewhere). */
export const clientModSupports = (gameVersion: string) => {
  const [major, minor, patch] = gameVersion.split(/[.-]/);
  if (major === '1' && minor === '21') return (Number.parseInt(patch ?? '', 10) || 0) <= 11;
  return major === '26' && (minor === '1' || minor === '2');
};

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

/** an installed file with a newer Modrinth version for its instance */
export interface ContentUpdate {
  filename: string;
  projectId: string;
  /** the installed version's number */
  currentVersion: string;
  /** the version to update to */
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
  /** the world's icon.png as a data URL, once the game has saved one */
  icon: string | null;
  /** what its level.dat says; null / false when it couldn't be read */
  levelName: string | null;
  gameMode: 'survival' | 'creative' | 'adventure' | 'spectator' | null;
  hardcore: boolean;
  cheats: boolean;
  /** the version it was last played in */
  version: string | null;
  /** as text: JS numbers can't hold every seed */
  seed: string | null;
}

/** a Java install found on this machine (`list_javas`) */
export interface JavaInstall {
  /** the java executable */
  path: string;
  /** as its `release` file gives it, e.g. "21.0.2" or "1.8.0_392" */
  version: string;
  major: number;
  vendor: string | null;
  /** one of the runtimes the launcher downloads by itself */
  bundled: boolean;
}

/** The Java major a Minecraft version asks for, when its id says
 * (Mojang's `javaVersion.majorVersion`); null for snapshots. */
export function javaFor(mc: string): number | null {
  const old = /^1\.(\d+)(?:\.(\d+))?$/.exec(mc);
  if (old) {
    const minor = Number(old[1]);
    const patch = Number(old[2] ?? 0);
    if (minor > 20 || (minor === 20 && patch >= 5)) return 21;
    if (minor >= 18) return 17;
    if (minor === 17) return 16;
    return 8;
  }
  // year-numbered releases (26.1 on) moved to Java 25
  return /^\d{2}\.\d+(\.\d+)?$/.test(mc) ? 25 : null;
}

/** Is release `a` newer than release `b`? null when either isn't a plain
 *  release number (snapshots, pre-releases). 26.1 > 1.21.11 falls out of
 *  comparing the parts as numbers. */
export function releaseNewer(a: string, b: string): boolean | null {
  const parts = (v: string) => (/^\d+(\.\d+){1,2}$/.test(v) ? v.split('.').map(Number) : null);
  const x = parts(a);
  const y = parts(b);
  if (!x || !y) return null;
  for (let i = 0; i < 3; i++) {
    const d = (x[i] ?? 0) - (y[i] ?? 0);
    if (d) return d > 0;
  }
  return false;
}

/** an entry of the instance's multiplayer list (`servers.dat`) */
export interface SavedServer {
  name: string;
  address: string;
  /** the icon the game cached at its last ping (data URL) */
  icon: string | null;
}

/** one run of MOTD text; `color` is `#rrggbb`, null for the default grey */
export interface MotdPart {
  text: string;
  color: string | null;
}

/** a server list ping: what the game's multiplayer screen shows */
export interface ServerStatus {
  motd: MotdPart[];
  online: number;
  max: number;
  version: string;
  pingMs: number;
  icon: string | null;
}

/** an instance another launcher on this machine keeps (FROM ANOTHER LAUNCHER) */
export interface ExternalInstance {
  source: string;
  name: string;
  gameVersion: string | null;
  loader: string;
  loaderVersion: string | null;
  /** its game folder — what gets copied, and the key to import it by */
  path: string;
  mods: number;
  worlds: number;
  /** why it can't come in, when it can't */
  blocked: string | null;
  /** the group the other launcher files it under; the import keeps it */
  group: string | null;
}

/** where a launch goes once the game is up: a server, a world, a recording */
export interface LaunchTarget {
  server?: string;
  world?: string;
  /** with `world`: open it to friends through a public relay address */
  host?: boolean;
  replay?: string;
}

/** a world or server played lately, from the game's own Quick Play log */
export interface RecentPlay {
  profileId: string;
  profileName: string;
  kind: 'world' | 'server';
  /** the save's folder name, or the server address */
  id: string;
  name: string;
  lastPlayed: number;
  gamemode: string;
}

/** a clip or replay the Dusk client recorded (an `.mcpr`) */
export interface Recording {
  path: string;
  name: string;
  kind: 'clip' | 'replay';
  profileId: string;
  profileName: string;
  /** unix millis */
  recordedAt: number;
  size: number;
  durationMs: number;
  /** server address, '' for singleplayer */
  server: string;
  mcVersion: string;
}

/** subfolders `show_in_folder` will open (mirrors the Rust allow-list) */
export type ProfileFolder =
  | ''
  | 'mods'
  | 'resourcepacks'
  | 'shaderpacks'
  | 'saves'
  | 'logs'
  | 'screenshots'
  | 'backups';

export interface AppInfo {
  launcherVersion: string;
  os: string;
  dataDir: string;
  /** this build can show Discord Rich Presence */
  discordAvailable: boolean;
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
  discordRpc: boolean;
  /** what the window does while the game runs */
  onPlay: 'keep' | 'minimize' | 'hide';
  notifyFriendsOnline: boolean;
  notifyMessages: boolean;
  clock24h: boolean;
  warnOnLinks: boolean;
  syncClientSettings: boolean;
  /** offline play's username; '' while offline play is locked */
  offlineName: string;
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
  /* `stopping` is UI-only: STOP was pressed and the game is shutting down */
  state: 'starting' | 'running' | 'stopping' | 'exited';
  code: number | null;
}

/** why the game exited with an error (event `game-crash`), worked out
 *  from its crash report and the end of its log */
export interface CrashInfo {
  profileId: string;
  code: number;
  title: string;
  /** what to do about it, one item per entry */
  advice: string[];
  /** the crash report, relative to the instance folder (SHOW REPORT) */
  report: string | null;
  /** the report's head or the log's tail (COPY DETAILS) */
  details: string;
}

/** one line of the running game's stdout/stderr (event `game-log`, batched) */
export interface GameLogLine {
  line: string;
  stream: 'out' | 'err';
}
export interface GameLogBatch {
  lines: GameLogLine[];
}
/** a mod id the instance's Fabric mods depend on that nothing in mods/ provides */
export interface MissingDep {
  id: string;
  /** what players call it, e.g. "Fabric API" */
  label: string;
  /** the Modrinth project to install it from */
  project: string;
  /** display names of the mods that need it */
  neededBy: string[];
  /** a disabled jar that provides it — turn it on instead of installing */
  disabledFile: string | null;
}

/** a library the mods need, present in a version they don't take */
export interface Mismatch {
  /** the jar that has it ("Fabric API"), "X (inside Y)" for a bundled one, or "Minecraft" */
  name: string;
  version: string;
  /** its file in mods/ — null for Minecraft itself */
  file: string | null;
  neededBy: { name: string; file: string; wants: string }[];
}
/** what would stop an instance's Fabric mods from loading */
export interface ModProblems {
  missing: MissingDep[];
  mismatched: Mismatch[];
  /** the same mod in mods/ more than once: keep the newest, the rest go off */
  duplicates: { name: string; keep: string; keepVersion: string; extra: string[] }[];
  /** a mod whose `breaks` names another one present */
  clashes: { name: string; file: string; other: string; otherVersion: string; otherFile: string }[];
}

/** worlds added to an instance's saves/ */
export interface ImportedWorlds {
  /** the folder names they landed under */
  added: string[];
  /** file names that held no world */
  skipped: string[];
}
/** one pack in a world's datapacks/ */
export interface Datapack {
  /** its name on disk, without a `.disabled` */
  file: string;
  /** pack.mcmeta's description, formatting stripped */
  description: string | null;
  enabled: boolean;
  /** a pack folder rather than a zip: can't be turned off from here */
  folder: boolean;
  size: number;
  icon: string | null;
}
/** the instance's logs/latest.log, for when the launcher didn't watch the run */
export interface LatestLog {
  lines: GameLogLine[];
  /** unix millis the game last wrote it */
  modified: number;
}
/** an older run's log or a crash report, picked in the LOG tab */
export interface LogFile {
  /** `logs/2026-10-01-1.log.gz`, `crash-reports/crash-….txt` */
  path: string;
  modified: number;
  size: number;
}

// ── browser fixtures ───────────────────────────────────────────────────────

const HOUR = 3600_000;
/** a flat placeholder image of a given shape, for the preview's gallery */
const shot = (w: number, h: number, fill: string, label: string) =>
  `data:image/svg+xml;utf8,${encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><rect width="100%" height="100%" fill="${fill}"/><text x="50%" y="50%" fill="#fff" font-size="${Math.round(w / 12)}" text-anchor="middle" dominant-baseline="middle">${label} ${w}×${h}</text></svg>`,
  )}`;

const fixtures: Record<string, unknown> = {
  list_profiles: [
    {
      id: 'p-dusk',
      name: 'DUSK 1.21.11',
      gameVersion: '1.21.11',
      loader: 'fabric',
      loaderVersion: '0.16.10',
      createdAt: Date.now() - 40 * HOUR,
      lastPlayed: Date.now() - 2 * HOUR,
      playSecs: 41 * 3600 + 17 * 60,
      jvmArgs: [],
      resolution: [1280, 720],
      server: 'play.dusk.gg',
      modCount: 38,
      art: 0x9e3779b9,
      memoryMb: null,
      javaPath: null,
      group: null,
      icon: null,
      pack: { projectId: 'm-fabu', versionId: 'v-3', versionNumber: '7.1.3' },
    },
    {
      id: 'p-vanilla',
      name: 'VANILLA 1.21',
      gameVersion: '1.21.4',
      loader: 'vanilla',
      loaderVersion: null,
      createdAt: Date.now() - 400 * HOUR,
      lastPlayed: Date.now() - 26 * HOUR,
      playSecs: 35 * 60,
      jvmArgs: [],
      resolution: [1280, 720],
      server: null,
      modCount: 0,
      art: 0x1b873593,
      memoryMb: null,
      javaPath: null,
      group: null,
      icon: null,
      pack: null,
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
    { id: '26.4-snapshot-2', type: 'snapshot', releaseAt: '2026-09-29' },
    { id: '26.3', type: 'release', releaseAt: '2026-09-15' },
    { id: '26.2', type: 'release', releaseAt: '2026-06-16' },
    { id: '26.1.2', type: 'release', releaseAt: '2026-04-09' },
    { id: '26.1.1', type: 'release', releaseAt: '2026-04-01' },
    { id: '26.1', type: 'release', releaseAt: '2026-03-24' },
    { id: '1.21.11', type: 'release', releaseAt: '2025-12-09' },
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
  recent_plays: [
    {
      profileId: 'p-dusk',
      profileName: 'DUSK 1.21.11',
      kind: 'server',
      id: 'mc.tryzwork.app',
      name: 'zWork SMP',
      lastPlayed: Date.now() - 2 * HOUR,
      gamemode: 'survival',
    },
    {
      profileId: 'p-dusk',
      profileName: 'DUSK 1.21.11',
      kind: 'world',
      id: 'New World',
      name: 'New World',
      lastPlayed: Date.now() - 20 * HOUR,
      gamemode: 'creative',
    },
    {
      profileId: 'p-vanilla',
      profileName: 'VANILLA 1.21',
      kind: 'world',
      id: 'Hardcore',
      name: 'Hardcore run',
      lastPlayed: Date.now() - 26 * HOUR,
      gamemode: 'survival',
    },
  ] satisfies RecentPlay[],
  mod_problems: {
    missing: [
      { id: 'cloth-config2', label: 'Cloth Config API', project: 'cloth-config', neededBy: ['FastQuit', 'More Culling', 'Gamma Utils', 'Combat Hitboxes'], disabledFile: null },
    ],
    mismatched: [
      {
        name: 'Fabric API',
        version: '0.116.0+1.21.11',
        file: 'fabric-api-0.116.0+1.21.11.jar',
        neededBy: [{ name: 'Sodium Extra', file: 'sodium-extra.jar', wants: '>=0.120.0' }],
      },
    ],
    duplicates: [{ name: 'Lithium', keep: 'lithium-fabric-0.15.1+mc1.21.11.jar', keepVersion: '0.15.1+mc1.21.11', extra: ['lithium-fabric-0.15.0+mc1.21.11.jar'] }],
    clashes: [{ name: 'Sodium', file: 'sodium-fabric-0.6.13+mc1.21.11.jar', other: 'Iris', otherVersion: '1.8.0+1.21.11', otherFile: 'iris-fabric-1.8.0+mc1.21.11.jar' }],
  } satisfies ModProblems,
  list_logs: [
    { path: 'logs/2026-10-06-2.log.gz', modified: Date.now() - 50 * 3600_000, size: 18_400 },
    { path: 'crash-reports/crash-2026-10-06_14.21.07-client.txt', modified: Date.now() - 52 * 3600_000, size: 9_100 },
    { path: 'logs/2026-10-06-1.log.gz', modified: Date.now() - 53 * 3600_000, size: 22_900 },
  ] satisfies LogFile[],
  read_log: {
    modified: Date.now() - 52 * 3600_000,
    lines: [
      { line: '---- Minecraft Crash Report ----', stream: 'out' },
      { line: 'Description: Rendering overlay', stream: 'out' },
      { line: 'java.lang.NullPointerException: Cannot invoke "net.minecraft.class_310.method_1551()"', stream: 'err' },
      { line: '\tat net.minecraft.class_329.method_1753(class_329.java:118)', stream: 'err' },
    ],
  } satisfies LatestLog,
  read_latest_log: {
    modified: Date.now() - 3 * 3600_000,
    lines: [
      { line: '[18:02:11] [main/INFO]: Loading Minecraft 1.21.11 with Fabric Loader 0.17.2', stream: 'out' },
      { line: '[18:02:14] [Render thread/INFO]: Setting user: Steve', stream: 'out' },
      { line: '[18:02:20] [Render thread/ERROR]: Failed to load texture: dusk:textures/gui/x.png', stream: 'err' },
      { line: 'java.io.FileNotFoundException: dusk:textures/gui/x.png', stream: 'err' },
      { line: '\tat net.minecraft.class_3300.method_14486(class_3300.java:42)', stream: 'err' },
      { line: '[18:02:21] [Render thread/INFO]: Created: 1024x1024x4 minecraft:textures/atlas/blocks.png-atlas', stream: 'out' },
      { line: '[18:40:02] [Render thread/INFO]: Stopping!', stream: 'out' },
    ],
  },
  list_javas: [
    {
      path: '/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home/bin/java',
      version: '25.0.4',
      major: 25,
      vendor: 'Homebrew',
      bundled: false,
    },
    {
      path: '/Users/you/Library/Application Support/FasterLauncher/runtimes/java-runtime-delta/jre.bundle/Contents/Home/bin/java',
      version: '21.0.7',
      major: 21,
      vendor: null,
      bundled: true,
    },
    {
      path: '/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home/bin/java',
      version: '17.0.14',
      major: 17,
      vendor: 'Azul Systems, Inc.',
      bundled: false,
    },
  ] satisfies JavaInstall[],
  list_servers: [
    { name: 'zWork SMP', address: 'mc.tryzwork.app', icon: null },
    { name: 'zWork PVP', address: 'pvp.tryzwork.app', icon: null },
    { name: 'old realm', address: 'gone.example.net', icon: null },
  ] satisfies SavedServer[],
  list_worlds: [
    {
      name: 'New World', modified: Date.now() - 2 * HOUR, size: 184_320_000, icon: null, levelName: 'New World',
      gameMode: 'survival', hardcore: true, cheats: false, version: '1.21.11', seed: '-4172144997902289642',
    },
    {
      name: 'Skyblock', modified: Date.now() - 90 * HOUR, size: 41_900_000, icon: null, levelName: '§6Skyblock §7v2',
      gameMode: 'creative', hardcore: false, cheats: true, version: '26.2', seed: '9154800003455089103',
    },
  ] satisfies World[],
  list_datapacks: [
    { file: 'Terralith.zip', description: 'Explore Nature’s Wonders', enabled: true, folder: false, size: 2_480_000, icon: null },
    { file: 'graves', description: 'Keeps your items in a grave', enabled: true, folder: true, size: 38_000, icon: null },
    { file: 'one-player-sleep.zip', description: 'Only one player needs to sleep', enabled: false, folder: false, size: 9_200, icon: null },
  ] satisfies Datapack[],
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
    discordAvailable: false,
  } satisfies AppInfo,
  get_settings: {
    theme: 'overworld',
    volume: 0.6,
    muted: false,
    reduceMotion: false,
    fpsCap: 30,
    selectedProfileId: 'p-dusk',
    memoryMb: 4096,
    defaultJvmArgs: '-Xms2G -Xmx4G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M',
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
    discordRpc: true,
    onPlay: 'keep',
    notifyFriendsOnline: true,
    notifyMessages: true,
    clock24h: false,
    warnOnLinks: true,
    syncClientSettings: true,
    offlineName: '',
  } satisfies Settings,
};

/** Commands that change real state: refused outright in the browser. */
const sideEffects = new Set([
  'install_and_launch',
  'upload_log',
  'add_server',
  'remove_server',
  'reveal_crash_report',
  'install_modpack',
  'install_modpack_version',
  'install_content_version_to_profile',
  'begin_login',
  'begin_code_login',
  'begin_reconsent_login',
  'upload_skin',
  'import_skin',
  'show_in_folder',
  'open_data_dir',
  'import_local_content',
  'import_content_paths',
  'import_world',
  'import_world_paths',
  'set_datapack_enabled',
  'remove_datapack',
  'add_datapacks',
  'add_datapack_paths',
  'install_content_to_profile',
  'import_mrpack',
  'export_instance',
  'install_bundled_pack',
  'import_wallpaper',
  'remove_wallpaper',
  'set_account_cape',
  'switch_account',
  'remove_account',
  'delete_screenshot',
  'reveal_screenshot',
  'delete_recording',
  'backup_world',
  'delete_world',
  'reveal_recording',
  'send_screenshot',
  'notify',
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

/* two of the preview's jars have something newer on "Modrinth" */
const previewUpdates = new Map<string, Omit<ContentUpdate, 'filename'>>([
  ['sodium-fabric-0.6.13+mc1.21.11.jar', { projectId: 'AANobbMI', currentVersion: 'mc1.21.11-0.6.13', versionId: 'u-sodium', versionNumber: 'mc1.21.11-0.7.2' }],
  ['lithium-fabric-0.15.0+mc1.21.11.jar', { projectId: 'gvQqBUqZ', currentVersion: 'mc1.21.11-0.15.0', versionId: 'u-lithium', versionNumber: 'mc1.21.11-0.15.1' }],
]);

function previewFolder(profileId: string, kind: string): ProfileMod[] {
  const key = `${profileId}/${kind}`;
  let list = previewContent.get(key);
  if (!list) {
    list =
      kind === 'mod' && profileId === 'p-dusk'
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
let previewReferral: Referral = {
  code: 'DUSK2PRV',
  referredBy: null,
  referralPaid: false,
  canClaim: true,
  invited: 2,
  paid: 1,
  referrerReward: 500,
  refereeReward: 250,
  coins: 0,
};
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
/** what each preview friend owns, so gift mode can mark it */
const previewGifted = new Map<string, Set<number>>();
const pq = (id: string, title: string, progress: number, goal: number, unit: string, coins: number, claimed = false): Quest => ({
  id, title, progress, goal, unit, coins, done: progress >= goal, claimed,
});
const previewQuests: Quests = {
  coins: 0,
  daily: [
    pq('play_30', 'Play for 30 minutes', 30, 30, 'min', 50),
    pq('friend_20', 'Play 20 minutes with a friend', 7, 20, 'min', 80),
    pq('chat_5', 'Send 5 messages to friends', 5, 5, 'count', 40, true),
  ],
  weekly: [
    pq('w_play_300', 'Play for 5 hours', 142, 300, 'min', 200),
    pq('w_days_5', 'Play on 5 different days', 3, 5, 'count', 200),
    pq('w_gift_1', 'Gift a friend a cosmetic', 0, 1, 'count', 150),
  ],
  streak: { days: 4, todayMinutes: 15, needMinutes: 15, coins: 35, done: true, claimed: false, cycle: [20, 25, 30, 35, 40, 50, 100], cycleDay: 3 },
  achievements: [
    pq('first_hour', 'First hour', 60, 60, 'min', 100, true),
    pq('hours_10', '10 hours played', 412, 600, 'min', 250),
    pq('first_friend', 'Made a friend', 1, 1, 'count', 100),
    pq('streak_7', '7-day streak', 4, 7, 'count', 200),
  ],
  claimable: 0,
  dailyReset: 5 * 3600 + 12 * 60,
  weeklyReset: 3 * 86400 + 4 * 3600,
};
function previewQuestsNow(): Quests {
  const q = previewQuests;
  const all = [...q.daily, ...q.weekly, ...q.achievements];
  q.claimable = all.filter((x) => x.done && !x.claimed).length + (q.streak.done && !q.streak.claimed ? 1 : 0);
  q.coins = previewCoins;
  return structuredClone(q);
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
/* the friends pane in the preview: one friend in game, one online, one
   offline, one incoming request, and a conversation with an unread line.
   Times are unix seconds, as the service sends them. */
const previewNow = () => Math.floor(Date.now() / 1000);
let previewFriends: Friend[] = [
  { uuid: 'friend-nocturne', username: 'Nocturne', online: true, playing: '1.21.4', server: 'play.dusk-smp.net', lastSeen: previewNow(), unread: 1 },
  { uuid: 'friend-sable', username: 'Sable', online: true, lastSeen: previewNow(), unread: 0 },
  { uuid: 'friend-ashen', username: 'Ashen', online: false, lastSeen: previewNow() - 5 * 3600, unread: 0 },
];
let previewFriendRequests: FriendRequests = {
  incoming: [{ id: 1, uuid: 'friend-wrenlight', username: 'Wrenlight', createdAt: previewNow() - 3600 }],
  outgoing: [],
};
let previewNextRequestId = 2;
const previewMessages = new Map<string, ChatMessage[]>();
let previewNextMessageId = 14;
let previewPrivacy: Privacy = { appearOffline: false, shareActivity: true, friendRequests: 'everyone' };
let previewBlocked: BlockedPlayer[] = [];
let previewOutfits: Outfit[] = [
  { id: 1, name: 'Night out', loadout: { cape: 5, accessories: [16] }, createdAt: previewNow() - 86400 },
];
let previewNextOutfitId = 2;
function previewConversation(uuid: string): ChatMessage[] {
  let list = previewMessages.get(uuid);
  if (!list) {
    const t = previewNow();
    list =
      uuid === 'friend-nocturne'
        ? [
            { id: 1, fromUuid: uuid, toUuid: '', body: 'hey, you around?', sentAt: t - 40 * 60, kind: 'text' },
            { id: 2, fromUuid: '', toUuid: uuid, body: 'yeah just got on', sentAt: t - 38 * 60, kind: 'text' },
            { id: 3, fromUuid: uuid, toUuid: '', body: 'hop on 1.21.4, we\'re building the base — map at https://dusk-smp.net/map', sentAt: t - 3 * 60, kind: 'text' },
            { id: 4, fromUuid: uuid, toUuid: '', body: 'Join me on play.dusk-smp.net', sentAt: t - 2 * 60, kind: 'invite', meta: { server: 'play.dusk-smp.net', version: '1.21.4' } },
            { id: 5, fromUuid: uuid, toUuid: '', body: 'Sent you Aurora Cape as a gift', sentAt: t - 60, kind: 'gift', meta: { item: 5, name: 'Aurora Cape', kind: 'cape' } },
            ...['Ember Halo', 'Night Wings', 'Lantern', 'Star Trail', 'Moth Cape', 'Crown', 'Fox Ears', 'Comet'].map(
              (n, i): ChatMessage => ({ id: 6 + i, fromUuid: '', toUuid: uuid, body: `Sent ${n} as a gift`, sentAt: t - 50 + i, kind: 'gift', meta: { item: 20 + i, name: n, kind: 'accessory' } }),
            ),
          ]
        : [];
    previewMessages.set(uuid, list);
  }
  return list;
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
  if (cmd === 'install_dusk_essentials') return 16 as T;
  if (cmd === 'release_art') {
    const art = (version: string, fill: string, blurb: string): ReleaseArt => ({
      version,
      kind: 'release',
      imageUrl: shot(540, 540, fill, version),
      blurb,
    });
    return [
      art('26.3', '#3f5a2e', 'Wilderness Bound is out now in Minecraft Java Edition'),
      art('26.2', '#4a2f5a', 'Chaos Cubed has landed in Minecraft Java Edition'),
      art('26.1', '#2f4a5a', 'Ready or not, here comes the Tiny Takeover drop'),
      art('1.21.11', '#5a3a2f', 'Today Mounts of Mayhem charges into Minecraft'),
    ] as T;
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
  if (cmd === 'get_referral') return structuredClone(previewReferral) as T;
  if (cmd === 'claim_referral') {
    if (!previewReferral.canClaim) throw new Error('you already entered a referral code');
    previewReferral = { ...previewReferral, referredBy: 'Preview Friend', canClaim: false };
    return structuredClone(previewReferral) as T;
  }
  if (cmd === 'list_account_capes') return [] as T;
  if (cmd === 'redeem_code') {
    if (String(args?.code).trim().toLowerCase() === 'cracked') {
      return { granted: 0, coins: previewCoins, unlocked: 'offline' } as T;
    }
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
  if (cmd === 'ping_server') {
    await new Promise((r) => setTimeout(r, 400));
    const address = String(args?.address);
    if (address.startsWith('gone.')) throw new Error("Can't reach the server");
    const pvp = address.startsWith('pvp.');
    const word = (t: string, from: number[], to: number[]) =>
      [...t].map((ch, i) => {
        const k = t.length > 1 ? i / (t.length - 1) : 0;
        const c = from.map((f, j) => Math.round(f + (to[j] - f) * k));
        return { text: ch, color: '#' + c.map((v) => v.toString(16).padStart(2, '0')).join('') };
      });
    return {
      motd: [
        { text: '» ', color: '#555555' },
        ...word(pvp ? 'zWork PVP' : 'zWork SMP', [255, 61, 61], [255, 193, 74]),
        { text: ' «', color: '#555555' },
        { text: '\n', color: null },
        ...word(pvp ? 'Practice • Duels • Climb the ranks' : 'Economy • Shops • TPA • RTP', [255, 138, 138], [255, 210, 122]),
      ],
      online: pvp ? 3 : 12,
      max: pvp ? 100 : 20,
      version: 'Velocity 1.7.2-26.3',
      pingMs: pvp ? 18 : 15,
      icon: null,
    } satisfies ServerStatus as T;
  }
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
  if (cmd === 'check_content_updates') {
    return previewFolder(String(args?.profileId), String(args?.kind)).flatMap((m) => {
      const u = previewUpdates.get(m.filename);
      return u ? [{ filename: m.filename, ...u }] : [];
    }) as T;
  }
  if (cmd === 'update_profile_content') {
    // swap the row for the new version's file; enabled state carries over
    const list = previewFolder(String(args?.profileId), String(args?.kind));
    const row = list.find((m) => m.filename === args?.filename);
    const u = previewUpdates.get(String(args?.filename));
    if (!row || !u) throw new Error('that file is no longer in the instance');
    const fresh = { ...row, filename: row.filename.replace(u.currentVersion.replace(/^mc[\d.]+-/, ''), u.versionNumber.replace(/^mc[\d.]+-/, '')) };
    list.splice(list.indexOf(row), 1, fresh);
    previewUpdates.delete(row.filename);
    return structuredClone(fresh) as T;
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
  if (cmd === 'rename_world') {
    const w = (fixtures.list_worlds as World[]).find((x) => x.name === args?.name);
    if (!w) throw new Error('world not found');
    const name = String(args?.levelName ?? '').trim();
    if (!name) throw new Error("The name can't be empty.");
    w.levelName = name;
    return undefined as T;
  }
  if (cmd === 'update_modpack') {
    const p = (fixtures.list_profiles as Profile[]).find((x) => x.id === args?.profileId);
    if (!p?.pack) throw new Error("This instance didn't come from a Modrinth pack.");
    const v = (fixtures.list_modpack_versions as ProjectVersion[]).find((x) => x.id === args?.versionId);
    if (!v) throw new Error('version not found');
    p.gameVersion = v.gameVersions[0] ?? p.gameVersion;
    p.pack = { ...p.pack, versionId: v.id, versionNumber: v.versionNumber };
    return structuredClone(p) as T;
  }
  if (cmd === 'set_profile_icon' || cmd === 'clear_profile_icon') {
    const p = (fixtures.list_profiles as Profile[]).find((x) => x.id === args?.profileId);
    if (!p) throw new Error('profile not found');
    p.icon = cmd === 'set_profile_icon' ? shot(96, 96, '#3b6b4a', 'icon') : null;
    return structuredClone(p) as T;
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
    if (patch.group !== undefined) p.group = patch.group.trim().slice(0, 32) || null;
    return structuredClone(p) as T;
  }
  if (cmd === 'repair_profile') return undefined as T;
  if (cmd === 'take_launch_request') return null as T;
  if (cmd === 'scan_external_instances')
    return [
      {
        source: 'Prism Launcher',
        name: 'Fabulously Optimized',
        gameVersion: '1.21.11',
        loader: 'fabric',
        loaderVersion: '0.16.10',
        path: '/mock/prism/fo/.minecraft',
        mods: 74,
        worlds: 3,
        blocked: null,
        group: 'PvP',
      },
      {
        source: 'CurseForge',
        name: 'All the Mods 10',
        gameVersion: '1.21.1',
        loader: 'forge',
        loaderVersion: '52.0.1',
        path: '/mock/curseforge/atm10',
        mods: 412,
        worlds: 1,
        blocked: 'Dusk runs Fabric and NeoForge, not Forge',
        group: null,
      },
      {
        source: 'Minecraft Launcher',
        name: 'Minecraft',
        gameVersion: '1.21.11',
        loader: 'vanilla',
        loaderVersion: null,
        path: '/mock/.minecraft',
        mods: 0,
        worlds: 8,
        blocked: null,
        group: null,
      },
    ] as T;
  if (cmd === 'duplicate_profile') {
    const list = fixtures.list_profiles as Profile[];
    const src = list.find((x) => x.id === args?.id);
    if (!src) throw new Error('profile not found');
    let name = `${src.name} (copy)`;
    for (let n = 2; list.some((x) => x.name === name); n++) name = `${src.name} (copy ${n})`;
    const copy: Profile = { ...structuredClone(src), id: `p-copy-${Date.now()}`, name, createdAt: Date.now(), lastPlayed: null };
    list.push(copy);
    previewFolder(copy.id, 'mod').push(...structuredClone(previewFolder(src.id, 'mod')));
    return structuredClone(copy) as T;
  }
  if (cmd === 'delete_profile') {
    const list = fixtures.list_profiles as Profile[];
    const i = list.findIndex((x) => x.id === args?.id);
    if (i >= 0) list.splice(i, 1);
    return undefined as T;
  }
  if (cmd === 'social_heartbeat') {
    return {
      requests: previewFriendRequests.incoming.length,
      unread: previewFriends.reduce((n, f) => n + f.unread, 0),
      online: previewFriends.filter((f) => f.online).length,
    } as T;
  }
  if (cmd === 'list_friends') return structuredClone(previewFriends) as T;
  if (cmd === 'remove_friend') {
    previewFriends = previewFriends.filter((f) => f.uuid !== args?.uuid);
    return structuredClone(previewFriends) as T;
  }
  if (cmd === 'list_friend_requests') return structuredClone(previewFriendRequests) as T;
  if (cmd === 'send_friend_request') {
    const username = String(args?.username ?? '').trim();
    if (!username) throw new Error('Enter a username first.');
    previewFriendRequests = {
      ...previewFriendRequests,
      outgoing: [
        ...previewFriendRequests.outgoing,
        { id: previewNextRequestId++, uuid: `preview-${username.toLowerCase()}`, username, createdAt: previewNow() },
      ],
    };
    return structuredClone(previewFriendRequests) as T;
  }
  if (cmd === 'accept_friend_request') {
    const req = previewFriendRequests.incoming.find((r) => r.id === args?.id);
    if (req) {
      previewFriends = [
        ...previewFriends,
        { uuid: req.uuid, username: req.username, online: false, lastSeen: previewNow() - 600, unread: 0 },
      ];
    }
    previewFriendRequests = {
      incoming: previewFriendRequests.incoming.filter((r) => r.id !== args?.id),
      outgoing: previewFriendRequests.outgoing.filter((r) => r.id !== args?.id),
    };
    return structuredClone(previewFriendRequests) as T;
  }
  if (cmd === 'decline_friend_request') {
    previewFriendRequests = {
      incoming: previewFriendRequests.incoming.filter((r) => r.id !== args?.id),
      outgoing: previewFriendRequests.outgoing.filter((r) => r.id !== args?.id),
    };
    return structuredClone(previewFriendRequests) as T;
  }
  if (cmd === 'get_friend_profile') {
    const uuid = String(args?.uuid);
    const friend = previewFriends.find((f) => f.uuid === uuid);
    if (!friend) throw new Error('not friends');
    const owned = previewGifted.get(uuid) ?? new Set([5, 16]);
    previewGifted.set(uuid, owned);
    return {
      uuid, username: friend.username, online: friend.online, lastSeen: friend.lastSeen, cape: 5, accessories: [16],
      owned: [...owned], badges: ['First hour', 'Made a friend'],
    } as T;
  }
  if (cmd === 'get_messages') {
    const uuid = String(args?.uuid);
    const afterId = Number(args?.afterId ?? 0);
    const batch = previewConversation(uuid).filter((m) => m.id > afterId);
    if (batch.length) previewFriends = previewFriends.map((f) => (f.uuid === uuid ? { ...f, unread: 0 } : f));
    return structuredClone(batch) as T;
  }
  if (cmd === 'send_message') {
    const uuid = String(args?.uuid);
    const body = String(args?.body ?? '').trim();
    if (!body) throw new Error('Type a message first.');
    const msg: ChatMessage = { id: previewNextMessageId++, fromUuid: '', toUuid: uuid, body, sentAt: previewNow(), kind: 'text' };
    previewConversation(uuid).push(msg);
    return structuredClone(msg) as T;
  }
  if (cmd === 'send_invite') {
    const uuid = String(args?.uuid);
    const server = String(args?.server ?? '').trim();
    if (!server) throw new Error('Join a server first — there\'s nothing to invite them to.');
    const msg: ChatMessage = {
      id: previewNextMessageId++, fromUuid: '', toUuid: uuid, body: `Join me on ${server}`, sentAt: previewNow(),
      kind: 'invite', meta: { server, version: (args?.version as string) ?? null },
    };
    previewConversation(uuid).push(msg);
    return structuredClone(msg) as T;
  }
  if (cmd === 'get_chat_image') return shot(640, 360, '#2a2346', 'screenshot') as T;
  if (cmd === 'game_activity') return null as T;
  if (cmd === 'get_privacy') return structuredClone(previewPrivacy) as T;
  if (cmd === 'set_privacy') {
    previewPrivacy = structuredClone(args?.privacy as Privacy);
    return structuredClone(previewPrivacy) as T;
  }
  if (cmd === 'list_blocked') return structuredClone(previewBlocked) as T;
  if (cmd === 'block_player') {
    const f = previewFriends.find((x) => x.uuid === args?.uuid);
    if (f) previewBlocked = [...previewBlocked, { uuid: f.uuid, username: f.username }];
    previewFriends = previewFriends.filter((x) => x.uuid !== args?.uuid);
    return structuredClone(previewBlocked) as T;
  }
  if (cmd === 'unblock_player') {
    previewBlocked = previewBlocked.filter((b) => b.uuid !== args?.uuid);
    return structuredClone(previewBlocked) as T;
  }
  if (cmd === 'gift_cosmetic') {
    const store = await previewStore();
    const item = store.items.find((i) => i.id === args?.id);
    if (!item) throw new Error('no such cosmetic');
    if (previewCoins < item.price) throw new Error(`Not enough coins — ${item.name} costs ${item.price}.`);
    const uuid = String(args?.uuid);
    if (previewGifted.get(uuid)?.has(item.id)) throw new Error('they already own that');
    previewCoins -= item.price;
    previewGifted.set(uuid, new Set([...(previewGifted.get(uuid) ?? [5, 16]), item.id]));
    const message: ChatMessage = {
      id: previewNextMessageId++, fromUuid: '', toUuid: uuid, body: `Sent you ${item.name} as a gift`, sentAt: previewNow(),
      kind: 'gift', meta: { item: item.id, name: item.name, kind: item.kind },
    };
    previewConversation(uuid).push(message);
    return { coins: previewCoins, message } as T;
  }
  if (cmd === 'get_quests') return previewQuestsNow() as T;
  if (cmd === 'claim_quest') {
    const q = previewQuests;
    const id = String(args?.id);
    let paid = 0;
    for (const x of [...q.daily, ...q.weekly, ...q.achievements]) {
      if (x.done && !x.claimed && (id === 'all' || id === x.id)) {
        x.claimed = true;
        paid += x.coins;
      }
    }
    if (q.streak.done && !q.streak.claimed && (id === 'all' || id === 'streak')) {
      q.streak.claimed = true;
      paid += q.streak.coins;
    }
    previewCoins += paid;
    return { paid, quests: previewQuestsNow() } as T;
  }
  if (cmd === 'list_outfits') return structuredClone(previewOutfits) as T;
  if (cmd === 'save_outfit') {
    const name = String(args?.name ?? '').trim();
    if (!name) throw new Error('Outfit names are 1–32 characters.');
    const loadout = structuredClone(args?.loadout as Loadout);
    const existing = previewOutfits.find((o) => o.name === name);
    if (existing) existing.loadout = loadout;
    else previewOutfits = [...previewOutfits, { id: previewNextOutfitId++, name, loadout, createdAt: previewNow() }];
    return structuredClone(previewOutfits) as T;
  }
  if (cmd === 'delete_outfit') {
    previewOutfits = previewOutfits.filter((o) => o.id !== args?.id);
    return structuredClone(previewOutfits) as T;
  }
  if (cmd === 'list_accounts') {
    return [
      { uuid: 'acc-preview', username: 'Preview', active: true },
      { uuid: 'acc-alt', username: 'PreviewAlt', active: false },
    ] as T;
  }
  if (cmd === 'list_screenshots') {
    const t = Date.now();
    return [
      { path: shot(1280, 720, '#3a2f5a', 'base at dusk'), name: '2026-09-25_21.14.03.png', profileId: 'p-dusk', profileName: 'DUSK 1.21.11', takenAt: t - 3 * HOUR, size: 2_400_000, favorite: true },
      { path: shot(1280, 720, '#1f4a3a', 'jungle'), name: '2026-09-24_18.02.44.png', profileId: 'p-dusk', profileName: 'DUSK 1.21.11', takenAt: t - 27 * HOUR, size: 3_100_000, favorite: false },
      { path: shot(1280, 720, '#4a2f1f', 'nether hub'), name: '2026-09-20_12.40.10.png', profileId: 'p-dusk', profileName: 'DUSK 1.21.11', takenAt: t - 6 * 24 * HOUR, size: 2_800_000, favorite: false },
    ] satisfies Screenshot[] as T;
  }
  if (cmd === 'set_screenshot_favorite') return undefined as T;
  if (cmd === 'list_recordings') {
    const t = Date.now();
    return [
      { path: '/preview/clips/clip_2026-09-25_21-20-11.mcpr', name: 'clip_2026-09-25_21-20-11.mcpr', kind: 'clip', profileId: 'p-dusk', profileName: 'DUSK 1.21.11', recordedAt: t - 2 * HOUR, size: 1_900_000, durationMs: 30_000, server: 'play.example.net', mcVersion: '1.21.11' },
      { path: '/preview/replays/replay_2026-09-24_17-40-02.mcpr', name: 'replay_2026-09-24_17-40-02.mcpr', kind: 'replay', profileId: 'p-dusk', profileName: 'DUSK 1.21.11', recordedAt: t - 28 * HOUR, size: 41_000_000, durationMs: 1_512_000, server: '', mcVersion: '1.21.11' },
    ] satisfies Recording[] as T;
  }
  if (cmd === 'recording_thumb') return shot(480, 270, '#2a2346', 'recording') as T;
  if (cmd === 'get_public_skin') return null as T;
  if (cmd === 'import_external_instance') {
    const ext = (await invoke<ExternalInstance[]>('scan_external_instances')).find((e) => e.path === args?.path);
    if (!ext || ext.blocked) throw new Error('That instance can’t be imported.');
    return invoke<T>('create_profile', { name: ext.name, gameVersion: ext.gameVersion, loader: ext.loader });
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
      group: null,
      icon: null,
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
  /** a new instance with this one's settings and folder (not its logs or screenshots) */
  duplicateProfile: (id: string) => invoke<Profile>('duplicate_profile', { id }),
  repairProfile: (id: string) => invoke<void>('repair_profile', { id }),
  /** a desktop shortcut that starts this instance; resolves to where it went */
  createShortcut: (profileId: string) => invoke<string>('create_shortcut', { profileId }),
  /** picker; null when cancelled */
  setProfileIcon: (profileId: string) => invoke<Profile | null>('set_profile_icon', { profileId }),
  clearProfileIcon: (profileId: string) => invoke<Profile>('clear_profile_icon', { profileId }),
  /** the instance a desktop shortcut started the launcher for, once */
  takeLaunchRequest: () => invoke<string | null>('take_launch_request'),
  launch: (profileId: string) => invoke<void>('install_and_launch', { profileId }),
  /** launch straight onto a server (a friend's, an invite) without saving it on the instance */
  joinServer: (profileId: string, server: string) =>
    invoke<void>('install_and_launch', { profileId, joinServer: server }),
  /** launch straight into one of the instance's singleplayer worlds */
  playWorld: (profileId: string, world: string, host = false) =>
    invoke<void>('install_and_launch', { profileId, openWorld: world, host }),
  /** launch the instance that recorded it, straight into this clip or replay */
  watchRecording: (profileId: string, path: string) =>
    invoke<void>('install_and_launch', { profileId, watchReplay: path }),
  gameActivity: () => invoke<GameActivity | null>('game_activity'),
  stopGame: () => invoke<void>('stop_game'),
  /** the game the backend is running right now, or null — the UI's source
   *  of truth for PLAY / STOP whenever the event stream may have been missed */
  gameState: async () => (await invoke<GameState | null | undefined>('game_state')) ?? null,
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
  /** fill a Fabric instance with Dusk Essentials for its version; resolves to how many files were added */
  installDuskEssentials: (profileId: string) => invoke<number>('install_dusk_essentials', { profileId }),
  /** Mojang's picture for each release, for the version cards */
  releaseArt: () => invoke<ReleaseArt[]>('release_art'),
  /** Fabric API plus Sodium, Lithium, FerriteCore & co., skipping any already there; resolves to how many were added */
  installPerformanceMods: (profileId: string) => invoke<number>('install_performance_mods', { profileId }),
  /** native picker → installs a .mrpack as a new instance (null if cancelled) */
  importMrpack: () => invoke<Profile | null>('import_mrpack'),
  /** move a pack instance to another of its pack's versions */
  updateModpack: (profileId: string, versionId: string) => invoke<Profile>('update_modpack', { profileId, versionId }),
  /** instances Prism, CurseForge, the Modrinth App and Mojang's launcher have here */
  scanExternalInstances: () => invoke<ExternalInstance[]>('scan_external_instances'),
  /** copy one of those in as a new instance (mods, config, worlds, packs, options) */
  importExternalInstance: (path: string) => invoke<Profile>('import_external_instance', { path }),
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
  /** the name the game lists the world under; its folder keeps its name */
  renameWorld: (profileId: string, name: string, levelName: string) =>
    invoke<void>('rename_world', { profileId, name, levelName }),
  /** the instance's multiplayer list, in the game's order */
  readLatestLog: (profileId: string) => invoke<LatestLog | null>('read_latest_log', { profileId }),
  /** older logs and crash reports, newest first */
  listLogs: (profileId: string) => invoke<LogFile[]>('list_logs', { profileId }),
  readLog: (profileId: string, path: string) => invoke<LatestLog>('read_log', { profileId, path }),
  /** Puts the log on mclo.gs (tokens and the home folder taken out); resolves to its link. */
  uploadLog: (text: string) => invoke<string>('upload_log', { text }),
  listJavas: () => invoke<JavaInstall[]>('list_javas'),
  listServers: (profileId: string) => invoke<SavedServer[]>('list_servers', { profileId }),
  /** Resolves to the new list. Waits for the instance's game to close. */
  addServer: (profileId: string, name: string, address: string) =>
    invoke<SavedServer[]>('add_server', { profileId, name, address }),
  removeServer: (profileId: string, name: string, address: string) =>
    invoke<SavedServer[]>('remove_server', { profileId, name, address }),
  /** MOTD, players and ping, resolving SRV records like the game does */
  pingServer: (address: string) => invoke<ServerStatus>('ping_server', { address }),
  /** the worlds and servers played most recently, across every instance */
  recentPlays: (limit?: number) => invoke<RecentPlay[]>('recent_plays', { limit }),
  /** zip a world into the instance's backups/ folder; resolves to the zip's name */
  backupWorld: (profileId: string, name: string) => invoke<string>('backup_world', { profileId, name }),
  /** move a world to the OS trash */
  deleteWorld: (profileId: string, name: string) => invoke<void>('delete_world', { profileId, name }),
  listContent: (profileId: string, kind: ContentKind) =>
    invoke<ProfileMod[]>('list_profile_content', { profileId, kind }),
  /** which Modrinth projects the folder already holds, by file hash */
  lookupContent: (profileId: string, kind: ContentKind) =>
    invoke<InstalledProject[]>('lookup_profile_content', { profileId, kind }),
  /** installed files with a newer Modrinth version for this instance */
  checkContentUpdates: (profileId: string, kind: ContentKind) =>
    invoke<ContentUpdate[]>('check_content_updates', { profileId, kind }),
  /** swap an installed file for `versionId` of its project, keeping it on/off */
  updateContent: (profileId: string, kind: ContentKind, filename: string, versionId: string) =>
    invoke<ProfileMod>('update_profile_content', { profileId, kind, filename, versionId }),
  setContentEnabled: (profileId: string, kind: ContentKind, filename: string, enabled: boolean) =>
    invoke<ProfileMod>('set_content_enabled', { profileId, kind, filename, enabled }),
  removeContent: (profileId: string, kind: ContentKind, filename: string) =>
    invoke<void>('remove_profile_content', { profileId, kind, filename }),
  /** files dropped on an instance: each lands in the folder it belongs in */
  importContentPaths: (profileId: string, paths: string[]) =>
    invoke<{ added: ProfileMod[]; worlds: string[]; skipped: string[] }>('import_content_paths', {
      profileId,
      paths,
    }),
  /** pick world zips → saves/; resolves to the names they landed under */
  importWorld: (profileId: string) => invoke<ImportedWorlds>('import_world', { profileId }),
  /** dropped world zips / folders → saves/ */
  importWorldPaths: (profileId: string, paths: string[]) =>
    invoke<ImportedWorlds>('import_world_paths', { profileId, paths }),
  /** a world's datapacks/: zips (on, or off as `.zip.disabled`) and pack folders */
  listDatapacks: (profileId: string, world: string) => invoke<Datapack[]>('list_datapacks', { profileId, world }),
  setDatapackEnabled: (profileId: string, world: string, file: string, enabled: boolean) =>
    invoke<void>('set_datapack_enabled', { profileId, world, file, enabled }),
  /** to the trash; `enabled` says which name it has on disk */
  removeDatapack: (profileId: string, world: string, file: string, enabled: boolean) =>
    invoke<void>('remove_datapack', { profileId, world, file, enabled }),
  /** pick zips to add; `added` is empty when the picker is cancelled */
  addDatapacks: (profileId: string, world: string) => invoke<ImportedWorlds>('add_datapacks', { profileId, world }),
  addDatapackPaths: (profileId: string, world: string, paths: string[]) =>
    invoke<ImportedWorlds>('add_datapack_paths', { profileId, world, paths }),
  /** native file picker (several at once) → copies .jar mods / .zip packs
   *  into the kind's folder; empty if cancelled */
  importLocalContent: (profileId: string, kind: ContentKind) =>
    invoke<ProfileMod[]>('import_local_content', { profileId, kind }),
  searchContent: (kind: ContentKind, query: string, gameVersion: string, loader: string, limit = 20) =>
    invoke<ModHit[]>('search_content', { kind, query, gameVersion, loader, limit }),
  /** what the instance's Fabric mods need that mods/ doesn't have */
  modProblems: (profileId: string) => invoke<ModProblems>('mod_problems', { profileId }),
  /** Linux: installed by scripts/install-linux.sh, so UPDATE re-runs the script */
  scriptInstalled: () => invoke<boolean>('script_installed'),
  /** re-run the install script, replacing this install with the latest release */
  scriptUpdate: () => invoke<void>('script_update'),
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
  loginWithCode: () => invoke<Account>('begin_code_login'),
  /** stop the sign-in in flight (closes the sign-in window if open) */
  cancelLogin: () => invoke<void>('cancel_login'),
  logout: () => invoke<void>('logout'),
  listAccounts: () => invoke<SavedAccount[]>('list_accounts'),
  switchAccount: (uuid: string) => invoke<Account>('switch_account', { uuid }),
  removeAccount: (uuid: string) => invoke<void>('remove_account', { uuid }),
  notify: (title: string, body: string) => invoke<void>('notify', { title, body }),
  listScreenshots: () => invoke<Screenshot[]>('list_screenshots'),
  setScreenshotFavorite: (path: string, favorite: boolean) =>
    invoke<void>('set_screenshot_favorite', { path, favorite }),
  /** moves it to the OS trash */
  deleteScreenshot: (path: string) => invoke<void>('delete_screenshot', { path }),
  revealScreenshot: (path: string) => invoke<void>('reveal_screenshot', { path }),
  listRecordings: () => invoke<Recording[]>('list_recordings'),
  /** the recording's thumbnail as a data URL, null when it has none */
  recordingThumb: (path: string) => invoke<string | null>('recording_thumb', { path }),
  /** moves it to the OS trash */
  deleteRecording: (path: string) => invoke<void>('delete_recording', { path }),
  revealRecording: (path: string) => invoke<void>('reveal_recording', { path }),
  revealCrashReport: (profileId: string, report: string) =>
    invoke<void>('reveal_crash_report', { profileId, report }),
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
  /** this account's referral code + status; creates the code on first ask */
  getReferral: () => invoke<Referral>('get_referral'),
  /** name who invited you (new accounts only, once); pays on first launch */
  claimReferral: (code: string) => invoke<Referral>('claim_referral', { code }),
  /** the Mojang capes on the signed-in account */
  listAccountCapes: () => invoke<AccountCape[]>('list_account_capes'),
  /** show a Mojang cape on the account, or hide it with null — no re-login */
  setAccountCape: (id: string | null) => invoke<void>('set_account_cape', { id }),
  /** spend coins on a catalog item; resolves to the refreshed store */
  buyCosmetic: (id: number) => invoke<Store>('buy_cosmetic', { id }),

  // ── friends / chat ──
  /** keep this account online for friends and read the badge counts;
   *  `playing` is the running game's version, or null */
  socialHeartbeat: (playing: string | null) => invoke<SocialSummary>('social_heartbeat', { playing }),
  listFriends: () => invoke<Friend[]>('list_friends'),
  removeFriend: (uuid: string) => invoke<Friend[]>('remove_friend', { uuid }),
  listFriendRequests: () => invoke<FriendRequests>('list_friend_requests'),
  /** send a request by username; auto-accepts if they already asked first */
  sendFriendRequest: (username: string) => invoke<FriendRequests>('send_friend_request', { username }),
  acceptFriendRequest: (id: number) => invoke<FriendRequests>('accept_friend_request', { id }),
  declineFriendRequest: (id: number) => invoke<FriendRequests>('decline_friend_request', { id }),
  getFriendProfile: (uuid: string) => invoke<FriendProfile>('get_friend_profile', { uuid }),
  /** poll for messages newer than `afterId` (0 = the whole history, capped at 200) */
  getMessages: (uuid: string, afterId = 0) => invoke<ChatMessage[]>('get_messages', { uuid, afterId }),
  sendMessage: (uuid: string, body: string) => invoke<ChatMessage>('send_message', { uuid, body }),
  /** a friend's skin as a data URL, fetched from Mojang's public session server */
  getPublicSkin: (uuid: string) => invoke<string | null>('get_public_skin', { uuid }),
  sendInvite: (uuid: string, server: string, version: string | null) =>
    invoke<ChatMessage>('send_invite', { uuid, server, version }),
  sendScreenshot: (uuid: string, path: string) => invoke<ChatMessage>('send_screenshot', { uuid, path }),
  /** a chat image as a data URL */
  getChatImage: (id: string) => invoke<string>('get_chat_image', { id }),
  getPrivacy: () => invoke<Privacy>('get_privacy'),
  setPrivacy: (privacy: Privacy) => invoke<Privacy>('set_privacy', { privacy }),
  listBlocked: () => invoke<BlockedPlayer[]>('list_blocked'),
  blockPlayer: (uuid: string) => invoke<BlockedPlayer[]>('block_player', { uuid }),
  unblockPlayer: (uuid: string) => invoke<BlockedPlayer[]>('unblock_player', { uuid }),
  giftCosmetic: (uuid: string, id: number) => invoke<{ coins: number; message: ChatMessage }>('gift_cosmetic', { uuid, id }),
  getQuests: () => invoke<Quests>('get_quests'),
  /** a quest id, "streak", or "all" */
  claimQuest: (id: string) => invoke<{ paid: number; quests: Quests }>('claim_quest', { id }),
  listOutfits: () => invoke<Outfit[]>('list_outfits'),
  saveOutfit: (name: string, loadout: Loadout) => invoke<Outfit[]>('save_outfit', { name, loadout }),
  deleteOutfit: (id: number) => invoke<Outfit[]>('delete_outfit', { id }),
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

/** total time played: "35 min", "3 h 12 min", "128 h" */
export function playtime(secs: number): string {
  const m = Math.floor(secs / 60);
  if (m < 60) return `${Math.max(1, m)} min`;
  const h = Math.floor(m / 60);
  return h >= 100 || m % 60 === 0 ? `${h} h` : `${h} h ${m % 60} min`;
}

export function loaderLabel(p: Profile) {
  if (p.loader === 'neoforge') return 'NeoForge';
  return p.loader.charAt(0).toUpperCase() + p.loader.slice(1);
}

export function fmtBytes(n: number): string {
  if (n >= 1_073_741_824) return `${(n / 1_073_741_824).toFixed(1)} GB`;
  if (n >= 1_048_576) return `${(n / 1_048_576).toFixed(1)} MB`;
  if (n >= 1024) return `${Math.round(n / 1024)} KB`;
  return `${n} B`;
}
