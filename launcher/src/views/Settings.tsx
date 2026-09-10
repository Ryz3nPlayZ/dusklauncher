import { useEffect, useState } from 'react';
import { NavCell, PxBox, PxButton, TT } from '../components/px/Px';
import { api, type AppInfo, type Settings } from '../lib/api';

const TABS = ['GENERAL', 'JAVA', 'DISPLAY', 'FILES'] as const;
type Tab = (typeof TABS)[number];

function Row({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="srow">
      <div className="srow__text">
        <TT size={20} tone="plain">
          {label}
        </TT>
        {hint && <span className="meta">{hint}</span>}
      </div>
      <div className="srow__control">{children}</div>
    </div>
  );
}

function Choice<T extends string | number>({
  value,
  options,
  onPick,
}: {
  value: T;
  options: { value: T; label: string }[];
  onPick: (v: T) => void;
}) {
  return (
    <>
      {options.map((o) => (
        <PxButton
          key={String(o.value)}
          family={value === o.value ? 'accent' : 'grey'}
          height="md"
          onClick={() => onPick(o.value)}
        >
          <TT size={16} tone={value === o.value ? 'accent' : undefined}>
            {o.label}
          </TT>
        </PxButton>
      ))}
    </>
  );
}

export default function SettingsView({
  settings,
  onSave,
}: {
  settings: Settings;
  onSave: (s: Settings) => void;
}) {
  const [tab, setTab] = useState<Tab>('GENERAL');
  const [info, setInfo] = useState<AppInfo | null>(null);
  const set = (patch: Partial<Settings>) => onSave({ ...settings, ...patch });

  useEffect(() => {
    void api.getAppInfo().then(setInfo);
  }, []);

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
                  onClick={() => info && void api.showInFolder(info.dataDir)}
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
