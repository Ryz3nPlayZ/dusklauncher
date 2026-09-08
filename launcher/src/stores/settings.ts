import { create } from 'zustand';
import { api, isTauri, type SettingsDto } from '../lib/tauri';
import { sceneLoop, playerLoop } from '../lib/fps';
import { configureSfx, primeSfx } from '../sfx/sfx';

export const DEFAULT_SETTINGS: SettingsDto = {
  theme: 'overworld',
  volume: 0.6,
  muted: false,
  reduceMotion: false,
  fpsCap: 30,
  selectedProfileId: null,
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
};

interface SettingsState {
  settings: SettingsDto;
  loaded: boolean;
  load: () => Promise<void>;
  update: (patch: Partial<SettingsDto>) => void;
}

let persistTimer: ReturnType<typeof setTimeout> | null = null;

function applySideEffects(s: SettingsDto) {
  const root = document.documentElement;
  root.dataset.theme = s.theme;
  configureSfx(s.volume, s.muted);
  sceneLoop.setFps(s.reduceMotion ? 0 : s.fpsCap);
  playerLoop.setFps(s.reduceMotion ? 0 : s.fpsCap);
  sceneLoop.pause('reduce-motion');
  if (!s.reduceMotion) {
    sceneLoop.resume('reduce-motion');
    playerLoop.resume('reduce-motion');
  } else {
    playerLoop.pause('reduce-motion');
  }
}

export const useSettings = create<SettingsState>((set, get) => ({
  settings: DEFAULT_SETTINGS,
  loaded: false,

  load: async () => {
    try {
      const s = await api.getSettings();
      const merged = { ...DEFAULT_SETTINGS, ...s };
      applySideEffects(merged);
      set({ settings: merged, loaded: true });
    } catch {
      applySideEffects(DEFAULT_SETTINGS);
      set({ loaded: true });
    }
    // first user gesture unlocks WebAudio
    window.addEventListener('pointerdown', primeSfx, { once: true });
  },

  update: (patch) => {
    const next = { ...get().settings, ...patch };
    set({ settings: next });
    applySideEffects(next);
    if (!isTauri) return;
    if (persistTimer) clearTimeout(persistTimer);
    persistTimer = setTimeout(() => {
      void api.setSettings(next).catch(() => {});
    }, 350);
  },
}));
