/**
 * Microsoft sign-in, shared by the launch gate and the Profile page.
 *
 * The backend runs the device-code flow and opens the verification page
 * with the code prefilled (`?otc=`), so the normal path is: click SIGN IN,
 * confirm in the browser, done. The code and link are still surfaced here
 * as the fallback — a browser that drops the query string, or one that
 * never opened.
 */
import { useCallback, useEffect, useState } from 'react';
import { openUrl } from '@tauri-apps/plugin-opener';
import { PxBox, PxButton, TT } from './px/Px';
import { api, isTauri, listen } from '../lib/api';

type AuthEvent = {
  state: 'waitingForBrowser' | 'deviceCode' | 'finishing' | 'signedIn';
  userCode?: string;
  verificationUri?: string;
};

export function useLogin(onDone: () => Promise<void> | void) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [auth, setAuth] = useState<AuthEvent | null>(null);

  useEffect(() => {
    let off: (() => void) | undefined;
    void listen<AuthEvent>('auth-state', (p) => setAuth(p)).then((f) => (off = f));
    return () => off?.();
  }, []);

  const start = useCallback(async () => {
    setBusy(true);
    setErr(null);
    setAuth(null);
    try {
      await api.login();
      await onDone();
    } catch (e) {
      setErr(String(e));
    }
    setBusy(false);
    setAuth(null);
  }, [onDone]);

  return { busy, err, auth, start, dismiss: () => setErr(null) };
}

/** The in-progress panel: what to do, the code, and the two fallbacks. */
export function DevicePanel({ auth }: { auth: AuthEvent | null }) {
  const [copied, setCopied] = useState(false);
  const code = auth?.state === 'deviceCode' ? auth.userCode : undefined;
  const uri = auth?.state === 'deviceCode' ? auth.verificationUri : undefined;
  const finishing = auth?.state === 'finishing';

  return (
    <PxBox family="panel" className="signin__panel">
      <span className="meta">
        {finishing
          ? 'Confirmed — fetching your profile…'
          : code
            ? 'A browser tab opened with this code already filled in. Confirm it there and come back.'
            : 'Opening your browser…'}
      </span>
      {code && (
        <>
          <div className="signin__code-row">
            <span className="signin__code">{code}</span>
            <PxButton
              family="grey"
              height="md"
              onClick={() => {
                void navigator.clipboard?.writeText(code);
                setCopied(true);
                setTimeout(() => setCopied(false), 1500);
              }}
            >
              <TT size={14}>{copied ? 'COPIED' : 'COPY'}</TT>
            </PxButton>
            {uri && (
              <PxButton family="blue" height="md" onClick={() => void openUrl(uri)}>
                <TT size={14} tone="blue">
                  OPEN LINK
                </TT>
              </PxButton>
            )}
          </div>
          <span className="meta">
            No tab? Enter the code by hand at {uri ? uri.replace(/^https?:\/\//, '').replace(/\?.*$/, '') : 'login.live.com/device'}.
          </span>
        </>
      )}
    </PxBox>
  );
}

/**
 * The launch gate: nothing else is reachable until a Microsoft account is
 * signed in. Only in the desktop app — the browser preview has no auth.
 */
export default function SignInGate({ onDone }: { onDone: () => Promise<void> | void }) {
  const { busy, err, auth, start } = useLogin(onDone);
  if (!isTauri) return null;

  return (
    <div className="modal-scrim signin">
      <PxBox family="panel" className="modal signin__box">
        <TT size={22}>SIGN IN TO PLAY</TT>
        <span className="meta">
          Dusk launches your own copy of Minecraft: Java Edition, so it needs the Microsoft account
          that owns it. Sign-in happens in your browser and comes straight back here.
        </span>

        {busy ? (
          <DevicePanel auth={auth} />
        ) : (
          <div className="modal__row">
            <PxButton family="blue" height="md" className="signin__cta" onClick={() => void start()}>
              <TT size={20} tone="blue">
                SIGN IN WITH MICROSOFT
              </TT>
            </PxButton>
          </div>
        )}

        {err && (
          <PxBox family="red" className="stack signin__err">
            <span className="meta">{err}</span>
          </PxBox>
        )}
      </PxBox>
    </div>
  );
}
