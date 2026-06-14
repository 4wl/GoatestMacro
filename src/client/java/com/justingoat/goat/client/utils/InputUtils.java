package com.justingoat.goat.client.utils;

import com.justingoat.goat.client.mixin.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

public class InputUtils {
    public static void setForward(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.forwardKey, pressed);
    }

    public static void setBack(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.backKey, pressed);
    }

    public static void setLeft(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.leftKey, pressed);
    }

    public static void setRight(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.rightKey, pressed);
    }

    public static void setAttack(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.attackKey, pressed);
        if (pressed) {
            triggerAttackWhenUngrabbed(client);
        }
    }

    public static void setJump(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.jumpKey, pressed);
    }

    public static void setSprint(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.sprintKey, pressed);
    }

    public static void setSneak(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.sneakKey, pressed);
    }

    public static void setUse(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.useKey, pressed);
        if (pressed) {
            triggerUseWhenUngrabbed(client);
        }
    }

    public static void setHotbarSlot(int slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        client.player.getInventory().setSelectedSlot(Math.max(0, Math.min(8, slot)));
    }

    public static void releaseAll() {
        setSneak(false);
        setUse(false);
        setForward(false);
        setBack(false);
        setLeft(false);
        setRight(false);
        setAttack(false);
        setJump(false);
        setSprint(false);
    }

    private static void setKey(KeyBinding keyBinding, boolean pressed) {
        if (keyBinding != null) {
            keyBinding.setPressed(pressed);
        }
    }

    private static void triggerAttackWhenUngrabbed(MinecraftClient client) {
        if (client.currentScreen != null || client.mouse == null || client.mouse.isCursorLocked()) return;
        ((MinecraftClientAccessor) client).invokeDoAttack();
    }

    private static void triggerUseWhenUngrabbed(MinecraftClient client) {
        if (client.currentScreen != null || client.mouse == null || client.mouse.isCursorLocked()) return;
        ((MinecraftClientAccessor) client).invokeDoItemUse();
    }
}
