package dev.dusk.client.module.setting;

/** A button on the settings page; nothing to save. */
public class ActionSetting extends Setting<Runnable> {
    public final String label;

    public ActionSetting(String id, String name, String label, Runnable action) {
        super(id, name, action);
        this.label = label;
    }

    public String button() { return label; }

    public void run() { value.run(); }

    @Override
    public Object save() { return null; }

    @Override
    public void load(Object raw) {}
}
