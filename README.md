# DuskLauncher

A modern, PvP-oriented Minecraft launcher for **1.21.11** and **26.2** (more versions later). Lean where Lunar is bloated, transparent where Dawn is closed-source. No ads, ever.

- `docs/RESEARCH.md` — competitive analysis of Lunar, Badlion, Feather→Dawn (with sources)
- `docs/ARCHITECTURE.md` — technical design, verified API details, IPC contract
- `docs/DESIGN.md` — pixel-art design system + rendering/performance architecture

## Layout

- `launcher/` — Tauri 2 desktop app. Rust core (`src-tauri/core`: meta, download, auth, fabric, modrinth, natives, java, profile, launch) + React/TS pixel-art UI (animated parallax scenes, live 3D player render, Modrinth modpacks, local skins).
- `client-mod/` — Fabric mod ("FasterClient"): PvP module framework + modules (Keystrokes, CPS counter, FPS display, ToggleSprint, Armor Status, Combo display; HitDelayFix/Zoom/HUD editor pending).

## Development

```bash
# Launcher (Rust core + Tauri shell)
cd launcher
npm install
npm run tauri dev

# Core tests
cd launcher/src-tauri/core && cargo test

# Frontend unit tests + typecheck
cd launcher && npm test && npx tsc --noEmit

# Browser-only UI dev (mock backend, no Rust needed)
cd launcher && npm run dev

# Regenerate bundled pixel art (avatar, default skin, app icon)
cd launcher && node scripts/gen-art.mjs

# Release build (.app + .dmg on macOS; regenerates icons via `npx tauri icon`)
cd launcher && npm run tauri build

# Client mod
cd client-mod && gradle build   # requires JDK 21; fabric-loom
```

## Roadmap

1. **M1 — Core launch**: Microsoft auth (OAuth → XBL → XSTS → minecraftservices), install/launch vanilla + Fabric 1.21.11 and 26.2 on macOS/Windows, sha1-verified downloads, natives extraction, log streaming, Mojang-provisioned Java runtimes. *(launch pipeline + UI done; MS auth pending Azure approval)*
2. **M2 — PvP suite**: client-mod MVP modules + drag-and-drop HUD layout editor, profile management, Modrinth modpack browsing + one-click install. *(launcher side done)*
3. **M3 — Performance**: curated Sodium/Lithium stack, ZGC/AlwaysPreTouch defaults (from Mojang's 26.2 `default-user-jvm`), per-profile tuning presets.
4. **M4 — Trust & growth**: settings sync, Discord RPC, open server-integration API, cosmetics.
5. **Later**: 1.8.9 support, NeoForge profiles.

## Anti-cheat stance

Launch via standard Fabric loader (`KnotClient`); no JVM agents, no runtime injection, no jar rewriting; mixins are client-side-only (rendering/HUD/input) and never touch outbound packets or movement math. See `docs/ARCHITECTURE.md`.

## Before release

- Azure app registration + Minecraft-services permission approval (required for real Microsoft login)
- Apple Developer ID + notarization, Windows Authenticode/Azure Trusted Signing (required for Tauri auto-updater)
