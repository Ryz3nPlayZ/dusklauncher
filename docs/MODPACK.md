# Dusk Essentials — what every Dusk profile carries

Every DUSK PROFILE (a NEW INSTANCE version card, and the instance seeded on
first run) is Fabric plus this set, resolved **live** for that exact game
version by `mods::install_dusk_essentials` (lineup in `DUSK_ESSENTIALS`).
Each mod takes its newest **release** build for the version; a beta/alpha
only when the mod has no release there at all (`modrinth::newest_preferring_release`).
A mod with no build for that version is skipped. Required dependencies
(Cloth Config, Text Placeholder API, …) are pulled in afterwards, and the
instance gets the tuned video defaults (`modpacks::seed_dusk_defaults`).

The DuskClient jar is **not** part of the set — the launcher force-loads it
into every Fabric profile at launch wherever a build exists (1.21 – 26.2).

## Offline fallback

`launcher/src-tauri/resources/modpacks/dusk-essentials.mrpack` is the same
lineup pinned to **1.21.11**, used only when first run can't reach Modrinth.
Rebuild it whenever the lineup changes:

```
node tools/build-default-pack.mjs
```

First-run wiring: a one-shot effect in `App.tsx` guarded by
`localStorage['dusk.defaultPackSeeded']` — create `DUSK 1.21.11`, install the
essentials; on failure, `modpacks::install_bundled_pack('dusk-essentials')`.

## Division of labor

Anything a DuskClient module already does stays out of the set: zoom,
freelook, fullbright, tab ping, hunger/saturation, container previews,
crosshair and background FPS are all modules. The set carries the
**performance** mods we could never maintain ourselves and a few QoL mods
that DuskClient doesn't cover.

## The lineup (16 + auto-deps)

### Performance core — depend, never fork

| Mod | License | Why |
|---|---|---|
| fabric-api | Apache-2.0 | Everything else needs it. |
| sodium | PolyForm-Shield-1.0.0 | The rendering engine. Ship **unmodified** + license notice; license bars building a competing renderer from it. |
| sodium-extra | LGPL-3.0 | Extra toggles (animations, particles, fog). |
| reeses-sodium-options | MIT | Usable settings GUI over Sodium. |
| iris | LGPL-3.0 | Shaders; users bring their own shaderpacks. |
| lithium | LGPL-3.0 | Game-logic optimization. |
| ferrite-core | MIT | Memory reduction. |
| immediatelyfast | LGPL-3.0+ | Batches immediate-mode (HUD/entity) rendering. |
| entityculling | tr7zw-Protective | Skips hidden-entity rendering. **Non-commercial** — fine while the launcher is free; revisit if we ever monetize bundles. |
| moreculling | GPL-3.0 | Culls block faces/models Sodium leaves in. |
| badoptimizations | MIT | Micro frame/render checks. |
| krypton | LGPL-3.0 | Network-stack optimization. |

### QoL — depend

| Mod | License | Role |
|---|---|---|
| modmenu | MIT | Mod list + config screens. |
| betterf3 | MIT | Replacement F3 screen (no 26.3 build yet — skipped there). |
| chat-heads | MPL-2.0 | Player heads in chat. |
| held-item-info | LGPL-3.0 | Held-item tooltip. |

### Dropped in v2 (DuskClient covers them)

zoomify → Zoom · freelook → Freelook · gamma-utils → Fullbright ·
better-ping-display → TabPing · appleskin → HungerInfo / FoodTooltip ·
shulkerboxtooltip → ContainerPreview · dynamiccrosshair → CustomCrosshair ·
dynamic-fps → BackgroundFps.

## Fork / absorb candidates (the "build the rest of the utilities" list)

Utilities small and client-render-only enough to **absorb into DuskClient**
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

**Never absorb** (license or size): the sodium stack, anything tr7zw
(protective license).

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
4. Avoid entirely: `custom-crosshair-mod` (ARR, no pack permission stated),
   NC-licensed fullbright variants.

## Loaded per launch, never installed

- **e4mc** (MIT, `qANg5Jrr`) — HOST in an instance's WORLDS tab. DuskClient
  opens the world to other players as it loads (`-Ddusk.host`) and e4mc relays
  it under a public `*.e4mc.link` address, so friends join without port
  forwarding. The launcher fetches the Fabric build for the game version into
  `tools/hosting/<version>/` and hands it to the loader with
  `-Dfabric.addMods` for that launch only; an instance that already has e4mc
  in mods/ uses its own copy. The address shows in the friends pane and goes
  out with INVITE; Discord only says "Hosting a world".

## Installed when the packs ask for them

Resource packs made for OptiFine keep their extras under
`assets/minecraft/optifine/` (or `mcpatcher/`), which the game ignores.
`packmods::pack_mods` reads each enabled pack's file list (zips: just the
central directory; folders: a few existence checks) and the content tab offers
INSTALL for each feature an instance has no mod for. Fabric only. Not in the
default set: ETF/EMF cost frames, and most players' packs don't need them.

| Pack folder | Feature | Mods (Modrinth) | License |
|---|---|---|---|
| `sky/` | custom skies | nuit-interop + nuit | MIT |
| `ctm/` | connected textures | continuity | LGPL-3.0 |
| `random/`, `mob/`, `emissive.properties` | random / glowing mob textures | entitytexturefeatures | LGPL-3.0 |
| `cem/` | custom mob models | entity-model-features (+ ETF) | LGPL-3.0 |
| `cit/` | custom item textures | cit-resewn (no build past 1.21.1 — the forks are single-maintainer, closed-source or ARR, so none is installed) | MIT |
| `gui/` | custom GUI textures | optigui | LGPL-3.0 |
| `anim/` | custom animations | animatica (up to 1.21.6) | LGPL-3.0 |

Nuit Interop pins one exact Nuit build in its fabric.mod.json, often not the
newest, so mods go in one at a time and a pinned library gets the build its
mod names (`packmods::exact_pins`).
