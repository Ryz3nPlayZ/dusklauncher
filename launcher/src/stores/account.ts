import { create } from 'zustand';
import { api, type AccountDto } from '../lib/tauri';
import { useUi } from './ui';

interface AccountState {
  account: AccountDto | null;
  loaded: boolean;
  /** the Mojang account's active skin as a data URL (null when none) */
  skinUrl: string | null;
  load: () => Promise<void>;
  login: () => Promise<void>;
  logout: () => Promise<void>;
  /** refetch the account's active skin (after upload/reset) */
  refreshSkin: () => Promise<void>;
}

async function fetchSkin(): Promise<string | null> {
  try {
    return await api.getAccountSkin();
  } catch {
    return null;
  }
}

export const useAccount = create<AccountState>((set) => ({
  account: null,
  loaded: false,
  skinUrl: null,

  load: async () => {
    try {
      const account = await api.getCurrentAccount();
      set({ account, loaded: true, skinUrl: account ? await fetchSkin() : null });
    } catch {
      set({ loaded: true });
    }
  },

  login: async () => {
    try {
      await api.beginLogin();
      set({ account: await api.getCurrentAccount(), skinUrl: await fetchSkin() });
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
    set({ account: null, skinUrl: null });
  },

  refreshSkin: async () => {
    set({ skinUrl: await fetchSkin() });
  },
}));
