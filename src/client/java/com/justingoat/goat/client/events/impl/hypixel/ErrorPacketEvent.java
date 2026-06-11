package com.justingoat.goat.client.events.impl.hypixel;

import com.justingoat.goat.client.events.AbstractEvent;
import net.azureaaron.hmapi.data.error.ErrorReason;
import net.azureaaron.hmapi.network.packet.s2c.HypixelS2CPacket;
import net.minecraft.network.packet.CustomPayload;

public class ErrorPacketEvent extends AbstractEvent {
    private final CustomPayload.Id<HypixelS2CPacket> id;
    private final ErrorReason errorReason;

    public ErrorPacketEvent(CustomPayload.Id<HypixelS2CPacket> id, ErrorReason errorReason) {
        this.id = id;
        this.errorReason = errorReason;
    }

    public CustomPayload.Id<HypixelS2CPacket> getId() { return id; }
    public ErrorReason getErrorReason() { return errorReason; }
}
