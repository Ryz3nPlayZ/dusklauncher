import { useEffect, useState } from 'react';
import { NavCell, PxBox, PxButton, TT } from '../components/px/Px';
import { Choice, Row } from '../components/px/Form';
import { api, type AppInfo, type Referral, type Settings } from '../lib/api';
import Wallpapers, { wallpaperLabel } from './Wallpapers';

const TABS = ['GENERAL', 'JAVA', 'DISPLAY', 'FILES'] as const;
type Tab = (typeof TABS)[number];

export default function SettingsView({
  settings,
  onSave,
}: {
  settings: Settings;
  onSave: (s: Settings) => void;
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
      setRedeemNote({ ok: true, text: `+${r.granted} COINS · BALANCE ${r.coins}` });
      setCode('');
    } catch (e) {
      setRedeemNote({ ok: false, text: String(e).replace(/^Error: /, '').toUpperCase() });
    } finally {
      setRedeeming(false);
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
                      : referralErr && referral
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
              <Row label="VOLUME">
                <input
                  className="slider"
                  type="range"
                  min={0}
                  max={100}
                  value={Math.round(settings.volume * 100)}
                  onChange={(e) => set({ volume: Number(e.target.value) / 100 })}
                />
                <Choice
                  value={settings.muted ? 'on' : 'off'}
                  onPick={(v) => set({ muted: v === 'on' })}
                  options={[
                    { value: 'off', label: 'SOUND' },
                    { value: 'on', label: 'MUTED' },
                  ]}
                />
              </Row>
            </>
          )}

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
              <Row label="ENVIRONMENT" hint="KEY=VALUE pairs, one per line, passed to the game process.">
                <PxBox family="panel" height="md" className="px--wide">
                  <input
                    className="input"
                    placeholder="MESA_GL_VERSION_OVERRIDE=4.6"
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
            </>
          )}

          {tab === 'DISPLAY' && (
            <>
              <Row label="RESOLUTION" hint="Default window size for new instances.">
                <PxBox family="panel" height="md">
                  <input
                    className="input"
                    type="number"
                    value={settings.width}
                    onChange={(e) => set({ width: Number(e.target.value) })}
                  />
                </PxBox>
                <PxBox family="panel" height="md">
                  <input
                    className="input"
                    type="number"
                    value={settings.height}
                    onChange={(e) => set({ height: Number(e.target.value) })}
                  />
                </PxBox>
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
