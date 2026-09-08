import { create } from 'zustand';
import { playSfx } from '../sfx/sfx';

export type View = 'home' | 'instances' | 'settings' | 'skins';

export interface Toast {
  id: number;
  kind: 'info' | 'success' | 'error';
  text: string;
}

interface UiState {
  view: View;
  setView: (v: View) => void;
  toasts: Toast[];
  toast: (text: string, kind?: Toast['kind']) => void;
  dismissToast: (id: number) => void;
  accountsOpen: boolean;
  setAccountsOpen: (open: boolean) => void;
  drawerOpen: boolean;
  toggleDrawer: () => void;
  /** pending PvP theme wipe: target theme + nonce to retrigger */
  pvpWipe: { toNether: boolean; nonce: number } | null;
  startPvpWipe: (toNether: boolean) => void;
  clearPvpWipe: () => void;
}

let nextToastId = 1;

export const useUi = create<UiState>((set, get) => ({
  view: 'home',
  setView: (v) => {
    if (v !== get().view) playSfx('tab');
    set({ view: v });
  },
  toasts: [],
  toast: (text, kind = 'info') => {
    const id = nextToastId++;
    set({ toasts: [...get().toasts, { id, kind, text }] });
    setTimeout(() => get().dismissToast(id), kind === 'error' ? 6500 : 4000);
  },
  dismissToast: (id) => set({ toasts: get().toasts.filter((t) => t.id !== id) }),
  accountsOpen: false,
  setAccountsOpen: (open) => set({ accountsOpen: open }),
  drawerOpen: false,
  toggleDrawer: () => set({ drawerOpen: !get().drawerOpen }),
  pvpWipe: null,
  startPvpWipe: (toNether) => set({ pvpWipe: { toNether, nonce: Date.now() } }),
  clearPvpWipe: () => set({ pvpWipe: null }),
}));
