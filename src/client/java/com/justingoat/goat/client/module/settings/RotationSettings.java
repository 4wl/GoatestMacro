package com.justingoat.goat.client.module.settings;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.NumberValue;

public class RotationSettings extends GoatModule {
    private final ModeValue mode;
    private final NumberValue globalSpeed;
    private final NumberValue smoothness;
    private final NumberValue noise;
    private final NumberValue overshoot;
    private final BooleanValue quantize;

    public RotationSettings() {
        super("RotationSettings", ModuleCategory.SETTINGS, true, false);
        mode = addMode("Mode", "Bezier", "Bezier", "Spring", "Linear");
        globalSpeed = addNumber("GlobalSpeed", 1.2, 0.2, 3.0);
        smoothness = addNumber("Smoothness", 0.55, 0.0, 1.0);
        noise = addNumber("Noise", 0.6, 0.0, 1.0);
        overshoot = addNumber("Overshoot", 0.2, 0.0, 1.0);
        quantize = addBoolean("MouseGCD", true);
    }

    public String getMode() {
        return mode.getValue();
    }

    public float getGlobalSpeed() {
        return (float) globalSpeed.getValue();
    }

    public float getSmoothness() {
        return (float) smoothness.getValue();
    }

    public float getNoise() {
        return (float) noise.getValue();
    }

    public float getOvershoot() {
        return (float) overshoot.getValue();
    }

    public boolean shouldQuantize() {
        return quantize.getValue();
    }
}
