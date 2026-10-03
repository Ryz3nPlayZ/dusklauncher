package dev.dusk.client.modules.misc;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.TextSetting;
import net.minecraft.client.Minecraft;

/**
 * For streaming: your username is drawn as another name everywhere on your
 * screen (chat, tab list, scoreboard, name tag). Only the drawing changes;
 * nothing you send is touched.
 */
public class NameHider extends Module {
    private static NameHider instance;
    /** Above zero while text is being read rather than drawn (copying, narration). */
    private static int bypass;

    private final TextSetting alias = add(new TextSetting("alias", "Show my name as", "You", 16));

    private String name;

    public NameHider() {
        super("namehider", "Name Hider", Category.MISC,
                "Shows your username as another name on your screen, for streaming.");
        instance = this;
    }

    public static void bypass(boolean start) {
        bypass += start ? 1 : -1;
    }

    /** The text with your name swapped for the alias; the same string when there's nothing to do. */
    public static String apply(String text) {
        NameHider m = instance;
        if (m == null || bypass > 0 || text.isEmpty() || !m.enabled()) return text;
        String own = m.ownName();
        if (own == null || own.isEmpty() || !text.contains(own)) return text;
        String alias = m.alias.get();
        return own.equals(alias) ? text : text.replace(own, alias);
    }

    private String ownName() {
        if (name == null) name = Minecraft.getInstance().getUser().getName();
        return name;
    }
}
