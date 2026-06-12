package com.justingoat.goat.client.module;

public enum ModuleCategory {
    MACRO("Macro", "A"),
    COMBAT("Combat", "C"),
    MOVEMENT("Movement", "M"),
    RENDER("Render", "R"),
    WORLD("World", "W"),
    MISC("Misc", "*"),
    SETTINGS("Settings", "S");

    private final String label;
    private final String icon;

    ModuleCategory(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String getLabel() {
        return label;
    }

    public String getIcon() {
        return icon;
    }
}
