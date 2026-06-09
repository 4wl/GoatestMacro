package com.justingoat.goat.client.module.value;

import java.util.List;

public final class ModeValue extends ModuleValue {
    private final List<String> modes;
    private String value;

    public ModeValue(String name, String value, List<String> modes) {
        super(name, ValueKind.MODE);
        this.value = value;
        this.modes = List.copyOf(modes);
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        if (modes.contains(value)) {
            this.value = value;
        }
    }

    public List<String> getModes() {
        return modes;
    }
}
