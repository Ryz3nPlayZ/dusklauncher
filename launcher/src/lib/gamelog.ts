import { useEffect, useState } from 'react';
import { isTauri, listen, type GameLogBatch, type GameLogLine, type GameState } from './api';

/* The running game's console, kept outside React so it survives leaving
   the editor. `game-log` batches carry no profile id — the backend only
   ever runs one game — so the log belongs to whichever instance the last
   `game-state: starting` named, and starts over on the next one. */

const CAP = 5000;

export interface GameLog {
  profileId: string | null;
  lines: GameLogLine[];
}

let current: GameLog = { profileId: null, lines: [] };
const subs = new Set<(log: GameLog) => void>();

function emit() {
  for (const fn of subs) fn(current);
}

function push(lines: GameLogLine[]) {
  let next = current.lines.concat(lines);
  if (next.length > CAP) next = next.slice(next.length - CAP);
  current = { ...current, lines: next };
  emit();
}

export function clearGameLog() {
  current = { ...current, lines: [] };
  emit();
}

export function getGameLog(): GameLog {
  return current;
}

let started = false;
/** wire the tauri events once, from the app shell */
export function startGameLog() {
  if (started) return;
  started = true;
  if (!isTauri) {
    current = { profileId: 'p-performium', lines: PREVIEW_LINES };
    return;
  }
  void listen<GameState>('game-state', (s) => {
    if (s.state === 'starting') {
      current = { profileId: s.profileId, lines: [] };
      emit();
    } else if (s.state === 'exited') {
      push([
        {
          line: `[launcher] process exited${s.code === null ? '' : ` with code ${s.code}`}`,
          stream: s.code ? 'err' : 'out',
        },
      ]);
    }
  });
  void listen<GameLogBatch>('game-log', (b) => push(b.lines));
}

/** the log if it belongs to this instance, else an empty one */
export function useGameLog(profileId: string): GameLogLine[] {
  const [log, setLog] = useState(current);
  useEffect(() => {
    subs.add(setLog);
    setLog(current);
    return () => {
      subs.delete(setLog);
    };
  }, []);
  return log.profileId === profileId ? log.lines : EMPTY;
}

const EMPTY: GameLogLine[] = [];

/* what the browser preview shows in the LOG tab */
const PREVIEW_LINES: GameLogLine[] = [
  '[12:04:01] [main/INFO]: Loading Minecraft 1.21.11 with Fabric Loader 0.16.10',
  '[12:04:01] [main/INFO]: Loading 38 mods:',
  '[12:04:01] [main/INFO]:    - fabric-api 0.116.0+1.21.11',
  '[12:04:01] [main/INFO]:    - sodium 0.6.13+mc1.21.11',
  '[12:04:01] [main/INFO]:    - lithium 0.15.0+mc1.21.11',
  '[12:04:02] [main/INFO]: SpongePowered MIXIN Subsystem Version=0.15.5 Source=file:fabric-loader.jar',
  '[12:04:04] [Render thread/INFO]: Setting user: zemu',
  '[12:04:05] [Render thread/INFO]: Backend library: LWJGL version 3.3.3',
  '[12:04:06] [Render thread/WARN]: Missing texture in model minecraft:block/example',
  '[12:04:07] [Render thread/INFO]: OpenAL initialized on device Built-in Output',
  '[12:04:07] [Render thread/INFO]: Sound engine started',
  '[12:04:08] [Render thread/INFO]: Created: 1024x1024x4 minecraft:textures/atlas/blocks.png-atlas',
  '[12:04:09] [Render thread/INFO]: Narrator library for x64 successfully loaded',
  '[12:04:10] [Render thread/INFO]: Reloading ResourceManager: vanilla, fabric, sodium',
  '[12:04:12] [Render thread/ERROR]: Failed to load resource pack: dusk-ui (Unsupported pack format 34)',
  '[12:04:14] [Render thread/INFO]: Connecting to play.dusk.gg, 25565',
  '[12:04:15] [Render thread/INFO]: Loaded 7 advancements',
].map((line) => ({ line, stream: /ERROR/.test(line) ? 'err' : 'out' }) as GameLogLine);
