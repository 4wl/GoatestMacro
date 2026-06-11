package com.justingoat.goat.client.events.impl.hypixel;

import com.justingoat.goat.client.events.AbstractEvent;

import java.util.Optional;

public class LocationUpdatePacketEvent extends AbstractEvent {
    private final String serverName;
    private final Optional<String> serverType;
    private final Optional<String> lobbyName;
    private final Optional<String> mode;
    private final Optional<String> map;

    public LocationUpdatePacketEvent(String serverName, Optional<String> serverType,
                                     Optional<String> lobbyName, Optional<String> mode,
                                     Optional<String> map) {
        this.serverName = serverName;
        this.serverType = serverType;
        this.lobbyName = lobbyName;
        this.mode = mode;
        this.map = map;
    }

    public String getServerName() { return serverName; }
    public Optional<String> getServerType() { return serverType; }
    public Optional<String> getLobbyName() { return lobbyName; }
    public Optional<String> getMode() { return mode; }
    public Optional<String> getMap() { return map; }
}
