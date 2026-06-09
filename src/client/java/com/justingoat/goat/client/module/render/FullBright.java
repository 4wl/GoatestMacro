package com.justingoat.goat.client.module.render;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.NumberValue;

import net.minecraft.client.MinecraftClient;

public final class FullBright extends GoatModule {
    private final NumberValue brightness;
    private Double previousGamma;

    public FullBright() {
        super("FullBright", ModuleCategory.RENDER, false);
        brightness = addNumber("Brightness", 10.0, 1.0, 15.0);
    }

    @Override
    public void tick(MinecraftClient client) {
        if (client.options == null) {
            return;
        }
        if (!isEnabled()) {
            restoreGamma(client);
            return;
        }
        if (previousGamma == null) {
            previousGamma = client.options.getGamma().getValue();
        }
        double targetGamma = brightness.getValue();
        if (client.options.getGamma().getValue() != targetGamma) {
            client.options.getGamma().setValue(targetGamma);
        }
    }

    private void restoreGamma(MinecraftClient client) {
        if (previousGamma != null) {
            client.options.getGamma().setValue(previousGamma);
            previousGamma = null;
        }
    }
}
