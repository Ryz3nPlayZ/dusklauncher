import { useEffect, useState } from 'react';
import { NavCell, PxBox, PxButton, TT } from '../components/px/Px';
import { Choice, Row } from '../components/px/Form';
import { api, type AppInfo, type Referral, type Settings } from '../lib/api';
import Wallpapers, { wallpaperLabel } from './Wallpapers';
import SettingsSocial from './SettingsSocial';

const TABS = ['GENERAL', 'SOCIAL', 'JAVA', 'DISPLAY', 'FILES'] as const;
type Tab = (typeof TABS)[number];

export default function SettingsView({
  settings,
  onSave,
  offline = false,
  onUnlocked,
}: {
  settings: Settings;
  onSave: (s: Settings) => void;
  /** playing under the offline name (no Microsoft account signed in) */
  offline?: boolean;
  /** a redeem code unlocked something on this machine (offline play) */
  onUnlocked?: (what: string) => void;
}) {
  const [tab, setTab] = useState<Tab>('GENERAL');
  const [info, setInfo] = useState<AppInfo | null>(null);
  /* the wallpaper library is its own page (the instances layout) */
  const [gallery, setGallery] = useState(false);
  const set = (patch: Partial<Settings>) => onSave({ ...settings, ...patch });
  /* redeem codes → Dusk coins; the wallet is server-side, so this only ever
     reports what the API said */
  const [code, setCode] = useState('');
  const [redeeming, setRedeeming] = useState(false);
  const [redeemNote, setRedeemNote] = useState<{ ok: boolean; text: string } | null>(null);
  const redeem = async () => {
    const trimmed = code.trim();
    if (!trimmed || redeeming) return;
    setRedeeming(true);
    setRedeemNote(null);
    try {
      const r = await api.redeemCode(trimmed);
      setRedeemNote({
        ok: true,
        text: r.unlocked === 'offline' ? 'OFFLINE PLAY UNLOCKED' : `+${r.granted} COINS · BALANCE ${r.coins}`,
      });
      if (r.unlocked) onUnlocked?.(r.unlocked);
      setCode('');
    } catch (e) {
      setRedeemNote({ ok: false, text: String(e).replace(/^Error: /, '').toUpperCase() });
    } finally {
      setRedeeming(false);
    }
  };

  /* the offline name: edited locally, saved once it's a valid player name */
  const [offlineName, setOfflineName] = useState(settings.offlineName);
  useEffect(() => setOfflineName(settings.offlineName), [settings.offlineName]);
  const nameOk = /^[A-Za-z0-9_]{3,16}$/.test(offlineName);
  const saveOfflineName = () => {
    if (nameOk && offlineName !== settings.offlineName) set({ offlineName });
    else if (!nameOk) setOfflineName(settings.offlineName);
  };

  /* the default window size: edited locally, saved once it's a size the game
     can open (the instance editor asks the same 320×240 minimum) */
  const [width, setWidth] = useState(String(settings.width));
  const [height, setHeight] = useState(String(settings.height));
  useEffect(() => setWidth(String(settings.width)), [settings.width]);
  useEffect(() => setHeight(String(settings.height)), [settings.height]);
  const sizeOk = Number.isInteger(Number(width)) && Number(width) >= 320 && Number.isInteger(Number(height)) && Number(height) >= 240;
  const saveSize = () => {
    if (!sizeOk) {
      setWidth(String(settings.width));
      setHeight(String(settings.height));
    } else if (Number(width) !== settings.width || Number(height) !== settings.height) {
      set({ width: Number(width), height: Number(height) });
    }
  };

  /* referrals: your code to share, and (new accounts, once) who invited you */
  const [referral, setReferral] = useState<Referral | null>(null);
  const [referralErr, setReferralErr] = useState<string | null>(null);
  const [refCode, setRefCode] = useState('');
  const [claiming, setClaiming] = useState(false);
  const [copied, setCopied] = useState(false);
  const claim = async () => {
    const trimmed = refCode.trim();
    if (!trimmed || claiming) return;
    setClaiming(true);
    setReferralErr(null);
    try {
      setReferral(await api.claimReferral(trimmed));
      setRefCode('');
    } catch (e) {
      setReferralErr(String(e).replace(/^Error: /, '').toUpperCase());
    } finally {
      setClaiming(false);
    }
  };
  const copyCode = async () => {
    if (!referral) return;
    try {
      await navigator.clipboard.writeText(referral.code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* no clipboard: the code is on screen anyway */
    }
  };

  useEffect(() => {
    void api.getAppInfo().then(setInfo);
    void api
      .getReferral()
      .then(setReferral)
      .catch((e) => setReferralErr(String(e).replace(/^Error: /, '')));
  }, []);

  if (gallery) {
    return (
      <Wallpapers
        current={settings.customBackground}
        onPick={(customBackground) => set({ customBackground })}
        onBack={() => setGallery(false)}
      />
    );
  }

  return (
    <div className="page">
      <div className="page__head">
        <h1 className="page__title">Settings</h1>
      </div>

      <div className="win win--solid">
        <div className="win__bar">
          {TABS.map((t) => (
            <NavCell key={t} label={t} active={tab === t} onClick={() => setTab(t)} />
          ))}
          <div className="win__fill" />
        </div>

        <div className="win__body settings__body scroll">
          {tab === 'GENERAL' && (
            <>
              <Row label="THEME" hint="Swaps the accent family and the scene behind the launcher.">
                <Choice
                  value={settings.theme}
                  onPick={(theme) => set({ theme })}
                  options={[
                    { value: 'overworld', label: 'OVERWORLD' },
                    { value: 'nether', label: 'NETHER' },
                  ]}
                />
              </Row>
              <Row
                label="WALLPAPER"
                hint={
                  settings.customBackground
                    ? `${wallpaperLabel(settings.customBackground)} — imports live in the data folder.`
                    : 'The built-in scene. Imports live in the data folder.'
                }
              >
                <PxButton family="blue" height="md" onClick={() => setGallery(true)}>
                  <TT size={16} tone="blue">
                    CHOOSE…
                  </TT>
                </PxButton>
              </Row>
              <Row
                label="WHILE PLAYING"
                hint={
                  settings.onPlay === 'hide'
                    ? 'The launcher hides while the game runs and comes back when it closes.'
                    : settings.onPlay === 'minimize'
                      ? 'The launcher minimizes while the game runs and comes back when it closes.'
                      : 'The launcher stays open next to the game.'
                }
              >
                <Choice
                  value={settings.onPlay}
                  onPick={(onPlay) => set({ onPlay })}
                  options={[
                    { value: 'keep', label: 'KEEP OPEN' },
                    { value: 'minimize', label: 'MINIMIZE' },
                    { value: 'hide', label: 'HIDE' },
                  ]}
                />
              </Row>
              <Row
                label="WORLD BACKUPS"
                hint={
                  settings.autoBackups > 0
                    ? `When the game closes, each world you played is zipped into the instance's backups/auto folder; the newest ${settings.autoBackups} per world are kept.`
                    : 'Worlds are only backed up when you ask.'
                }
              >
                <Choice
                  value={settings.autoBackups}
                  onPick={(autoBackups) => set({ autoBackups })}
                  options={[
                    { value: 0, label: 'OFF' },
                    { value: 3, label: 'KEEP 3' },
                    { value: 5, label: 'KEEP 5' },
                    { value: 10, label: 'KEEP 10' },
                  ]}
                />
              </Row>
              <Row label="SIGN-IN METHOD" hint="Official uses the Minecraft launcher's identity; Azure uses our own registration.">
                <Choice
                  value={settings.authMode}
                  onPick={(authMode) => set({ authMode })}
                  options={[
                    { value: 'official', label: 'OFFICIAL' },
                    { value: 'azure', label: 'AZURE' },
                  ]}
                />
              </Row>
              <Row label="REDUCE MOTION" hint="Freezes the scene and the player animation.">
                <Choice
                  value={settings.reduceMotion ? 'on' : 'off'}
                  onPick={(v) => set({ reduceMotion: v === 'on' })}
                  options={[
                    { value: 'off', label: 'OFF' },
                    { value: 'on', label: 'ON' },
                  ]}
                />
              </Row>
              <Row
                label="SYNC CLIENT SETTINGS"
                hint="Your Dusk HUD layout, module options and menu prefs follow your account to every instance and computer."
              >
                <Choice
                  value={settings.syncClientSettings ? 'on' : 'off'}
                  onPick={(v) => set({ syncClientSettings: v === 'on' })}
                  options={[
                    { value: 'off', label: 'OFF' },
                    { value: 'on', label: 'ON' },
                  ]}
                />
              </Row>
              <Row label="SCENE FPS" hint="The launcher never takes frames the game could use.">
                <Choice
                  value={settings.fpsCap}
                  onPick={(fpsCap) => set({ fpsCap })}
                  options={[
                    { value: 30, label: '30' },
                    { value: 60, label: '60' },
                    { value: 0, label: 'STATIC' },
                  ]}
                />
              </Row>
              <Row
                label="REDEEM CODE"
                hint={
                  redeemNote?.ok === false
                    ? redeemNote.text
                    : 'Turns a code into Dusk coins for the store. Each code works once per account.'
                }
                controlClassName="srow__control--wrap"
              >
                <PxBox family="panel" height="md">
                  <input
                    className="input"
                    value={code}
                    placeholder="CODE"
                    disabled={redeeming}
                    spellCheck={false}
                    autoCapitalize="off"
                    onChange={(e) => setCode(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && void redeem()}
                  />
                </PxBox>
                <PxButton family="install" height="md" disabled={!code.trim() || redeeming} onClick={() => void redeem()}>
                  <TT size={16}>{redeeming ? 'REDEEMING…' : 'REDEEM'}</TT>
                </PxButton>
                {redeemNote?.ok && (
                  <TT size={13} tone="green">
                    {redeemNote.text}
                  </TT>
                )}
              </Row>
              {offline && settings.offlineName && (
                <Row
                  label="OFFLINE NAME"
                  hint={
                    nameOk
                      ? 'The name you play under without a Microsoft account. Online-mode servers will turn you away.'
                      : '3–16 letters, digits or underscores.'
                  }
                >
                  <PxBox family="panel" height="md">
                    <input
                      className="input"
                      value={offlineName}
                      maxLength={16}
                      spellCheck={false}
                      autoCapitalize="off"
                      onChange={(e) => setOfflineName(e.target.value)}
                      onBlur={saveOfflineName}
                      onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
                    />
                  </PxBox>
                </Row>
              )}
              <Row
                label="INVITE FRIENDS"
                hint={
                  referral
                    ? `A friend who enters your code and launches the game earns you ${referral.referrerReward} coins and them ${referral.refereeReward}. ${referral.invited} invited · ${referral.paid} paid out.`
                    : (referralErr ?? 'Loading your referral code…')
                }
                controlClassName="srow__control--wrap"
              >
                {referral && (
                  <>
                    <PxBox family="panel" height="md">
                      <TT size={16} tone="accent">
                        {referral.code}
                      </TT>
                    </PxBox>
                    <PxButton family="grey" height="md" onClick={() => void copyCode()}>
                      <TT size={16}>{copied ? 'COPIED' : 'COPY'}</TT>
                    </PxButton>
                  </>
                )}
              </Row>
              {referral && (referral.canClaim || referral.referredBy) && (
                <Row
                  label="INVITED BY"
                  hint={
                    referral.referredBy
                      ? referral.referralPaid
                        ? `${referral.referredBy} invited you. The bonus is paid.`
                        : `${referral.referredBy} invited you. You both get paid when you first launch the game.`
                      : referralErr
                        ? referralErr
                      : `Got a code from a friend? You get ${referral.refereeReward} coins after your first launch. New accounts only.`
                  }
                  controlClassName="srow__control--wrap"
                >
                  {referral.referredBy ? (
                    <TT size={16} tone={referral.referralPaid ? 'green' : 'dim'}>
                      {referral.referredBy.toUpperCase()}
                    </TT>
                  ) : (
                    <>
                      <PxBox family="panel" height="md">
                        <input
                          className="input"
                          value={refCode}
                          placeholder="FRIEND'S CODE"
                          disabled={claiming}
                          spellCheck={false}
                          autoCapitalize="characters"
                          onChange={(e) => setRefCode(e.target.value)}
                          onKeyDown={(e) => e.key === 'Enter' && void claim()}
                        />
                      </PxBox>
                      <PxButton family="install" height="md" disabled={!refCode.trim() || claiming} onClick={() => void claim()}>
                        <TT size={16}>{claiming ? 'CHECKING…' : 'ENTER'}</TT>
                      </PxButton>
                    </>
                  )}
                </Row>
              )}
            </>
          )}

          {tab === 'SOCIAL' && <SettingsSocial settings={settings} set={set} info={info} />}

          {tab === 'JAVA' && (
            <>
              <Row label="MEMORY" hint={`${settings.memoryMb} MB handed to the JVM.`}>
                <input
                  className="slider"
                  type="range"
                  min={1024}
                  max={16384}
                  step={512}
                  value={settings.memoryMb}
                  onChange={(e) => set({ memoryMb: Number(e.target.value) })}
                />
              </Row>
              <Row label="JVM ARGUMENTS" hint="Applied to new instances.">
                <PxBox family="panel" height="md" className="px--wide">
                  <input
                    className="input"
                    value={settings.defaultJvmArgs}
                    onChange={(e) => set({ defaultJvmArgs: e.target.value })}
                  />
                </PxBox>
              </Row>
              <Row label="ENVIRONMENT" hint="KEY=VALUE pairs separated by ; passed to the game process.">
                <PxBox family="panel" height="md" className="px--wide">
                  <input
                    className="input"
                    placeholder="MESA_GL_VERSION_OVERRIDE=4.6; __GL_THREADED_OPTIMIZATIONS=1"
                    value={settings.envVars}
                    onChange={(e) => set({ envVars: e.target.value })}
                  />
                </PxBox>
              </Row>
              <Row label="PRE-LAUNCH HOOK" hint="Runs before the game starts.">
                <PxBox family="panel" height="md" className="px--wide">
                  <input
                    className="input"
                    value={settings.prelaunchHook}
                    onChange={(e) => set({ prelaunchHook: e.target.value })}
                  />
                </PxBox>
              </Row>
              <Row label="WRAPPER" hint="Starts Java through this command, e.g. gamemoderun or prime-run.">
                <PxBox family="panel" height="md" className="px--wide">
                  <input
                    className="input"
                    placeholder="gamemoderun"
                    value={settings.wrapperHook}
                    onChange={(e) => set({ wrapperHook: e.target.value })}
                  />
                </PxBox>
              </Row>
              <Row label="POST-EXIT HOOK" hint="Runs after the game closes.">
                <PxBox family="panel" height="md" className="px--wide">
                  <input
                    className="input"
                    value={settings.postExitHook}
                    onChange={(e) => set({ postExitHook: e.target.value })}
                  />
                </PxBox>
              </Row>
            </>
          )}

          {tab === 'DISPLAY' && (
            <>
              <Row
                label="RESOLUTION"
                hint={sizeOk ? 'Default window size for new instances.' : 'At least 320 × 240.'}
              >
                <PxBox family="panel" height="md">
                  <input
                    className="input"
                    type="number"
                    min={320}
                    value={width}
                    onChange={(e) => setWidth(e.target.value)}
                    onBlur={saveSize}
                    onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
                  />
                </PxBox>
                <PxBox family="panel" height="md">
                  <input
                    className="input"
                    type="number"
                    min={240}
                    value={height}
                    onChange={(e) => setHeight(e.target.value)}
                    onBlur={saveSize}
                    onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
                  />
                </PxBox>
              </Row>
            </>
          )}

          {tab === 'FILES' && (
            <>
              <Row label="DATA FOLDER" hint={info?.dataDir}>
                <PxButton
                  family="blue"
                  height="md"
                  disabled={!info}
                  onClick={() => void api.openDataDir()}
                >
                  <TT size={16} tone="blue">
                    REVEAL
                  </TT>
                </PxButton>
              </Row>
              <Row label="LAUNCHER" hint={info ? `${info.launcherVersion} · ${info.os}` : ''}>
                <PxBox family="panel" height="md">
                  <TT size={16} tone="dim">
                    {info?.launcherVersion ?? '—'}
                  </TT>
                </PxBox>
              </Row>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
