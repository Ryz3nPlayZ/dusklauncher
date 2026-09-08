import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { PixelIcon } from '../components/PixelIcon';
import { api, listen } from '../lib/tauri';
import { useAccount } from '../stores/account';
import { playSfx } from '../sfx/sfx';

type Phase = 'idle' | 'waitingForBrowser' | 'finishing';

/**
 * Blocking first-run gate: the launcher is useless without a Microsoft
 * session (launch is refused server-side), so new / signed-out users land
 * here until they sign in. Failures render inline with the full backend
 * message — never a bare status code.
 */
export default function Onboarding({ onDone }: { onDone: () => void }) {
  const [phase, setPhase] = useState<Phase>('idle');
  const [error, setError] = useState<string | null>(null);
  const busy = phase !== 'idle';

  useEffect(() => {
    let unlisten: (() => void) | undefined;
    void listen<{ state: string }>('auth-state', (p) => {
      if (p.state === 'waitingForBrowser') setPhase('waitingForBrowser');
      else if (p.state === 'finishing') setPhase('finishing');
    }).then((u) => {
      unlisten = u;
    });
    return () => unlisten?.();
  }, []);

  async function signIn() {
    setError(null);
    setPhase('idle');
    playSfx('click');
    try {
      await api.beginLogin();
      await useAccount.getState().load();
      playSfx('success');
      onDone();
    } catch (e) {
      setPhase('idle');
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  /** Repair path for Xbox 400s: forces Microsoft's permission screen so a
   *  missing XboxLive.signin grant can actually be approved. */
  async function reconsent() {
    setError(null);
    setPhase('idle');
    playSfx('click');
    try {
      await api.beginReconsentLogin();
      await useAccount.getState().load();
      playSfx('success');
      onDone();
    } catch (e) {
      setPhase('idle');
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return createPortal(
    <div className="onboard-overlay anim-fade-in">
      <div className="onboard-card pcard pixel-notch anim-pop-in">
        <div className="onboard-brand">
          <PixelIcon name="sparkle" size={30} className="text-accent" />
          <span className="onboard-word font-pixel-bold">
            DUSK<span>LAUNCHER</span>
          </span>
        </div>

        <h1 className="onboard-title font-pixel-bold">SIGN IN TO PLAY</h1>
        <p className="onboard-sub">
          DuskLauncher needs the Microsoft account that owns Minecraft: Java Edition.
          A browser tab opens — sign in there, then come back.
        </p>

        <ol className="onboard-steps">
          <li>
            <span className="onboard-step-n font-pixel">1</span> Sign in with Microsoft
          </li>
          <li>
            <span className="onboard-step-n font-pixel">2</span> We verify game ownership
          </li>
          <li>
            <span className="onboard-step-n font-pixel">3</span> Play — no login ever again
          </li>
        </ol>

        <button
          className="pbtn pbtn--install pbtn--lg pbtn--block onboard-cta"
          disabled={busy}
          onClick={() => void signIn()}
        >
          <PixelIcon name="user" size={13} />
          {phase === 'waitingForBrowser'
            ? 'WAITING FOR BROWSER…'
            : phase === 'finishing'
              ? 'FINISHING SIGN-IN…'
              : 'SIGN IN WITH MICROSOFT'}
        </button>

        {phase === 'waitingForBrowser' && (
          <p className="onboard-hint text-3">
            No tab opened? Check behind this window — then approve Microsoft and return here.
          </p>
        )}

        {error && (
          <div className="onboard-error" role="alert">
            <div className="onboard-error__head font-pixel">
              <PixelIcon name="close" size={11} /> SIGN-IN FAILED
            </div>
            <p>{error}</p>
            <div className="onboard-error__actions">
              <button className="pbtn pbtn--sm" onClick={() => void signIn()}>
                <PixelIcon name="refresh" size={10} /> TRY AGAIN
              </button>
              <button
                className="pbtn pbtn--sm"
                title="Forces Microsoft's permission screen — fixes Xbox 400s caused by a missing XboxLive.signin grant"
                onClick={() => void reconsent()}
              >
                <PixelIcon name="user" size={10} /> RE-CONSENT & RETRY
              </button>
            </div>
          </div>
        )}

        <button className="onboard-skip font-pixel" onClick={onDone}>
          continue offline (can't launch games)
        </button>
      </div>
    </div>,
    document.body,
  );
}
