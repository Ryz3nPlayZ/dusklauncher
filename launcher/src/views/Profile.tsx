import { useEffect, useState } from 'react';
import { DevicePanel, useLogin } from '../components/SignIn';
import PlayerHead from '../components/PlayerHead';
import { PxBox, PxButton, TT } from '../components/px/Px';
import { api, isTauri, type Account, type AppInfo, type SavedAccount } from '../lib/api';

export default function Profile({
  account,
  skin,
  onChange,
}: {
  account: Account | null;
  skin: string | null;
  onChange: () => Promise<void> | void;
}) {
  const [signingOut, setSigningOut] = useState(false);
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [accounts, setAccounts] = useState<SavedAccount[]>([]);
  const [switching, setSwitching] = useState<string | null>(null);
  const [switchErr, setSwitchErr] = useState<string | null>(null);
  const refreshAccounts = () => void api.listAccounts().then(setAccounts).catch(() => setAccounts([]));
  const { busy: signingIn, err, auth, start } = useLogin(async () => {
    await onChange();
    refreshAccounts();
  });
  const busy = signingIn || signingOut || switching !== null;

  useEffect(() => {
    void api.getAppInfo().then(setInfo);
    refreshAccounts();
  }, []);

  const switchTo = async (uuid: string) => {
    setSwitching(uuid);
    setSwitchErr(null);
    try {
      await api.switchAccount(uuid);
      await onChange();
      refreshAccounts();
    } catch (e) {
      setSwitchErr(String(e));
    } finally {
      setSwitching(null);
    }
  };
  const others = accounts.filter((a) => !a.active);

  return (
    <div className="page">
      <div className="page__head">
        <h1 className="page__title">Profile</h1>
      </div>

      <div className="column">
        <PxBox family={account?.authenticated ? 'green' : 'panel'} className="account">
          <PlayerHead skin={skin} size={64} />
          <div className="account__text">
            <TT size={22} tone={account?.authenticated ? 'green' : undefined}>
              {account?.username ?? 'NOT SIGNED IN'}
            </TT>
            <span className="meta">
              {account?.authenticated
                ? `Microsoft account · ${account.uuid}`
                : 'Sign in with Microsoft to launch owned copies of the game and apply skins.'}
            </span>
          </div>
          <div className="account__actions">
            {account?.authenticated ? (
              <PxButton
                family="red"
                height="md"
                disabled={busy}
                onClick={async () => {
                  setSigningOut(true);
                  await api.logout();
                  await onChange();
                  refreshAccounts();
                  setSigningOut(false);
                }}
              >
                <TT size={22} tone="red">
                  SIGN OUT
                </TT>
              </PxButton>
            ) : (
              <PxButton
                family="blue"
                height="md"
                disabled={busy}
                onClick={() => void start()}
              >
                <TT size={22} tone="blue">
                  {busy ? 'WAITING…' : 'SIGN IN'}
                </TT>
              </PxButton>
            )}
          </div>
        </PxBox>

        {signingIn && <DevicePanel auth={auth} />}

        {(account?.authenticated || others.length > 0) && (
          <PxBox family="panel" className="stack">
            <TT size={16} tone="dim">
              ACCOUNTS
            </TT>
            <span className="meta">
              Switch between Microsoft accounts without signing in again. Each keeps its own friends and Dusk wallet.
            </span>
            {others.map((a) => (
              <div key={a.uuid} className="srow">
                <div className="srow__text">
                  <TT size={20}>{a.username}</TT>
                  <span className="meta">{a.uuid}</span>
                </div>
                <div className="srow__control">
                  <PxButton family="blue" height="md" disabled={busy} onClick={() => void switchTo(a.uuid)}>
                    <TT size={16} tone="blue">
                      {switching === a.uuid ? 'SWITCHING…' : 'SWITCH'}
                    </TT>
                  </PxButton>
                  <PxButton
                    family="grey"
                    height="md"
                    disabled={busy}
                    title="Forget this account on this computer"
                    onClick={() =>
                      void api
                        .removeAccount(a.uuid)
                        .then(refreshAccounts)
                        .catch((e) => setSwitchErr(String(e)))
                    }
                  >
                    <TT size={16}>REMOVE</TT>
                  </PxButton>
                </div>
              </div>
            ))}
            {switchErr && <span className="meta">{switchErr}</span>}
            {account?.authenticated && (
              <div>
                <PxButton family="grey" height="md" disabled={busy} onClick={() => void start()}>
                  <TT size={16}>+ ADD ACCOUNT</TT>
                </PxButton>
              </div>
            )}
          </PxBox>
        )}

        {err && (
          <PxBox family="red" className="stack">
            <span className="meta">{err}</span>
          </PxBox>
        )}

        <PxBox family="panel" className="stack">
          <TT size={16} tone="dim">
            SESSION
          </TT>
          <span className="meta">
            {busy
              ? 'A browser window is open — finish the Microsoft sign-in there.'
              : account?.authenticated
                ? 'Session is stored locally and refreshed automatically before each launch.'
                : isTauri
                  ? 'No session stored. Sign-in opens your browser and returns to the launcher.'
                  : 'Browser preview — sign-in only works in the desktop app.'}
          </span>
          {info && (
            <span className="meta">
              DuskLauncher {info.launcherVersion} · {info.os}
            </span>
          )}
        </PxBox>
      </div>
    </div>
  );
}
