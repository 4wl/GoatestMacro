package com.justingoat.goat.client.events.impl.packet;

import com.justingoat.goat.client.events.AbstractEvent;

public class SlotChangePacketEvent extends AbstractEvent {
    private final int fromSlot;
    private final int toSlot;

    public SlotChangePacketEvent(int fromSlot, int toSlot) {
        this.fromSlot = fromSlot;
        this.toSlot = toSlot;
    }

    public int getFromSlot() { return fromSlot; }
    public int getToSlot() { return toSlot; }
}
