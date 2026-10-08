/**
 * Microsoft sign-in, shared by the launch gate and the Profile page.
 *
 * The gate leads with the device-code flow: it starts on its own, the
 * browser opens on Microsoft's page with the code already filled in, and the
 * code sits big on screen in case it has to be typed. "Or sign in here"
 * switches to the in-app Microsoft window instead; "add account later"
 * closes the gate (PLAY brings it back while nobody is signed in). If the
 * in-app window can't run, the user is offered the way forward rather than a
 * silent switch.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { openUrl } from '@tauri-apps/plugin-opener';
import { PxBox, PxButton, TT } from './px/Px';
import { api, isTauri, listen } from '../lib/api';

type AuthEvent = {
  state: 'waitingForBrowser' | 'webview' | 'webviewFailed' | 'deviceCode' | 'finishing' | 'signedIn';
  userCode?: string;
  verificationUri?: string;
  reason?: string;
};

export function useLogin(onDone: () => Promise<void> | void) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [auth, setAuth] = useState<AuthEvent | null>(null);

  useEffect(() => {
    // unlisten through the promise, so a close before it resolves still lets go
    const off = listen<AuthEvent>('auth-state', (p) => setAuth(p));
    return () => void off.then((f) => f());
  }, []);

  /* the login in flight, so a switch can cancel it and wait for the backend
     to let go of its sign-in lock before starting the next one */
  const pending = useRef<Promise<unknown> | null>(null);

  // Shared attempt runner. On failure `auth` is kept — a trailing
  // `webviewFailed` event is what tells the UI to offer the code choice.
  const run = useCallback(
    async (invokeLogin: () => Promise<unknown>) => {
      setBusy(true);
      setErr(null);
      setAuth(null);
      const attempt = invokeLogin();
      pending.current = attempt;
      try {
        await attempt;
        await onDone();
      } catch (e) {
        if (pending.current === attempt) pending.current = null;
        // a cancel is the user's choice, not a failure
        if (!String(e).includes('sign-in cancelled')) setErr(String(e));
        else setAuth(null);
        setBusy(false);
        return;
      }
      if (pending.current === attempt) pending.current = null;
      setBusy(false);
      setAuth(null);
    },
    [onDone],
  );

  /** stop the login in flight (if any) and wait until it has unwound */
  const cancel = useCallback(async () => {
    const p = pending.current;
    if (!p) return;
    await api.cancelLogin().catch(() => {});
    await p.catch(() => {});
  }, []);

  const start = useCallback(async () => {
    await cancel();
    await run(api.login);
  }, [cancel, run]);
  const startWithCode = useCallback(async () => {
    await cancel();
    await run(api.loginWithCode);
  }, [cancel, run]);

  return { busy, err, auth, start, startWithCode, cancel, dismiss: () => setErr(null) };
}

/** The in-progress panel: what to do, the code, and the two fallbacks. */
export function DevicePanel({ auth }: { auth: AuthEvent | null }) {
  const [copied, setCopied] = useState(false);
  const code = auth?.state === 'deviceCode' ? auth.userCode : undefined;
  const uri = auth?.state === 'deviceCode' ? auth.verificationUri : undefined;
  const finishing = auth?.state === 'finishing';
  const webview = auth?.state === 'webview';

  return (
    <PxBox family="panel" className="signin__panel">
      <span className="meta">
        {finishing
          ? 'Signed in — fetching your profile…'
          : code
            ? 'A browser tab opened with this code already filled in. Confirm it there and come back.'
            : webview
              ? 'A Microsoft sign-in window opened — finish signing in there and you will come right back here.'
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
 * The launch gate: shown while no Microsoft account is signed in (and
 * offline play isn't unlocked). Only in the desktop app — the browser
 * preview has no auth.
 */
export default function SignInGate({
  onDone,
  onLater,
}: {
  onDone: () => Promise<void> | void;
  /** ADD ACCOUNT LATER: close the gate without signing in */
  onLater: () => void;
}) {
  const { busy, err, auth, start, startWithCode, cancel } = useLogin(onDone);
  /* which flow the user is on: the code (default) or the in-app window */
  const [mode, setMode] = useState<'code' | 'window'>('code');

  /* the code flow starts by itself — once, even under StrictMode's double
     mount (a second begin would just hit "already in progress") */
  const started = useRef(false);
  useEffect(() => {
    if (!isTauri || started.current) return;
    started.current = true;
    void startWithCode();
  }, [startWithCode]);

  /** When the Azure flow is rejected (app ID not allow-listed), Settings is
   * unreachable behind this gate — the switch has to be offered right here. */
  const switchToOfficial = async () => {
    try {
      const s = await api.getSettings();
      await api.setSettings({ ...s, authMode: 'official' });
    } catch {
      // Settings unreachable: retry anyway — login re-resolves the mode.
    }
    await start();
  };

  const useWindow = () => {
    setMode('window');
    void start();
  };
  const useCode = () => {
    setMode('code');
    void startWithCode();
  };

  if (!isTauri) return null;

  return (
    <div className="modal-scrim signin">
      <PxBox family="panel" className="modal signin__box">
        <TT size={22}>SIGN IN TO PLAY</TT>
        <span className="meta">
          Dusk launches your own copy of Minecraft: Java Edition, so it needs the Microsoft account
          that owns it.
        </span>

        {busy ? (
          <DevicePanel auth={auth} />
        ) : (
          <div className="modal__row">
            <PxButton
              family="blue"
              height="md"
              className="signin__cta"
              onClick={mode === 'code' ? useCode : useWindow}
            >
              <TT size={20} tone="blue">
                {mode === 'code' ? 'GET A SIGN-IN CODE' : 'OPEN THE SIGN-IN WINDOW'}
              </TT>
            </PxButton>
          </div>
        )}

        {err && (
          <PxBox family="red" className="stack signin__err">
            <span className="meta">{err}</span>
            {/* The webview couldn't run — let the user pick the way forward
             * instead of silently switching flows on them. */}
            {auth?.state === 'webviewFailed' && (
              <div className="modal__row">
                <PxButton family="blue" height="md" onClick={useWindow}>
                  <TT size={14} tone="blue">
                    TRY THE SIGN-IN WINDOW AGAIN
                  </TT>
                </PxButton>
                <PxButton family="grey" height="md" onClick={useCode}>
                  <TT size={14}>SIGN IN WITH A CODE</TT>
                </PxButton>
              </div>
            )}
            {/* Text match against the mapped Azure-400 error: that specific
             * failure is the one whose advertised fix needs Settings access
             * the gate is blocking. */}
            {err.includes('has not been approved by Microsoft') && (
              <PxButton family="grey" height="md" onClick={() => void switchToOfficial()}>
                <TT size={14}>SWITCH TO OFFICIAL SIGN-IN AND RETRY</TT>
              </PxButton>
            )}
          </PxBox>
        )}

        <div className="signin__links">
          {mode === 'code' ? (
            <button type="button" className="signin__link" onClick={useWindow}>
              or sign in here instead
            </button>
          ) : (
            <button type="button" className="signin__link" onClick={useCode}>
              or use a code in your browser
            </button>
          )}
          <button
            type="button"
            className="signin__link signin__link--quiet"
            onClick={() => {
              void cancel();
              onLater();
            }}
          >
            add account later
          </button>
        </div>
      </PxBox>
    </div>
  );
}
