package com.justingoat.goat.client.module.movement;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.ModeValue;

import net.minecraft.client.MinecraftClient;

public final class AutoSprint extends GoatModule {
    private final ModeValue mode;

    public AutoSprint() {
        super("Sprint", ModuleCategory.MOVEMENT, true);
        mode = addMode("Mode", "Legit", "Legit", "Omni");
        addNumber("Boost", 1.0, 0.1, 2.0);
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.currentScreen != null) {
            return;
        }
        boolean moving = isOmni()
            ? isMovementKeyPressed(client)
            : client.options.forwardKey.isPressed();
        boolean hasEnergy = client.player.getHungerManager().getFoodLevel() > 6 || client.player.getAbilities().allowFlying;
        if (moving && hasEnergy && !client.player.isSneaking() && !client.player.isUsingItem()) {
            client.player.setSprinting(true);
        }
    }

    private boolean isOmni() {
        return mode.getValue().equals("Omni");
    }

    private boolean isMovementKeyPressed(MinecraftClient client) {
        return client.options.forwardKey.isPressed()
            || client.options.backKey.isPressed()
            || client.options.leftKey.isPressed()
            || client.options.rightKey.isPressed();
    }
}
