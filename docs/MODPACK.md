# The default modpack — Dusk Essentials

The pack that ships inside the launcher and seeds the first instance on first
run. Target: **Minecraft 1.21.11, Fabric** (matches `client-mod`). Built with:

```
node tools/build-default-pack.mjs
```

which resolves every slug below to its newest Fabric/1.21.11 build on
Modrinth, pulls required dependencies recursively, and zips
`launcher/src-tauri/resources/modpacks/dusk-essentials.mrpack` (bundled via the
existing `resources/` rule in `tauri.conf.json`). Rerun + commit whenever the
lineup changes. The FasterClient jar is **not** in the pack — the launcher
force-loads it into every Fabric profile at launch (`install_and_launch`), so
the pack carries only third-party mods.

First-run wiring: `modpacks::install_bundled_pack` (Rust) + a one-shot effect
in `App.tsx` guarded by `localStorage['dusk.defaultPackSeeded']`.

## Division of labor

The launcher-brand utilities (keystrokes, CPS counter, FPS display,
toggle-sprint, armor status, combo counter — already registered as modules in
`FasterClient`) are ours. The pack therefore carries **performance** mods we
could never maintain ourselves, and **utility** mods that are big, subtle, or
server-adjacent enough that forking them would be a liability. Anything small
and client-render-only is a candidate to absorb into FasterClient instead
(see "Fork / absorb candidates" below).

## The lineup (27 files: 23 picked + 4 auto-deps)

### Performance core — depend, never fork

| Mod | License | Why in the pack |
|---|---|---|
| sodium | PolyForm-Shield-1.0.0 | The rendering engine. Ship **unmodified** + license notice; license bars building a competing renderer from it. |
| sodium-extra | LGPL-3.0 | Extra toggles (animations, particles, fog). |
| reeses-sodium-options | MIT | Usable settings GUI over Sodium. |
| iris | LGPL-3.0 | Shaders; user installs shaderpacks themselves. |
| lithium | LGPL-3.0 | Game-logic optimization. |
| ferrite-core | MIT | Memory reduction. |
| immediatelyfast | LGPL-3.0+ | Batches immediate-mode (HUD/entity) rendering. |
| entityculling | tr7zw-Protective | Skips hidden-entity rendering. **Non-commercial** — fine while the launcher is free; revisit if we ever monetize bundles. |
| dynamic-fps | MIT | Throttles when unfocused. |
| krypton | LGPL-3.0 | Network-stack optimization. |
| badoptimizations | MIT | Micro frame/render checks. |

### Utilities — depend

| Mod | License | Role |
|---|---|---|
| modmenu | MIT | Mod list + config screens (also how users reach Zoomify/YACL configs). |
| zoomify | LGPL-3.0 | The zoom. Infinite configurability, actively maintained (isxander). Do **not** fork — LGPL + huge surface. |
| freelook | AGPL-3.0 | Detached camera (Celibistrial's, 2.5M downloads). AGPL is fine for unmodified redistribution; **forking/vendoring would AGPL our client** — never absorb. |
| gamma-utils | LGPL-3.0 | Fullbright/gamma (the one Performium ships too). |
| betterf3 | MIT | Replace F3 screen. |
| chat-heads | MPL-2.0 | Player heads in chat. |
| shulkerboxtooltip | MIT | Shulker previews. |
| held-item-info | LGPL-3.0 | Held-item tooltip. |
| appleskin | Unlicense | Food/saturation HUD. |
| better-ping-display-fabric | MIT | Numeric ping in tab list. |
| dynamiccrosshair | LGPL-3.0 | Crosshair behavior/style (20M downloads). |

### Auto-resolved dependencies

fabric-api · yetanotherconfiglib (Zoomify) · cloth-config (Gamma Utils) ·
fabric-language-kotlin (Zoomify) · text-placeholder-api (Mod Menu).

## What Performium taught us (and where we differ)

Performium (`performium-was-taken`, 490k downloads, MIT) is the closest prior
art: client-only Fabric, "under 50 mods", performance-core + curated configs.
Its v2 (MC 26.2) runs Sodium/Lithium/C2ME/Krypton/FerriteCore/VMP/
ScalableLux/ImmediatelyFast/EntityCulling/MoreCulling/BadOptimizations/Packet
Fixer + Iris/Continuity/Puzzle/ETF/EMF/OptiGUI/Capes + Zoomify/Gamma Utils/
Language Reload/Resourcify + ~40 hand-tuned config overrides, and it contains
**no mods of the author's own** — the value is curation, not code.

Differences, deliberately:

- **1.21.11, not 26.2** — that's what FasterClient builds against; revisit
  together when we move the client mod to 26.x.
- **No C2ME / VMP / ScalableLux / Packet Fixer** — chunk-gen and server-side
  throughput don't help a client/utility pack; C2ME is alpha-quality.
- **No ModernFix** — its Fabric line skips 1.21.11 entirely.
- **PvP/QoL utilities in the pack** (freelook, crosshair, ping, shulker
  tooltip…) — Performium is perf-only; our pack is the Lunar-style default
  instance, and the HUD basics come from FasterClient instead of FlexHUD.
- **No config overrides yet** — Performium's biggest hidden value. Worth
  copying later via `overrides/` in the generator (options.txt presets,
  sodium-options.json, etc.).

## Fork / absorb candidates (the "build the rest of the utilities" list)

Utilities small and client-render-only enough to **absorb into FasterClient**
rather than ship third-party — each has a working reference implementation to
study (all MIT/Zlib/BSD, so reference-and-rewrite is unproblematic):

| Utility | Reference | License | Notes |
|---|---|---|---|
| HUD layout editor | Azz-9/Flex-HUD (`flexhud`) | MIT | The one "flex hud" that exists — not by maybeizen. Movable-module system; our `Module` already stores x/y anchors, so the missing half is the drag screen (see docs/IN-GAME-GUI.md). |
| Toggle-sprint HUD | Aeltumn/toggle-sprint-display | LGPL-3.0 | We already have the module; match its indicator styling. |
| Keystrokes/CPS | FolzyStudio/Keystrokes+ · marblock/modern-keystrokes | MIT | Same — our modules exist, need the render layer. |
| Coordinates / direction HUD | Boxadactle/CoordinatesDisplay | GPL-3.0 | GPL: reference only, clean-room the code. |
| Screenshot gallery | LGatodu47/screenshot-viewer | MIT | Later; launcher could own this surface instead. |
| Time/weather changer | alex265/time-weather-changer | BSD-2 | Small mixin into client time rendering. |

**Never absorb** (license or size): zoomify (LGPL), freelook (AGPL),
gamma-utils, sodium stack, anything tr7zw (protective license).

**Optional tiers for the Store** (not the default pack): Xaero's Minimap +
World Map (ARR — requires a visible credit link to the mod page when
distributed outside CurseForge/Modrinth, and bars pack monetization), ReplayMod
(GPL, fine — but banned on some PvP servers, so never default), 3D Skin
Layers + Not Enough Animations (tr7zw-Protective), C2ME (for heavy worldgen
users).

## License watchlist for a distributed bundle

1. **sodium** — PolyForm Shield: ship unmodified, keep the license notice,
   non-compete clause (no competing renderer from its code).
2. **tr7zw mods** (entityculling is in-pack) — non-commercial; fine while the
   launcher/pack is free, must be dropped if we monetize.
3. **Xaero's** (not in pack) — ARR with conditional pack permission.
4. **AGPL freelook** — redistribute unmodified only.
5. Avoid entirely: `custom-crosshair-mod` (ARR, no pack permission stated),
   NC-licensed fullbright variants.
