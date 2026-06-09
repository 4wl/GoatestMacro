package com.justingoat.goat.client.gui;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

public final class CustomFontRenderer implements AutoCloseable {
    private static final Identifier FONT = Identifier.of("goat", "font/gui.ttf");
    private static final float FONT_SIZE = 9.0F;
    private static final int PADDING = 2;

    private final Map<GlyphKey, Glyph> glyphs = new HashMap<>();
    private final Font font;

    public CustomFontRenderer() {
        this.font = loadFont();
    }

    public void drawText(DrawContext context, String text, int x, int y, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }

        Glyph glyph = getGlyph(text, color);
        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            glyph.id,
            x,
            y - glyph.ascentOffset,
            0.0F,
            0.0F,
            glyph.width,
            glyph.height,
            glyph.width,
            glyph.height
        );
    }

    public int getWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return measure(text).width;
    }

    @Override
    public void close() {
        MinecraftClient client = MinecraftClient.getInstance();
        for (Glyph glyph : glyphs.values()) {
            client.getTextureManager().destroyTexture(glyph.id);
        }
        glyphs.clear();
    }

    private Glyph getGlyph(String text, int color) {
        GlyphKey key = new GlyphKey(text, color);
        Glyph cached = glyphs.get(key);
        if (cached != null) {
            return cached;
        }

        TextMetrics metrics = measure(text);
        int imageWidth = Math.max(1, metrics.width + PADDING * 2);
        int imageHeight = Math.max(1, metrics.height + PADDING * 2);
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setFont(font);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            graphics.setColor(new java.awt.Color(color, true));
            graphics.drawString(text, PADDING, PADDING + metrics.ascent);
        } finally {
            graphics.dispose();
        }

        NativeImage nativeImage = new NativeImage(imageWidth, imageHeight, false);
        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < imageWidth; x++) {
                nativeImage.setColorArgb(x, y, image.getRGB(x, y));
            }
        }

        Identifier id = Identifier.of("goat", "dynamic/font/" + glyphs.size());
        NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "Goat GUI text", nativeImage);
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);

        Glyph glyph = new Glyph(id, imageWidth, imageHeight, PADDING);
        glyphs.put(key, glyph);
        return glyph;
    }

    private TextMetrics measure(String text) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setFont(font);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            FontMetrics metrics = graphics.getFontMetrics();
            return new TextMetrics(metrics.stringWidth(text), metrics.getHeight(), metrics.getAscent());
        } finally {
            graphics.dispose();
        }
    }

    private Font loadFont() {
        try (InputStream stream = MinecraftClient.getInstance().getResourceManager().open(FONT)) {
            return Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(Font.PLAIN, FONT_SIZE);
        } catch (FontFormatException | IOException ignored) {
            return new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(FONT_SIZE));
        }
    }

    private record GlyphKey(String text, int color) {
    }

    private record TextMetrics(int width, int height, int ascent) {
    }

    private record Glyph(Identifier id, int width, int height, int ascentOffset) {
    }
}
