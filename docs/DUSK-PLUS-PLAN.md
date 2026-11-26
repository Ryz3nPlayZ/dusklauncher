# Dusk + OneClient features: everything left to do

Audit date: 2026-11-25. This covers DuskClient (`client-mod/`), the PolyPlus fork (`duskplus/`, "Dusk+"), the launcher, and 1.8.9.

Size guide: **S** is an afternoon, **M** is a day or two, **L** is most of a week, **XL** is more than that.

---

## Where things stand

- In DUSK 1.21.11, DuskClient 0.2.0 and the fork (polyplus 2.3.1) both load, along with OneConfig 1.9.4, polycompose and Fabric Language Kotlin. That instance launches, and the log has no mixin errors.
- The fork is built for 9 versions: 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, 26.1, 26.2 and 26.3. **OneConfig has no 1.21.3 build**, so 1.21.3 stays Dusk-only. The fork's source also has a 1.8.9 Ornithe target that has never been built.
- Upstream PolyPlus is now **2.4.1** (released today). Our fork is based on 2.3.1, and OneClient's 1.8.9 already runs 2.4.0.
- The launcher **does not deliver the fork at all**. The 1.21.11 instance has it only because the jars were copied in by hand. Nothing in `launcher/src-tauri` mentions polyplus or OneConfig.
- Only two things are coordinated between Dusk and the fork:
  1. The title screen: Dusk hands it over when polyplus is loaded.
  2. `DuskBridge`: the fork calls Dusk's account system for sign-in, friends, messages, blocks and privacy.

  Everything else is patched by both mods independently.
- All Polyfrost server addresses have been removed from the fork. The server-backed features are either pointed at Dusk's server or switched off (see Phase 5).

---

## Owned by you (I stay out)

The new cosmetics system: **emotes, cosmetics, capes** (and wardrobe/outfits as part of it). Right now both mods hook player, avatar and cape rendering, and both have emotes, so your new system should replace both sides. The fork's outfit code calls `/outfits/...`, but the server has `/v1/me/outfits`, so it is unwired. That's yours to decide.

---

## Phase 0: Fix what's already broken or wrong (S each)

| # | Item | Where |
|---|---|---|
| 0.1 | Right shift opens OneConfig, and Dusk's settings key gets wiped to unbound. Most likely cause is OneConfig's keybind conflict detector plus both mods defaulting to right shift. Not proven yet. | `DuskClient.java:191`, OneConfig `oneConfigKeybind` |
| 0.2 | The fork's configs load twice ("already initialized, skipping" for pause, lobby, party, mainmenu, screens and cosmetics) | fork config registration |
| 0.3 | The black square. Still not explained. I removed Polydium, Gnetum and Ixeris from Dusk Essentials on a guess, and the code comment states that guess as fact. Test each one alone in game (you test), then correct the comment. | `launcher/src-tauri/src/mods.rs` ~957 |
| 0.4 | Slipstream was removed from the macOS list on the same guess. OneClient 1.8.9 runs it fine ("Presenting without OpenGL swapping" in its log). Re-test it. | `mods.rs` `DUSK_ESSENTIALS_MAC` |
| 0.5 | Dusk Essentials ships `chat-heads` and `held-item-info`, which duplicate Dusk's ChatHeads and HeldItem. Its own comment says duplicates are left out. | `mods.rs` |
| 0.6 | `dusk-essentials.mrpack` doesn't match the list in code | `launcher/src-tauri/resources/modpacks/` |
| 0.7 | Party invite button bug | `duskplus/.../gui/lobbyshell/LobbyParty.kt:308` |
| 0.8 | Voice text still describes Epic Online Services | `duskplus/.../gui/lobbysocial/SocialParty.kt:610` |
| 0.9 | `duskplus/NOTICE` has claims that are wrong. Rewrite it to say exactly what was taken and changed. | `duskplus/NOTICE` |
| 0.10 | Check every launcher instance starts. Earlier only the main one launched cleanly, and I never confirmed the others after the fixes. | launcher profiles |

---

## Phase 1: Dusk look, OneClient polish (M–L)

Goal: keep OneClient's custom menus and make them look like Dusk.

1. **Fork OneConfig** (LGPL-3.0; credit it in NOTICE). Our build replaces the Modrinth OneConfig jar. Its `fabric.mod.json` "breaks polyplus <2.3.0" rule has to keep working with our fork's version.
2. **Dusk theme.** Register a theme from `OneConfigUITheme` with Dusk's colours, shapes, font and logo, and make it the default. About 126 fork files already read colours from the theme. Swap the ~50 hard-coded `Color(0x…)` values for theme tokens.
3. **Replace Polyfrost art and branding**: logos, the "OneClient" window title (`OneClientWindowTitle.kt`), the client brand, the watermark, the startup splash and the panorama defaults.
4. **Polish fixes in the OneConfig fork**: corner radius consistency, text that overflows or gets cut off, and ellipsis and min-widths on buttons and cards. Go screen by screen with screenshots.
5. **Right shift goes back to Dusk** and OneConfig's own key is turned off by default. This fixes 0.1.

## Phase 2: One settings menu (L)

OneConfig's menu becomes the only settings menu, and Dusk's modules show up inside it.

1. Write an adapter that turns each Dusk `Module` into a OneConfig `Tree`, with each setting as `Properties.functional(getter, setter)`. Dusk keeps storing its own settings, so nothing migrates and nobody loses config.
2. Map Dusk's 8 setting types (Bool 231, Int 109, Choice 45, Color 34, Key 25, Text 9, Action 5, PixelGrid 1) to OneConfig controls. **Not yet confirmed:** how OneConfig picks a control (slider, dropdown and so on) for a property. It uses `MetaKeys`, but I haven't tested it.
3. Put the pages in Dusk groups (HUD, Render, Chat, Misc) with `PageGroup.create` and `OneConfigPages.register`.
4. HUD: **keep Dusk's HUD system and editor** for now. OneConfig's HUD is a separate migration to decide on later. Link the HUD editor from the menu.
5. Retire `DuskSettingsScreen` once the adapter covers everything.

## Phase 3: Remove the double-ups (M total, S per area)

Each area gets one owner. The other side checks for it and stays out.

| Area | Owner | Work |
|---|---|---|
| Title / lobby | Fork, rebranded | Already handed over. Remove Dusk's lobby code paths once Phase 1 lands. |
| Pause menu | Fork | Move Dusk's extra buttons (replay etc.) into the fork's pause screen, then drop Dusk's pause hooks |
| Chat UI + emoji | Fork | Compare Dusk's ChatStack, History, Copy, CopyLines, Timestamps, Search and Mentions against the fork's chat. Keep what the fork lacks as fork-side features or Dusk modules that skip when the fork is loaded. |
| Tab list ping | One of them | Both draw ping. Pick one; the files for it are another session's, so coordinate. |
| Keybind screens | Fork + OneConfig | Drop Dusk's KeyBindsList/KeyBindsScreen mixins, or make them skip |
| Confirm disconnect | Dusk | Turn off the fork's compat hook |
| Fullbright / hitbox | Dusk | Fork compat hooks skip |
| Social / friends | Fork screens, Dusk server | Already on Dusk's backend through DuskBridge. Retire `SocialScreen`. |
| First-run defaults | Dusk modules | Remove the fork's onboarding toggles that duplicate Dusk modules (toggle sprint, motion blur, fire overlay, shield height and others) |
| Cosmetics, emotes, capes | **You** | Your rework |

## Phase 4: The launcher delivers it (M)

1. Ship the fork jar per version: either from our release assets or bundled in the client-mod releases.
2. Fetch OneConfig (our fork), polycompose 3.0.9 and Fabric Language Kotlin per version, and inject them with `-Dfabric.addMods` like DuskClient. Skip them on 1.21.3.
3. Only inject when the instance doesn't already have its own copy (same check e4mc uses).
4. Make the release CI build the fork for all 9 versions next to client-mod.
5. Build and check exit codes for every version. Smoke-test each one in game (you test).

## Phase 5: Server-backed features (only when you ask)

What the fork calls today compared with what `server/src/main.rs` has:

| Feature | Fork calls | Server has | State |
|---|---|---|---|
| Friends, requests, blocks, messages, privacy | `/v1/friends…`, `/v1/blocks…`, `/v1/messages…`, `/v1/me/privacy` | Yes | Working through DuskBridge |
| Parties + invites | `/invites…`, party routes | No | `PartyApi` is stubbed to say "Parties aren't available"; hidden |
| Invite a friend to your world | `/sessions/{id}/invite` | No | Hosting itself works (e4mc relays it, and the launcher already fetches e4mc). Only passing the invite needs the server. |
| Voice | `/voice/token`, `/voice/pass` (Epic) | `/v1/voice` (ours, different) | Epic is removed. Needs a decision: wire it to our own voice, or drop it. |
| Live updates (friend online, new message) | websocket (`PolyConnection`) | No | Polling only |
| Featured servers | `disabled.invalid` | No | Off |
| Discord rich presence | needs `DUSK_DISCORD_APP_ID` | n/a | Built, off until you make a Discord app |

## Phase 6: 1.8.9 (XL, needs a decision first)

**Why OneClient 1.8.9 hits ~2000 fps.** Your install (`~/Library/Application Support/Polyfrost/OneClient/clusters/1.8.9 Fabric`) doesn't run on Forge. It runs on **Ornithe**, which is Fabric for old versions, on **Java 25**, with these mods:

| Mod | What it does | Licence | Can we use it? |
|---|---|---|---|
| **Argentum** (+ extras, Cera) | Rendering rewrite. It bundles **Celeritas**, a Sodium-style renderer for old versions. Most likely the biggest part of the FPS gain. | All rights reserved | Download from Modrinth only. No fork, no bundling. |
| **Polydium** | Misc client optimizations | PolyForm Shield | Download only. The licence bars competing products, so don't fork it. |
| **Starlight (legacy)** | Light engine rewrite | GPL-3.0 | Yes |
| **Sarcio** | Memory fixes and bug fixes | LGPL-3.0 | Yes |
| **Slipstream** | Presents frames without the OpenGL swap (the log says so) | MIT | Yes |
| Async logger, Pylon (raw input), Keyyyyyyyy, SoundFix, CrashPatch | Smaller fixes | mixed | Mostly yes |

Its log shows the game on the Apple GL-over-Metal driver ("2.1 Metal") on your M5. I can't say which mod gives how much without testing each one, but a Sodium-class renderer on 1.8.9's simple world is the usual reason for numbers like that.

**Our 1.8.9 is Forge** (`client-mod-189`, about 178 files, Forge 11.15.1.2318). None of the Ornithe speed mods, OneConfig 1.8.9 or PolyPlus 1.8.9 can load on Forge. So:

- **Option A (recommended): move Dusk 1.8.9 to Ornithe.**
  1. Launcher support for the Ornithe loader (it has none today).
  2. Port client-mod-189's modules from Forge events to Ornithe and mixins.
  3. Build the fork's existing `1.8.9-ornithe` target.
  4. Fetch the Ornithe speed stack from Modrinth.

  This gives the same FPS base as OneClient, plus the OneClient UI on 1.8.9. Server-side ViaVersion for your bedwars server is unaffected.
- **Option B: stay on Forge.** Keep the current build and look for Forge-side speed mods (Patcher, OptiFine and similar). Much less work, but it won't match the Ornithe numbers, and there's no OneClient UI on 1.8.9.

`client-mod-189` currently has uncommitted edits from another session (`GuiPlayerTabOverlayMixin`, `Ping`). Any port waits until that work is committed.

## Phase 7: Keeping up with upstream (S, recurring)

- Rebase the fork from 2.3.1 to upstream 2.4.1, and repeat for each new release. Record what we changed (DuskBridge, the removed Polyfrost and Epic code, stubs) so a merge is mechanical.
- Do the same for the OneConfig fork from Phase 1.

---

## Decisions I need from you

1. **1.8.9:** move to Ornithe (Option A) or stay on Forge (Option B)?
2. **Voice:** wire the fork's voice UI to our `/v1/voice`, or remove it?
3. **Chat:** for each Dusk chat feature the fork lacks, port it into the fork or keep it as a Dusk module?

## Suggested order

Phase 0 → Phase 1 → Phase 2 → Phase 3 → Phase 4. Then Phase 6 once you've decided on 1.8.9, and Phase 7 before any public release. Phase 5 only when you ask. Your cosmetics work runs alongside all of this.
