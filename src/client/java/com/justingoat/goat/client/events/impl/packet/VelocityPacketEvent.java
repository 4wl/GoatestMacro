package com.justingoat.goat.client.events.impl.packet;

import com.justingoat.goat.client.events.AbstractEvent;

public class VelocityPacketEvent extends AbstractEvent {
    private final int entityId;
    private final double velocityX, velocityY, velocityZ;

    public VelocityPacketEvent(int entityId, double velocityX, double velocityY, double velocityZ) {
        this.entityId = entityId;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
    }

    public int getEntityId() { return entityId; }
    public double getVelocityX() { return velocityX; }
    public double getVelocityY() { return velocityY; }
    public double getVelocityZ() { return velocityZ; }

    public double getSpeed() {
        return Math.sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ);
    }
}
