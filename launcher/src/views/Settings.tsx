import { useEffect, useState } from 'react';
import { PixelIcon, type IconName } from '../components/PixelIcon';
import { Dropdown, Field, Toggle } from '../components/ui';
import { api, isTauri, type AppInfoDto, type FpsTarget, type ThemeName } from '../lib/tauri';
import { playerLoop } from '../lib/fps';
import { useSettings } from '../stores/settings';
import { useUi } from '../stores/ui';
import { playSfx } from '../sfx/sfx';

type Tab = 'general' | 'java' | 'display' | 'files' | 'debug';

const TABS: { id: Tab; label: string; icon: IconName }[] = [
  { id: 'general', label: 'GENERAL', icon: 'gear' },
  { id: 'java', label: 'JAVA & MEMORY', icon: 'java' },
  { id: 'display', label: 'DISPLAY', icon: 'monitor' },
  { id: 'files', label: 'FILES', icon: 'folder' },
  { id: 'debug', label: 'DEBUG', icon: 'bug' },
];

const JAVA_ROWS: { major: string; label: string; hint: string }[] = [
  { major: '8', label: 'JAVA 8', hint: 'legacy versions (jre-legacy)' },
  { major: '17', label: 'JAVA 17', hint: '1.17 – 1.20.4' },
  { major: '21', label: 'JAVA 21', hint: '1.20.5 – 26.x' },
  { major: '25', label: 'JAVA 25', hint: 'newest runtimes' },
];

export default function Settings() {
  const [tab, setTab] = useState<Tab>('general');
  return (
    <div className="page">
      <div className="page__head">
        <h1 className="page__title">SETTINGS</h1>
      </div>
      <nav className="settings-tabs">
        {TABS.map((t) => (
          <button
            key={t.id}
            className={`settings-tab ${tab === t.id ? 'is-active' : ''}`}
            onClick={() => {
              setTab(t.id);
              playSfx('tab');
            }}
          >
            <PixelIcon name={t.icon} size={13} />
            {t.label}
          </button>
        ))}
      </nav>
      <div className="settings-body scroll-y">
        {tab === 'general' && <GeneralTab />}
        {tab === 'java' && <JavaTab />}
        {tab === 'display' && <DisplayTab />}
        {tab === 'files' && <FilesTab />}
        {tab === 'debug' && <DebugTab />}
      </div>
    </div>
  );
}

function SettingsCard({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="pcard pixel-notch settings-card">
      <h2 className="font-pixel settings-card__title">{title}</h2>
      <div className="settings-card__body">{children}</div>
    </section>
  );
}

function Row({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="settings-row">
      <div className="settings-row__label">
        <span className="font-pixel">{label}</span>
        {hint && <span className="text-3">{hint}</span>}
      </div>
      <div className="settings-row__control">{children}</div>
    </div>
  );
}

/** Scene background picker (moved out of the footer chin): an image or video
 *  loop behind everything, or empty for the animated scene. */
function BackgroundPicker() {
  const custom = useSettings((s) => s.settings.customBackground);
  const update = useSettings((s) => s.update);
  const toast = useUi((s) => s.toast);

  async function pick() {
    try {
      if (isTauri) {
        const { open } = await import('@tauri-apps/plugin-dialog');
        const picked = await open({
          multiple: false,
          filters: [{ name: 'Background', extensions: ['png', 'jpg', 'jpeg', 'webp', 'mp4', 'webm', 'mov'] }],
        });
        if (typeof picked === 'string' && picked) {
          update({ customBackground: picked });
          playSfx('click');
        }
      } else {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*,video/*';
        input.onchange = () => {
          const f = input.files?.[0];
          if (f) update({ customBackground: URL.createObjectURL(f) });
        };
        input.click();
      }
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    }
  }

  return (
    <div className="path-row">
      <code className="pinput path-row__path">{custom || 'Animated scene'}</code>
      <button className="pbtn" onClick={() => void pick()}>
        <PixelIcon name="monitor" size={11} /> {custom ? 'CHANGE' : 'PICK FILE'}
      </button>
      {custom && (
        <button
          className="pbtn"
          title="Reset to animated scene"
          onClick={() => update({ customBackground: '' })}
        >
          <PixelIcon name="close" size={11} /> RESET
        </button>
      )}
    </div>
  );
}

// ── general ────────────────────────────────────────────────────────────────

function GeneralTab() {
  const { settings, update } = useSettings();
  const toast = useUi((s) => s.toast);
  const [reconsenting, setReconsenting] = useState(false);

  async function reconsent() {
    setReconsenting(true);
    try {
      await api.beginReconsentLogin();
      const { useAccount } = await import('../stores/account');
      await useAccount.getState().load();
      toast('Signed in', 'success');
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'error');
    } finally {
      setReconsenting(false);
    }
  }

  return (
    <div className="settings-grid">
      <SettingsCard title="MICROSOFT SIGN-IN">
        <Row
          label="SIGN-IN METHOD"
          hint="official works out of the box; azure needs Microsoft approval"
        >
          <div className="theme-picker">
            <button
              className={`theme-chip ${settings.authMode !== 'azure' ? 'is-on' : ''}`}
              onClick={() => update({ authMode: 'official' })}
            >
              OFFICIAL
            </button>
            <button
              className={`theme-chip ${settings.authMode === 'azure' ? 'is-on' : ''}`}
              onClick={() => update({ authMode: 'azure' })}
            >
              AZURE APP
            </button>
          </div>
        </Row>
        {settings.authMode === 'azure' && (
          <Row label="AUTH CLIENT ID" hint="Azure app ID — empty uses the shipped default">
            <div className="path-row">
              <input
                className="pinput mono"
                style={{ flex: 1, minWidth: 0 }}
                placeholder="default (shipped)"
                spellCheck={false}
                value={settings.authClientId}
                onChange={(e) => update({ authClientId: e.target.value })}
              />
              {settings.authClientId && (
                <button className="pbtn" onClick={() => update({ authClientId: '' })}>
                  RESET
                </button>
              )}
            </div>
          </Row>
        )}
        <Row label="RE-CONSENT" hint="fixes Xbox 400s: re-approves XboxLive.signin">
          <button className="pbtn" disabled={reconsenting} onClick={() => void reconsent()}>
            <PixelIcon name="refresh" size={11} />
            {reconsenting ? 'WAITING FOR BROWSER…' : 'RE-CONSENT & SIGN IN'}
          </button>
        </Row>
        <p className="text-3 settings-hint">
          Use a personal Microsoft account that owns Minecraft: Java Edition
          (work/school accounts are rejected by Xbox Live). Official signs in
          as the official Minecraft launcher's app and needs no setup; Azure
          App uses our own registration and must be approved by Microsoft
          (aka.ms/mce-reviewappid) or Xbox rejects it with a 400. Switching
          methods signs you out — sign in again after changing this.
        </p>
      </SettingsCard>

      <SettingsCard title="APPEARANCE">
        <Row label="THEME" hint="scene + accent palette">
          <div className="theme-picker">
            <button
              className={`theme-chip theme-chip--nether ${settings.theme === 'nether' ? 'is-on' : ''}`}
              onClick={() => update({ theme: 'nether' })}
            >
              NETHER
            </button>
            <button
              className={`theme-chip theme-chip--overworld ${settings.theme === 'overworld' ? 'is-on' : ''}`}
              onClick={() => update({ theme: 'overworld' as ThemeName })}
            >
              OVERWORLD
            </button>
          </div>
        </Row>
        <Row label="REDUCE MOTION" hint="freezes the background scene">
          <Toggle checked={settings.reduceMotion} onChange={(v) => update({ reduceMotion: v })} />
        </Row>
        <Row label="SCENE FPS" hint="background animation cap">
          <Dropdown
            width={140}
            value={String(settings.fpsCap)}
            onChange={(v) => update({ fpsCap: Number(v) as FpsTarget })}
            options={[
              { value: '30', label: '30 FPS' },
              { value: '60', label: '60 FPS' },
              { value: '0', label: 'Static' },
            ]}
          />
        </Row>
      </SettingsCard>

      <SettingsCard title="SOUND">
        <Row label="MUTE">
          <Toggle checked={settings.muted} onChange={(v) => update({ muted: v })} />
        </Row>
        <Row label="VOLUME" hint={`${Math.round(settings.volume * 100)}%`}>
          <input
            className="prange"
            type="range"
            min={0}
            max={100}
            value={Math.round(settings.volume * 100)}
            onChange={(e) => update({ volume: Number(e.target.value) / 100, muted: false })}
          />
        </Row>
      </SettingsCard>
    </div>
  );
}

// ── java & memory ──────────────────────────────────────────────────────────

function JavaTab() {
  const { settings, update } = useSettings();
  const toast = useUi((s) => s.toast);

  function setMemory(mb: number) {
    const gb = mb / 1024;
    const args = settings.defaultJvmArgs.replace(/-Xms\d+[GM]/g, `-Xms${Math.min(2, Math.floor(gb))}G`).replace(/-Xmx\d+[GM]/g, `-Xmx${Math.round(gb)}G`);
    update({ memoryMb: mb, defaultJvmArgs: args.includes('-Xmx') ? args : `-Xms1G -Xmx${Math.round(gb)}G ${args}`.trim() });
  }

  async function browseJava(major: string) {
    if (!isTauri) {
      toast('Folder picker requires the desktop app', 'error');
      return;
    }
    const { open } = await import('@tauri-apps/plugin-dialog');
    const dir = await open({ directory: true, title: `Select Java ${major} home` });
    if (typeof dir === 'string') {
      const paths = { ...settings.javaPaths, [major]: dir };
      update({ javaPaths: paths });
    }
  }

  const mem = settings.memoryMb;
  return (
    <div className="settings-grid settings-grid--wide">
      <SettingsCard title="MEMORY ALLOCATION">
        <div className="mem-widget">
          <button className="pbtn" onClick={() => setMemory(Math.max(1024, mem - 512))}>
            <PixelIcon name="minus" size={9} />
          </button>
          <input
            className="pinput mem-widget__num mono"
            value={mem}
            onChange={(e) => {
              const v = Number(e.target.value.replace(/\D/g, ''));
              if (v > 0) setMemory(v);
            }}
          />
          <span className="font-pixel text-3">MB</span>
          <input
            className="prange mem-widget__slider"
            type="range"
            min={1024}
            max={32768}
            step={512}
            value={mem}
            onChange={(e) => setMemory(Number(e.target.value))}
          />
        </div>
        <p className="text-3 settings-hint">
          Default heap for new profiles. Actual -Xmx lives in each profile's JVM arguments.
        </p>
      </SettingsCard>

      <SettingsCard title="DEFAULT JVM ARGUMENTS">
        <textarea
          className="pinput"
          rows={3}
          value={settings.defaultJvmArgs}
          onChange={(e) => update({ defaultJvmArgs: e.target.value })}
        />
        <p className="text-3 settings-hint">
          PvP-tuned from Mojang's 26.2 defaults (ZGC + AlwaysPreTouch) — tune freely.
        </p>
      </SettingsCard>

      <SettingsCard title="JAVA RUNTIMES">
        <p className="text-3 settings-hint">
          Leave empty to auto-provision Mojang runtimes. Set a path to use your own JDK for that
          Java major.
        </p>
        {JAVA_ROWS.map((row) => (
          <div className="settings-row" key={row.major}>
            <div className="settings-row__label">
              <span className="font-pixel">{row.label}</span>
              <span className="text-3">{row.hint}</span>
            </div>
            <div className="settings-row__control java-path-row">
              <input
                className="pinput"
                placeholder="auto (Mojang runtime)"
                value={settings.javaPaths[row.major] ?? ''}
                onChange={(e) =>
                  update({ javaPaths: { ...settings.javaPaths, [row.major]: e.target.value } })
                }
              />
              <button className="pbtn" onClick={() => void browseJava(row.major)}>
                <PixelIcon name="folder" size={11} /> BROWSE
              </button>
            </div>
          </div>
        ))}
      </SettingsCard>

      <SettingsCard title="HOOKS & ENVIRONMENT">
        <Field label="ENVIRONMENT VARIABLES" hint="one KEY=VALUE per line">
          <textarea
            className="pinput"
            rows={3}
            placeholder={'__GL_THREADED_OPTIMIZATIONS=0\n.MANGOHUD=1'}
            value={settings.envVars}
            onChange={(e) => update({ envVars: e.target.value })}
          />
        </Field>
        <div className="settings-grid">
          <Field label="PRELAUNCH HOOK" hint="runs before the game">
            <input
              className="pinput"
              value={settings.prelaunchHook}
              onChange={(e) => update({ prelaunchHook: e.target.value })}
            />
          </Field>
          <Field label="WRAPPER" hint="e.g. mangohud">
            <input
              className="pinput"
              value={settings.wrapperHook}
              onChange={(e) => update({ wrapperHook: e.target.value })}
            />
          </Field>
          <Field label="POST-EXIT HOOK" hint="runs after the game exits">
            <input
              className="pinput"
              value={settings.postExitHook}
              onChange={(e) => update({ postExitHook: e.target.value })}
            />
          </Field>
        </div>
      </SettingsCard>
    </div>
  );
}

// ── display ────────────────────────────────────────────────────────────────

function DisplayTab() {
  const { settings, update } = useSettings();
  return (
    <div className="settings-grid">
      <SettingsCard title="BACKGROUND">
        <Row
          label="SCENE IMAGE"
          hint="Still image or video loop behind everything. Empty = animated scene."
        >
          <BackgroundPicker />
        </Row>
      </SettingsCard>
      <SettingsCard title="DEFAULT WINDOW SIZE">
        <Row label="WIDTH">
          <input
            className="pinput mono"
            value={settings.width}
            onChange={(e) => update({ width: Number(e.target.value.replace(/\D/g, '')) || 1280 })}
          />
        </Row>
        <Row label="HEIGHT">
          <input
            className="pinput mono"
            value={settings.height}
            onChange={(e) => update({ height: Number(e.target.value.replace(/\D/g, '')) || 720 })}
          />
        </Row>
        <p className="text-3 settings-hint">Applied to newly created profiles.</p>
      </SettingsCard>
    </div>
  );
}

// ── files ──────────────────────────────────────────────────────────────────

function FilesTab() {
  const [info, setInfo] = useState<AppInfoDto | null>(null);
  useEffect(() => {
    void api.getAppInfo().then(setInfo).catch(() => {});
  }, []);
  const toast = useUi((s) => s.toast);

  async function reveal() {
    if (!isTauri) return;
    const { revealItemInDir } = await import('@tauri-apps/plugin-opener');
    if (info) {
      revealItemInDir(info.dataDir).catch(() => toast('Could not open folder', 'error'));
    }
  }

  return (
    <div className="settings-grid">
      <SettingsCard title="DATA DIRECTORY">
        <Row label="LOCATION" hint="profiles, assets, runtimes, settings">
          <div className="path-row">
            <code className="pinput path-row__path">{info?.dataDir ?? '…'}</code>
            <button className="pbtn" onClick={() => void reveal()}>
              <PixelIcon name="folder" size={11} /> OPEN
            </button>
          </div>
        </Row>
        <Row label="PROFILES" hint="each profile isolates versions + mods">
          <span className="text-2">{info ? `${info.dataDir}/profiles` : '…'}</span>
        </Row>
      </SettingsCard>
    </div>
  );
}

// ── debug ──────────────────────────────────────────────────────────────────

/** Counts frames through the shared FrameLoops for one second at a time —
 *  reads the real capped/paused render rate, not an uncapped rAF probe. */
function FpsMeter() {
  const [player, setPlayer] = useState(0);
  const cap = useSettings((s) => s.settings.fpsCap);
  const reduceMotion = useSettings((s) => s.settings.reduceMotion);

  useEffect(() => {
    let p = 0;
    const offPlayer = playerLoop.on(() => p++);
    const iv = setInterval(() => {
      setPlayer(p);
      p = 0;
    }, 1000);
    return () => {
      offPlayer();
      clearInterval(iv);
    };
  }, []);

  return (
    <SettingsCard title="RENDER PERFORMANCE">
      <Row label="BACKGROUND">
        <span className="mono text-2">CSS compositor</span>
      </Row>
      <Row label="3D PLAYER">
        <span className="mono text-2">{player} fps</span>
      </Row>
      <Row label="CAP">
        <span className="mono text-2">
          {reduceMotion ? 'static (reduce-motion)' : cap === 0 ? 'static' : `${cap} fps`}
        </span>
      </Row>
      <p className="text-3 settings-hint">
        The background scene is CSS-animated (browser compositor — no frame
        loop). Live frame count from the 3D player's frame governor; zeros
        while paused (hidden window, game running, static cap) are expected.
      </p>
    </SettingsCard>
  );
}

function DebugTab() {
  const [info, setInfo] = useState<AppInfoDto | null>(null);
  const { settings } = useSettings();
  const toast = useUi((s) => s.toast);
  useEffect(() => {
    void api.getAppInfo().then(setInfo).catch(() => {});
  }, []);

  function exportDebug() {
    const dump = [
      `DuskLauncher ${info?.launcherVersion ?? '?'}`,
      `OS: ${info?.os ?? '?'}`,
      `Data dir: ${info?.dataDir ?? '?'}`,
      `Theme: ${settings.theme} | fpsCap: ${settings.fpsCap} | reduceMotion: ${settings.reduceMotion}`,
      `Memory: ${settings.memoryMb} MB`,
      `JVM args: ${settings.defaultJvmArgs}`,
      `Java paths: ${JSON.stringify(settings.javaPaths)}`,
      `Auth mode: ${settings.authMode}${settings.authMode === 'azure' && settings.authClientId ? ` (custom ${settings.authClientId.trim()})` : ''}`,
      `Hooks: pre=${settings.prelaunchHook} wrapper=${settings.wrapperHook} post=${settings.postExitHook}`,
    ].join('\n');
    void navigator.clipboard
      .writeText(dump)
      .then(() => toast('Debug info copied to clipboard', 'success'))
      .catch(() => toast('Clipboard unavailable', 'error'));
  }

  return (
    <div className="settings-grid">
      <SettingsCard title="VERSIONS">
        <Row label="LAUNCHER">
          <span className="mono text-2">DuskLauncher {info?.launcherVersion ?? '…'}</span>
        </Row>
        <Row label="OS">
          <span className="mono text-2">{info?.os ?? '…'}</span>
        </Row>
        <Row label="DATA DIR">
          <span className="mono text-2">{info?.dataDir ?? '…'}</span>
        </Row>
      </SettingsCard>
      <SettingsCard title="EXPORT">
        <p className="text-3 settings-hint">
          Copies launcher version, OS, settings and hook configuration as text.
        </p>
        <button className="pbtn pbtn--success-outline" onClick={exportDebug}>
          <PixelIcon name="download" size={11} /> EXPORT DEBUG
        </button>
      </SettingsCard>
      <FpsMeter />
    </div>
  );
}
