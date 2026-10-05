# DuskLauncher

A modern, PvP-oriented Minecraft launcher for **1.21 through 26.2**. Lean where Lunar is bloated, transparent where Dawn is closed-source. No ads, ever.

- `docs/RESEARCH.md` — competitive analysis of Lunar, Badlion, Feather→Dawn (with sources)
- `docs/ARCHITECTURE.md` — technical design, verified API details, IPC contract
- `docs/DESIGN.md` — pixel-art design system + rendering/performance architecture

## Install

**macOS** (Apple Silicon + Intel), via Homebrew:

```bash
brew install --cask ryz3nplayz/tap/dusklauncher
```

The builds aren't Apple-notarized yet, so the cask strips the quarantine flag itself (Homebrew 7 dropped `--no-quarantine`) — it opens first try. Installed from the DMG instead? Do it by hand: `xattr -dr com.apple.quarantine /Applications/DuskLauncher.app`, or right-click → Open once. Either way the launcher updates itself from then on (`brew upgrade` skips it on purpose).

**Linux** (x86_64): one command, no sudo, installs into your home folder and uses your system's WebKitGTK 4.1 (the AppImage's bundled copy shows a grey window on newer Mesa, e.g. Arch / Hyprland):

```sh
curl -fsSL https://raw.githubusercontent.com/Ryz3nPlayZ/dusklauncher/main/launcher/scripts/install-linux.sh | sh
```

It adds an app-menu entry and a `dusklauncher` command, and the launcher's UPDATE button re-runs it. `… | sh -s -- --uninstall` removes it (instances and settings stay). The AppImage and .deb are still on the [latest release](https://github.com/ryz3nplayz/dusklauncher/releases/latest).

**Windows**: grab the installer from the [latest release](https://github.com/ryz3nplayz/dusklauncher/releases/latest).

## Layout

- `launcher/` — Tauri 2 desktop app. Rust core (`src-tauri/core`: meta, download, auth, fabric, modrinth, natives, java, profile, launch) + React/TS pixel-art UI (animated parallax scenes, live 3D player render, Modrinth modpacks, local skins).
- `client-mod/` — Fabric mod ("DuskClient", nine builds covering every release from 1.21 through 26.2, force-injected into every Fabric instance the launcher starts): module framework, 34 HUD elements (keystrokes, CPS, FPS, ping, TPS, armor, effects, shield, combo, reach, coords, clock, …), render modules (custom crosshair, hitboxes, nametags, particles, motion blur, fullbright, colour grading, low fire/shield, time and weather changers, …), Toggle Sprint, cosmetics, clips and replays, and an in-game HUD editor (Right Shift) with a single centred module window. See `docs/IN-GAME-GUI.md`.

## Development

```bash
# Launcher (Rust core + Tauri shell)
cd launcher
npm install
npm run tauri dev

# Core tests
cd launcher/src-tauri/core && cargo test

# Frontend typecheck
cd launcher && npx tsc --noEmit

# Cosmetics server tests
cd server && cargo test

# Browser-only UI dev (mock backend, no Rust needed)
cd launcher && npm run dev

# Regenerate bundled pixel art (avatar, default skin, app icon)
cd launcher && node scripts/gen-art.mjs

# Release build (.app + .dmg on macOS; regenerates icons via `npx tauri icon`)
cd launcher && npm run tauri build

# Client mod (all nine Minecraft targets; Gradle 9.7 + JDK 21 for 1.21.x, JDK 25 for 26.x — CI uses the same)
cd client-mod && for mc in 1.21.1 1.21.3 1.21.4 1.21.5 1.21.8 1.21.10 1.21.11 26.1 26.2; do gradle build -Pmc=$mc || break; done

# In-game tests (boots the client: mixin audit, HUD, cosmetics, server API; not on 1.21.1/1.21.3)
cd client-mod && gradle runClientGameTest -Pmc=1.21.11
```

## Roadmap

1. **M1 — Core launch**: Microsoft auth (OAuth → XBL → XSTS → minecraftservices), install/launch vanilla + Fabric 1.21.11 and 26.2 on macOS/Windows, sha1-verified downloads, natives extraction, log streaming, Mojang-provisioned Java runtimes. *(launch pipeline + UI done; MS auth works via the Official sign-in mode — see `docs/ARCHITECTURE.md`)*
2. **M2 — PvP suite**: client-mod MVP modules + drag-and-drop HUD layout editor, profile management, Modrinth modpack browsing + one-click install. *(launcher side done)*
3. **M3 — Performance**: curated Sodium/Lithium stack, G1 defaults tuned like Mojang's launcher, per-profile tuning presets (BALANCED / LOW LATENCY generational ZGC). *(JVM defaults and presets done)*
4. **M4 — Trust & growth**: settings sync, Discord RPC, open server-integration API, cosmetics. *(done; server protocol in [docs/SERVER-API.md](docs/SERVER-API.md))*
5. **Later**: 1.8.9 support. *(NeoForge profiles done: 1.20.2 and later, through the official installer; DuskClient stays Fabric-only)*

## Anti-cheat stance

Launch via standard Fabric loader (`KnotClient`); no JVM agents, no runtime injection, no jar rewriting; mixins are client-side-only (rendering/HUD/input) and never touch outbound packets or movement math. See `docs/ARCHITECTURE.md`.

## Before release

- Azure app Minecraft-services permission approval via https://aka.ms/mce-reviewappid (needed only for the optional Azure App sign-in mode; the default Official mode needs no approval)
- OS code signing (Apple Developer ID / Authenticode) is *not* required for updates — only for a prompt-free first install. Add the `APPLE_*` secrets listed in `.github/workflows/release.yml` and the macOS builds sign + notarize on their own.

## Releasing

Two commands, from `launcher/`, on a clean tree:

```bash
scripts/release.sh 0.2.1      # bumps Cargo.toml / package.json / lockfiles, commits "Release v0.2.1", tags, pushes
```

The tag push runs `.github/workflows/release.yml`: it checks the tag matches the workspace version, builds the client mod jars, then macOS (arm64 + Intel), Windows and Linux (AppImage + deb), signs the updater bundles with the minisign key (`TAURI_SIGNING_PRIVATE_KEY` repo secret; pubkey in `tauri.conf.json`), and opens a **draft** release with the installers and `latest.json`. Draft = nobody's launcher sees it yet, so install a build and try it first. Then:

```bash
scripts/publish.sh v0.2.1     # verifies every platform's installer + updater bundle + .sig + latest.json, writes notes from the commits, publishes, bumps the Homebrew cask
```

The Homebrew cask lives in [ryz3nplayz/homebrew-tap](https://github.com/ryz3nplayz/homebrew-tap) (`Casks/dusklauncher.rb`) and is regenerated by `publish.sh` with the new version and DMG checksums — never edit it by hand.

Publishing is the moment running launchers pick it up — they poll `releases/latest/download/latest.json` every 6 hours and on startup, then show UPDATE TO vX.Y.Z in the corner. `scripts/publish.sh vX.Y.Z --check` verifies without publishing.
