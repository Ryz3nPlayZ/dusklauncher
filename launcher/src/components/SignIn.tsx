/**
 * Microsoft sign-in, shared by the launch gate and the Profile page.
 *
 * Default flow opens an in-app Microsoft sign-in window — pick the account
 * there and you come straight back, nothing to type. If that window can't
 * run, the user is offered the choice (window again vs the official title's
 * device-code flow) rather than a silent switch; the code panel then shows
 * the prefilled verification link (`?otc=`) plus manual fallbacks.
 */
import { useCallback, useEffect, useState } from 'react';
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
    let off: (() => void) | undefined;
    void listen<AuthEvent>('auth-state', (p) => setAuth(p)).then((f) => (off = f));
    return () => off?.();
  }, []);

  // Shared attempt runner. On failure `auth` is kept — a trailing
  // `webviewFailed` event is what tells the UI to offer the code choice.
  const run = useCallback(
    async (invokeLogin: () => Promise<unknown>) => {
      setBusy(true);
      setErr(null);
      setAuth(null);
      try {
        await invokeLogin();
        await onDone();
      } catch (e) {
        setErr(String(e));
        setBusy(false);
        return;
      }
      setBusy(false);
      setAuth(null);
    },
    [onDone],
  );

  const start = useCallback(() => run(api.login), [run]);
  const startWithCode = useCallback(() => run(api.loginWithCode), [run]);

  return { busy, err, auth, start, startWithCode, dismiss: () => setErr(null) };
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
 * The launch gate: nothing else is reachable until a Microsoft account is
 * signed in. Only in the desktop app — the browser preview has no auth.
 */
export default function SignInGate({ onDone }: { onDone: () => Promise<void> | void }) {
  const { busy, err, auth, start, startWithCode } = useLogin(onDone);

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
            {/* The webview couldn't run — let the user pick the way forward
             * instead of silently switching flows on them. */}
            {auth?.state === 'webviewFailed' && (
              <div className="modal__row">
                <PxButton family="blue" height="md" onClick={() => void start()}>
                  <TT size={14} tone="blue">
                    TRY THE SIGN-IN WINDOW AGAIN
                  </TT>
                </PxButton>
                <PxButton family="grey" height="md" onClick={() => void startWithCode()}>
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
      </PxBox>
    </div>
  );
}
