# Competitive Research: Minecraft PvP Clients & Launchers

Research date: 2026-09-05. Sources verified via live web research; speculation is flagged inline.

## Executive summary

The PvP client market consolidated hard in 2025–2026:

- **March 2025**: Moonsworth (Lunar Client) acquired Badlion Client from ESL FACEIT Group. Badlion's standalone launcher is discontinued (frozen at v4.5.8); it now runs only as a toggle inside Lunar's launcher. One company controls both "legacy" PvP clients (~3M+ monthly actives combined).
- **May–June 2026**: InPvP acquired Feather Client (asset sale, explicitly to avoid liability for Feather's alleged misconduct) and rebranded it **Dawn Client**. Dawn is marketed as "the official launcher of MCPVP.com" with a $100k tournament prize pool.

The market's biggest clients share documented weaknesses that define our opportunity: a bloated, ad-serving launcher (Lunar: Electron + Overwolf, "adfarm" reputation, RAM leaks, hijacked save directories), a locked mod ecosystem, and a closed-source trust deficit that Feather's ad-fraud scandal made acute. **A lean, transparent launcher with a proper PvP mod suite attacks every documented complaint.**

## Dawn Client (primary analysis — rebranded Feather)

### Origins & trust baggage
- InPvP (owner: Mohamed "PizzaMC" Weheba) acquired Feather via asset sale, announced 2026-05-21 at UGCon; rebrand announced 2026-06-24 with full account/cosmetic/settings migration ([GamesBeat](https://gamesbeat.com/inpvp-acquires-feather-client-rebrands-as-dawn-exclusive/), [dawn.gg announcement](https://dawn.gg/community/news/feather-is-now-dawn)).
- Feather's baggage: viral 2026-04 video (CalebIsSalty) alleging the launcher loaded background ads while minimized (ad-impression fraud); ad partner Aditude cut ties 2026-03-20 then resumed with Dawn. 2022: exposed for allegedly stealing code from Sk1er's Patcher mod. Reddit consensus: not a virus, but "sketchy company" ([r/CompetitiveMinecraft "Screw Dawn Client"](https://www.reddit.com/r/CompetitiveMinecraft/comments/1urfbal/screw_dawn_client/), [virus thread](https://www.reddit.com/r/minecraftclients/comments/1uybvl7/is_dawnfeather_client_minecraft_launcher_a_virus/)).

### Architecture
- Hybrid: desktop launcher managing instances + in-game "modules". Community analysis concludes it's "just a Forge/Fabric mod under the hood with some built-in mods" ([Hypixel thread](https://hypixel.net/threads/why-feather-client-is-lying-to-you.4842120/)).
- **New Dawn launcher is explicitly NOT Electron**: built on **Kotlin Compose**, "no bundled Chromium", lightweight/fast-boot; NeoForge support "coming soon". This is the strongest market signal: lightweight native UI is now table stakes.
- Profile-per-version model (unlimited profiles, each with own mods/shaders/settings), ~1.8.9 → 1.21.x, Fabric + Forge loaders per profile. Exact 1.8.9 mechanism undocumented (likely custom mixin/agent — *speculation*).
- Platforms: Windows, macOS (Apple Silicon), Linux (Flatpak/tarball), a standalone Fabric mod jar, and Bedrock profiles ("first non-injectable Bedrock custom client").
- Fully closed-source and obfuscated; no public decompiles.

### Features
- 100+ toggleable modules: Animations, Armor Status, CPS counter, Custom Chat/F3/Fog, Damage Indicator, Zoom, FOV changer, Freecam, ToggleSprint, Keystrokes, FPS display, scoreboard/nametag HUD modules.
- Performance: FPS boost optimizations; advertised "−25ms latency reduction" (marketing claim, unverified). Sodium bundling unconfirmed (closed internals).
- Launcher: one-click Modrinth modpacks, multi-instance, log viewer, user-added Fabric/Forge mods, built-in voice chat, waypoints, screenshots, Discord Rich Presence (with a [server-facing RPC API](https://docs.feathermc.com/server-api/meta/discord-rich-presence/)).
- **MCPVP integration**: queue duels from launcher, cross-server matchmaking, spectating, stats/leaderboards, "verified built-in anti-cheat" for duels, launcher-required tournament verification.

### UX & business
- In-game mod menu with per-module HUD layout customization, pinning, recoloring; settings sync via Microsoft account.
- Free client; historical monetization = launcher ads (Aditude) + cosmetics. Dawn states it will de-emphasize ads and pivot to cosmetics/in-game items; expects no profitability for 12 months.

## Lunar Client

### Architecture
- **Electron** launcher, also distributed via **Overwolf** ([Lunar × Overwolf](https://www.lunarclient.com/news/lunar-client-x-overwolf)). Community criticism of the overhead is persistent ([Electron complaints](https://www.reddit.com/r/LunarClientHelp/comments/1c8xsax/electron_launcher_for_lunar_client_application/)).
- One install covers all versions: per-version artifacts under `.lunarclient/offline/multiver/...`, assets from Lunar's own CDN at launch, game saves redirected away from `.minecraft` (a top complaint — [example](https://www.reddit.com/r/minecraftlunarclient/comments/1u0yncr/lunar_client_has_changed_the_save_directory_of_my/)).
- Closed-source custom client per version with **proprietary JVM class-transformation at startup** (Java premain agents attachable) — not standard Fabric/Forge ([lunar-client-qt RE docs](https://github.com/Youded-byte/lunar-client-qt), [uku3lig blog](https://uku3lig.net/posts/2024-08-20-lunar-compat/)). Only ProGuard-obfuscated, so decompiles readably.
- On modern versions it **loads Sodium alongside its own optimizations** and "Turbo Entities" ([official post](https://www.lunarclient.com/news/how-to-boost-fps-in-modern-minecraft-with-lunar-client-and-turbo-entities)).
- Open artifacts: [Apollo](https://github.com/LunarClient/Apollo) (server integration API, 25+ modules), [ServerMappings](https://www.lunarclient.com/news/what-is-lunar-clients-server-mappings), [ReplayMod-new](https://github.com/LunarClient/ReplayMod-new), [lunarclient.dev](https://lunarclient.dev/).

### Features & monetization
- 65+ mods: keystrokes, CPS, FPS display, Freelook, zoom, toggle-sprint, etc. (HitDelayFix was present, then removed — [petition](https://www.change.org/p/lunar-client-add-the-hitdelayfix-back-in-lunar-and-badlion-client); the 1.8 PvP community cares intensely about this mod.)
- Built-in Replay Mod + Rewind (rolling-buffer clip tool). Friends/parties, Lunar Network minigames, Discord RPC, emote wheel.
- Free + cosmetics store + Coins; **Lunar+ subscription ~$6.99–12.50/mo** which includes "ad-free experience" — confirming the launcher serves ads ([store](https://store.lunarclient.com)).

### Complaints (2024–2026)
- RAM: freezes at max allocation, suspected leaks, 4GB+ baseline ([1](https://www.reddit.com/r/minecraftlunarclient/comments/1n3lbr5/ram_issue_while_using_lunar_client/), [2](https://www.reddit.com/r/minecraftlunarclient/comments/1s53109/lunar_taking_up_4gb_of_ram/), [LinusTechTips](https://linustechtips.com/topic/1536143-lunar-client-has-ram-consumption-issues-help/)).
- "Adfarm" launcher sentiment, forced updates, save-directory hijacking, locked mod ecosystem (no arbitrary Fabric mods).

## Badlion Client

- 100+ mods, **all free**: Keystrokes (with anti-autoclicker CPS detection), CPS, FPS display, Perspective (freelook), ToggleSneak/Sprint, zoom ([mod list](https://www.badlion.net/free-minecraft-mods)).
- Differentiator: **server-controlled mod API** — servers can disable/limit mods per-player via [BadlionClientModAPI](https://github.com/BadlionNetwork/BadlionClientModAPI). Bundles BAC anti-cheat (activates only on integrating servers; Hypixel doesn't use it).
- Monetization: Premium ~$3.33–3.99/mo, Premium+ ~$7.99/mo (cosmetics, discounts, HD skins) ([store](https://store.badlion.net/category/premium)).
- Post-acquisition: launches only through Lunar's launcher; standalone frozen at 4.5.8.

## Anti-cheat landscape

- Hypixel doesn't endorse any client; rule is "no unfair advantage". Lunar/Badlion/Dawn are widely used and not bannable per se; individual blacklisted mods can be punished regardless.
- Modern server anticheats (Grim, Vulcan — Vulcan supports 1.8–26.2) are server-side and statistical; they don't trust the client. Our job is to **not look like a cheat**: no runtime JVM injection, no agent loading, no jar transformation outside standard Fabric loader/mixin bootstrapping, keep client jar sha1-verified ([Vulcan](https://www.spigotmc.org/resources/vulcan-anti-cheat-advanced-cheat-detection-1-8-26-2-folia-supported.83626/)).
- Closed per-version clients + proprietary agents earn anticheat trust; open Fabric loaders rely on server-side mod-whitelisting policies instead.

## Strategic takeaways for FasterLauncher

1. **Leanness is the wedge**: Lunar = Electron + Overwolf + ads + RAM leaks; Dawn markets on "no Chromium" and wins. Native-feeling, fast, small launcher is table stakes.
2. **Trust is the attack surface**: Feather/Dawn's reputation damage + closed sources means an open-source-core, telemetry-light launcher has a real audience (r/CompetitiveMinecraft users are actively looking for alternatives).
3. **Proven architecture**: profile-per-version + Fabric profile JSON launch path + mixin-based client mod; a standalone mod jar is a cheap second distribution channel (Dawn ships one).
4. **Don't serve ads**. Cosmetics are the accepted monetization; every player in the space pivoted there.
5. **Server integration is the moat**: Lunar's Apollo/ServerMappings, Badlion's ModAPI, Dawn's MCPVP matchmaking. Design an open server-integration API early.
6. **PvP mod essentials** (must-have list): keystrokes, CPS counter, FPS display, toggle-sprint/sneak, hit-delay fix, animations toggles, zoom, freelook/perspective, armor status, combo display, custom scoreboard, nametags, HUD layout editor.
