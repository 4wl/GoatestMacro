package com.justingoat.goat.client.module.value;

public final class NumberValue extends ModuleValue {
    private double value;
    private final double min;
    private final double max;

    public NumberValue(String name, double value, double min, double max) {
        super(name, ValueKind.NUMBER);
        this.value = value;
        this.min = min;
        this.max = max;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = Math.max(min, Math.min(max, value));
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }
}
