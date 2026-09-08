import { create } from 'zustand';
import { api, type Loader, type ProfileDto, type ProfilePatch } from '../lib/tauri';
import { useSettings } from './settings';

interface ProfilesState {
  profiles: ProfileDto[];
  loaded: boolean;
  load: () => Promise<void>;
  create: (name: string, gameVersion: string, loader: Loader, server?: string) => Promise<ProfileDto>;
  update: (id: string, patch: ProfilePatch) => Promise<void>;
  remove: (id: string) => Promise<void>;
  selected: () => ProfileDto | null;
  select: (id: string) => void;
}

export const useProfiles = create<ProfilesState>((set, get) => ({
  profiles: [],
  loaded: false,

  load: async () => {
    try {
      const profiles = await api.listProfiles();
      set({ profiles, loaded: true });
    } catch {
      set({ loaded: true });
    }
  },

  create: async (name, gameVersion, loader, server) => {
    const p = await api.createProfile(name, gameVersion, loader, server);
    set({ profiles: [...get().profiles, p] });
    if (!useSettings.getState().settings.selectedProfileId) {
      useSettings.getState().update({ selectedProfileId: p.id });
    }
    return p;
  },

  update: async (id, patch) => {
    const updated = await api.updateProfile(id, patch);
    set({ profiles: get().profiles.map((p) => (p.id === id ? updated : p)) });
  },

  remove: async (id) => {
    await api.deleteProfile(id);
    const profiles = get().profiles.filter((p) => p.id !== id);
    set({ profiles });
    if (useSettings.getState().settings.selectedProfileId === id) {
      useSettings.getState().update({ selectedProfileId: profiles[0]?.id ?? null });
    }
  },

  selected: () => {
    const { profiles } = get();
    const wanted = useSettings.getState().settings.selectedProfileId;
    return profiles.find((p) => p.id === wanted) ?? profiles[0] ?? null;
  },

  select: (id) => useSettings.getState().update({ selectedProfileId: id }),
}));
