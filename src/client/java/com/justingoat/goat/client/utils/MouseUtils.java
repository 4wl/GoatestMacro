package com.justingoat.goat.client.utils;

import com.justingoat.goat.client.mixin.MouseAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.MouseInput;
import org.lwjgl.glfw.GLFW;

public class MouseUtils {

    public static void simulateLeftClick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null || client.getWindow() == null) return;

        long windowHandle = client.getWindow().getHandle();
        MouseAccessor mouse = (MouseAccessor) client.mouse;
        MouseInput input = new MouseInput(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);

        mouse.invokeOnMouseButton(windowHandle, input, GLFW.GLFW_PRESS);
        try {
            Thread.sleep(RandomUtils.randomInt(60, 150));
        } catch (InterruptedException ignored) {}
        mouse.invokeOnMouseButton(windowHandle, input, GLFW.GLFW_RELEASE);
    }

    public static void simulateRightClick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null || client.getWindow() == null) return;

        long windowHandle = client.getWindow().getHandle();
        MouseAccessor mouse = (MouseAccessor) client.mouse;
        MouseInput input = new MouseInput(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0);

        mouse.invokeOnMouseButton(windowHandle, input, GLFW.GLFW_PRESS);
        try {
            Thread.sleep(RandomUtils.randomInt(60, 150));
        } catch (InterruptedException ignored) {}
        mouse.invokeOnMouseButton(windowHandle, input, GLFW.GLFW_RELEASE);
    }
}
