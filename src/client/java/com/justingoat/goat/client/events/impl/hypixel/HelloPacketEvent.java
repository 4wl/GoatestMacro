package com.justingoat.goat.client.events.impl.hypixel;

import com.justingoat.goat.client.events.AbstractEvent;
import net.azureaaron.hmapi.data.server.Environment;

public class HelloPacketEvent extends AbstractEvent {
    private final Environment environment;

    public HelloPacketEvent(Environment environment) {
        this.environment = environment;
    }

    public Environment getEnvironment() {
        return environment;
    }
}
