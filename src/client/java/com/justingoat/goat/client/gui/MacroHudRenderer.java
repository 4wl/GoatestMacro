package com.justingoat.goat.client.gui;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class MacroHudRenderer {
    private static final Identifier HUD_ID = Identifier.of("goat", "macro_hud");

    private static final int X = 8;
    private static final int Y = 8;
    private static final int WIDTH = 176;
    private static final int HEIGHT = 52;
    private static final int BACKGROUND = 0xAA101216;
    private static final int BORDER = 0xFF55D68A;
    private static final int TITLE = 0xFFFFFFFF;
    private static final int LABEL = 0xFF8E98A8;
    private static final int VALUE = 0xFFE8EEF7;

    private MacroHudRenderer() {
    }

    public static void register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, HUD_ID, MacroHudRenderer::render);
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.options.hudHidden) return;

        ActiveMacro active = findActiveMacro();
        if (active == null) return;

        context.fill(X, Y, X + WIDTH, Y + HEIGHT, BACKGROUND);
        context.fill(X, Y, X + 2, Y + HEIGHT, BORDER);
        drawBorder(context, X, Y, WIDTH, HEIGHT, 0x6655D68A);

        context.drawText(client.textRenderer, Text.literal(active.info.getHudName() + " macro is active"), X + 8, Y + 7, TITLE, false);
        context.drawText(client.textRenderer, Text.literal("State"), X + 8, Y + 23, LABEL, false);
        context.drawText(client.textRenderer, Text.literal(active.info.getHudState()), X + 54, Y + 23, VALUE, false);
        context.drawText(client.textRenderer, Text.literal("Runtime"), X + 8, Y + 36, LABEL, false);
        context.drawText(client.textRenderer, Text.literal(formatDuration(active.module.getEnabledDurationMillis())), X + 54, Y + 36, VALUE, false);
    }

    private static ActiveMacro findActiveMacro() {
        for (GoatModule module : ModuleManager.getModules()) {
            if (module.isEnabled() && module instanceof MacroHudInfo info) {
                return new ActiveMacro(module, info);
            }
        }
        return null;
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    private static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

    private record ActiveMacro(GoatModule module, MacroHudInfo info) {
    }
}
