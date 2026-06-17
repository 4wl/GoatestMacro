package com.justingoat.goat.client.utils;

import com.justingoat.goat.client.mixin.MouseAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.MouseInput;
import org.lwjgl.glfw.GLFW;

public class MouseUtils {
    private static boolean mouseUngrabbed = false;
    private static boolean wasCursorLocked = false;
    private static boolean previousPauseOnLostFocus = true;

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

    public static void ungrabMouse() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.mouse == null || client.getWindow() == null) return;
        if (mouseUngrabbed) return;

        previousPauseOnLostFocus = client.options.pauseOnLostFocus;
        client.options.pauseOnLostFocus = false;
        wasCursorLocked = client.mouse.isCursorLocked();

        if (wasCursorLocked) {
            client.mouse.unlockCursor();
        }
        GLFW.glfwSetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        mouseUngrabbed = true;
    }

    public static void regrabMouse() {
        regrabMouse(false);
    }

    public static void regrabMouse(boolean force) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.mouse == null || client.getWindow() == null) return;
        if (!mouseUngrabbed && !force) return;

        mouseUngrabbed = false;
        client.options.pauseOnLostFocus = previousPauseOnLostFocus;
        if ((wasCursorLocked || force) && client.currentScreen == null) {
            client.mouse.lockCursor();
        }
        wasCursorLocked = false;
    }

    public static void setCursorLockedForMacroInput(boolean locked) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.mouse == null || !mouseUngrabbed) return;
        ((MouseAccessor) client.mouse).setCursorLocked(locked);
    }

    public static boolean shouldBlockCursorLock() {
        return mouseUngrabbed;
    }

    public static boolean isMouseUngrabbed() {
        return mouseUngrabbed;
    }
}
