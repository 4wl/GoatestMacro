package com.justingoat.goat.client.gui.shader;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

public final class StarfieldTextureRenderer {
    private static final int TEX_SIZE = 384;
    private static final int FRAME_COUNT = 40;
    private static final long FRAME_MILLIS = 90L;

    private static final Identifier[] TEXTURES = new Identifier[FRAME_COUNT];
    private static boolean created;
    private static long startMillis;

    private StarfieldTextureRenderer() {
    }

    public static void draw(DrawContext context) {
        ensureTextures();
        int frame = (int) (((System.currentTimeMillis() - startMillis) / FRAME_MILLIS) % FRAME_COUNT);
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                TEXTURES[frame],
                0,
                0,
                0.0f,
                0.0f,
                context.getScaledWindowWidth(),
                context.getScaledWindowHeight(),
                TEX_SIZE,
                TEX_SIZE,
                TEX_SIZE,
                TEX_SIZE
        );
    }

    private static void ensureTextures() {
        if (created) return;

        MinecraftClient client = MinecraftClient.getInstance();
        for (int frame = 0; frame < FRAME_COUNT; frame++) {
            double time = frame / (double) FRAME_COUNT;
            NativeImage image = new NativeImage(TEX_SIZE, TEX_SIZE, false);
            for (int y = 0; y < TEX_SIZE; y++) {
                for (int x = 0; x < TEX_SIZE; x++) {
                    double u = x / (double) (TEX_SIZE - 1);
                    double v = y / (double) (TEX_SIZE - 1);
                    image.setColorArgb(x, y, colorAt(u, v, time));
                }
            }

            Identifier textureId = Identifier.of("goat", "dynamic/starfield_frame_" + frame);
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "Goat animated starfield", image);
            client.getTextureManager().registerTexture(textureId, texture);
            TEXTURES[frame] = textureId;
        }

        startMillis = System.currentTimeMillis();
        created = true;
        System.out.println("[Goat] Animated starfield textures generated OK");
    }

    private static int colorAt(double u, double v, double time) {
        double drift = time * 2.0 * Math.PI;
        double px = (u - 0.5) * 2.0;
        double py = (v - 0.5) * 2.0;
        double aspectPx = px * 1.35;

        double wave = Math.sin(aspectPx * 2.4 + drift * 0.65) * 0.10;
        double bandCenter = wave - 0.04 + Math.sin(drift * 0.42) * 0.025;
        double band = Math.exp(-Math.pow(Math.abs(py - bandCenter), 1.45) * 5.6);

        double cloudX = aspectPx * 2.0 + 17.0 + time * 1.65;
        double cloudY = py * 2.2 - 9.0 - time * 0.85;
        double clouds = fbm(cloudX, cloudY)
                * fbm(aspectPx * 4.7 - 3.0 - time * 2.1, py * 4.2 + 31.0 + time * 1.2);
        double dust = fbm(aspectPx * 11.0 + 4.0 + time * 3.0, py * 10.0 - 12.0);
        double galaxy = band * smoothstep(0.22, 0.72, clouds) * (1.0 - dust * 0.40);

        double r = mix(0.010, 0.030, v);
        double g = mix(0.012, 0.018, v);
        double b = mix(0.030, 0.070, v);

        r += 0.52 * galaxy + 0.36 * Math.pow(Math.max(0.0, galaxy - 0.20), 1.5);
        g += 0.22 * galaxy + 0.46 * Math.pow(galaxy, 1.35);
        b += 0.92 * galaxy + 0.30 * Math.pow(galaxy, 1.15);

        double stars = star(u + time * 0.018, v, 70.0, 0.955, 0.72, time)
                + star(u - time * 0.010, v + time * 0.012, 135.0, 0.978, 0.92, time)
                + star(u + time * 0.006, v - time * 0.018, 245.0, 0.991, 1.20, time);
        r += stars * 0.86;
        g += stars * 0.92;
        b += stars;

        double glowX = aspectPx + 0.28 + Math.sin(drift * 0.35) * 0.06;
        double glowY = py - 0.10 + Math.cos(drift * 0.28) * 0.04;
        double centerGlow = Math.pow(Math.max(0.0, 1.0 - distance(glowX, glowY) * 3.2), 6.0);
        r += centerGlow * 0.42;
        g += centerGlow * 0.70;
        b += centerGlow * 1.00;

        double vignette = 1.0 - smoothstep(0.62, 1.18, distance(px, py));
        r *= 0.40 + vignette * 0.86;
        g *= 0.40 + vignette * 0.86;
        b *= 0.42 + vignette * 0.92;

        return argb(255, gamma(r), gamma(g), gamma(b));
    }

    private static double star(double u, double v, double scale, double threshold, double brightness, double time) {
        double gx = u * scale;
        double gy = v * scale;
        double ix = Math.floor(gx);
        double iy = Math.floor(gy);
        double fx = gx - ix - 0.5;
        double fy = gy - iy - 0.5;
        double rnd = hash(ix, iy);
        if (rnd < threshold) return 0.0;

        double size = mix(0.010, 0.040, hash(ix + 6.17, iy - 2.41));
        double twinkle = 0.70 + 0.30 * Math.sin(time * Math.PI * 2.0 * (1.0 + rnd * 2.5) + rnd * 27.0);
        return (1.0 - smoothstep(0.0, size, distance(fx, fy))) * brightness * twinkle;
    }

    private static double fbm(double x, double y) {
        double value = 0.0;
        double amp = 0.5;
        for (int i = 0; i < 6; i++) {
            value += noise(x, y) * amp;
            double nx = x * 1.62 - y * 1.21 + 17.7;
            double ny = x * 1.21 + y * 1.62 - 11.3;
            x = nx;
            y = ny;
            amp *= 0.52;
        }
        return value;
    }

    private static double noise(double x, double y) {
        double ix = Math.floor(x);
        double iy = Math.floor(y);
        double fx = smooth(x - ix);
        double fy = smooth(y - iy);
        double a = hash(ix, iy);
        double b = hash(ix + 1.0, iy);
        double c = hash(ix, iy + 1.0);
        double d = hash(ix + 1.0, iy + 1.0);
        return mix(mix(a, b, fx), mix(c, d, fx), fy);
    }

    private static double hash(double x, double y) {
        double n = Math.sin(x * 127.1 + y * 311.7) * 43758.5453123;
        return n - Math.floor(n);
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double distance(double x, double y) {
        return Math.sqrt(x * x + y * y);
    }

    private static double gamma(double value) {
        return Math.pow(clamp(value, 0.0, 1.0), 0.86);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int argb(int a, double r, double g, double b) {
        return (a << 24)
                | ((int) Math.round(clamp(r, 0.0, 1.0) * 255.0) << 16)
                | ((int) Math.round(clamp(g, 0.0, 1.0) * 255.0) << 8)
                | (int) Math.round(clamp(b, 0.0, 1.0) * 255.0);
    }
}
