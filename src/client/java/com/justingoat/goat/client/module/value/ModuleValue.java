package com.justingoat.goat.client.module.value;

public abstract class ModuleValue {
    private final String name;
    private final ValueKind kind;

    protected ModuleValue(String name, ValueKind kind) {
        this.name = name;
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public ValueKind getKind() {
        return kind;
    }
}
