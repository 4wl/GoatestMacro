package com.justingoat.goat.client.gui;

import java.util.ArrayList;
import java.util.List;

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
    private static final int MIN_WIDTH = 176;
    private static final int ROW_HEIGHT = 13;
    private static final int BACKGROUND = 0xAA101216;
    private static final int BORDER = 0xFF55D68A;
    private static final int TITLE = 0xFFFFFFFF;
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

        List<String> rows = new ArrayList<>();
        rows.add("State: " + active.info.getHudState());
        rows.addAll(active.info.getHudExtraLines());
        rows.add("Runtime: " + formatDuration(active.module.getEnabledDurationMillis()));

        String title = active.info.getHudName() + " macro is active";
        int width = Math.max(MIN_WIDTH, client.textRenderer.getWidth(title) + 16);
        for (String row : rows) {
            width = Math.max(width, client.textRenderer.getWidth(row) + 16);
        }
        int height = 21 + rows.size() * ROW_HEIGHT + 6;

        context.fill(X, Y, X + width, Y + height, BACKGROUND);
        context.fill(X, Y, X + 2, Y + height, BORDER);
        drawBorder(context, X, Y, width, height, 0x6655D68A);

        context.drawText(client.textRenderer, Text.literal(title), X + 8, Y + 7, TITLE, false);
        int rowY = Y + 23;
        for (String row : rows) {
            context.drawText(client.textRenderer, Text.literal(row), X + 8, rowY, VALUE, false);
            rowY += ROW_HEIGHT;
        }
    }

    private static ActiveMacro findActiveMacro() {
        ActiveMacro best = null;
        for (GoatModule module : ModuleManager.getModules()) {
            if (module.isEnabled() && module instanceof MacroHudInfo info) {
                if (best == null || info.getHudPriority() > best.info.getHudPriority()) {
                    best = new ActiveMacro(module, info);
                }
            }
        }
        return best;
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
