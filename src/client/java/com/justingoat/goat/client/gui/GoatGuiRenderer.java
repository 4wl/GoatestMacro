package com.justingoat.goat.client.gui;

import net.minecraft.client.gui.DrawContext;

public final class GoatGuiRenderer {
    private final DrawContext context;
    private final CustomFontRenderer font;

    public GoatGuiRenderer(DrawContext context, CustomFontRenderer font) {
        this.context = context;
        this.font = font;
    }

    public void fill(int x1, int y1, int x2, int y2, int color) {
        context.fill(x1, y1, x2, y2, color);
    }

    public void rounded(int x, int y, int width, int height, int color) {
        context.fill(x + 2, y, x + width - 2, y + height, color);
        context.fill(x, y + 2, x + width, y + height - 2, color);
        context.fill(x + 1, y + 1, x + width - 1, y + 2, color);
        context.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, color);
    }

    public void border(int x, int y, int width, int height, int color) {
        context.fill(x + 2, y, x + width - 2, y + 1, color);
        context.fill(x + 2, y + height - 1, x + width - 2, y + height, color);
        context.fill(x, y + 2, x + 1, y + height - 2, color);
        context.fill(x + width - 1, y + 2, x + width, y + height - 2, color);
        context.fill(x + 1, y + 1, x + 2, y + 2, color);
        context.fill(x + width - 2, y + 1, x + width - 1, y + 2, color);
        context.fill(x + 1, y + height - 2, x + 2, y + height - 1, color);
        context.fill(x + width - 2, y + height - 2, x + width - 1, y + height - 1, color);
    }

    public void enableScissor(int x, int y, int width, int height) {
        context.enableScissor(x, y, x + width, y + height);
    }

    public void disableScissor() {
        context.disableScissor();
    }

    public void text(String text, int x, int y, int color) {
        font.drawText(context, text, x, y, color);
    }

    public void mediumText(String text, int x, int y, int color) {
        font.drawMediumText(context, text, x, y, color);
    }

    public int textWidth(String text) {
        return font.getWidth(text);
    }
}
