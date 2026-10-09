# Custom in-game GUI — implementation guide

How custom GUI works in DuskClient (MC **1.21 through 26.3** from one source
tree: ten build targets — `1.21.1 1.21.3 1.21.4 1.21.5 1.21.8 1.21.10 1.21.11
26.1 26.2 26.3`, each declared for a run of API-compatible releases in
`gradle.properties` — **Mojang mappings**; the 2D draw class is `GuiGraphics`,
ids are `net.minecraft.resources.Identifier` on 1.21.11+ and
`ResourceLocation` below, hidden behind `Compat`). `src/main` is shared;
`src/mc<version>/` layers stack on top of it newest-first (`mc_<key>_layers`),
later layers override file-by-file and a layer's `layer.exclude` hides files
from the layers beneath it. Written against what's already in the tree; every
"exists" line is a real anchor.

## What exists (implemented Sept 2026)

| Piece | Where |
|---|---|
| Custom title screen (vanilla widgets + fill/drawString) | `gui/DuskTitleScreen.java` |
| In-game settings screen (background path, non-HUD toggles, "Modules & HUD Editor" button) | `gui/DuskSettingsScreen.java` |
| **HUD editor** — Right Shift in game; drag/scroll/arrow-nudge elements, right-click for settings | `gui/HudEditorScreen.java` |
| **Module window** — the single centred, bound window (list ↔ settings, minimise, scroll) | `gui/ModuleWindow.java`, `gui/widget/*` (toggle, slider, cycle, colour hex field) |
| Module system: id, category, description, enabled, x/y, typed settings, Gson persistence (`config/duskclient-hud.json`) | `module/Module.java`, `module/ModuleManager.java`, `module/setting/*` |
| HUD element base classes + renderer | `hud/HudElement.java` (scale/background settings), `hud/TextHud.java` ("Label: value"), `hud/HudRenderer.java` |
| HUD layer registration + crosshair replacement (per-MC) | `src/mc*/java/.../hud/HudHooks.java` |
| Fullbright lightmap mixin (per-MC: `LightTexture.updateLightTexture` / `LightmapRenderStateExtractor.extract`) | `src/mc*/java/.../mixin/FullbrightMixin.java` |
| Draw abstraction so modules are version-agnostic | `gui/Canvas.java`, per-MC `gui/GraphicsCanvas.java` |
| Click edge-detection shared by CPS/keystrokes/reach/combo | `hud/ClickTracker.java` |

### Modules

- **HUD** (`modules/hud/`): Keystrokes, CPS, FPS, Ping, Armor Status, Held Item,
  Potion Effects, Combo Counter, Reach, Sprint Status,
  Coordinates, Nether Coordinates, Speed, Biome, Light
  Level, Clock, Game Time, Day Counter, Weather, Playtime, Entity Count,
  Memory, Server Address. Each is a `HudElement` with its own settings; the
  starter set (FPS, CPS, Ping, XYZ, Keystrokes, Armor, Effects, Sprint Status)
  is on by default.
- **Ported modules.** Modules that recreate a known mod follow that mod's
  behaviour and defaults, and say so in their class comment. Permissive
  sources (MIT, Zlib, Unlicense) are ported with attribution in
  `client-mod/NOTICE`. GPL/LGPL mods are behaviour-only (no code), and
  tr7zw mods (3D skin layers, wavey capes) are our own implementations.
  - **Toggle Sprint**: toggle-toggle-sprint (Zlib). Keys flip vanilla's own
    Sprint/Sneak toggle options. Toggle Sneak is unbound because Right Shift
    opens the Dusk menu.
  - **Freelook**: FreeLook (MIT). Hold Left Alt or toggle with Right Alt;
    shoulder clamp and eased return.
  - **Hunger Info**: AppleSkin (Unlicense). Saturation/exhaustion overlays,
    hunger/health previews for held food, tooltip food values and the
    `appleskin:` sync payloads (exhaustion/saturation only come from
    AppleSkin servers or singleplayer). Not registered when AppleSkin is
    installed.
  - **Shield Statuses**: Walksy's Shield Statuses + WalksyLib (MIT). Tints
    every player's shield green/red by raised/disabled state.
  - **Compact Chat**, **Chat Timestamps** (MIT), and the **Combo Counter**
    from Eymistaken's HUD (MIT).
  - **Chat Heads** (MPL, behaviour only; 1.21.9+, not registered when Chat
    Heads is installed): the sender's face as a glyph in front of their
    lines, the sender being the first of a line's opening words that is a
    player's name. **Chat Mentions**: your name and your own words coloured,
    with a ping, except in your own messages.
  - **Confirm Disconnect** (LGPL, behaviour only): asks before leaving.
  - **Fullbright**: Gamma Utils' gamma controls (LGPL, behaviour only). G
    toggles 1500 %, arrow keys step 10 % within −750…1500 %, an action-bar
    "Gamma: N%" message and an optional smooth transition.
- **Render**: Custom Crosshair (cross/square/dot/circle, gap/size/thickness,
  outline, rainbow, colour-by-target, bow/attack-cooldown gap), Motion Blur
  (natural-motionblur style frame accumulation, strength 1–100 %; runs at
  the tail of `GameRenderer.renderLevel` so the HUD/GUI is never blurred —
  `render/MotionBlurRenderer.java` + `post_effect/motion_blur.json`).

### Adding a module

1. Subclass `TextHud` (one-liner value) or `HudElement` (custom `width/height/render` on a `Canvas`), or `Module` for non-HUD.
2. Declare settings with `add(new BoolSetting/IntSetting/ChoiceSetting/ColorSetting(...))`; the module window builds its widgets from them.
3. `modules.register(new X())` in `DuskClient.onInitializeClient`. Persistence, the editor and the window pick it up automatically.

Version-specific Minecraft calls go through `compat/Compat.java` (`currentScreen`, `setScreen`, `dayTime`, …); everything under `src/main` must compile against all ten targets (anything that differs per version belongs in a `src/mc*` layer).

### Verifying

`gradle build -Pmc=<mc>` for each of the ten targets (JDK 21 for 1.21.x, JDK
25 for 26.x). `gradle runClientGameTest -Pmc=<mc>` runs `HudGameTest` on the
targets that have a gametest harness (`mc_<key>_gametest` is `false` for
1.21.1 and 1.21.3, whose Fabric API lacks the client gametest module): it
enters a world, asserts crosshair/fullbright state, that the motion-blur post
pass actually ran, and screenshots the live HUD plus the editor's list and
settings views into `build/run/clientGameTest/screenshots/`. The same run
includes `MixinAuditGameTest`, which force-loads every mixin target so a stale
injection descriptor fails the test instead of crashing a player when the
target class first loads. It also includes `ServerApiGameTest`, which runs the
`dusk:hello`/`dusk:rules` handshake against the integrated server. Rendering has
been checked in-game on 1.21.11 and 26.x; the older targets are verified by
compiling and by javap-ing their mixin targets against the mapped jars.

## Not bundled (from the reference mod list)

- **Simple Voice Chat** — needs its server-side protocol; nothing to
  reimplement client-only. Ship it as a regular mod in the modpack.
- **bactromod** extras beyond the HUD (TPS estimation etc.) — not done.

## Textured custom chrome (when fill/drawString isn't enough)

1. Ship PNGs under `src/main/resources/assets/duskclient/textures/gui/`,
   reference as `Identifier.fromNamespaceAndPath("duskclient", "gui/panel")`.
2. Draw with `GuiGraphics.blit(...)` (stretched) or — better for panels —
   `graphics.blitNineSliced(...)` so corners stay crisp at any size.
3. For runtime files (the launcher wallpaper bridge below), upload a
   `NativeImage` through a `DynamicTexture` — copy the registration pattern
   from `CapeTexture`, which already handles `duskclient:` ids and
   render-thread registration.
4. Custom interactive widgets: subclass `AbstractWidget`, override
   `renderWidget` + narrate; Dusk screens then compose them like vanilla
   `Button`s.

## Title-screen wallpaper (hook exists, fill it in)

`DuskConfig.backgroundPath` (empty = vanilla panorama) +
`DuskTitleScreen.renderCustomBackground` are waiting. Implementation: if the
path is set, load it once into a `DynamicTexture` (NativeImage supports PNG;
for the launcher's MP4s transcode a still frame at import time — the game
cannot play video), then in `renderCustomBackground` blit it stretched to the
screen *before* the dark overlay. Natural bridge: the launcher already writes
`duskclient.json`; on instance launch it can export the selected wallpaper as
a PNG next to the game dir and set `backgroundPath`, so the in-game menu and
the launcher shell share one look.

## Camera & input utilities (freelook/zoom pattern, for later)

Not GUI, but the same mixin discipline as the cosmetics package:

- **Zoom** — we ship Zoomify; don't build our own.
- **Freelook** — done: a port of FreeLook (MIT) in `modules/render/Freelook.java`
  and `mixin/freelook`.
