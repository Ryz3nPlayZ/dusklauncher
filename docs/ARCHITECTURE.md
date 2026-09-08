# FasterLauncher — Architecture

Status: design, 2026-09-05. Facts below were verified live against Mojang/Fabric meta and minecraft.wiki on this date.

## Product framing

A modern, PvP-oriented Minecraft launcher targeting **1.21.11** and **26.2** first (more versions later). Design drivers from `docs/RESEARCH.md`:

- Lean, fast, native-feeling launcher — counter Lunar's Electron+Overwolf "adfarm" and Dawn's "no Chromium" pitch
- Transparent (open-source core), no ads, minimal telemetry
- Profile-per-version model; anti-cheat-safe launch path (standard Fabric, no injection)
- PvP mod suite as first-class bundled Fabric mods

## Monorepo layout

```
fasterlauncher/
├── docs/                 # RESEARCH.md, ARCHITECTURE.md, DESIGN.md
├── launcher/             # Tauri 2 desktop app
│   ├── src-tauri/        # Rust core (crate: fasterlauncher-core + tauri shell)
│   │   ├── core/         # launcher engine, UI-independent, testable
│   │   │   └── src/
│   │   │       ├── meta.rs        # version_manifest_v2 + per-version JSON
│   │   │       ├── download.rs    # concurrent fetch, sha1 verify, resume
│   │   │       ├── java.rs        # Mojang java-runtime provisioning
│   │   │       ├── auth.rs        # Microsoft OAuth → XBL → XSTS → MSA
│   │   │       ├── fabric.rs      # Fabric meta profile JSON installation
│   │   │       ├── modrinth.rs    # modpack search + .mrpack install
│   │   │       ├── natives.rs     # LWJGL natives download + extraction
│   │   │       ├── profile.rs     # user profiles (version, mods, JVM args)
│   │   │       └── launch.rs      # classpath/args builder, process spawn
│   │   └── src/          # Tauri commands/events: commands, modpacks, settings, skins
│   ├── scripts/          # gen-art.mjs (original pixel art PNG generator)
│   └── src/              # React + TypeScript UI (design/, background/, player/, views/, stores/)
│       └── views: Home, Profiles, Mods (Modrinth), Settings, Skins
└── client-mod/           # Fabric mod: the in-game PvP client
    └── src/main/java/... # module framework + PvP modules
```

## Launcher core (Rust)

### Version meta
- Source of truth: `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` (format unchanged in 2026; includes per-version sha1).
- Per-version JSON at `https://piston-meta.mojang.com/v1/packages/<sha1>/<id>.json`. Structure (verified against 26.2): `mainClass`, rule-based `arguments.game`/`arguments.jvm` (os.name/os.arch/features), `libraries` with `downloads.artifact` + native `classifiers` (e.g. `natives-macos-arm64`), `assetIndex`, `downloads.client` (sha1 + size), `javaVersion`.
- 26.2 specifics to honor: assets index `"32"` from `resources.download.minecraft.net/<2-char>/<hash>`; JVM args `--sun-misc-unsafe-memory-access=allow`, `--enable-native-access=ALL-UNNAMED`; the **`default-user-jvm`** block (`-Xms2G/-Xmx4G`, compact object headers, `AlwaysPreTouch`, **ZGC** on modern OSes) — we adopt it as our default PvP profile JVM config (frame-time consistency).

### Java runtime provisioning
- Never use system Java. Fetch Mojang's provisioned runtimes via the launcher meta `java-runtime` manifests (`javaVersion.component` per version): `java-runtime-epsilon`/major 25 for 26.1+; Java 21 for 1.20.5–1.21.x; Java 17 for 1.18–1.20.4. Cache per-component under `<data>/runtimes/`, platform-specific manifests with sha1-verified tarballs.

### Downloads
- Concurrent (8–16) with global concurrency limit; sha1 verify every artifact (libraries, client jar, assets); on mismatch re-download once then surface error. Never modify Mojang jars in place.

### Authentication (Microsoft)
Two sign-in modes (`Settings → Microsoft sign-in → Sign-in method`), sharing one code path (`core/src/auth.rs`, `AuthMode`):

- **Official (default):** the official Minecraft launcher's Xbox title ID (`00000000402b5328`) on the legacy `login.live.com` endpoints (scope `XboxLive.signin XboxLive.offline_access`, `RpsTicket: t=<token>`). Works with **zero Azure app and zero Microsoft approval** — same approach as RaphiMC's MinecraftAuth and the wider third-party ecosystem. Sign-in is **device-code** (RFC 8628): `oauth20_connect.srf` issues a user code shown in the UI, the user confirms at `microsoft.com/link`, and we poll `oauth20_token.srf` (`authorization_pending` backoff, `slow_down` doubles the interval). live.com registers only `oauth20_desktop.srf` as this title's redirect — loopback URIs like `http://127.0.0.1:<port>` are **rejected** (verified live), so the auth-code flow can't be used here.
- **Azure App:** DuskLauncher's own registration on the v2 `consumers` endpoints (scope `XboxLive.signin offline_access`, `RpsTicket: d=<token>`). Requires Microsoft's AppID approval — submit at https://aka.ms/mce-reviewappid (weeks of lead time) — otherwise Xbox rejects the token with an opaque HTTP 400.
   Azure registration checklist (an Xbox HTTP 400 almost always means one of these is wrong):
   - Supported account types = **Personal Microsoft accounts only** (work/school accounts are rejected by Xbox Live).
   - Platform = **Mobile and desktop applications** with Redirect URI exactly `http://127.0.0.1:19735` (byte-for-byte what the launcher sends).
   - Public client flows enabled, no client secret; the token request repeats `scope=XboxLive.signin offline_access` so refreshes keep the grant.
   - If the 400 persists, the stored grant is stale: the launcher's **Re-consent** sign-in (`prompt=consent`) forces Microsoft to show the permission screen again.
Chain (see [minecraft.wiki/w/Microsoft_authentication](https://minecraft.wiki/w/Microsoft_authentication)):
1. Interactive login: auth-code + loopback redirect (Azure mode, `prompt=select_account`) or device-code (Official mode).
2. `POST user.auth.xboxlive.com/user/authenticate` (`RpsTicket: d=` Azure token or `t=` title token) → XBL token + `uhs`
3. `POST xsts.auth.xboxlive.com/xsts/authorize` (`SandboxId: RETAIL`, `RelyingParty: rp://api.minecraftservices.com/`) → XSTS token; map `XErr` codes (child account, ban) to user messages
4. `POST api.minecraftservices.com/authentication/login_with_xbox` (`identityToken: XBL3.0 x=<uhs>;<xsts>`) → Bearer access token (~24h)
5. `GET /entitlements/mcstore` (license check) and `GET /minecraft/profile` (uuid, name, skins)
- Persist refresh token in OS keychain (via Tauri stronghold/keyring plugin); silent refresh on launch. Switching sign-in methods invalidates the stored refresh token (endpoints differ) — next sign-in is interactive.
- **Microsoft-only auth.** No offline/cracked mode: EULA requirement and anticheat ecosystems block/fingerprint such launchers.

### Launch path (anti-cheat safe)
- Vanilla and Fabric differ only in the version JSON + classpath: Fabric's `https://meta.fabricmc.net/v2/versions/loader/<game_version>/<loader_version>/profile/json` is a drop-in piston-format profile whose `mainClass` is `net.fabricmc.loader.impl.launch.knot.KnotClient` (verify exact v2 schema at implementation time). Installing Fabric = fetch that profile, add its libraries, done.
- Rules: no JVM agents, no runtime injection, no jar rewriting. Mixins are client-side only (rendering/HUD/input), never touch outbound packets or movement math. Client jar sha1 always verified. This is what keeps Grim/Vulcan/Hypixel-style setups comfortable.

### Profiles
- One profile = { MC version, loader (vanilla/fabric), loader version, mod list (bundled client-mod + user-added from Modrinth), JVM args (default from 26.2 `default-user-jvm`), resolution, server shortcuts }.
- Data layout: `<data>/profiles/<id>/{version.json, mods/, assets, libraries}` with a shared content-addressed library/asset store across profiles (like Lunar's single-install model, but standard).
- Modrinth API (`api.modrinth.com/v2`) for user mods: search, per-version file resolution, mrpack later.

### App shell
- **Tauri 2 + React/TS.** Matches Modrinth's Theseus pattern; ~10x lighter than Electron; Tauri updater plugin for signed auto-updates. Long ops (downloads/auth) run in Rust, stream progress to UI via Tauri events.
- Code signing is a release requirement, not MVP: Apple Developer ID + notarytool; Windows Authenticode (EV/OV) or Azure Trusted Signing — Tauri updater and SmartScreen both demand it.

### UI / rendering (implemented)
- Frameless window with custom pixel titlebar; the full design system lives in
  `docs/DESIGN.md` and `launcher/src/design/`.
- **IPC contract** (`launcher/src/lib/tauri.ts` ↔ `src-tauri/src/`): commands
  `list_profiles`, `create_profile`, `update_profile`, `delete_profile`,
  `list_versions`, `install_and_launch`, `stop_game`, `begin_login`,
  `begin_reconsent_login` (same flow with `prompt=consent`, the repair path
  for an Xbox 400 caused by a missing `XboxLive.signin` grant), `logout`,
  `get_current_account`, `get_settings`, `set_settings`, `get_app_info`,
  `search_modpacks`, `install_modpack`, `list_skins`, `import_skin`,
  `delete_skin`, `set_selected_skin`, `read_skin`. Events: `launch-progress`
  (throttled), `game-log` (batched lines), `game-state`
  (starting/running/exited + exit code). DTOs are camelCase both sides.
- Launch command line is built as **jvm args → mainClass → game args** (unit
  tested in `core/src/launch.rs`); LWJGL natives are downloaded per-platform
  classifier and extracted (META-INF skipped); the asset index is persisted to
  `assets/indexes/<id>.json`; game stdout/stderr stream to the UI launch console.
- Global settings (`settings.json`) cover theme/sound/motion/fps, default
  memory + JVM args, per-Java-major runtime overrides, env vars, and
  prelaunch/wrapper/post-exit hooks (all wired into the spawn).

## Client mod (Fabric)

- Ship as a regular Fabric mod (the same jar doubles as a standalone install, like Dawn's standalone jar).
- **Module framework**: registry of toggleable modules, each with config (keybind, HUD anchor, colors), JSON config persistence, and an in-game modular HUD menu (drag-to-position HUD elements) — the UX baseline set by Lunar/Badlion/Dawn.
- MVP modules: Keystrokes, CPS counter, FPS display, ToggleSprint/ToggleSneak, HitDelayFix (the community-validated fix Lunar removed), Zoom, Armor Status HUD, Combo display, Coordinates, Custom scoreboard.
- Performance stack (phase 2): bundle Sodium + Lithium + immediatelyFast-style optimizations on the profile, curated and version-pinned.
- Targets: 1.21.11 first (last obfuscated version — needs Yarn/mojmap as usual); 26.1+ is **unobfuscated**, so the mod port to 26.2 is materially cheaper. Use one codebase with per-version branches/multiloader layout as needed.
- Server-facing API (phase 3, moat): an open equivalent of Apollo/BadlionClientModAPI — servers can advertise/disallow modules; plus Discord Rich Presence.

## Milestones

1. **M1 — Core launch**: MS auth → install/launch vanilla + Fabric 1.21.11 and 26.2 on macOS/Windows (Linux close behind). Log viewer. Auto-update skeleton.
2. **M2 — PvP suite**: client-mod MVP modules + HUD layout editor; profile management UI; Modrinth mod browsing.
3. **M3 — Performance**: curated Sodium/Lithium stack, per-profile tuning presets, ZGC defaults.
4. **M4 — Trust & growth**: open-source core polish, settings sync, Discord RPC, server-integration API, cosmetics (deferred — no ads, ever).
5. **Later**: 1.8.9 support (evaluate: legacy Fabric vs custom approach), NeoForge profiles, Bedrock is out of scope.

## Open items / needs from user

- Azure app registration (client_id + redirect) and Minecraft-services permission approval — required before real MS login works.
- Code-signing certificates (Apple Developer ID, Windows Authenticode/Azure Trusted Signing).
- Branding: name/domain, store assets.
