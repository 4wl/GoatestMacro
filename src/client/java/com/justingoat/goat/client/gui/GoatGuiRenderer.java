package com.justingoat.goat.client.gui;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.gui.DrawContext;

public final class GoatGuiRenderer {
    private static final Map<Integer, int[]> CORNER_CACHE = new HashMap<>();

    private final DrawContext context;
    private final CustomFontRenderer font;

    public GoatGuiRenderer(DrawContext context, CustomFontRenderer font) {
        this.context = context;
        this.font = font;
    }

    public void fill(int x1, int y1, int x2, int y2, int color) {
        context.fill(x1, y1, x2, y2, color);
    }

    public void roundedRect(int x, int y, int w, int h, int r, int color) {
        if (r <= 0 || h < r * 2 || w < r * 2) {
            context.fill(x, y, x + w, y + h, color);
            return;
        }
        int[] offsets = cornerOffsets(r);
        for (int i = 0; i < r; i++) {
            context.fill(x + offsets[i], y + i, x + w - offsets[i], y + i + 1, color);
            context.fill(x + offsets[i], y + h - 1 - i, x + w - offsets[i], y + h - i, color);
        }
        context.fill(x, y + r, x + w, y + h - r, color);
    }

    public void roundedOutline(int x, int y, int w, int h, int r, int color) {
        if (r <= 0) {
            context.fill(x, y, x + w, y + 1, color);
            context.fill(x, y + h - 1, x + w, y + h, color);
            context.fill(x, y + 1, x + 1, y + h - 1, color);
            context.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
            return;
        }
        int[] offsets = cornerOffsets(r);
        context.fill(x + offsets[0], y, x + w - offsets[0], y + 1, color);
        context.fill(x + offsets[0], y + h - 1, x + w - offsets[0], y + h, color);
        context.fill(x, y + r, x + 1, y + h - r, color);
        context.fill(x + w - 1, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            int o = offsets[i];
            int adj = (i + 1 < r) ? offsets[i + 1] : 0;
            int lo = Math.min(o, adj);
            int hi = Math.max(o, adj);
            context.fill(x + lo, y + i, x + hi + 1, y + i + 1, color);
            context.fill(x + w - hi - 1, y + i, x + w - lo, y + i + 1, color);
            context.fill(x + lo, y + h - 1 - i, x + hi + 1, y + h - i, color);
            context.fill(x + w - hi - 1, y + h - 1 - i, x + w - lo, y + h - i, color);
        }
    }

    public void shadow(int x, int y, int w, int h, int r) {
        roundedRect(x + 3, y + 4, w, h, r, 0x18000000);
        roundedRect(x + 1, y + 2, w, h, r, 0x28000000);
    }

    public void glassPanel(int x, int y, int w, int h, int r) {
        glassPanel(x, y, w, h, r, 0xB0100E1E);
    }

    public void glassPanel(int x, int y, int w, int h, int r, int bgColor) {
        shadow(x, y, w, h, r);
        roundedRect(x, y, w, h, r, bgColor);
        roundedOutline(x, y, w, h, r, 0x30FFFFFF);
        if (r > 0) {
            int inset = cornerOffsets(r)[0];
            context.fill(x + inset, y, x + w - inset, y + 1, 0x50FFFFFF);
        } else {
            context.fill(x, y, x + w, y + 1, 0x50FFFFFF);
        }
    }

    public void glassCard(int x, int y, int w, int h, int r, float hover) {
        int bg = lerpColor(0x15FFFFFF, 0x30FFFFFF, hover);
        roundedRect(x, y, w, h, r, bg);
        roundedOutline(x, y, w, h, r, lerpColor(0x15FFFFFF, 0x28FFFFFF, hover));
    }

    public void enableScissor(int x, int y, int w, int h) {
        context.enableScissor(x, y, x + w, y + h);
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

    public void boldText(String text, int x, int y, int color) {
        font.drawBoldText(context, text, x, y, color);
    }

    public int textWidth(String text) {
        return font.getWidth(text);
    }

    public int mediumTextWidth(String text) {
        return font.getMediumWidth(text);
    }

    public int boldTextWidth(String text) {
        return font.getBoldWidth(text);
    }

    public static int lerpColor(int from, int to, float t) {
        if (t <= 0) return from;
        if (t >= 1) return to;
        int fa = (from >> 24) & 0xFF, fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int ta = (to >> 24) & 0xFF, tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        return ((int) (fa + (ta - fa) * t) << 24)
             | ((int) (fr + (tr - fr) * t) << 16)
             | ((int) (fg + (tg - fg) * t) << 8)
             |  (int) (fb + (tb - fb) * t);
    }

    private static int[] cornerOffsets(int r) {
        return CORNER_CACHE.computeIfAbsent(r, radius -> {
            int[] offsets = new int[radius];
            for (int i = 0; i < radius; i++) {
                double dy = radius - i - 0.5;
                offsets[i] = (int) Math.ceil(radius - Math.sqrt((double) radius * radius - dy * dy));
            }
            return offsets;
        });
    }
}
