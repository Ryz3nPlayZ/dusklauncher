import { useEffect, useRef } from 'react';
import { useUi } from '../stores/ui';
import { useSettings } from '../stores/settings';

/**
 * PVPMODE theme transition — background-layer only, never over the UI.
 *
 * A radial noise-dissolve flood that starts at the scene sun (both scenes
 * park their sun left-of-center) and expands outward: phase A floods the
 * shimmer in (the theme swaps underneath while covered), phase B erodes it
 * back out through the noise field, revealing the new scene. The flood is a
 * high-exposure gradient with a time-scrolled specular streak field, which
 * is what gives the liquid-metal shimmer.
 *
 * Palettes (directional, like the reference):
 * - nether -> overworld: deep purple -> violet -> lavender-white, white-hot
 *   leading edge, overexposed.
 * - overworld -> nether: black -> dark red -> crimson, white-hot hairline
 *   core at the front.
 *
 * Mounted inside .stage (below the app UI), so panels/buttons stay visible
 * throughout. Reduce-motion or GL failure = instant swap.
 */

const VERT = `
attribute vec2 a_pos;
void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }
`;

const FRAG = `
precision mediump float;
uniform vec2 u_res;
uniform float u_t;
uniform vec2 u_sun;
uniform float u_toNether;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
             mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) {
  return vnoise(p) * 0.55 + vnoise(p * 2.13 + 7.7) * 0.28 + vnoise(p * 4.41 + 3.1) * 0.17;
}

// directional palettes; s = 0 trailing inner .. 1 leading front
vec3 palOver(float s) {
  vec3 c1 = vec3(0.10, 0.02, 0.22); // deep purple
  vec3 c2 = vec3(0.42, 0.12, 0.85); // violet
  vec3 c3 = vec3(0.85, 0.82, 1.00); // lavender-white
  vec3 c4 = vec3(1.00, 1.00, 1.00); // white hot
  vec3 col = mix(c1, c2, smoothstep(0.0, 0.45, s));
  col = mix(col, c3, smoothstep(0.45, 0.8, s));
  col = mix(col, c4, smoothstep(0.8, 0.97, s));
  return col * 1.35; // overexposed
}
vec3 palNether(float s) {
  vec3 c1 = vec3(0.00, 0.00, 0.00); // black
  vec3 c2 = vec3(0.30, 0.02, 0.06); // dark red
  vec3 c3 = vec3(1.00, 0.10, 0.22); // crimson
  vec3 c4 = vec3(1.00, 1.00, 1.00); // white-hot hairline
  vec3 col = mix(c1, c2, smoothstep(0.0, 0.5, s));
  col = mix(col, c3, smoothstep(0.5, 0.85, s));
  col = mix(col, c4, smoothstep(0.93, 1.0, s));
  return col * 1.25;
}

void main() {
  vec2 uv = gl_FragCoord.xy / u_res;
  float aspect = u_res.x / max(u_res.y, 1.0);
  vec2 p = vec2(uv.x * aspect, uv.y);
  vec2 sun = vec2(u_sun.x * aspect, u_sun.y);

  // noise field drifts outward from the sun as the wipe runs (liquid feel)
  vec2 flow = normalize(p - sun + 1e-4) * u_t * 1.6;
  float n = fbm(uv * 6.0 + flow + vec2(0.0, u_t * 0.7));
  float n2 = fbm(uv * 13.0 - flow * 1.7 + 4.2);

  float d = distance(p, sun);
  float dn = d + (n - 0.5) * 0.38; // noise-eroded front

  // phase A: flood radius 0 -> full cover; phase B: hold then erode out
  float R = u_t < 0.45 ? (u_t / 0.45) * 1.9 : 1.9;
  float flood = 1.0 - smoothstep(R - 0.10, R + 0.02, dn);

  float erode = 1.0 - smoothstep(0.5, 1.0, u_t + (n2 - 0.5) * 0.55);
  float alpha = flood * erode;
  if (alpha < 0.004) discard;

  // band coordinate: 0 deep inside the flood, 1 at the leading front
  float s = clamp(1.0 - (R - dn) / 0.55, 0.0, 1.0);
  vec3 col = u_toNether > 0.5 ? palNether(s) : palOver(s);

  // liquid-metal specular: tight sine streaks warped by the noise field,
  // scrolled with time; hot where streaks align with the front
  float streak = sin(dot(p, vec2(26.0, -18.0)) + n * 9.0 - u_t * 22.0);
  streak = pow(smoothstep(0.55, 1.0, streak), 3.0);
  float frontGlow = smoothstep(0.75, 1.0, s);
  col += vec3(1.0, 0.98, 1.0) * streak * (0.25 + 0.75 * frontGlow);

  // posterize slightly for the chunky game read, then expose hot
  col = floor(col * 7.0 + 0.5) / 7.0;

  gl_FragColor = vec4(col, alpha);
}
`;

/** sun anchor in CSS coords (both scenes park the sun left-of-center) */
const SUN = { x: 0.17, y: 0.48 }; // y flipped to GL below

const DURATION_MS = 1150;
/** theme swaps while the flood covers the scene */
const SWAP_AT = 0.34;

function compile(gl: WebGLRenderingContext, type: number, src: string) {
  const sh = gl.createShader(type)!;
  gl.shaderSource(sh, src);
  gl.compileShader(sh);
  if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error('shader');
  return sh;
}

export default function PvpWipe() {
  const wipe = useUi((s) => s.pvpWipe);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (!wipe) return;
    const target = wipe.toNether ? 'nether' : 'overworld';
    const apply = () => useSettings.getState().update({ theme: target });
    const done = () => useUi.getState().clearPvpWipe();

    if (useSettings.getState().settings.reduceMotion) {
      apply();
      done();
      return;
    }
    const canvas = canvasRef.current;
    const gl = canvas?.getContext('webgl', { alpha: true, antialias: false });
    if (!canvas || !gl) {
      apply();
      done();
      return;
    }

    // half-res + pixelated upscale: chunky, and cheap
    canvas.width = Math.max(2, Math.floor(window.innerWidth / 2));
    canvas.height = Math.max(2, Math.floor(window.innerHeight / 2));

    let raf = 0;
    try {
      const prog = gl.createProgram()!;
      gl.attachShader(prog, compile(gl, gl.VERTEX_SHADER, VERT));
      gl.attachShader(prog, compile(gl, gl.FRAGMENT_SHADER, FRAG));
      gl.linkProgram(prog);
      if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error('link');
      gl.useProgram(prog);

      const buf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.bufferData(
        gl.ARRAY_BUFFER,
        new Float32Array([-1, -1, 3, -1, -1, 3]),
        gl.STATIC_DRAW,
      );
      const loc = gl.getAttribLocation(prog, 'a_pos');
      gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);

      gl.uniform2f(gl.getUniformLocation(prog, 'u_res'), canvas.width, canvas.height);
      gl.uniform1f(gl.getUniformLocation(prog, 'u_toNether'), wipe.toNether ? 1 : 0);
      // gl_FragCoord is y-up; CSS sun is y-down
      gl.uniform2f(gl.getUniformLocation(prog, 'u_sun'), SUN.x, 1 - SUN.y);
      const uT = gl.getUniformLocation(prog, 'u_t');
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.clearColor(0, 0, 0, 0);

      const t0 = performance.now();
      let swapped = false;
      const frame = (now: number) => {
        const t = Math.min(1, (now - t0) / DURATION_MS);
        if (t >= SWAP_AT && !swapped) {
          swapped = true;
          apply(); // swap scene mid-flood; CSS crossfade runs underneath
        }
        gl.clear(gl.COLOR_BUFFER_BIT);
        gl.uniform1f(uT, t);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        if (t < 1) {
          raf = requestAnimationFrame(frame);
        } else {
          done();
        }
      };
      raf = requestAnimationFrame(frame);
    } catch {
      apply();
      done();
    }
    return () => cancelAnimationFrame(raf);
  }, [wipe]);

  if (!wipe) return null;
  return <canvas ref={canvasRef} className="scene-wipe" aria-hidden="true" />;
}
