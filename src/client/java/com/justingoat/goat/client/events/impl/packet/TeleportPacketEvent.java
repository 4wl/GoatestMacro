package com.justingoat.goat.client.events.impl.packet;

import com.justingoat.goat.client.events.AbstractEvent;

public class TeleportPacketEvent extends AbstractEvent {
    private final double fromX, fromY, fromZ;
    private final double toX, toY, toZ;
    private final float fromYaw, fromPitch;
    private final float toYaw, toPitch;

    public TeleportPacketEvent(double fromX, double fromY, double fromZ,
                               double toX, double toY, double toZ,
                               float fromYaw, float fromPitch,
                               float toYaw, float toPitch) {
        this.fromX = fromX; this.fromY = fromY; this.fromZ = fromZ;
        this.toX = toX; this.toY = toY; this.toZ = toZ;
        this.fromYaw = fromYaw; this.fromPitch = fromPitch;
        this.toYaw = toYaw; this.toPitch = toPitch;
    }

    public double getFromX() { return fromX; }
    public double getFromY() { return fromY; }
    public double getFromZ() { return fromZ; }
    public double getToX() { return toX; }
    public double getToY() { return toY; }
    public double getToZ() { return toZ; }
    public float getFromYaw() { return fromYaw; }
    public float getFromPitch() { return fromPitch; }
    public float getToYaw() { return toYaw; }
    public float getToPitch() { return toPitch; }

    public double getDistance() {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double getTotalRotation() {
        float yawDiff = Math.abs(toYaw - fromYaw);
        float pitchDiff = Math.abs(toPitch - fromPitch);
        return Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }
}
