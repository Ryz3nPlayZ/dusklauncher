# Background scene assets

Layered pixel-art background extracted from **dawn.gg** (their landing page
uses the same art as the launcher). Extracted 2026-09-06/07 from the site's
public asset URLs. Important: these are dawn.gg's art files — fine as local
dev/placeholder assets, but don't ship them in a release without checking
usage rights.

## Scenes

| Directory | Theme | Used as |
|---|---|---|
| `dawn/`   | gold sunset | launcher `overworld` theme |
| `mcpvp/`  | red         | launcher `nether` theme |

Both scenes share the same 400×280 native canvas and are composed bottom→top:

| z | File | Kind | Notes |
|---|---|---|---|
| 0 | `rear.png` | static, RGBA | sky panorama: sun glow, clouds, distant mesas |
| 1 | `sea-anim.webp` | **baked 16-frame loop, 1328 ms** (~83 ms/frame) | animated water, alpha |
| 2 | `foreground.png` | static, RGBA | terrain silhouette, transparent sky |
| 3 | `flower-anim.webp` | **baked 16-frame loop, 2000 ms** (125 ms/frame) | sway animation, alpha — `dawn/` only |

Composition recipe (what dawn.gg does and what `SceneBackground.tsx` does):
stack all layers with `object-fit: cover; object-position: center bottom`,
`image-rendering: pixelated`. Native art is 400×280 — never smooth-scale it.

## Mobs (`mobs/`)

Ambient critters drifting left→right with a vertical bob. Drift/bob timings
in `SceneBackground.tsx` are lifted verbatim from dawn.gg's markup; sprite
sizes there are the site originals multiplied by `MOB_SCALE` (**0.45** — the
tuned value; site default is 1.0).

| Sprite | Scene | Site sizes |
|---|---|---|
| `bee1.webp` | dawn (×2) | 168, 120 |
| `bee2.webp` | dawn | 144, mirrored |
| `ghast-big.webp` | mcpvp (×2) | 288, 240, all mirrored |
| `ghast-small.webp` | mcpvp (×2) | 180, 144, all mirrored |

## VFX

`fire-loop.webm` — pre-baked fire video from dawn.gg's kit cards. Not part of
the background; if used, the site treatment is `mix-blend-mode: screen` at 60%
opacity. Swap-out only (it's a video, not a shader).

## Can textures be swapped / edited?

- **Static PNGs** (`rear`, `foreground`, mob sprites): fully swappable and
  editable. Any RGBA art at the same canvas size drops straight in; keep the
  pixel-art scale (400×280) or adjust `object-fit` expectations.
- **Animated WebPs** (`sea-anim`, `flower-anim`): **pre-baked frame
  animations, not re-renderable**. There is no shader or simulation behind
  them — the animation is 16 fixed frames inside the file. You cannot change
  their timing, direction, or motion from code. To change them, either swap
  the whole file for another animated WebP of the same canvas size, or
  extract/author frames yourself and re-encode.
- **`fire-loop.webm`**: same story — baked video, swap-out only.

### Quick color changes without touching assets

CSS `filter` on the layer/scene works on baked animations too (the browser
applies it post-decode): hue-rotate, saturate, brightness. Add a `filter`
style to a layer in `SceneBackground.tsx` — good for tint experiments, not
for real palette swaps (it shifts *everything*, including terrain).

### Verified recolor pipeline for animated WebPs (ffmpeg + webp tools)

Tested end-to-end (ffmpeg 9 + cwebp/webpmux from homebrew `webp`), including
a browser render check: recolors every frame, preserves alpha and frame
timing, loops forever. ffmpeg can decode animated WebP but **cannot encode**
it (no libwebp encoder in the homebrew build) — hence the cwebp/webpmux
steps. ffmpeg emits every frame **twice**; use the odd-numbered files.

```bash
# 1. decode + recolor all frames (any ffmpeg filter chain works here)
ffmpeg -i sea-anim.webp -vf "hue=h=140:s=1.3" f%02d.png     # → f01..f32, odd = unique

# 2. encode each unique frame losslessly (odd files = the 16 real frames)
for i in $(seq 1 2 31); do cwebp -quiet -lossless f$(printf %02d $i).png -o f$(printf %02d $i).webp; done

# 3. assemble: 83 ms/frame (match the original's timing!), dispose=none, no-blend, loop forever
webpmux $(for i in $(seq 1 2 31); do printf -- "-frame f%02d.webp +83+0+0+0-b " $i; done) -loop 0 -o sea-recolor.webp
```

The `-b` suffix (no-blend) matters: each stored frame is a full composed
canvas, so it must *replace* the canvas, not alpha-blend over the previous
one. With the original's per-frame duration (`webpinfo` / parse ANMF) and
alpha intact, the result is visually identical to the source — only
recolored. Pixel data round-trips exactly; only RGB under fully-transparent
pixels may be rewritten by the encoder (invisible by definition).

