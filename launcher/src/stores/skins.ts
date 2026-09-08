import { create } from 'zustand';
import { api, type SkinDto } from '../lib/tauri';
import { useAccount } from './account';
import { useUi } from './ui';

interface SkinsState {
  skins: SkinDto[];
  /** data URL per skin name (skins are a few KB each) */
  dataUrls: Record<string, string>;
  /** data URL of the selected skin (or null → bundled default) */
  selectedSkin: string | null;
  loaded: boolean;
  load: () => Promise<void>;
  importSkin: () => Promise<SkinDto | null>;
  select: (name: string) => Promise<void>;
  rename: (oldName: string, newName: string) => Promise<boolean>;
  remove: (name: string) => Promise<void>;
  /** upload a wardrobe skin to the signed-in Mojang account */
  apply: (name: string, variant: 'classic' | 'slim') => Promise<boolean>;
}

async function fetchUrls(list: SkinDto[]): Promise<Record<string, string>> {
  const entries = await Promise.all(
    list.map(async (s) => {
      try {
        return [s.name, await api.readSkin(s.name)] as const;
      } catch {
        return [s.name, ''] as const;
      }
    }),
  );
  return Object.fromEntries(entries);
}

export const useSkins = create<SkinsState>((set, get) => ({
  skins: [],
  dataUrls: {},
  selectedSkin: null,
  loaded: false,

  load: async () => {
    try {
      const skins = await api.listSkins();
      const dataUrls = await fetchUrls(skins);
      const sel = skins.find((s) => s.selected);
      set({ skins, dataUrls, selectedSkin: sel ? dataUrls[sel.name] || null : null, loaded: true });
    } catch {
      set({ loaded: true });
    }
  },

  importSkin: async () => {
    try {
      const skin = await api.importSkin();
      if (!skin) return null; // dialog cancelled
      const skins = [...get().skins.filter((s) => s.name !== skin.name), skin];
      const dataUrls = { ...(await fetchUrls(skins)) };
      const sel = skins.find((s) => s.selected);
      set({ skins, dataUrls, selectedSkin: sel ? dataUrls[sel.name] || null : null });
      useUi.getState().toast(`Imported skin "${skin.name}"`, 'success');
      return skin;
    } catch (e) {
      useUi.getState().toast(e instanceof Error ? e.message : String(e), 'error');
      return null;
    }
  },

  select: async (name) => {
    await api.setSelectedSkin(name);
    const skins = get().skins.map((s) => ({ ...s, selected: s.name === name }));
    set({ skins, selectedSkin: get().dataUrls[name] || null });
  },

  rename: async (oldName, newName) => {
    try {
      await api.renameSkin(oldName, newName);
      const skins = get().skins.map((s) =>
        s.name === oldName ? { ...s, name: newName } : s,
      );
      const dataUrls = { ...get().dataUrls };
      dataUrls[newName] = dataUrls[oldName] ?? '';
      delete dataUrls[oldName];
      const sel = skins.find((s) => s.selected);
      set({ skins, dataUrls, selectedSkin: sel ? dataUrls[sel.name] || null : null });
      return true;
    } catch (e) {
      useUi.getState().toast(e instanceof Error ? e.message : String(e), 'error');
      return false;
    }
  },

  remove: async (name) => {    await api.deleteSkin(name);
    const skins = get().skins.filter((s) => s.name !== name);
    const dataUrls = { ...get().dataUrls };
    delete dataUrls[name];
    const sel = skins.find((s) => s.selected);
    set({ skins, dataUrls, selectedSkin: sel ? dataUrls[sel.name] || null : null });
  },

  apply: async (name, variant) => {
    try {
      await api.uploadSkin(name, variant);
      await useAccount.getState().refreshSkin();
      useUi.getState().toast(`Applied "${name}" to your account`, 'success');
      return true;
    } catch (e) {
      useUi.getState().toast(e instanceof Error ? e.message : String(e), 'error');
      return false;
    }
  },
}));
