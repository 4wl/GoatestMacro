package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;

public final class MacroControls {
    private MacroControls() {
    }

    public static void stopMovement() {
        InputUtils.setForward(false);
        InputUtils.setBack(false);
        InputUtils.setLeft(false);
        InputUtils.setRight(false);
        InputUtils.setJump(false);
        InputUtils.setSprint(false);
        InputUtils.setSneak(false);
    }

    public static void stopActions() {
        InputUtils.setAttack(false);
        InputUtils.setUse(false);
    }

    public static void stopAll() {
        InputUtils.releaseAll();
    }

    public static void stopAllAndClearRotation(MinecraftClient client, AimController aim) {
        if (aim != null) aim.applyAndClear(client);
        RotationInterpolator.clearActive();
        stopAll();
    }
}
