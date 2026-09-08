/**
 * Typed IPC layer. One place that knows every command, event and DTO on the
 * wire (camelCase both sides — Rust DTOs use #[serde(rename_all = "camelCase")]).
 *
 * When running outside Tauri (plain `vite dev` in a browser) a lightweight
 * mock backend keeps the whole UI navigable for fast frontend iteration.
 */

import { invoke as tauriInvoke } from '@tauri-apps/api/core';
import { listen as tauriListen, type UnlistenFn } from '@tauri-apps/api/event';

// ── DTOs ───────────────────────────────────────────────────────────────────

export type Loader = 'vanilla' | 'fabric';

export interface ProfileDto {
  id: string;
  name: string;
  gameVersion: string;
  loader: Loader;
  loaderVersion: string | null;
  createdAt: number;
  lastPlayed: number | null;
  jvmArgs: string[];
  resolution: [number, number];
  server: string | null;
  modCount: number;
  art: number | null; // seed for procedural card art
}

export interface ProfilePatch {
  name?: string;
  gameVersion?: string;
  loader?: Loader;
  loaderVersion?: string | null;
  jvmArgs?: string[];
  resolution?: [number, number];
  server?: string | null;
}

export interface VersionInfo {
  id: string;
  type: 'release' | 'snapshot' | 'old_beta' | 'old_alpha';
  releasedAt: string;
}

export type ThemeName = 'nether' | 'overworld';

export type FpsTarget = 30 | 60 | 0;

export interface SettingsDto {
  theme: ThemeName;
  volume: number; // 0..1
  muted: boolean;
  reduceMotion: boolean;
  fpsCap: 30 | 60 | 0;
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
  authClientId: string; // override for the shipped Azure client_id; '' = use shipped default
  customBackground: string; // '' = animated scene; otherwise image/video path
}

export interface AppInfoDto {
  launcherVersion: string;
  os: string;
  dataDir: string;
}

export interface AccountDto {
  username: string;
  uuid: string;
  authenticated: boolean; // true after Microsoft login (session in keychain + session.json)
}

// Events emitted without a command round-trip:
// - 'auth-state' { state: 'waitingForBrowser' | 'finishing' | 'signedIn' } during login

export interface ProfileModDto {
  filename: string;
  size: number;
  enabled: boolean; // false = stored as <name>.<ext>.disabled
}

/** Installable content kind: mods, resource packs, shaders. */
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

export interface SkinDto {
  name: string;
  addedAt: number;
  selected: boolean;
}

export type LaunchStage = 'libraries' | 'client' | 'assets' | 'java' | 'mods' | 'launching';

export interface LaunchProgressEvent {
  profileId: string;
  stage: LaunchStage;
  done: number;
  total: number;
  doneBytes: number;
  totalBytes: number;
}

export interface GameLogEvent {
  line: string;
  stream: 'out' | 'err';
}

export interface GameLogBatch {
  lines: GameLogEvent[];
}

export interface GameStateEvent {
  profileId: string;
  state: 'starting' | 'running' | 'exited';
  code: number | null;
}

// Modrinth ──────────────────────────────────────────────────────────────────

export interface ModpackHit {
  id: string;
  slug: string;
  title: string;
  description: string;
  author: string;
  downloads: number;
  follows: number;
  iconUrl: string | null;
  updatedAt: string;
  categories: string[];
  versions: string[];
  loaders: string[];
}

export interface ModpackVersionDto {
  id: string;
  name: string;
  gameVersions: string[];
  loaders: string[];
  published: string | null;
}

export interface ModpackSearchResponse {
  hits: ModpackHit[];
  total: number;
  page: number;
  pageSize: number;
}

export interface ModpackFacets {
  categories: string[];
  versions: string[];
  loaders: string[];
}

// ── mock backend (browser dev mode) ────────────────────────────────────────

export const isTauri =
  typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

const now = Date.now();
const mockProfiles: ProfileDto[] = [
  {
    id: 'p1',
    name: 'PERFORMIUM',
    gameVersion: '26.2',
    loader: 'fabric',
    loaderVersion: '0.17.2',
    createdAt: now - 8 * 3600e3,
    lastPlayed: now - 8 * 3600e3,
    jvmArgs: ['-Xms2G', '-Xmx4G'],
    resolution: [1280, 720],
    server: null,
    modCount: 14,
    art: 1337,
  },
  {
    id: 'p2',
    name: 'DUSK',
    gameVersion: '1.21.11',
    loader: 'vanilla',
    loaderVersion: null,
    createdAt: now - 9 * 86400e3,
    lastPlayed: now - 9 * 86400e3,
    jvmArgs: ['-Xms2G', '-Xmx4G'],
    resolution: [1280, 720],
    server: 'play.example.net',
    modCount: 0,
    art: 424242,
  },
];

let mockSettings: SettingsDto = {
  theme: 'nether',
  volume: 0.15,
  muted: false,
  reduceMotion: false,
  fpsCap: 30,
  selectedProfileId: 'p1',
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
  customBackground: '',
};

function mockInvoke(cmd: string, args: Record<string, unknown> = {}): unknown {
  switch (cmd) {
    case 'list_profiles':
      return mockProfiles;
    case 'create_profile':
      return mockProfiles[0];
    case 'update_profile':
      return mockProfiles[0];
    case 'delete_profile':
      return null;
    case 'list_versions':
      return ['26.2', '26.1.2', '26.1.1', '26.1', '1.21.11', '1.21.10'].map((id, i) => ({
        id,
        type: 'release',
        releasedAt: new Date(now - i * 7 * 86400e3).toISOString(),
      }));
    case 'get_settings':
      return mockSettings;
    case 'set_settings':
      mockSettings = { ...mockSettings, ...(args.settings as SettingsDto) };
      return mockSettings;
    case 'get_app_info':
      return { launcherVersion: '0.2.0-dev', os: 'Mac OS X (browser)', dataDir: '/tmp/dusk' };
    case 'get_current_account':
      return { username: 'Player', uuid: '00000000-0000-0000-0000-000000000000', authenticated: false };
    case 'begin_login':
      return { username: 'Player', uuid: '00000000-0000-0000-0000-000000000000', authenticated: true };
    case 'begin_reconsent_login':
      return { username: 'Player', uuid: '00000000-0000-0000-0000-000000000000', authenticated: true };
    case 'list_profile_mods':
      return [];
    case 'list_profile_content':
      return [];
    case 'search_mods':
      return [];
    case 'list_skins':
      return [];
    case 'search_modpacks':
      return mockModpacks(args);
    case 'list_modpack_versions':
      return [
        { id: 'v1', name: '1.0.0', gameVersions: ['26.2'], loaders: ['fabric'], published: new Date(now).toISOString() },
        { id: 'v0', name: '0.9.0', gameVersions: ['1.21.11'], loaders: ['fabric'], published: new Date(now - 30 * 86400e3).toISOString() },
      ];
    default:
      return null;
  }
}

function mockModpacks(args: Record<string, unknown>): ModpackSearchResponse {
  const hits: ModpackHit[] = [
    ['Fabulously Optimized', 'robotkoer', 16_000_000, 4200, 'A featureful, stable Minecraft modpack with vanilla visuals and performance at its core.'],
    ['Cobblemon Official Modpack', 'Cobblemon Team', 1_200_000, 8100, 'The official Cobblemon modpack — catch, train and battle Pokémon in Minecraft.'],
    ['Simply Optimized', 'RaptorClaws', 2_800_000, 1900, 'Performance-focused modpack with minimal changes to vanilla gameplay.'],
    ['Adrenaline', 'SettingDust', 900_000, 660, 'A lightweight performance pack built on Sodium with extra tweaks.'],
    ['Prominence II RPG', 'Hypnotic', 1_900_000, 5300, 'An action-adventure RPG modpack with quests, bosses and dungeons.'],
    ['Better MC', 'LunaPixelStudios', 3_100_000, 4400, 'An expanded vanilla-plus adventure with new biomes, bosses and tech.'],
  ].map(([title, author, downloads, follows, description], i) => ({
    id: `mock-${i}`,
    slug: String(title).toLowerCase().replace(/[^a-z0-9]+/g, '-'),
    title: String(title),
    description: String(description),
    author: String(author),
    downloads: Number(downloads),
    follows: Number(follows),
    iconUrl: null,
    updatedAt: new Date(now - i * 86400e3).toISOString(),
    categories: i % 2 ? ['adventure', 'magic'] : ['optimization', 'utility'],
    versions: ['26.2', '1.21.11'],
    loaders: ['fabric'],
  }));
  return { hits, total: 703, page: Number(args.page ?? 0), pageSize: Number(args.pageSize ?? 20) };
}

// ── invoke / listen wrappers ───────────────────────────────────────────────

export async function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  if (!isTauri) return mockInvoke(cmd, args) as T;
  return tauriInvoke<T>(cmd, args);
}

export function listen<T>(event: string, handler: (payload: T) => void): Promise<UnlistenFn> {
  if (!isTauri) return Promise.resolve(() => {});
  return tauriListen<T>(event, (e) => handler(e.payload));
}

// ── command surface ────────────────────────────────────────────────────────

export const api = {
  listProfiles: () => invoke<ProfileDto[]>('list_profiles'),
  createProfile: (name: string, gameVersion: string, loader: Loader, server?: string) =>
    invoke<ProfileDto>('create_profile', { name, gameVersion, loader, server: server ?? null }),
  updateProfile: (id: string, patch: ProfilePatch) =>
    invoke<ProfileDto>('update_profile', { id, patch }),
  deleteProfile: (id: string) => invoke<void>('delete_profile', { id }),
  listVersions: () => invoke<VersionInfo[]>('list_versions'),
  installAndLaunch: (profileId: string) => invoke<void>('install_and_launch', { profileId }),
  stopGame: () => invoke<void>('stop_game'),
  beginLogin: () => invoke<AccountDto>('begin_login'),
  beginReconsentLogin: () => invoke<AccountDto>('begin_reconsent_login'),
  logout: () => invoke<void>('logout'),
  listProfileMods: (profileId: string) => invoke<ProfileModDto[]>('list_profile_mods', { profileId }),
  listProfileContent: (profileId: string, kind: ContentKind) =>
    invoke<ProfileModDto[]>('list_profile_content', { profileId, kind }),
  removeProfileContent: (profileId: string, filename: string, kind: ContentKind) =>
    invoke<void>('remove_profile_content', { profileId, filename, kind }),
  setContentEnabled: (profileId: string, filename: string, enabled: boolean, kind: ContentKind) =>
    invoke<ProfileModDto>('set_content_enabled', { profileId, filename, enabled, kind }),
  removeProfileMod: (profileId: string, filename: string) =>
    invoke<void>('remove_profile_mod', { profileId, filename }),
  setModEnabled: (profileId: string, filename: string, enabled: boolean) =>
    invoke<ProfileModDto>('set_mod_enabled', { profileId, filename, enabled }),
  importLocalMod: (profileId: string) =>
    invoke<ProfileModDto | null>('import_local_mod', { profileId }),
  searchMods: (query: string, gameVersion: string, loader: string, limit: number) =>
    invoke<ModHit[]>('search_mods', { query, gameVersion, loader, limit }),
  searchContent: (query: string, gameVersion: string, loader: string, limit: number, kind: ContentKind) =>
    invoke<ModHit[]>('search_content', { query, gameVersion, loader, limit, kind }),
  installContentToProfile: (profileId: string, projectId: string, kind: ContentKind) =>
    invoke<ProfileModDto>('install_content_to_profile', { profileId, projectId, kind }),
  installModToProfile: (profileId: string, projectId: string) =>
    invoke<ProfileModDto>('install_mod_to_profile', { profileId, projectId }),
  installBundledClientMod: (profileId: string) =>
    invoke<ProfileModDto | null>('install_bundled_client_mod', { profileId }),
  getCurrentAccount: () => invoke<AccountDto | null>('get_current_account'),
  getSettings: () => invoke<SettingsDto>('get_settings'),
  setSettings: (settings: SettingsDto) => invoke<SettingsDto>('set_settings', { settings }),
  getAppInfo: () => invoke<AppInfoDto>('get_app_info'),
  searchModpacks: (
    query: string,
    facets: ModpackFacets,
    page: number,
    pageSize: number,
    sort: string,
  ) => invoke<ModpackSearchResponse>('search_modpacks', { query, facets, page, pageSize, sort }),
  installModpack: (id: string) => invoke<ProfileDto>('install_modpack', { id }),
  listModpackVersions: (id: string) =>
    invoke<ModpackVersionDto[]>('list_modpack_versions', { id }),
  installModpackVersion: (id: string, versionId: string) =>
    invoke<ProfileDto>('install_modpack_version', { id, versionId }),
  listSkins: () => invoke<SkinDto[]>('list_skins'),
  importSkin: () => invoke<SkinDto | null>('import_skin'),
  renameSkin: (oldName: string, newName: string) =>
    invoke<void>('rename_skin', { oldName, newName }),
  deleteSkin: (name: string) => invoke<void>('delete_skin', { name }),
  setSelectedSkin: (name: string) => invoke<void>('set_selected_skin', { name }),
  readSkin: (name: string) => invoke<string>('read_skin', { name }), // data URL
  uploadSelectedSkin: (variant: 'classic' | 'slim') =>
    invoke<void>('upload_selected_skin', { variant }),
};
