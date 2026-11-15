package dev.dusk.client.module;

public enum Category {
    HUD("HUD"), MOVEMENT("Movement"), RENDER("Render"), MISC("Utility");

    public final String label;

    Category(String label) { this.label = label; }
}
