package com.justingoat.goat.client.module.value;

public final class BooleanValue extends ModuleValue {
    private boolean value;

    public BooleanValue(String name, boolean value) {
        super(name, ValueKind.BOOLEAN);
        this.value = value;
    }

    public boolean getValue() {
        return value;
    }

    public void setValue(boolean value) {
        this.value = value;
    }

    public void toggle() {
        value = !value;
    }
}
