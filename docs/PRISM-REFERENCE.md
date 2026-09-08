# Prism Reference Map

Prism Launcher (GPL-3.0-only) is a **read-only** reference for this closed-source
codebase. Rules: link to files, never paste code — not even into comments.
Protocol facts (URLs, JSON shapes, error codes) are not copyrightable; Prism's
expression of them is. When in doubt, verify against minecraft.wiki or the live
Mojang/Fabric/Modrinth APIs instead.

Repo: https://github.com/PrismLauncher/PrismLauncher (`develop` branch, checked 2026-09-07).

## Auth — `launcher/minecraft/auth/`

| File | What to learn | Maps to |
|---|---|---|
| `AuthFlow.h` | Login as a chain of steps, each with own retry/error mapping | `core/src/auth.rs`, `src-tauri/src/auth_flow.rs` |
| `steps/MSAStep` + `steps/MSADeviceCodeStep` | The two MSA entries (auth-code+loopback vs device-code fallback) | `auth::exchange_code`, `auth_flow::run_login` (device-code = future fallback) |
| `steps/XboxUserStep`, `steps/XboxAuthorizationStep`, `Parsers.cpp` | XErr table: which wire response means child account vs ban vs missing Xbox profile | `auth::map_xsts_error` |
| `steps/EntitlementsStep`, `MinecraftProfileStep`, `GetSkinStep` | Ownership check → profile fetch → skin fetch ordering | `auth::has_entitlements`, `fetch_profile`, `upload_skin` |
| `MinecraftAccount.h/.cpp` | Refresh state machine: silent refresh vs forced interactive login | `auth::refresh_session`, `commands::ensure_play_session` |
| `AccountList` (+ `AccountData`) | Multi-account store shape | single-account for now; skim only |

## Downloads — `launcher/net/`

| File | What to learn | Maps to |
|---|---|---|
| `NetJob.h` | Job/queue model for concurrent fetching | `core/src/download.rs` (`download_all`) |
| `ChecksumValidator.h` | Hash-before-write discipline | `download::fetch_and_write` retry-once |
| `HttpMetaCache.h` | Conditional-GET caching for version lists | **missing**: would make manifest refresh nearly free |
| `FileSink.h` | Streaming to disk instead of buffering | **missing**: `download_one` buffers whole file in RAM; fine for jars, revisit for >200MB artifacts |

## Mods — `launcher/modplatform/`

| File | What to learn | Maps to |
|---|---|---|
| `ModIndex.h` | Version metadata schema (project ↔ version ↔ file) | `core/src/modrinth.rs` |
| `EnsureMetadataTask.cpp` | murmur-hash fingerprinting of local jars to match provider entries — **the** trick behind update detection | **missing**: update checks are the next mods milestone |
| `CheckUpdateTask.h` | Update-check orchestration | future |
| `modrinth/`, `flame/` | Per-provider quirks | `modrinth.rs`; CurseForge needs our own API key + ToS compliance before touching |

## Java — `launcher/java/`

| File | What to learn | Maps to |
|---|---|---|
| `JavaChecker`, `JavaInstallList`, `JavaVersion` | Detecting system installs (parse `java -version`, rank by major) | **missing**: we provision Mojang runtimes (`core/src/java.rs`); detection is a settings-page fallback |

## Skip entirely

Qt UI files, CMake build, translations, `PasteUpload`, instance-window code.
None of it applies to a Tauri+React shell.
