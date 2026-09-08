import { useEffect, useRef } from 'react';
import { useUi } from '../stores/ui';
import { useSettings } from '../stores/settings';

/**
 * PVPMODE theme transition: a fullscreen noise-dissolve (luma/threshold
 * wipe driven by procedural value-noise fBm). Phase A wipes the target
 * theme's accent color IN through organic cloud edges with a hot
 * near-inverted edge band; the theme swaps at peak cover; phase B dissolves
 * back out to the new page. Rendered at quarter resolution and upscaled
 * with pixelation so the dissolve reads chunky, not smooth.
 *
 * Respects reduce-motion (instant swap) and degrades to instant on GL failure.
 */

const VERT = `
attribute vec2 a_pos;
void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }
`;

const FRAG = `
precision mediump float;
uniform vec2 u_res;
uniform float u_t;
uniform vec3 u_col;
uniform vec3 u_edge;

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

void main() {
  vec2 uv = gl_FragCoord.xy / u_res;
  float aspect = u_res.x / max(u_res.y, 1.0);
  float n = fbm(uv * vec2(7.0 * aspect, 7.0));
  float cover = u_t < 0.5 ? u_t * 2.0 : (1.0 - u_t) * 2.0;
  float m = smoothstep(cover - 0.08, cover + 0.08, n);
  float edge = smoothstep(0.10, 0.0, abs(n - cover))
    * step(0.001, cover) * step(cover, 0.999);
  // posterized grade on the wipe color for a chunky game look
  vec3 graded = floor(u_col * 5.0 + 0.5) / 5.0;
  vec3 col = mix(graded, u_edge, edge);
  float alpha = 1.0 - m;
  if (alpha < 0.004) discard;
  gl_FragColor = vec4(col, alpha);
}
`;

// wipe body color (darkened target accent) + hot edge per target theme
const THEME_WIPE = {
  nether: { col: [0.42, 0.03, 0.1] as const, edge: [1.0, 0.88, 0.9] as const },
  overworld: { col: [0.5, 0.4, 0.02] as const, edge: [1.0, 0.98, 0.9] as const },
};

const DURATION_MS = 900;

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

    // instant path: no motion, or no GL
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

    canvas.width = Math.max(2, Math.floor(window.innerWidth / 4));
    canvas.height = Math.max(2, Math.floor(window.innerHeight / 4));

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

      const uRes = gl.getUniformLocation(prog, 'u_res');
      const uT = gl.getUniformLocation(prog, 'u_t');
      const uCol = gl.getUniformLocation(prog, 'u_col');
      const uEdge = gl.getUniformLocation(prog, 'u_edge');
      const pal = THEME_WIPE[target];
      gl.uniform2f(uRes, canvas.width, canvas.height);
      gl.uniform3f(uCol, pal.col[0], pal.col[1], pal.col[2]);
      gl.uniform3f(uEdge, pal.edge[0], pal.edge[1], pal.edge[2]);
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.clearColor(0, 0, 0, 0);

      const t0 = performance.now();
      let swapped = false;
      const frame = (now: number) => {
        const t = Math.min(1, (now - t0) / DURATION_MS);
        if (t >= 0.5 && !swapped) {
          swapped = true;
          apply(); // swap theme at peak cover
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
  return <canvas ref={canvasRef} className="pvp-wipe" aria-hidden="true" />;
}
