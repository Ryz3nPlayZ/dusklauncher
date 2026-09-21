/**
 * Self-update over Tauri's updater plugin.
 *
 * Releases carry a minisign signature (see `plugins.updater.pubkey` in
 * tauri.conf.json); the plugin refuses anything the private key didn't sign.
 * That is separate from OS code signing — an unsigned bundle updates fine,
 * because the download never passes through a browser and so never picks up
 * the quarantine / mark-of-the-web flag that triggers Gatekeeper/SmartScreen.
 *
 * In the browser preview there is no updater, so a fixture stands in and the
 * "install" is a visible fake so the button can be styled.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { isTauri } from './api';

export type UpdateStatus =
  | { kind: 'idle' }
  | { kind: 'checking' }
  | { kind: 'available'; version: string; notes: string | null }
  | { kind: 'downloading'; version: string; pct: number | null }
  | { kind: 'ready'; version: string }
  | { kind: 'error'; message: string };

const RECHECK_MS = 6 * 60 * 60 * 1000;

const loadUpdater = () => import('@tauri-apps/plugin-updater');
const loadProcess = () => import('@tauri-apps/plugin-process');

type Update = Awaited<ReturnType<Awaited<ReturnType<typeof loadUpdater>>['check']>>;

export function useUpdater() {
  const [status, setStatus] = useState<UpdateStatus>({ kind: 'idle' });
  const updateRef = useRef<Update>(null);

  const check = useCallback(async () => {
    setStatus({ kind: 'checking' });
    if (!isTauri) {
      setStatus({ kind: 'available', version: '0.0.0-preview', notes: 'Browser preview fixture.' });
      return;
    }
    try {
      const { check } = await loadUpdater();
      const update = await check();
      updateRef.current = update;
      setStatus(update ? { kind: 'available', version: update.version, notes: update.body ?? null } : { kind: 'idle' });
    } catch (e) {
      // a check that can't reach the release feed (offline, no release cut
      // yet) is nothing the user can act on — stay quiet, retry on the next
      // interval. Only a failed *install* surfaces as an error.
      console.warn('update check failed', e);
      setStatus({ kind: 'idle' });
    }
  }, []);

  const install = useCallback(async () => {
    if (status.kind !== 'available' && status.kind !== 'error') return;
    if (!isTauri) {
      // walk the states so the button can be seen in every one
      for (let pct = 0; pct <= 100; pct += 20) {
        setStatus({ kind: 'downloading', version: '0.0.0-preview', pct });
        await new Promise((r) => setTimeout(r, 200));
      }
      setStatus({ kind: 'ready', version: '0.0.0-preview' });
      return;
    }
    const update = updateRef.current;
    if (!update) return void check();
    const version = update.version;
    let total: number | null = null;
    let got = 0;
    setStatus({ kind: 'downloading', version, pct: null });
    try {
      await update.downloadAndInstall((ev) => {
        if (ev.event === 'Started') total = ev.data.contentLength ?? null;
        else if (ev.event === 'Progress') {
          got += ev.data.chunkLength;
          setStatus({ kind: 'downloading', version, pct: total ? Math.round((got / total) * 100) : null });
        }
      });
      // Windows runs the installer and exits on its own; macOS/Linux swapped
      // the bundle in place and wait for us to relaunch
      setStatus({ kind: 'ready', version });
    } catch (e) {
      setStatus({ kind: 'error', message: String(e) });
    }
  }, [status.kind, check]);

  const restart = useCallback(async () => {
    if (!isTauri) return;
    const { relaunch } = await loadProcess();
    await relaunch();
  }, []);

  useEffect(() => {
    void check();
    const t = setInterval(() => {
      // never interrupt a download or a pending restart
      setStatus((s) => {
        if (s.kind === 'idle' || s.kind === 'error') void check();
        return s;
      });
    }, RECHECK_MS);
    return () => clearInterval(t);
  }, [check]);

  return { status, check, install, restart };
}
