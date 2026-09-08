import { create } from 'zustand';
import { api, listen, type GameLogBatch, type GameStateEvent, type LaunchProgressEvent } from '../lib/tauri';
import { playSfx } from '../sfx/sfx';
import { useUi } from './ui';

export type LaunchPhase = 'idle' | 'preparing' | 'downloading' | 'running' | 'error';

const LOG_CAP = 400;

interface LaunchState {
  phase: LaunchPhase;
  profileId: string | null;
  progress: LaunchProgressEvent | null;
  log: GameLogBatch['lines'];
  error: string | null;
  launch: (profileId: string) => Promise<void>;
  stop: () => Promise<void>;
  bindEvents: () => void; // call once at app start
}

export const useLaunch = create<LaunchState>((set, get) => ({
  phase: 'idle',
  profileId: null,
  progress: null,
  log: [],
  error: null,

  launch: async (profileId) => {
    if (get().phase === 'downloading' || get().phase === 'preparing') return;
    set({ phase: 'preparing', profileId, error: null, progress: null });
    playSfx('launch');
    try {
      await api.installAndLaunch(profileId);
      set({ phase: 'running' });
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      set({ phase: 'error', error: msg });
      playSfx('error');
      useUi.getState().toast(`Launch failed: ${msg}`, 'error');
    }
  },

  stop: async () => {
    try {
      await api.stopGame();
      set({ phase: 'idle', progress: null });
    } catch {
      /* ignore */
    }
  },

  bindEvents: () => {
    void listen<LaunchProgressEvent>('launch-progress', (p) => {
      set({ progress: p, phase: get().phase === 'preparing' ? 'downloading' : get().phase });
    });
    void listen<GameLogBatch>('game-log', (batch) => {
      if (!batch?.lines?.length) return;
      const log = [...get().log, ...batch.lines];
      if (log.length > LOG_CAP) log.splice(0, log.length - LOG_CAP);
      set({ log });
    });
    void listen<GameStateEvent>('game-state', (ev) => {
      if (ev.state === 'running') {
        set({ phase: 'running' });
        useUi.getState().toast('Game is running', 'success');
      } else if (ev.state === 'exited') {
        set({ phase: 'idle', progress: null });
        if (ev.code === 0) playSfx('success');
      }
    });
  },
}));
