import { useEffect, useState } from 'react';
import { PxBox, PxButton, TT } from '../components/px/Px';
import { Choice, Row } from '../components/px/Form';
import { api, type AppInfo, type BlockedPlayer, type Privacy, type Settings } from '../lib/api';

/* ── SETTINGS → SOCIAL ────────────────────────────────────────────────────
   What friends see (privacy lives on the Dusk service, so it saves on each
   pick), who's blocked, and the launcher-side social switches: OS
   notifications, Discord Rich Presence, the chat clock and the link
   warning. */

const errText = (e: unknown) => String(e).replace(/^Error: /, '');

const onOff = [
  { value: 'off', label: 'OFF' },
  { value: 'on', label: 'ON' },
] as const;
const toggle = (v: boolean) => (v ? 'on' : 'off');

export default function SettingsSocial({
  settings,
  set,
  info,
}: {
  settings: Settings;
  set: (patch: Partial<Settings>) => void;
  info: AppInfo | null;
}) {
  const [privacy, setPrivacy] = useState<Privacy | null>(null);
  const [blocked, setBlocked] = useState<BlockedPlayer[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void api
      .getPrivacy()
      .then(setPrivacy)
      .catch((e) => setError(errText(e)));
    void api
      .listBlocked()
      .then(setBlocked)
      .catch(() => setBlocked([]));
  }, []);

  const savePrivacy = (patch: Partial<Privacy>) => {
    if (!privacy) return;
    const next = { ...privacy, ...patch };
    setPrivacy(next);
    setError(null);
    api
      .setPrivacy(next)
      .then(setPrivacy)
      .catch((e) => {
        setPrivacy(privacy);
        setError(errText(e));
      });
  };

  const unblock = (uuid: string) => {
    setError(null);
    api
      .unblockPlayer(uuid)
      .then(setBlocked)
      .catch((e) => setError(errText(e)));
  };

  const discordReady = info?.discordAvailable ?? false;

  return (
    <>
      {error && (
        <PxBox family="red" height="md" className="social__notice" role="alert">
          <span className="meta">{error}</span>
        </PxBox>
      )}

      <Row label="APPEAR OFFLINE" hint="Friends see you as offline. You can still chat.">
        {privacy ? (
          <Choice
            value={toggle(privacy.appearOffline)}
            onPick={(v) => savePrivacy({ appearOffline: v === 'on' })}
            options={[...onOff]}
          />
        ) : (
          <Pending />
        )}
      </Row>
      <Row label="SHARE ACTIVITY" hint="Friends see your game version and the server you're on, and can JOIN you.">
        {privacy ? (
          <Choice
            value={toggle(privacy.shareActivity)}
            onPick={(v) => savePrivacy({ shareActivity: v === 'on' })}
            options={[...onOff]}
          />
        ) : (
          <Pending />
        )}
      </Row>
      <Row label="FRIEND REQUESTS" hint="Who can send you one.">
        {privacy ? (
          <Choice
            value={privacy.friendRequests}
            onPick={(friendRequests) => savePrivacy({ friendRequests })}
            options={[
              { value: 'everyone', label: 'EVERYONE' },
              { value: 'friends_of_friends', label: 'FRIENDS OF FRIENDS' },
              { value: 'nobody', label: 'NOBODY' },
            ]}
          />
        ) : (
          <Pending />
        )}
      </Row>

      <Row label="FRIEND ONLINE ALERTS" hint="A system notification when a friend comes online while the launcher is in the background.">
        <Choice
          value={toggle(settings.notifyFriendsOnline)}
          onPick={(v) => set({ notifyFriendsOnline: v === 'on' })}
          options={[...onOff]}
        />
      </Row>
      <Row label="MESSAGE ALERTS" hint="A system notification for new messages while the launcher is in the background.">
        <Choice
          value={toggle(settings.notifyMessages)}
          onPick={(v) => set({ notifyMessages: v === 'on' })}
          options={[...onOff]}
        />
      </Row>
      <Row
        label="DISCORD PRESENCE"
        hint={
          discordReady
            ? 'Shows the version and server you are playing on your Discord profile.'
            : 'Not available in this build.'
        }
      >
        <Choice
          value={discordReady && settings.discordRpc ? 'on' : 'off'}
          onPick={(v) => discordReady && set({ discordRpc: v === 'on' })}
          options={[...onOff]}
        />
      </Row>
      <Row label="CLOCK" hint="How times read in chat and the screenshot gallery.">
        <Choice
          value={settings.clock24h ? '24' : '12'}
          onPick={(v) => set({ clock24h: v === '24' })}
          options={[
            { value: '12', label: '12-HOUR' },
            { value: '24', label: '24-HOUR' },
          ]}
        />
      </Row>
      <Row label="LINK WARNING" hint="Ask before opening a link from chat in your browser.">
        <Choice
          value={toggle(settings.warnOnLinks)}
          onPick={(v) => set({ warnOnLinks: v === 'on' })}
          options={[...onOff]}
        />
      </Row>

      <div className="social__section">
        <TT size={16} tone="dim">
          {blocked && blocked.length > 0 ? `BLOCKED ${blocked.length}` : 'BLOCKED'}
        </TT>
      </div>
      {blocked?.length === 0 && (
        <Row label="NOBODY" hint="Block someone from their profile in the friends pane.">
          {null}
        </Row>
      )}
      {blocked?.map((b) => (
        <Row key={b.uuid} label={b.username} hint="Can't send you requests or messages.">
          <PxButton family="grey" height="md" onClick={() => unblock(b.uuid)}>
            <TT size={16}>UNBLOCK</TT>
          </PxButton>
        </Row>
      ))}
    </>
  );
}

function Pending() {
  return (
    <PxBox family="panel" height="md">
      <TT size={16} tone="dim">
        …
      </TT>
    </PxBox>
  );
}
