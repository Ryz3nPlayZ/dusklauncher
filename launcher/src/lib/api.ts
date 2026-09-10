/**
 * Tauri bridge. Every view talks to the backend through here.
 *
 * Outside Tauri (plain `vite dev` in a browser, used for visual work on the
 * UI) there is no IPC, so a small honest fixture set answers instead — the
 * views can't tell the difference, and nothing silently pretends a real
 * launch happened.
 */
import defaultSkin from '../assets/skins/dusk-knight.png';
import { invoke as tauriInvoke } from '@tauri-apps/api/core';
import { listen as tauriListen } from '@tauri-apps/api/event';

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
}

export interface Account {
  username: string;
  uuid: string;
  authenticated: boolean;
}

export interface Skin {
  name: string;
  addedAt: number;
  selected: boolean;
}

export interface Version {
  id: string;
  type: string;
  releaseAt: string;
}

export interface AppInfo {
  launcherVersion: string;
  os: string;
  dataDir: string;
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

// ── browser fixtures ───────────────────────────────────────────────────────

const HOUR = 3600_000;
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
    },
  ] satisfies Profile[],
  get_current_account: null,
  list_skins: [
    { name: 'dusk-knight', addedAt: Date.now() - 3 * HOUR, selected: true },
    { name: 'dusk-knight-alt', addedAt: Date.now() - HOUR, selected: false },
  ] satisfies Skin[],
  read_skin: defaultSkin,
  get_account_skin: null,
  list_versions: [] satisfies Version[],
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
  'begin_login',
  'begin_reconsent_login',
  'upload_skin',
  'import_skin',
  'show_in_folder',
]);

export async function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  if (isTauri) return tauriInvoke<T>(cmd, args);
  if (sideEffects.has(cmd)) {
    throw new Error(`"${cmd}" needs the desktop app — this is the browser preview.`);
  }
  if (cmd in fixtures) return structuredClone(fixtures[cmd]) as T;
  if (cmd === 'set_settings') return (args?.settings as T) ?? (fixtures.get_settings as T);
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
  updateProfile: (id: string, patch: Record<string, unknown>) =>
    invoke<Profile>('update_profile', { id, patch }),
  deleteProfile: (id: string) => invoke<void>('delete_profile', { id }),
  launch: (profileId: string) => invoke<void>('install_and_launch', { profileId }),
  stopGame: () => invoke<void>('stop_game'),
  listVersions: () => invoke<Version[]>('list_versions'),
  showInFolder: (path: string) => invoke<void>('show_in_folder', { path }),

  getSettings: () => invoke<Settings>('get_settings'),
  setSettings: (settings: Settings) => invoke<Settings>('set_settings', { settings }),

  getAccount: () => invoke<Account | null>('get_current_account'),
  login: () => invoke<Account>('begin_login'),
  logout: () => invoke<void>('logout'),
  getAppInfo: () => invoke<AppInfo>('get_app_info'),

  listSkins: () => invoke<Skin[]>('list_skins'),
  readSkin: (name: string) => invoke<string>('read_skin', { name }),
  importSkin: () => invoke<Skin | null>('import_skin'),
  deleteSkin: (name: string) => invoke<void>('delete_skin', { name }),
  selectSkin: (name: string) => invoke<void>('set_selected_skin', { name }),
  uploadSkin: (name: string, variant: 'classic' | 'slim') =>
    invoke<void>('upload_skin', { name, variant }),
  accountSkin: () => invoke<string | null>('get_account_skin'),
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
