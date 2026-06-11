package com.justingoat.goat.client.utils;

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

    public static void releaseAll() {
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
}
