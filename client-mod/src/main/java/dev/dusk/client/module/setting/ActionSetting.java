package dev.dusk.client.module.setting;

/** A button on the settings page that runs something; nothing is saved. */
public class ActionSetting extends Setting<Boolean> {
    private final String button;
    private final Runnable action;

    public ActionSetting(String id, String name, String button, Runnable action) {
        super(id, name, false);
        this.button = button;
        this.action = action;
    }

    public String button() { return button; }

    public void run() { action.run(); }

    @Override
    public Object save() { return null; }

    @Override
    public void load(Object raw) {}
}
