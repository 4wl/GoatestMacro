package com.justingoat.goat.client.events.impl.packet;

import com.justingoat.goat.client.events.AbstractEvent;
import net.minecraft.particle.ParticleEffect;

public class ParticlePacketEvent extends AbstractEvent {
    private final ParticleEffect particle;
    private final double x, y, z;
    private final int count;

    public ParticlePacketEvent(ParticleEffect particle, double x, double y, double z, int count) {
        this.particle = particle;
        this.x = x;
        this.y = y;
        this.z = z;
        this.count = count;
    }

    public ParticleEffect getParticle() { return particle; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public int getCount() { return count; }
}
