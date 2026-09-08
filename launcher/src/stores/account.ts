import { create } from 'zustand';
import { api, type AccountDto } from '../lib/tauri';
import { useUi } from './ui';

interface AccountState {
  account: AccountDto | null;
  loaded: boolean;
  load: () => Promise<void>;
  login: () => Promise<void>;
  logout: () => Promise<void>;
}

export const useAccount = create<AccountState>((set) => ({
  account: null,
  loaded: false,

  load: async () => {
    try {
      set({ account: await api.getCurrentAccount(), loaded: true });
    } catch {
      set({ loaded: true });
    }
  },

  login: async () => {
    try {
      await api.beginLogin();
      set({ account: await api.getCurrentAccount() });
      useUi.getState().toast('Signed in', 'success');
    } catch (e) {
      // expected until the Azure app registration exists — surface honestly
      useUi.getState().toast(e instanceof Error ? e.message : String(e), 'error');
    }
  },

  logout: async () => {
    try {
      await api.logout();
    } catch {
      /* logout never blocks the UI */
    }
    set({ account: null });
  },
}));
