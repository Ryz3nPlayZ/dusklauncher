# Custom in-game GUI — implementation guide

How custom GUI works in DuskClient (MC **1.21.11** and **26.2** from one source tree, Fabric API 0.141.2 / 0.161.0,
**Mojang mappings** — the 2D draw class is `GuiGraphics`, ids are
`net.minecraft.resources.Identifier`). Written against what's already in the
tree; every "exists" line is a real anchor.

## What already exists

| Piece | Where |
|---|---|
| Custom title screen (vanilla widgets + fill/drawString) | `gui/DuskTitleScreen.java` |
| In-game settings screen, opened by Right Shift keybind | `gui/DuskSettingsScreen.java`, `DuskClient.java:48-57` |
| Title-screen takeover (setScreen mixin) | `mixin/MinecraftClientMixin.java` |
| Module system: id, category, enabled, **x/y anchors**, Gson persistence | `module/Module.java`, `module/ModuleManager.java` |
| 6 registered modules (keystrokes, CPS, FPS, toggle-sprint, armor, combo) — state-only stubs | `DuskClient.java:37-43` |
| Texture upload precedent (NativeImage → DynamicTexture under `duskclient:`) | `cosmetics/CapeTexture.java` |
| Background-texture hook, deliberately unimplemented | `DuskTitleScreen.renderCustomBackground` (lines 88-95) + `DuskConfig.backgroundPath` |

The one thing missing for "custom GUI in game": **nothing renders the modules**
— there is no HUD layer registration anywhere in the mod.

## The three surfaces

### 1. Screens (menus) — already proven

A `Screen` subclass: build widgets in `init()`, draw in `render()` with
`renderBackground(...)` → `super.render()` → `fill`/`drawString` on top. Open
with `minecraft.setScreen(...)` (see the keybind handler). Vanilla widgets
(`Button.builder(...).bounds(x,y,w,h).build()`, `EditBox`) are free; custom
looks come from drawing your own chrome behind/around them. `DuskTitleScreen`
is the reference.

### 2. HUD — the missing layer (do this first)

Register one Fabric API HUD layer in `onInitializeClient` that renders every
enabled module:

```java
HudLayerRegistrationCallback.EVENT.register(attach ->
    attach.attachLayerAfter(IdentifiedLayer.MISC_OVERLAYS,
        Identifier.fromNamespaceAndPath("duskclient", "modules"),
        (guiGraphics, delta) -> DuskClient.modules().renderAll(guiGraphics, delta)));
```

(`HudLayerRegistrationCallback` is Fabric API's current HUD API — it orders us
after a named vanilla layer instead of fighting other mods on the legacy
`HudRenderCallback`.) Then give `Module` a render method and implement it per
module with `GuiGraphics` primitives — `fill` for plates, `drawString` with
`Minecraft.getInstance().font`, `renderItem`/`renderItemDecorations` for the
armor module. Modules already persist x/y anchors (`Module.saveState()`), so
positions survive restarts for free. Draw text with the vanilla font at GUI
scale; if we want the launcher's two-tone pixel type in-game, that's a custom
font provider JSON under `assets/duskclient/font/` — later.

Rules of thumb: respect `client.options.hideGui`, skip when
`client.options.renderDebug` if a module would overlap F3, and multiply
plate alpha by the vanilla text opacity option so the HUD honors accessibility
settings.

### 3. The HUD editor (drag-to-place)

A plain `Screen` (open it from `DuskSettingsScreen`): render the game world
via `renderBackground` (in-world it already shows through), then render every
module at its anchor exactly as the HUD layer does, each inside a drawn
border (`fill` corners). Interactions:

- `mouseClicked` → hit-test modules against their `(x, y, w, h)`; give
  `Module` `width()`/`height()` (fixed per module — keystrokes is one grid,
  CPS/FPS one line, armor a column).
- `mouseDragged` → move the grabbed module (clamp to the scaled window), set
  `enabled`, mark dirty.
- `onClose` → `ModuleManager.saveConfig()` (already writes `{enabled, x, y}`
  per module).

This is the same design FlexHUD (MIT, github.com/Azz-9/Flex-HUD) ships; ours
can stay much smaller because anchors/categories/persistence already exist.

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
- **Freelook** — mixin the camera update (`Camera`/`GameRenderer` orientation
  path) to use a detached yaw/pitch while a keybind is held, capture mouse
  delta in `MouseHandler` before vanilla consumes it, restore on release.
  Needs care in third-person + smoothing; freelook is AGPL-licensed, so this
  is reference-only (read their repo, write our own).

## Suggested build order

1. HUD layer registration + render for the six existing modules.
2. HUD editor screen (drag + persist).
3. `DuskSettingsScreen` becomes a scroll list of modules (the current
   one-page toggle loop is already at its limit) + "layout…" button for (2).
4. Title-screen background texture from `DuskConfig.backgroundPath`.
5. Launcher ↔ client mod wallpaper/config bridge.
