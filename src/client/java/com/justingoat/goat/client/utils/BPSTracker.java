package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;

public final class BPSTracker {
    private static final long WINDOW_MS = 3000L;
    private static final Deque<Sample> samples = new ArrayDeque<>();
    private static double bps = 0.0;
    private static long lowMovementSince = 0L;

    private BPSTracker() {}

    public static void update(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (client == null || client.player == null || client.world == null) {
            reset();
            return;
        }

        Vec3d pos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        samples.addLast(new Sample(now, pos));
        while (samples.size() > 1 && now - samples.peekFirst().time > WINDOW_MS) {
            samples.removeFirst();
        }

        if (samples.size() < 2) {
            bps = 0.0;
            return;
        }

        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        double seconds = Math.max(0.05, (last.time - first.time) / 1000.0);
        double dx = last.pos.x - first.pos.x;
        double dz = last.pos.z - first.pos.z;
        bps = Math.sqrt(dx * dx + dz * dz) / seconds;
    }

    public static void reset() {
        samples.clear();
        bps = 0.0;
        lowMovementSince = 0L;
    }

    public static double getBps() {
        return bps;
    }

    public static boolean isLowMovementFor(double threshold, long durationMs) {
        long now = System.currentTimeMillis();
        if (bps <= threshold) {
            if (lowMovementSince == 0L) lowMovementSince = now;
            return now - lowMovementSince >= durationMs;
        }
        lowMovementSince = 0L;
        return false;
    }

    private record Sample(long time, Vec3d pos) {}
}
