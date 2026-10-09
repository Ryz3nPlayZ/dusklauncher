# Performance review — the Dusk Essentials pack and the injected client

A code review, not a benchmark: nothing here was timed in game. It records
what each Dusk profile runs, what DuskClient adds to every frame and tick,
and what is worth doing next.

## What a Dusk profile runs

**JVM** (`core/src/profile.rs`): 2 GB min / 4 GB max heap by default, G1 with
the usual Minecraft tuning (`G1NewSizePercent=20`, `G1ReservePercent=20`,
`MaxGCPauseMillis=50`, 32 MB regions). The old ZGC + `AlwaysPreTouch`
defaults, which made the game slow to start and held the whole heap from the
first second, are migrated away on startup (`settings.rs`, version 1). The
memory slider rewrites only `-Xmx`/`-Xms`, and `-Xms` is capped at 2 GB, so
a large slider value doesn't claim all that memory at launch.

**Mods** (`docs/MODPACK.md`): the performance core is the set most optimised
packs agree on. Sodium renders, Lithium makes game logic cheaper (this also
helps single player and hosted worlds, since the built-in server runs it),
FerriteCore saves memory, and ImmediatelyFast batches HUD and entity drawing.
EntityCulling and MoreCulling skip what you can't see, BadOptimizations
removes small per-frame checks, and Krypton speeds up the network code. None
of them do the same job twice, and DuskClient replaces the mods that would
otherwise add their own hooks (zoom, freelook, fullbright, ping, AppleSkin,
shulker previews, crosshair, Dynamic FPS).

**Video defaults** (`modpacks::seed_dusk_defaults`) are applied only when a
Dusk instance is created. `options.txt` is written only if the instance
doesn't have one yet. Two Sodium settings are also set: a CPU render-ahead
limit of 2, and, on macOS, lower resolution on Retina screens.

## What DuskClient adds per frame and per tick

The client has about 60 mixins on 1.21.11+. The ones on hot paths, and their
cost while their module is **off**:

| Hook | Runs | Cost while off |
|---|---|---|
| Particles (`SingleQuadParticleMixin`, `ParticleLightMixin`, `ParticleMixin.move`) | every particle, every frame | one static `Particles.active()` check; the per-type lookup is an identity-map hit, cached per type |
| Replay hand (`ReplayHandStateMixin`: `getItemBySlot`, `isUsingItem`, …) | every living entity, many times a tick (server side too in single player) | one static null check (`ReplayPlayer.current`) |
| Glint colour (`GlintColorMixin` on `RenderType.draw`) | every draw call | one reference compare against the glint pipeline |
| Damage tint / shield events (`LivingEntity.handleDamageEvent`, `handleEntityEvent`) | only when the server sends the event | negligible |
| Item Physics (`ItemPhysicsRendererMixin`) | every dropped item, every frame | one boolean on the render state, set when the state is extracted |
| Scroll Transfer (`ScrollTransferMouseMixin`) | each scroll notch | a screen-type check |
| Scrollable Tooltips (`TooltipScrollMixin` on the tooltip positioner) | each tooltip drawn | one enabled check; a new position object only while the tooltip is actually scrolled |
| Item Pickups (`ItemPickupMixin` on `handleTakeItemEntity`) | once per pickup packet, not per frame | one static `ItemPickups.active()` check |
| Custom Skies | the sky pass | nothing unless a resource pack has an OptiFine sky; the sky cube is built once and stays on the GPU, and the pipelines are made once per blend mode |

Modules that loop over entities limit how often they do it. EntityCount
counts once per game tick and caches the text. Shield Statuses and Cape
Physics run only while their module is on, and only once per tick, over the
player list rather than every entity. The own-nametag check makes one small
box query per frame, and only in third person with that option on.

Every hot hook checks a flag first and returns before doing any work.

## Per-frame work trimmed (October 2026)

- **Numbers on the HUD and nametags are built by hand.** `Fmt.fixed`,
  which Speed, Reach, Distance, Compass, Pitch, TPS and Server Lag already
  used, was a wrapper around `String.format`, which parses its pattern on
  every call; it now writes the digits itself. Cooldowns, Looking At,
  Playtime, Stopwatch, Potion Effects, waypoint distances and the hearts on
  nametags called `String.format` directly and now go through `Fmt`
  (`fixed`, `clock`, `pad2`) or plain string joins. Checked against `String.format`
  on 8.5 million values (random, edge and halfway cases, 0–3 decimals,
  every clock time under 3 hours) with no difference; one call takes
  about 35 ns instead of 196 ns. Nametag hearts mattered most, since they
  ran once for every visible player every frame.
- **Text HUD modules work out their text once per frame.** `TextHud` keeps
  the text for the frame it was made for, so asking for the width, height
  and drawing it don't build it three times. Item Pickups does the same
  for its list, which was rebuilt (and expired entries filtered) four
  times a frame.

## Hook audit (October 2026)

Every hook that runs per particle, per entity or per tick was read for work
done while its module is off. Nothing new needed fixing; this is what was
checked, so the next pass can start from it.

- **Per particle, per frame** (`SingleQuadParticleMixin`,
  `ParticleLightMixin`, `ParticleMixin`): `ParticleHooks.of` checks
  `Particles.active()` before anything else and resolves a particle type's
  settings once into an identity map, so with the module off the six reads
  per particle are a flag check each.
- **Per entity, per frame**: damage tint (`DamageOverlayMixin`), cape shape
  (`CapeExtractMixin` → `CapeSim.shape`), nametag filter, health and badge
  (`NametagStateMixin` → `NametagHooks`) each return on their module's flag
  first. The cape simulation map is pruned every tick, so players who leave
  don't pile up.
- **Per tick** (`DuskClient` end-of-tick handler): modules tick only while
  on, the cosmetics loadout file is checked once a second, the shared ray
  cast runs only while Distance or Sign Reader is on, and Hypixel Tweaks'
  handler returns at once with no GG pending.
- **Instance defaults**: Dusk Essentials' Sodium tuning (two frames queued
  ahead instead of three, lower resolution on Retina screens) is written when
  Sodium arrives with it. Running Essentials again on an instance that
  already had Sodium used to write them over the player's own choices; it
  no longer does.

## Small costs left in on purpose

- HUD text is still rebuilt each frame it's shown, not once per tick. It's
  a few short strings per frame; caching per tick would save little.
- Item Physics calls `getModelBoundingBox()` a second time for items lying
  on the ground (vanilla already calls it once). It's a small calculation
  over the item's model parts.

## Worth considering next

- **C2ME** for players who host worlds or explore new chunks a lot. It's
  listed as an optional Store tier in MODPACK.md, because it changes how the
  world generates in parallel and isn't needed for multiplayer.
- **ModernFix** (LGPL-3.0) speeds up loading and lowers memory use in
  large packs. Dusk Essentials is small, so the gain would be small. Check
  that it works with Sodium and Iris on the newest versions before adding it.
- **Test frame times in game** on a slow machine, comparing
  vanilla + Essentials with and without DuskClient. Measuring with
  `runClientGameTest` is off limits (it opens a game window), so this has
  to be a manual run.
