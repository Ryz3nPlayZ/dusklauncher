package dev.dusk.client.perf;

/**
 * The 1.21.11 build's frame profiler, as the no-op calls the shared menu code
 * makes. The 1.8.9 build has no Performance page to show it on.
 */
public final class Perf {
    public static final int TICK = 0, HUD = 1, WORLD = 2, OTHER = 3;

    private Perf() {}

    public static final class Section {
        public final String name;

        Section(String name) { this.name = name; }
    }

    public static Section part(String name) { return new Section(name); }

    public static Section section(String name, String group) { return new Section(name); }

    public static long start() { return 0L; }

    public static void end(Section s, int slot, long start) {}

    public static void menuOpen() {}
}
