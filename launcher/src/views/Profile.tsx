import { useEffect, useState } from 'react';
import PlayerHead from '../components/PlayerHead';
import { PxBox, PxButton, TT } from '../components/px/Px';
import { api, isTauri, type Account, type AppInfo } from '../lib/api';

export default function Profile({
  account,
  skin,
  onChange,
}: {
  account: Account | null;
  skin: string | null;
  onChange: () => Promise<void> | void;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useState<AppInfo | null>(null);

  useEffect(() => {
    void api.getAppInfo().then(setInfo);
  }, []);

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
                  setBusy(true);
                  await api.logout();
                  await onChange();
                  setBusy(false);
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
                onClick={async () => {
                  setBusy(true);
                  setErr(null);
                  try {
                    await api.login();
                    await onChange();
                  } catch (e) {
                    setErr(String(e));
                  }
                  setBusy(false);
                }}
              >
                <TT size={22} tone="blue">
                  {busy ? 'WAITING…' : 'SIGN IN'}
                </TT>
              </PxButton>
            )}
          </div>
        </PxBox>

        {err && (
          <PxBox family="red" height="md" className="stack">
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
