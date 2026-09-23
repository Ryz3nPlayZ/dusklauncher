package dev.dusk.client.modules.render;

import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;

/**
 * PolyWeather: picks the weather you see. The server's weather is untouched,
 * so the rain still soaks farmland and mobs still burn in the sun you drew
 * over it; only the rendering, the fog and the ambient sound follow this.
 *
 * <p>PolyWeather's "use IRL weather" option is not ported: it geolocates the
 * player by IP and pulls the local forecast from a web service, which is not
 * something a HUD toggle should do behind your back. The strengths are whole
 * percents rather than PolyWeather's 0..1 steps, because the editor's sliders
 * are integer-valued.
 */
public class WeatherChanger extends Module {
    private static WeatherChanger instance;

    private static final String CLEAR = "Clear";
    private static final String RAIN = "Rain";
    private static final String STORM = "Storm";
    private static final String SNOW = "Snow";

    private final ChoiceSetting weather =
            add(new ChoiceSetting("weather", "Weather", CLEAR, CLEAR, RAIN, STORM, SNOW));
    private final IntSetting rainStrength = add(new IntSetting("rain", "Rain strength", 100, 0, 100, 10, "%"));
    private final IntSetting snowStrength = add(new IntSetting("snow", "Snow strength", 100, 0, 100, 10, "%"));
    private final IntSetting thunderStrength = add(new IntSetting("thunder", "Storm strength", 100, 0, 100, 10, "%"));
    private final BoolSetting sounds = add(new BoolSetting("sounds", "Weather sounds", true));

    /** PolyWeather eases each strength towards its target instead of snapping. */
    private float prevRain;
    private float prevSnow;
    private float prevStorm;

    public WeatherChanger() {
        super("weatherchanger", "Weather Changer", Category.RENDER,
                "Shows the weather you pick, leaving the server's alone.");
        instance = this;
    }

    public static boolean active() {
        WeatherChanger m = instance;
        return m != null && m.enabled();
    }

    /** Anything but clear counts as rainy; snow is rain the game draws white. */
    public static boolean isRainy() {
        WeatherChanger m = instance;
        return m != null && !m.weather.is(CLEAR);
    }

    public static boolean isStormy() {
        WeatherChanger m = instance;
        return m != null && m.weather.is(STORM);
    }

    public static boolean isSnowy() {
        WeatherChanger m = instance;
        return m != null && m.weather.is(SNOW);
    }

    public static boolean sounds() {
        WeatherChanger m = instance;
        return m != null && m.sounds.get();
    }

    /** What the rain/snow renderers, the sky fade and the ambient loop read. */
    public static float precipitationStrength(float delta) {
        if (isSnowy()) return snowStrength(delta);
        if (isRainy()) return rainStrength(delta);
        return 0f;
    }

    public static float rainStrength(float delta) {
        WeatherChanger m = instance;
        if (m == null) return 0f;
        if (!isRainy()) {
            m.prevRain = 0f;
            return 0f;
        }
        m.prevRain = lerp(m.prevRain, m.rainStrength.get() / 100f, delta);
        return m.prevRain;
    }

    public static float snowStrength(float delta) {
        WeatherChanger m = instance;
        if (m == null) return 0f;
        if (!isSnowy()) {
            m.prevSnow = 0f;
            return 0f;
        }
        m.prevSnow = lerp(m.prevSnow, m.snowStrength.get() / 100f, delta);
        return m.prevSnow;
    }

    public static float stormStrength(float delta) {
        WeatherChanger m = instance;
        if (m == null) return 0f;
        if (!isStormy()) {
            m.prevStorm = 0f;
            return 0f;
        }
        m.prevStorm = lerp(m.prevStorm, m.thunderStrength.get() / 100f, delta);
        return m.prevStorm;
    }

    private static float lerp(float a, float b, float delta) {
        return a + (b - a) * delta;
    }
}
