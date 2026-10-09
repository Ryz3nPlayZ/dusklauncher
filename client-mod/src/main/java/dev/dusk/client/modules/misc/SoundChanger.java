package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.IntSetting;

import java.util.HashMap;
import java.util.Map;

/**
 * Lunar's Sound Changer: a volume for each of the sounds people most often
 * want quieter, which vanilla's category sliders can't single out (rain
 * without the rest of the weather, footsteps without the rest of the blocks).
 * Applied where the game works out a sound's volume, so a sound turned down to
 * nothing isn't played at all and a looping one (rain, a minecart) follows the
 * slider as it moves.
 */
public class SoundChanger extends Module {
    private static SoundChanger instance;

    /** The sounds each slider covers: a sound id path starting with one of these, or ending with it after a '*'. */
    private record Group(IntSetting volume, String[] prefixes) {
        boolean matches(String path) {
            for (String p : prefixes) {
                if (p.startsWith("*") ? path.endsWith(p.substring(1)) : path.startsWith(p)) return true;
            }
            return false;
        }
    }

    private final Group[] groups = {
            group("rain", "Rain", "weather.rain"),
            group("thunder", "Thunder", "entity.lightning_bolt."),
            group("explosions", "Explosions", "entity.generic.explode", "entity.tnt.primed", "entity.wind_charge.wind_burst"),
            group("fireworks", "Fireworks", "entity.firework_rocket."),
            group("footsteps", "Footsteps", "*.step"),
            group("eating", "Eating & drinking", "entity.generic.eat", "entity.generic.drink", "entity.player.burp",
                    "item.honey_bottle.drink"),
            group("hits", "Hits", "entity.player.attack."),
            group("experience", "Experience orbs", "entity.experience_orb.", "entity.player.levelup"),
            group("villagers", "Villagers", "entity.villager.", "entity.wandering_trader."),
            group("portals", "Nether portals", "block.portal."),
            group("pistons", "Pistons", "block.piston."),
            group("minecarts", "Minecarts", "entity.minecart."),
            group("bosses", "Withers & the dragon", "entity.wither.", "entity.ender_dragon."),
    };

    /** sound id path → index into groups, or -1; filled as sounds are first heard */
    private final Map<String, Integer> lookup = new HashMap<>();

    public SoundChanger() {
        super("soundchanger", "Sound Changer", Category.MISC,
                "Turn down rain, footsteps, explosions, villagers and other sounds one by one.");
        instance = this;
    }

    private Group group(String id, String name, String... prefixes) {
        return new Group(add(new IntSetting(id, name, 100, 0, 100, 5, "%")), prefixes);
    }

    /** The volume to play a sound at, given its id path and the volume vanilla worked out. */
    public static float volume(String path, float vanilla) {
        SoundChanger m = instance;
        if (m == null || !m.enabled() || vanilla <= 0F) return vanilla;
        int g = m.lookup.computeIfAbsent(path, m::groupOf);
        return g < 0 ? vanilla : vanilla * m.groups[g].volume().get() / 100F;
    }

    private int groupOf(String path) {
        for (int i = 0; i < groups.length; i++) {
            if (groups[i].matches(path)) return i;
        }
        return -1;
    }
}
