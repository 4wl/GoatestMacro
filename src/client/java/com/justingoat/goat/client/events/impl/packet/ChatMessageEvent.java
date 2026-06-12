package com.justingoat.goat.client.events.impl.packet;

import com.justingoat.goat.client.events.AbstractEvent;

public class ChatMessageEvent extends AbstractEvent {
    private final String message;
    private final boolean isOverlay;

    public ChatMessageEvent(String message, boolean isOverlay) {
        this.message = message;
        this.isOverlay = isOverlay;
    }

    public String getMessage() { return message; }
    public boolean isOverlay() { return isOverlay; }
}
