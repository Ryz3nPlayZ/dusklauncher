# SMP setup inventory (as of 2026-10-16)

Server: Purpur 26.2 on the Oracle box, behind Velocity. Joins via `mc.tryzwork.app`.

## Infrastructure
| Piece | Detail |
|---|---|
| Game server | Purpur 26.2, `~/smp-purpur`, systemd `minecraft-smp`, localhost:25583, RCON 25595 |
| Proxy | Velocity (`~/velocity-smp`, `minecraft-smp-proxy`), public :25565, online-mode (Microsoft auth), modern forwarding, 512 MB heap |
| Proxy plugin | `limited-offline-mode` (lets the backend run `online-mode=false` safely behind the proxy) |
| JVM | 6 GB heap, Aikar's G1 flags, `Restart=on-failure` |
| Settings | whitelist ON and enforced (27 entries), 20 slots, view/sim distance 10, easy, spawn-protection 16, flight allowed, border effectively off (~60M) |
| Gamerules | keepInventory false, mobGriefing true, sleeping 100% (all defaults) |
| Ops | one (4yy4) |
| Not on this server | no datapacks, no server-side Fabric/Forge mods; everything is plugins |

## Plugins
| Plugin | Version | What it gives us |
|---|---|---|
| EssentialsX | 2.22.1-dev+27 | homes, TPA, kits, economy core, `/back`, AFK, `/superpickaxe`, spawn, msg etc. |
| Vault | 1.7.3 | economy/permissions bridge (EssentialsX -> everything else) |
| LuckPerms | 5.5.87 (H2 file DB) | permission groups |
| EconomyShopGUI | 7.3.2 | `/shop`, `/sellgui`, buy/sell screens, sell-all |
| ShopTools (our own) | 1.0.0 | `/shopfind` (search the shop), `/sellchest` (sell a chest's contents); source in `shoptools/` on the server |
| LeafRTP | 3.2.1 | `/rtp` |
| Combat Tag | 2.0.0 | combat timer |
| FastAsyncWorldEdit | 2.16.0 | `//` building tools, `//undo` |
| Grim | 2.3.74 (PacketEvents bundled) | anticheat (just added, default config) |
| ViaVersion + ViaBackwards | 5.12.1-SNAPSHOT | lets older clients join (Grim warns about old clients riding vehicles) |
| spark | | profiler |

## Gameplay features and their settings
**Teleport / homes (EssentialsX)**
- TPA: request/accept, no cooldown, no warmup delay, safe-teleport on. Requests expire after 120 s.
- Homes (`sethome-multiple`): default 3, vip 5, staff 10.
- Respawn at bed on; not forced to a home. Not sent to spawn on join.
- Kits defined: tools (10 s), dtools (10 min), notch / color / firework (100 min).
- AFK after 5 min.

**RTP (LeafRTP)**
- Circle radius 256 around 0,0 (inner no-go radius 64), Y 32-255.
- 2 s warmup, 300 s cooldown, free to use. Paying for someone else costs $200, biome selection $5000.

**Combat timer (Combat Tag)**
- 20 s tag, tag ends if the opponent is gone, killed on logout (only if at fault).
- Blocks /tpa family, /home(s), /back, /spawn, /warp(s), /rtp, /wild while tagged.

**Economy**
- Vault economy through EssentialsX. Start balance $5000, symbol `$` prefix, `/pay` works, economy log off.
- Shop (EconomyShopGUI): sections Building, Combat, Decoration, Farming, Magic, Mobs plus an "Everything Else" section (348 buy entries) and misc/music/resources/storage. Sell price lookup via `/sellgui`, shift-right-click sell-all.
- Our additions: `/shopfind <item>` and `/sellchest`.

**Anticheat (Grim)**: default punishments (mostly alert/log; Simulation/Knockback thresholds alert). Not tuned yet. Ops see alerts.

## Backups
- Hourly at :17, rsync with hard-links, `save-off`/`save-all flush` around it. Keeps 48 hourly + 14 daily (03:xx). About 11 GB for 67 snapshots.
- Covers the SMP overworld/nether/end only. PvP worlds are not covered yet.
- Chunk-level restore was done several times by splicing region/entities/poi files (works well; containers revert to the backup's contents).

## Client side (separate)
- Players use the DuskLauncher instance; Litematica is configured there, with `pasteReplaceBehavior` needing to be set to all to paste over terrain.

## Things to decide for the new SMP
1. Whitelist or open? Currently whitelist-only.
2. Keep keepInventory off? Sleep percentage 100 is annoying with more players.
3. Border: none set. RTP radius 256 is small for a public server.
4. Grim: tune or keep alert-only? Exempt flying/elytra/speed mods (Litematica + creative-test accounts).
5. Backups: replicate the script; add off-box copy (everything is on the same disk).
6. LuckPerms groups: confirm default/vip/staff exist and which perms they have (not exported yet).
7. Warps/spawn: no warps are defined on this server.
8. Claims/land protection: none installed, so builds are unprotected.
9. Chat, tab list, MOTD: only vanilla/default.
10. Merge ShopTools into a proper repo (it only exists on the server and in the session scratchpad).
