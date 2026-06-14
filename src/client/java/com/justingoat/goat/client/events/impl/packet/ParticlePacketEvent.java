package com.justingoat.goat.client.events.impl.packet;

import com.justingoat.goat.client.events.AbstractEvent;
import net.minecraft.particle.ParticleEffect;

public class ParticlePacketEvent extends AbstractEvent {
    private final ParticleEffect particle;
    private final double x, y, z;
    private final float offsetX, offsetY, offsetZ, speed;
    private final int count;
    private final boolean forceSpawn;
    private final boolean important;

    public ParticlePacketEvent(ParticleEffect particle, double x, double y, double z, int count) {
        this(particle, x, y, z, 0.0f, 0.0f, 0.0f, 0.0f, count, false, false);
    }

    public ParticlePacketEvent(ParticleEffect particle, double x, double y, double z,
                               float offsetX, float offsetY, float offsetZ, float speed,
                               int count, boolean forceSpawn, boolean important) {
        this.particle = particle;
        this.x = x;
        this.y = y;
        this.z = z;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.speed = speed;
        this.count = count;
        this.forceSpawn = forceSpawn;
        this.important = important;
    }

    public ParticleEffect getParticle() { return particle; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getOffsetX() { return offsetX; }
    public float getOffsetY() { return offsetY; }
    public float getOffsetZ() { return offsetZ; }
    public float getSpeed() { return speed; }
    public int getCount() { return count; }
    public boolean shouldForceSpawn() { return forceSpawn; }
    public boolean isImportant() { return important; }
}
