package com.justingoat.goat.client.module.render;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.NumberValue;
import net.minecraft.client.MinecraftClient;

public final class CustomFOV extends GoatModule {
    private final NumberValue fov;
    private Integer previousFov;

    public CustomFOV() {
        super("CustomFOV", ModuleCategory.RENDER, false);
        fov = addNumber("FOV", 90.0, 30.0, 120.0);
    }

    @Override
    public void tick(MinecraftClient client) {
        if (client.options == null) {
            return;
        }
        if (!isEnabled()) {
            restoreFov(client);
            return;
        }
        if (previousFov == null) {
            previousFov = client.options.getFov().getValue();
        }
        int targetFov = (int) Math.round(fov.getValue());
        if (client.options.getFov().getValue() != targetFov) {
            client.options.getFov().setValue(targetFov);
        }
    }

    private void restoreFov(MinecraftClient client) {
        if (previousFov != null) {
            client.options.getFov().setValue(previousFov);
            previousFov = null;
        }
    }
}
