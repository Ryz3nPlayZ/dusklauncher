export const ROUTES = ['home', 'instances', 'cosmetics', 'store', 'quests', 'profile', 'settings'] as const;
export type Route = (typeof ROUTES)[number];
