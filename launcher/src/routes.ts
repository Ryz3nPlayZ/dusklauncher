export const ROUTES = ['home', 'instances', 'cosmetics', 'store', 'profile', 'settings'] as const;
export type Route = (typeof ROUTES)[number];
