# Dusk server API

Servers can switch DuskClient modules off for the players connected to them. Examples are
fullbright on a hardcore UHC or hitboxes in a minigame. The protocol is two plugin-message
channels. Each carries a bare UTF-8 JSON body with no length prefix, so a Paper plugin can
send it with `Player#sendPluginMessage` and needs no extra library.

Protocol version: **1**. It is supported on every Minecraft target the client builds for
(1.21 to 26.3).

## `dusk:rules` (server → client)

```json
{"disable": ["fullbright", "hitbox"]}
```

- Each packet **replaces** the previous rule set. Sending `{"disable": []}` lifts every block.
- The blocks last until the player disconnects. Nothing about them is saved.
- A blocked module stays off even if the player tries to turn it back on. Its description
  in the menu reads "Disabled by this server." The player's own on/off choice is kept and
  applies again once the block is lifted.
- If the packet turns off a module the player had enabled, they get one chat line naming it.
- The client ignores unknown ids and ignores the whole packet if its body is malformed.

Dusk advertises `dusk:rules` in the `minecraft:register` handshake. A server can therefore
tell that a player runs Dusk by checking `Player#getListeningPluginChannels()` for it.

## `dusk:hello` (client → server)

If the server registers `dusk:hello`, the client sends this once per connection:

```json
{"protocol": 1, "version": "0.2.0", "mc": "1.21.11", "modules": ["keystrokes", "fps", "..."]}
```

`modules` lists every module id that this client build knows. Some render and chat modules exist only
on 1.21.11 and later.

## Module ids

| Category | Ids |
| --- | --- |
| HUD | `armor` `bedwarsresources` `biome` `clock` `combo` `compass` `cooldowns` `coords` `cps` `day` `distance` `effects` `entities` `fps` `fullinventory` `gametime` `helditem` `inventorydisplay` `itemcounter` `itempickups` `keystrokes` `light` `lookingat` `lowdurability` `memory` `minimap` `nethercoords` `ping` `pitchdisplay` `playtime` `reach` `resourcepacks` `server` `serverlag` `signreader` `skyblockstats` `sneakstatus` `speed` `sprintstatus` `stopwatch` `totems` `tps` `weather` |
| Movement | `togglesprint` |
| Render | `behindyou` `blockoutline` `bossbar` `capephysics` `colorsaturation` `containerpreview` `crosshair` `customskies` `damagetint` `fogcontrol` `fovchanger` `freecam` `freelook` `fullbright` `glintcolor` `hitbox` `hungerinfo` `itemphysics` `itemscale` `lightoverlay` `lowfire` `lowshield` `motion_blur` `nametags` `nonightvision` `nopumpkinblur` `particles` `riptideshieldfix` `scoreboard` `shieldstatuses` `skinlayers3d` `tabping` `timechanger` `totempop` `weatherchanger` `zoom` |
| Utility | `autoreconnect` `backgroundfps` `boatmap` `chatcopy` `chatheads` `chathistory` `chatmacros` `chatmentions` `chatsearch` `chattimestamps` `compactchat` `confirmdisconnect` `gamemodeswitcher` `hypixel` `namehider` `scrolltooltips` `scrolltransfer` `slotlock` `soundchanger` `statistics` `tntcountdown` `waypoints` |

## Paper example

```java
public final class NoFullbright extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, "dusk:rules");
        getServer().getMessenger().registerIncomingPluginChannel(this, "dusk:hello",
                (channel, player, body) -> getLogger().info(player.getName() + " runs Dusk: "
                        + new String(body, StandardCharsets.UTF_8)));
        getServer().getPluginManager().registerEvents(this, this);
    }

    // The client lists its channels right after joining, so wait for that before sending.
    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        if (e.getChannel().equals("dusk:rules")) {
            byte[] body = "{\"disable\":[\"fullbright\",\"hitbox\"]}".getBytes(StandardCharsets.UTF_8);
            e.getPlayer().sendPluginMessage(this, "dusk:rules", body);
        }
    }
}
```

## Fabric server example

Register a payload whose codec writes the JSON's raw bytes. This is the same shape as
`client-mod/src/mc1_21_11/java/dev/dusk/client/server/ServerChannel.java`. Then call
`ServerPlayNetworking.send(player, new Rules(json))` once
`ServerPlayNetworking.canSend(player, Rules.TYPE)` is true, for example in
`S2CPlayChannelEvents.REGISTER`.
