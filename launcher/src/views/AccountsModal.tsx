import { useState } from 'react';
import { Modal } from '../components/ui';
import { PixelIcon } from '../components/PixelIcon';
import { useUi } from '../stores/ui';
import { useAccount } from '../stores/account';
import { api } from '../lib/tauri';

/** Accounts pop-out modal (design §8): active card + ADD ACCOUNT. */
export default function AccountsModal() {
  const { accountsOpen, setAccountsOpen } = useUi();
  const { account, logout } = useAccount();
  const toast = useUi((s) => s.toast);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!accountsOpen) return null;

  async function addAccount() {
    setError(null);
    setBusy(true);
    try {
      await api.beginLogin();
      await useAccount.getState().load();
      toast('Signed in', 'success');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  /** Repair path for Xbox 400s: forces Microsoft's permission screen so a
   *  missing XboxLive.signin grant can actually be approved. */
  async function reconsentAccount() {
    setError(null);
    setBusy(true);
    try {
      await api.beginReconsentLogin();
      await useAccount.getState().load();
      toast('Signed in', 'success');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title="ACCOUNTS" onClose={() => setAccountsOpen(false)} width={460}>
      <div className="acct">
        <div className="acct__sub font-pixel">ACTIVE</div>
        <div className="acct__card">
          <img
            className="acct__head"
            src="img/head-placeholder.png"
            alt=""
            draggable={false}
          />
          <div className="acct__meta">
            <div className="acct__name font-pixel-bold">{account?.username ?? 'PLAYER'}</div>
            <div className="acct__rows">
              <span className="acct__row">
                <PixelIcon name="user" size={11} /> Java · offline session
              </span>
              {!account?.authenticated && <span className="acct__row text-3">Not signed in</span>}
            </div>
          </div>
          <div className="acct__actions">
            <button className="pbtn pbtn--sm">PROFILE</button>
            {account?.authenticated && (
              <button className="pbtn pbtn--sm pbtn--danger-outline" onClick={() => void logout()}>
                LOGOUT
              </button>
            )}
          </div>
        </div>

        {!account?.authenticated && (
          <p className="acct__note text-3">
            Sign in with the Microsoft account that owns Minecraft: Java Edition.
            A browser tab opens to approve — then you're set.
          </p>
        )}

        {error && (
          <div className="acct__error" role="alert">
            <p>{error}</p>
            <div className="acct__error-actions">
              <button className="pbtn pbtn--sm" disabled={busy} onClick={() => void addAccount()}>
                <PixelIcon name="refresh" size={10} /> TRY AGAIN
              </button>
              <button
                className="pbtn pbtn--sm"
                disabled={busy}
                title="Forces Microsoft's permission screen — fixes Xbox 400s caused by a missing XboxLive.signin grant"
                onClick={() => void reconsentAccount()}
              >
                <PixelIcon name="user" size={10} /> RE-CONSENT & RETRY
              </button>
            </div>
          </div>
        )}

        <button
          className="pbtn pbtn--block pbtn--gold-outline"
          disabled={busy}
          onClick={() => void addAccount()}
        >
          <PixelIcon name="plus" size={11} /> {busy ? 'WAITING FOR BROWSER…' : 'ADD ACCOUNT'}
        </button>
      </div>
    </Modal>
  );
}
