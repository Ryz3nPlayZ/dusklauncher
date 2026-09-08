/**
 * UI sound effects — synthesized with WebAudio, zero asset bytes.
 * Master volume / mute comes from the settings store; the AudioContext is
 * created lazily on first user gesture (browser autoplay policy).
 */

type SfxName = 'click' | 'hover' | 'success' | 'error' | 'launch' | 'tab';

let ctx: AudioContext | null = null;
let master: GainNode | null = null;
let volume = 0.6;
let muted = false;

function ensureCtx(): AudioContext | null {
  if (ctx) return ctx;
  try {
    ctx = new AudioContext({ latencyHint: 'interactive' });
    master = ctx.createGain();
    master.gain.value = muted ? 0 : volume * 0.5;
    master.connect(ctx.destination);
  } catch {
    ctx = null;
  }
  return ctx;
}

export function configureSfx(v: number, m: boolean) {
  volume = v;
  muted = m;
  if (master && ctx) {
    master.gain.setTargetAtTime(m ? 0 : v * 0.5, ctx.currentTime, 0.01);
  }
}

function blip(
  freq: number,
  dur: number,
  type: OscillatorType,
  gain: number,
  slideTo?: number,
  delay = 0,
) {
  const c = ensureCtx();
  if (!c || !master || muted || volume <= 0) return;
  const t0 = c.currentTime + delay;
  const osc = c.createOscillator();
  const g = c.createGain();
  osc.type = type;
  osc.frequency.setValueAtTime(freq, t0);
  if (slideTo) osc.frequency.exponentialRampToValueAtTime(slideTo, t0 + dur);
  g.gain.setValueAtTime(gain, t0);
  g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
  osc.connect(g).connect(master);
  osc.start(t0);
  osc.stop(t0 + dur + 0.02);
}

function noiseWhoosh(dur = 0.6) {
  const c = ensureCtx();
  if (!c || !master || muted || volume <= 0) return;
  const len = Math.floor(c.sampleRate * dur);
  const buf = c.createBuffer(1, len, c.sampleRate);
  const data = buf.getChannelData(0);
  for (let i = 0; i < len; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / len);
  const src = c.createBufferSource();
  src.buffer = buf;
  const filter = c.createBiquadFilter();
  filter.type = 'lowpass';
  filter.frequency.setValueAtTime(300, c.currentTime);
  filter.frequency.exponentialRampToValueAtTime(2400, c.currentTime + dur * 0.7);
  const g = c.createGain();
  g.gain.setValueAtTime(0.5, c.currentTime);
  g.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + dur);
  src.connect(filter).connect(g).connect(master);
  src.start();
}

export function playSfx(name: SfxName) {
  if (muted || volume <= 0) return;
  if (ctx?.state === 'suspended') void ctx.resume();
  switch (name) {
    case 'click':
      blip(660, 0.06, 'square', 0.18, 880);
      break;
    case 'hover':
      blip(440, 0.03, 'square', 0.05);
      break;
    case 'tab':
      blip(520, 0.05, 'square', 0.12, 620);
      break;
    case 'success':
      blip(523, 0.09, 'square', 0.16);
      blip(659, 0.09, 'square', 0.16, undefined, 0.09);
      blip(784, 0.14, 'square', 0.16, undefined, 0.18);
      break;
    case 'error':
      blip(180, 0.18, 'sawtooth', 0.2, 90);
      break;
    case 'launch':
      noiseWhoosh(0.7);
      blip(320, 0.5, 'sawtooth', 0.08, 720);
      break;
  }
}

/** Prime the AudioContext on the first user gesture. */
export function primeSfx() {
  const c = ensureCtx();
  if (c && c.state === 'suspended') void c.resume();
}
