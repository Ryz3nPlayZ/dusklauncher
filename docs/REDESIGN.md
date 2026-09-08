# DuskLauncher Redesign (Dawn-parity pass)

Reference: Dawn Launcher screenshots (home, create-profile flow, browse
modpacks, cosmetics inventory/skins, accounts, store, active-profile state,
in-game home, mod menu, resource packs). Rule from `DESIGN.md` still holds:
**every visible element does something real — no ads, no dead chrome.**

## 1. Design system (implemented)

Tokens: `launcher/src/design/tokens.css` (`--btn-*`, type scale).
Buttons: `base.css` — one Dawn system everywhere:

- dark gradient tile `linear-gradient(#2a2a2e, #141416)`, **2px black** frame,
  lit top edge, hard drop shadow;
- hover = `brightness(1.18)`; **press = translate(2px,2px) + shadow drop**,
  `80ms steps(3)` (the "pushed down" feel);
- meanings: `--btn-cta` PLAY/primary (gold/crimson per theme, `.pbtn--hero`
  with white corner ticks), `--btn-install` green INSTALL/ADD/APPLY/CREATE
  (`.pbtn--install`), `--btn-danger` red STOP/LOGOUT/REMOVE (`.pbtn--danger`).

Typography law: **Monocraft Bold for headings, tabs, buttons, titles**;
Inter for body/descriptions/metadata only (`base.css` enforces the split —
this was the "font doesn't look Minecraft" bug: pixel font was inconsistently
applied, not the wrong font).

## 2. Screens (implemented, backend-backed only)

- **Home** (`views/Home.tsx`): centered, max-width 640 console with PLAY NOW
  + instance selector only (QUICK JOIN removed). Running state = ACTIVE green
  tile + red STOP square + selector (Dawn active-state pattern).
  Player: `SkinViewer` with `interactive={false} breathe` — no drag/zoom,
  sine-bob idle (paused while running / reduce-motion), white-silhouette +
  darken hover retained from `home.css`.
- **Instances** (ex-Profiles, `views/Instances.tsx`): IMPORT removed. NEW
  INSTANCE → choose modal (CUSTOM INSTANCE hero / BROWSE MODPACKS green) →
  custom form (BACK/CANCEL/CREATE) or Mods browser.
- **Mods browser** (`views/Modpacks.tsx`): INSTALL now opens a themed picker
  (`list_modpack_versions` → version dropdown showing name · MC · loader ·
  date → `install_modpack_version`). New commands in `src-tauri/src/modpacks.rs`.
- **Instance content** (`views/InstanceContent.tsx`, in instance settings):
  MODS / RESOURCE PACKS / SHADERS tabs. Installed list (toggle/remove) +
  Modrinth search filtered to the instance's game version (+ loader for mods)
  + one-click ADD. Backend: `mods.rs` generalized to
  `list/search/install/remove/set_enabled …_content` with `ContentKind`
  (`mod|resourcepack|shader` → `mods/|resourcepacks/|shaderpacks/`,
  `.disabled` suffix convention for all). Old `*_mod(s)` commands are thin
  wrappers — no IPC break.
- **Cosmetics** (`views/Skins.tsx`, now in nav with a shirt glyph):
  INVENTORY/SKINS/REGISTRY tabs kept; inspector has −/+ zoom, CANCEL +
  green APPLY SKIN, 3-point lighting (`SkinViewer.tsx`), `capeUrl` prop
  plumbed (`loadCape`) for the next step. Accounts modal unchanged in
  structure (matches Dawn's accounts popup), picks up the new buttons.
- **Backgrounds**: `customBackground` in settings (Rust `settings.rs` +
  `SettingsDto`, tolerant `#[serde(default)]`). `SceneBackground` renders the
  image/video full-bleed (video pauses while running). Footer BG tile:
  click = pick (native dialog / file input), × = reset, right-click = reset.

## 3. Store without building a backend (recommendation)

Do **not** build auth/payments/entitlements now. Ship in this order:

1. **Free local cosmetics first** (no server): cape PNGs + hat JSON in
   `profiles/<id>/cosmetics/`; client-mod renders them (see §5). Proves the
   renderer before any money.
2. **Static catalog**: a `catalog.json` + PNGs on Cloudflare R2 / GitHub
   Pages (versioned URL, sha256 per file). Launcher fetches + caches; client
   fetches by UUID hash. Cost ≈ $0, no accounts, no API to design.
3. **Paid unlocks only when retention justifies it**: Tebex (handles VAT,
   fraud, Minecraft EULA-friendly) → webhook → Cloudflare Worker + D1
   entitlements (`uuid → [sku]`). Client polls one endpoint. Never Stripe
   direct — chargebacks + VAT will eat you.

## 4. HUD / mod system (recommendation)

- **Fork, don't write**: [FlexHUD](https://github.com/... ) (Fabric, YACL
  config, anchor + drag editor already) — re-skin panels to §1, strip
  server-specific modules, keep its `HudElement` registry. Our `Module`
  framework (`client-mod/.../module/`) maps 1:1 onto elements; the pending
  "HUD layout editor screen" TODO in `FasterClient.java` becomes a thin
  adapter over their editor.
- **Motion blur**: bundle Motschen's open-source motion-blur approach
  (framebuffer accumulation, 1-pass, strength slider) as an optional RENDER
  toggle, default off. Do not hand-roll shaders — accumulation + Sodium +
  Iris conflicts are already solved there.

## 5. Cosmetics system (recommendation)

- **Capes**: custom `CapeFeatureRenderer` (Fabric `LivingEntityFeatureRenderer`)
  fetching PNG by UUID from the §3 catalog, cached in-memory + disk,
  `loadCape`-equivalent upload path. Reference impl pattern: Capelike.
- **Hats/auras/extras**: Trinkets API slots + GeckoLib models for animated
  pieces; static hats as `EntityModelLayer` boxes (no new deps). For a
  zero-code start, **Figura** already renders full custom avatars — evaluate
  interop before building hats natively.
- **Launcher side**: inventory/registry tabs + `capeUrl` prop are staged;
  next step is a `list_capes`/`equip_cape` command pair backed by the §3
  catalog, reusing the skins inspector layout.

## 6. In-game menu (scaffolded, needs `gradle build` with JDK 21)

`client-mod` (no new deps, vanilla + fabric-api only):

- `config/DuskConfig.java` — `config/duskclient.json`, shared path with the
  launcher for the background picker bridge.
- `gui/DuskTitleScreen.java` — DUSK wordmark, Singleplayer / Multiplayer /
  Dusk Settings / Minecraft Settings / Quit, account tile (session username),
  bottom-right BG shortcut. No partners/store.
- `gui/DuskSettingsScreen.java` — our mod menu: module toggles (wired to
  `ModuleManager`, persists), background path field, Done.
- `mixin/MinecraftClientMixin.java` — routes vanilla `TitleScreen` → Dusk
  while no world is loaded (recursion-safe).
- RShift (configurable in Controls) opens Dusk Settings in-game.

Deliberately deferred to a texture pass: in-game **image** backgrounds
(NativeImage upload must be pinned to one Yarn version to compile against
the right `DrawContext.drawTexture` overload); video backgrounds in-game
are out of scope (no decoder in MC — images only, launcher keeps video).

## 7. Verifiers

```bash
cd launcher && npx tsc --noEmit && npm test
cd launcher/src-tauri/core && cargo test
cd launcher/src-tauri && cargo check
cd client-mod && gradle build   # requires JDK 21; NOT verified here (no JDK on this machine)
```
