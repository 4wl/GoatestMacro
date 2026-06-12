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
    private static final String REGULAR_FONT = "/assets/goat/fonts/Rubik-Regular.ttf";
    private static final String MEDIUM_FONT = "/assets/goat/fonts/Rubik-Medium.ttf";
    private static final String BOLD_FONT = "/assets/goat/fonts/Rubik-Bold.ttf";
    private static final float FONT_SIZE = 9.0F;
    private static final int RENDER_SCALE = 2;
    private static final int PADDING = 2 * RENDER_SCALE;

    private final Map<GlyphKey, Glyph> glyphs = new HashMap<>();
    private final Font regularFont;
    private final Font mediumFont;
    private final Font boldFont;
    private int nextTextureId;

    public CustomFontRenderer() {
        this.regularFont = loadFont(REGULAR_FONT, Font.PLAIN);
        this.mediumFont = loadFont(MEDIUM_FONT, Font.PLAIN);
        this.boldFont = loadFont(BOLD_FONT, Font.BOLD);
    }

    public void drawText(DrawContext context, String text, int x, int y, int color) {
        drawText(context, text, x, y, color, FontWeight.REGULAR);
    }

    public void drawMediumText(DrawContext context, String text, int x, int y, int color) {
        drawText(context, text, x, y, color, FontWeight.MEDIUM);
    }

    public void drawBoldText(DrawContext context, String text, int x, int y, int color) {
        drawText(context, text, x, y, color, FontWeight.BOLD);
    }

    public int getWidth(String text) {
        return getWidth(text, FontWeight.REGULAR);
    }

    public int getMediumWidth(String text) {
        return getWidth(text, FontWeight.MEDIUM);
    }

    public int getBoldWidth(String text) {
        return getWidth(text, FontWeight.BOLD);
    }

    @Override
    public void close() {
        MinecraftClient client = MinecraftClient.getInstance();
        for (Glyph glyph : glyphs.values()) {
            client.getTextureManager().destroyTexture(glyph.id);
        }
        glyphs.clear();
    }

    private void drawText(DrawContext context, String text, int x, int y, int color, FontWeight weight) {
        if (text == null || text.isEmpty()) {
            return;
        }

        Glyph glyph = getGlyph(text, color, weight);
        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            glyph.id,
            x,
            y - glyph.ascentOffset,
            0.0F,
            0.0F,
            glyph.displayWidth,
            glyph.displayHeight,
            glyph.textureWidth,
            glyph.textureHeight,
            glyph.textureWidth,
            glyph.textureHeight
        );
    }

    private int getWidth(String text, FontWeight weight) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return toLogicalSize(measure(text, weight).width);
    }

    private Glyph getGlyph(String text, int color, FontWeight weight) {
        GlyphKey key = new GlyphKey(text, color, weight);
        Glyph cached = glyphs.get(key);
        if (cached != null) {
            return cached;
        }

        Font font = fontFor(weight);
        TextMetrics metrics = measure(text, weight);
        int imageWidth = Math.max(1, metrics.width + PADDING * 2);
        int imageHeight = Math.max(1, metrics.height + PADDING * 2);
        int displayWidth = toLogicalSize(imageWidth);
        int displayHeight = toLogicalSize(imageHeight);
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setFont(font);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
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

        Identifier id = Identifier.of("goat", "dynamic/font/" + nextTextureId++);
        NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "Goat GUI text", nativeImage);
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);

        Glyph glyph = new Glyph(id, imageWidth, imageHeight, displayWidth, displayHeight, PADDING / RENDER_SCALE);
        glyphs.put(key, glyph);
        return glyph;
    }

    private TextMetrics measure(String text, FontWeight weight) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setFont(fontFor(weight));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            FontMetrics metrics = graphics.getFontMetrics();
            return new TextMetrics(metrics.stringWidth(text), metrics.getHeight(), metrics.getAscent());
        } finally {
            graphics.dispose();
        }
    }

    private Font fontFor(FontWeight weight) {
        return switch (weight) {
            case REGULAR -> regularFont;
            case MEDIUM -> mediumFont;
            case BOLD -> boldFont;
        };
    }

    private Font loadFont(String resourcePath, int fallbackStyle) {
        try (InputStream stream = CustomFontRenderer.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return new Font(Font.SANS_SERIF, fallbackStyle, Math.round(FONT_SIZE * RENDER_SCALE));
            }
            return Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(Font.PLAIN, FONT_SIZE * RENDER_SCALE);
        } catch (FontFormatException | IOException ignored) {
            return new Font(Font.SANS_SERIF, fallbackStyle, Math.round(FONT_SIZE * RENDER_SCALE));
        }
    }

    private int toLogicalSize(int renderSize) {
        return Math.max(1, (int) Math.ceil(renderSize / (double) RENDER_SCALE));
    }

    private enum FontWeight {
        REGULAR,
        MEDIUM,
        BOLD
    }

    private record GlyphKey(String text, int color, FontWeight weight) {
    }

    private record TextMetrics(int width, int height, int ascent) {
    }

    private record Glyph(
        Identifier id,
        int textureWidth,
        int textureHeight,
        int displayWidth,
        int displayHeight,
        int ascentOffset
    ) {
    }
}
