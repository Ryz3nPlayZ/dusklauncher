# Cosmetics — design

Decisions (2026-09-17):

- **Capes: 100 % MinecraftCapes-compatible** — same texture format, same features (animated, glint, upside-down, ears), and we *consume* `api.minecraftcapes.net` so anyone who already has a MinecraftCapes cape shows up in Dusk with it.
- **Everything else: Cosmetica's format and renderer** — ported from [cosmetica-core](https://github.com/Cosmetica-cc/cosmetica-core) (Apache-2.0; branches `1.21.11` and `26.2` exist).
- **Assets ship in the client jar; the server only stores IDs.** One small binary + SQLite + Caddy on the Oracle ARM box.
- Java Edition only.

Reference repos studied: MinecraftCapes (LGPL-2.1), Cosmetica-2 + cosmetica-core (Apache-2.0), Essential (view-only licence → architecture reference, nothing copied).

---

## 1. Formats (these are the compatibility contract)

### 1.1 Capes — MinecraftCapes format

| | Rule |
|---|---|
| Static cape | PNG, any size; padded up to the next multiple of 64×32 (`ceil`). Vanilla cape UV layout. |
| Animated cape | Vertical strip. `frameHeight = width / 2`; `frames = height / frameHeight`; animated iff `height != width/2`. **100 ms per frame** (10 fps), fixed. |
| Elytra | Uses the cape texture (vanilla layout; the elytra region of the 64×32 tile). |
| Ears | Separate small PNG, drawn as Deadmau5 ears (vanilla `ears` model layer). |
| Flags | `capeGlint: bool` (enchant glint over the cape), `upsideDown: bool` (renders the player flipped — "Dinnerbone"). |
| Profile record | `{ "cape_url": string?, "ear_url": string?, "capeGlint": bool, "upsideDown": bool }` — this is what `GET https://api.minecraftcapes.net/profile/{uuid-without-dashes}` returns, and what our own cape record mirrors. |

Because the format is identical, a MinecraftCapes cape PNG is a valid Dusk cape asset unchanged, and vice versa.

### 1.2 Accessories — Cosmetica format

An accessory = **Blockbench block-model JSON** + **PNG** + metadata. Registry entry as shipped (`registry.json` → `accessories[]`, assets at `accessories/<id>/model.json` + `texture.png`):

```jsonc
{
  "id": 16,                    // numeric, shared id space with capes, stable forever
  "name": "Red Fire Arm",
  "attachment": "right_arm",   // head | body | left_arm | right_arm | left_leg | right_leg
  "offset": [1.0, 11.5, 0.0],  // in 1/16 units, added to the attachment's base offset
  "mirrored": false,           // swap L/R part + scale(-1,1,1)
  "frames": 1, "ticksPerFrame": 5,   // animated = vertical tilesheet, frame h = height/frames, 1 tick = 50 ms
  "flags": 0,                  // bitmask below
  "source": "cosmetica:ufa1T"  // provenance only; the mod never reads it
}
```

Flags (bitmask, from `Accessory.Flag`): `HIDE_WITH_HELMET 0x1`, `HIDE_WITH_CHESTPLATE 0x2`, `HIDE_WITH_LEGGINGS 0x4`, `HIDE_WITH_BOOTS 0x8`, `HIDE_WITH_CLOAK 0x10`, `HIDE_WITH_ELYTRA 0x20`, `HIDE_WITH_PARROT 0x40`.

Model semantics (cosmetica-core `CosmeticaModel`/`BlockFaceUV`), replicated in `AccessoryModel` (mod) and `accessoryMesh.ts` (launcher) so the two previews agree pixel for pixel:

- `elements[].from/to` in 1/16 units; `rotation {origin, axis, angle}` (or `x/y/z` degrees) rotates the corners about `origin` as X(θ), Y(−θ), Z(θ) with no rescale.
- `faces.<dir>.uv` is in **0..16 of the whole texture** regardless of `texture_size`; missing `uv` falls back to the vanilla footprint. `rotation` on a face shifts the corner→uv index by `rotation/90`; corner index `i` reads `u = i%4>1 ? u1 : u0`, `v = i%4∈{1,2} ? v1 : v0`.
- Corner order per face (0 = `from`, 1 = `to`): DOWN (0,0,1)(0,0,0)(1,0,0)(1,0,1) · UP (0,1,0)(0,1,1)(1,1,1)(1,1,0) · NORTH (1,1,0)(1,0,0)(0,0,0)(0,1,0) · SOUTH (0,1,1)(0,0,1)(1,0,1)(1,1,1) · WEST (0,1,0)(0,0,0)(0,0,1)(0,1,1) · EAST (1,1,1)(1,0,1)(1,0,0)(1,1,0).

Placement math (from `Accessory.attachmentTransform` + `CosmeticaModel.submitOnPart`), kept bit-for-bit so Cosmetica-authored models sit identically:

```
base offset by attachment: HEAD (0,+4)  RIGHT_ARM (+1,-6)  LEFT_ARM (-1,-6)  BODY/LEGS (0,-8)   [x,y in 1/16]
slim skin: LEFT_ARM x += 0.5, RIGHT_ARM x -= 0.5
mirrored: swap L/R part, scale(-1,1,1)

part.translateAndRotate(pose); scale(1,-1,-1); [mirror]; rotateY(π);
translate((ox+dx)/16, (oy+dy)/16 + 0.25, oz/16); translate(-0.5,-0.5,-0.5);
quads at from/16..to/16 via submitCustomGeometry(entityTranslucent(frame), colour -1, NO_OVERLAY, light)
```

The launcher bakes the same chain into skinview3d coordinates (`Q = m + (−8,−4,−8) + 16·offset`; `sv = mirrored ? (Qx, Qy, −Qz) : (−Qx, Qy, −Qz)`, uv `v` flipped) and parents the mesh to the matching `head/body/rightArm/…` group, so what the wardrobe shows is what the layer draws.

Quads are submitted directly (`submitCustomGeometry`) rather than through the vanilla block-model bakery: the bakery wants an atlas sprite and it would re-square the UVs, which is exactly the Cosmetica-compat we are trying to keep.

### 1.3 Per-player loadout (what the server stores)

```json
{ "cape": 7, "accessories": [16, 12], "settings": { "12": { "offset": [0, 1, 0], "mirror": false } } }
```

Slots are strings; `cape` is one numeric asset ID, `accessories` is an ordered array of them (the accessory carries its own attachment, so any number can share a body part — this replaced the per-attachment `hat`/`back`/… slots of the first draft). IDs are small ints, never re-used. Unknown slot or ID → client silently ignores (older client, newer catalog). `settings` (per-equip offset/mirror) is reserved for phase 4.

---

## 2. Bundled catalog

```
client-mod/src/main/resources/assets/duskclient/cosmetics/
  registry.json                 # { "version": 3, "capes": [...], "accessories": [...] }; capes carry an optional frameMs (default 100)
  capes/7/cape.png              # + optional ears.png; { glint, upsideDown } live in registry.json
  accessories/12/model.json
  accessories/12/texture.png
```

- `registry.json` is the single source of truth. The **launcher reads it out of the mod jar it already ships** (it's a zip) — no second copy, no CDN.
- Jar growth is the only cost. Dozens of items ≈ a few MB; fine. Escape hatch if it ever matters: move `cosmetics/` into a `cosmetics.zip` the launcher drops next to the mod and the registry loader reads from either. Server unchanged either way.
- Adding a cosmetic = add folder + registry entry + release the mod. Old clients render the new ID as nothing.

---

## 3. Server (Oracle ARM box)

**Stack**: one Rust binary (`axum` + `rusqlite` bundled + `jsonwebtoken`), `cargo zigbuild --target aarch64-unknown-linux-musl` → static binary, `systemd` unit, Caddy in front for TLS. Rust rather than Go only because the launcher is already Rust; Go would be equally fine. ~30 MB RSS.

**SQLite** (WAL mode):

```sql
CREATE TABLE loadouts (
  uuid       TEXT PRIMARY KEY,       -- 32 hex, no dashes
  loadout    TEXT NOT NULL,          -- JSON from §1.3, ≤ 2 KiB
  updated_at INTEGER NOT NULL
);
-- later, for paid items:
CREATE TABLE entitlements (uuid TEXT, item TEXT, PRIMARY KEY (uuid, item));
```

**Endpoints** (whole API):

| | |
|---|---|
| `POST /auth` | body `{uuid, username, serverId}`. Server calls `https://sessionserver.mojang.com/session/minecraft/hasJoined?username=&serverId=`; 200 with matching id → `{ "token": <JWT HS256, sub=uuid, exp=24h> }`. Rate-limit per IP. |
| `PUT /loadout` | `Authorization: Bearer`. Body = §1.3. Validate: slots ∈ allow-list, IDs are ints, ≤ 2 KiB; with entitlements on, reject IDs not owned and not free. Upsert. |
| `GET /cosmetics?uuids=a,b,c` | ≤ 200 uuids. Returns `{ "<uuid>": <loadout>, … }` for known players only. `Cache-Control: public, max-age=60`. No auth. |
| `GET /healthz` | for the uptime check. |

**Auth handshake** is the standard cosmetics-mod trick (LabyMod/Cosmetica/Essential all do it): the client performs `sessionService.joinServer(uuid, accessToken, serverId)` with a random `serverId` it just generated, then hands `serverId` to us; only the true owner of the access token could have made Mojang record that join. No Azure app, no OAuth.

Who publishes: **the mod**. It already holds the session token in-game, auths on startup, and PUTs its loadout if the local file is newer than the server copy. The launcher never talks to the cosmetics server (it can later, using its own MC token, if we want equip-from-launcher to be instant).

Bandwidth: a 200-player lobby costs one ~10 KB GET. Negligible.

---

### 3.1 Who sees what (until the server exists)

Every cosmetics mod reads its *own* backend; none of them exposes a write API. So cross-mod visibility is exactly "does the other mod's backend know about you", and only the row the Dusk mod controls can be guaranteed by us:

| wearer → viewer | Dusk client | MinecraftCapes mod | Cosmetica / other mods | vanilla |
|---|---|---|---|---|
| **Dusk registry cape** | wearer only (self); **others: not yet** — needs §3 `GET /cosmetics` + `CosmeticsStore` | only if the wearer also uploads the same PNG at minecraftcapes.net (SAVE PNG in the store) | Cosmetica shows MinecraftCapes capes, so same answer; other mods: no | no |
| **Dusk accessory** | wearer only; others `List.of()` until §3 | no (no accessory concept) | Cosmetica only, and only if uploaded to their site | no |
| **MinecraftCapes cape/ears/glint** | **yes, guaranteed** — `MinecraftCapesProvider` runs for every non-local player (`minecraftCapes && showOthers`); pinned by `CrossModCapesGameTest` against the live profile of the MinecraftCapes author | yes | Cosmetica yes; others depends | no |
| **Cosmetica cosmetics** | no (§7: not depending on their client) | no | yes | no |
| **Mojang/official cape** | yes unless `hideOfficialCapes` | yes | yes | yes |

Reading: a MinecraftCapes user is always visible to Dusk users; a Dusk user is visible to MinecraftCapes/Cosmetica users only by re-uploading their cape there; two Dusk users see each other's Dusk cosmetics only after phase 3 ships. Nothing short of a server can make the Dusk→Dusk cell a guarantee, and nothing at all can make the Dusk→other-mod cells one.

---

## 4. Client-mod (`dev.fasterlauncher.client.cosmetics`)

### 4.1 Mappings

Both reference codebases use **Mojang mappings** (`AvatarRenderer`, `AvatarRenderState`, `CapeLayer`, `PlayerSkin`, `SubmitNodeCollector`), and 26.x ships unobfuscated. Switching the mod from yarn to `loom.officialMojangMappings()` before this work makes the port mechanical and keeps one code path for 1.21.11 and 26.2. Recommended.

### 4.2 Data flow

```
PlayerInfo added (tab list)  ──►  CosmeticsStore.request(uuid)   [batched, 500 ms debounce]
                                       │  GET /cosmetics?uuids=…
                                       ▼
                     loadout ──► resolve IDs against Registry ──► build immutable PlayerCosmetics
                       │ no cape / unknown player
                       ▼
             GET api.minecraftcapes.net/profile/{uuid}  (fallback provider, 10 min cache, config toggle)
                       │
                       ▼
             textures registered on client thread ──► snapshot swapped in ──► rendered next frame
```

- `CosmeticsStore`: `Map<UUID, Entry{state NONE/LOADING/LOADED/FAILED, PlayerCosmetics, expiresAt}>`, TTL 5 min, negative cache 10 min, cleared on disconnect. One worker executor; all `TextureManager.register` calls via `Minecraft.execute`.
- `PlayerCosmetics` is immutable: cape/ears identifiers + flags, list of `Accessory`s with baked `BlockModelPart`s, precomputed hide-flags. Only swapped once everything it references is loaded (Cosmetica's `CosmeticEquipHelper` queue does the same: keep rendering the old set until the new one is fully ready).
- Local player: seeded from `config/duskclient.json` (`cosmetics.loadout`) at startup so previews work offline and before auth; then published to the server (§3).
- Provider priority for capes: **Dusk loadout → MinecraftCapes → vanilla official cape** (`hideOfficialCapes` config, default `false`).

### 4.3 Render hooks (five mixins + one feature layer)

As shipped for 1.21.11 (`mixin/cosmetics/`); older game lines swap in their own flavour of the same hooks (see the per-era table below):

| Hook | What | Port from |
|---|---|---|
| `AbstractClientPlayer.getSkin` RETURN | Return a `PlayerSkin` with cape **and elytra** `ClientAsset.Texture` swapped (one-slot cache per entry so nothing allocates per frame); strips the official cape when `hideOfficialCapes`. | cosmetica-core `AbstractClientPlayerMixin` + `CapeTextureManager`; MinecraftCapes `MixinPlayerInfo` |
| `AvatarRenderer.extractRenderState` TAIL + `@Unique` field on `AvatarRenderState` (`ExtendedAvatarRenderState`) | Carry the `PlayerCosmetics` snapshot into the render state; sets `isUpsideDown` (negating `xRot`/`yRot` like vanilla's Dinnerbone path) and `showExtraEars` when the player has ears. Non-`Player` avatars (mannequins) are skipped until phase 4. | both |
| `CapeLayer.submit` | `@WrapOperation` on `SubmitNodeCollector.submitModel` → `entityTranslucent` so alpha capes work, then a second submit at `order(1)` with `armorEntityGlint()` when `glint` (1.21.11 has no `armorCutoutNoCullGlint`). | MinecraftCapes `MixinCapeLayer`; cosmetica-core `CapeLayerMixin` |
| `Deadmau5EarsLayer.submit` | `@WrapOperation` on `submitModel` → when the player has a 14×7 ears texture, submit a second `PlayerEarsModel` baked with MinecraftCapes' UVs (`texOffs(0,0)`, `CubeDeformation(1,1,0.2)`, 14×7 sheet) via `entityCutoutNoCull`; otherwise vanilla's deadmau5 path is untouched. | MinecraftCapes `MixinDeadmau5EarsLayer` (they replace `createEarsLayer` globally; we keep both models) |
| `ClientMannequin.getSkin`/`updateSkin` (26.x only) | Same substitution for mannequins. *Phase 4.* | MinecraftCapes |
| `AccessoriesLayer` added in `AvatarRenderer.<init>` TAIL (the mixin extends `LivingEntityRenderer` to reach `addLayer`) | Draws the local player's accessories with the §1.2 transform through `submitCustomGeometry`; flag checks against helmet/chestplate/leggings/boots/cloak/elytra/parrot; skipped for invisible players and hidden parts. *Phase 2.* | cosmetica-core `AccessoryLayer` + `CosmeticaModel`, transform replicated |

Animated capes: `CapeTexture` splits the strip into one `DynamicTexture` per frame at load time and `PlayerCosmetics.Asset.texturePath()` returns the frame for `currentTimeMillis()/100 % frames`, so `CapeLayer` picks the frame on every call with no tick hook. Static capes are padded to the next power-of-two multiple of 64×32 exactly like MinecraftCapes.

Animated accessories reuse the cape scheme instead of porting `CosmeticaTexture`: `CapeTexture.prepareFrames` splits the vertical tilesheet into one texture per frame and `current()` picks by `currentTimeMillis()/(ticksPerFrame*50)`. Capes keep MinecraftCapes' 100 ms default; registry capes may override it with `frameMs` (GIF imports keep their real frame delay).

Anti-cheat stance holds: render layers, a render-state field, a skin-record substitution, two HTTP GETs. No packets, no netty, no entity/input classes.

### 4.4 Licensing in the mod

Ported files keep the Apache-2.0 header + a `NOTICE` entry (cosmetica-core: Isaiah "Eyezah" Meek, Mekal "Valoeghese" Covic et al.); MinecraftCapes-derived bits note LGPL-2.1 origin. Whole mod stays GPL-3.0-or-later. `client-mod/NOTICE` carries the attributions; the cosmetics package is a clean-room reimplementation of both projects' *behaviour* (formats, cache layout, UVs, render types), not copied source.

---

## 5. Launcher

### 5.1 The forced mod

The launcher and the client mod are separate deliverables, but every Fabric instance the launcher starts gets the mod whether or not the user installed it:

- `client-mod` builds once per game line (`gradle build -Pmc=<target>` for `1.21.1 1.21.3 1.21.4 1.21.5 1.21.8 1.21.10 1.21.11 26.1 26.2`; `gradle.properties` maps each target to the release range its jar declares); each build's `installToLauncher` copies `remapJar` to `launcher/src-tauri/resources/duskclient-<mc>.jar`. `tauri.conf.json` bundles `resources/`, so all nine jars ship inside the app and `cosmetics::client_mod_jar_for` picks the one for the profile's version:

  | Instance version | Jar |
  |---|---|
  | 1.21, 1.21.1 | `duskclient-1.21.1.jar` |
  | 1.21.2, 1.21.3 | `duskclient-1.21.3.jar` |
  | 1.21.4 | `duskclient-1.21.4.jar` |
  | 1.21.5 | `duskclient-1.21.5.jar` |
  | 1.21.6 – 1.21.8 | `duskclient-1.21.8.jar` |
  | 1.21.9, 1.21.10 | `duskclient-1.21.10.jar` |
  | 1.21.11 | `duskclient-1.21.11.jar` |
  | 26.1.x | `duskclient-26.1.jar` |
  | 26.2 | `duskclient-26.2.jar` |

  Any other version launches without the mod (`clientModSupports` in `api.ts` mirrors the mapping for the instance list). Per-era cosmetics flavours, oldest first: **1.21–1.21.1** has no render states and no `post_effect` pipeline — `PlayerRendererMixin` adds `AccessoriesLayer` from the `PlayerRenderer` ctor, `UpsideDownMixin` injects into the static `LivingEntityRenderer.isEntityUpsideDown`, the cape/ears layers wrap `PlayerModel.renderCloak`/`renderEars`, and motion blur drives the legacy `PostChain` with a code-added pass (its program JSON/fsh must live under `assets/minecraft/shaders/program/duskclient_motion_blur.*` because `EffectInstance` only resolves the `minecraft` namespace). **1.21.2–1.21.4** get `PlayerRenderState` + `CompiledShaderProgram`; **1.21.5** the `RenderPass` consumer; **1.21.6–1.21.10** uniform blocks and `GpuBuffer`; **1.21.11+** the `Avatar*` render states and `SubmitNodeCollector` described above. The 26.1 flavour is the 26.2 render/GUI code with screens still owned by `Minecraft` (26.2 moved them to `Gui`) and `getClientLevel()` in the gametest harness.
- At launch, for any profile whose loader is Fabric, `launch.rs` appends `-Dfabric.addMods=<path to the bundled jar>` to the JVM args. Fabric Loader treats that exactly like a jar in `mods/`, so nothing is copied into the instance and updating the launcher updates the mod. Vanilla/other-loader profiles are untouched.
- `bundled_client_mod_jar` looks in order at `$DUSK_CLIENT_MOD_JAR`, the Tauri resource dir, `src-tauri/resources/` in debug builds, then `<data>/client-mod.jar` / `<data>/bundled/client-mod.jar` (manual override). It is `None` in a build without the jar — the wardrobe then shows the error instead of an empty grid, and launch proceeds without the mod.

### 5.2 Loadout storage (deviation from the original plan)

The loadout is **launcher-wide**, not per instance: one cape for the account, not one per modpack. The launcher keeps it at `<data>/cosmetics.json` (`{"cape": 2, "accessories": [16]}`, hence `Loadout = Record<string, number | number[] | object>`). On every Fabric launch it is merged into that instance's `config/duskclient.json` under `cosmetics.loadout`, leaving the mod's own keys (`showOthers`, `minecraftCapes`, `hideOfficialCapes`) alone. The mod reads it at startup and on `reloadLocal()`.

### 5.3 Commands and UI

- Rust `cosmetics.rs`: `list_cosmetics()` (reads `registry.json` out of the bundled jar with the `zip` crate), `read_cosmetic_texture(kind, id)` (`cape` | `ears` | `accessory`) → PNG data URL from the jar, `read_cosmetic_model(id)` → the accessory's block-model JSON, `get_loadout()`, `set_loadout(loadout)`, `write_loadout_to_instance(instance)` (used by launch). Optional later: `publish_loadout()` doing the §3 handshake with the launcher's own MC token.
- `Cosmetics.tsx` CAPES tab: grid of `CapeSwatch` tiles (the cape's outer face on a 2D canvas, animated strips cycle at 100 ms), a NONE tile, the account's current skin in `PlayerRender` wearing the picked cape (skinview3d `loadCape` with `backEquipment: 'cape'`, strips split per frame client-side because skinview3d only takes single 64×32-scaled images) and `loadEars` for capes with ears. Green ring = worn, accent ring = previewed; APPLY CAPE / REMOVE CAPE → `set_loadout`. The browser preview (`npm run dev` outside Tauri) shows the real catalog: `vite.config.ts` serves `client-mod/src/main/resources/assets/duskclient/cosmetics/` at `/__cosmetics`, and the stubs in `api.ts` fetch `registry.json` and the PNGs from there.
- CAPES and ACCESSORIES tabs share one wardrobe: one viewer wearing the whole picked look, one CANCEL / APPLY LOOK pair writing `cape` + `accessories` together. Accessory tiles are toggles (`AccessorySwatch` draws the first element's face straight off the texture); the viewer builds the meshes with `src/lib/accessoryMesh.ts` (block model → `BufferGeometry`, parented to skinview3d's `head/body/rightArm/…` groups with the §1.2 transform). Offset nudge + mirror controls (`settings`) are phase 4.
- **Store + inventory** (`Store.tsx`, route `store`): what the user *owns* lives in `<data>/inventory.json` (`{"owned": [ids]}`); `load_inventory` always unions in whatever the loadout wears, so a loadout written before the file existed is still "owned". Commands: `get_inventory()`, `claim_cosmetic(id)` (rejects ids not in the bundled catalog; everything is free until phase 5, when Tebex entitlements replace this file). The store previews the picked item on the account skin over the current look, GET claims, EQUIP/UNEQUIP writes the loadout slot directly. The wardrobe (`Cosmetics.tsx`) lists **owned** items only and ends every grid with a GET MORE tile that opens the store.
- `client-mod/tools/import_cosmetics.py <folder>` drops downloaded capes (PNG or GIF → frame strip, `frameMs` from the GIF, downscaled to ≤8 MP) and Cosmetica exports (`<name> - Cosmetica model.json` + `texture.png`, metadata looked up on `api.cloaks.gg`) into the registry, keeping ids stable by name.

---

## 6. Phasing

1. **Capes, self-only** — registry + `AbstractClientPlayer.getSkin` mixin + animated frames + glint/upside-down + `duskclient.json` + launcher CAPES tab. Also the MinecraftCapes provider, since it's the same code path. *Result: any MinecraftCapes user is already visible in Dusk.*
   **Status: implemented** (mod compiles against 1.21.11 Mojang mappings, `runClient` reaches the title screen with all six mixins applied; launcher `cargo check` / `tsc` / `vite build` clean; wardrobe verified in the browser preview). Still to be eyeballed in a world: the actual cape/ears/glint draw on a player, and a MinecraftCapes profile fetch against the live API. Ears landed here rather than in 4 because the ears path is the same provider + one extra layer.
2. **Accessories, self-only** — port bakery/texture/layer from cosmetica-core; 3–5 bundled items; launcher three.js preview.
   **Status: implemented** (`AccessoryModel` + `AccessoriesLayer` in the mod, `accessoryMesh.ts` + ACCESSORIES tab in the launcher, `import_cosmetics.py` tooling; registry ships 11 imported capes and 1 accessory, the Cosmetica "Red Fire Arm"). Verified: launcher preview places the arm model on the right arm; in-game draw covered by the client gametest (`cd client-mod && gradle runClientGameTest` → `src/gametest/.../CosmeticsGameTest.java` equips cape 5 + accessory 16, joins a world and writes front/back screenshots to `build/run/clientGameTest/screenshots/`). The mod skips its custom title screen under that harness (`fabric.client.gametest` system property). `CrossModCapesGameTest` resolves a live MinecraftCapes profile (cape + ears + glint) through `CosmeticsManager.get` as a non-local player and asserts it registers with no registry accessories; it skips itself when the API is unreachable.
3. **Server** — axum binary, three endpoints, `hasJoined` auth, `CosmeticsStore` batch fetch. *Result: other Dusk users see you.*
4. **Polish** — ears, mannequins (26.x), per-equip offset/mirror, more slots, in-game equip screen.
5. **Entitlements + Tebex** — only once 3 has retention (REDESIGN §3).

## 7. Not doing

- Depending on the `cosmetica-core` jar (URL-only textures, drags in their API client, websocket, auth and nametag mixins).
- Any Essential code. Any packet injection.
- Bedrock Edition. ("Bedrock geometry" in earlier notes meant Blockbench's Java-mod export format; irrelevant now.)
- Proxying MinecraftCapes through our server — their own mod hits `api.minecraftcapes.net` straight from clients, so we do the same, with a config toggle.
