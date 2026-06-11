package com.justingoat.goat.client.events.impl.skyblock;

import com.justingoat.goat.client.events.AbstractEvent;
import com.justingoat.goat.client.utils.Location;

public class LocationChangedEvent extends AbstractEvent {
    private final Location location;

    public LocationChangedEvent(Location location) {
        this.location = location;
    }

    public Location getLocation() {
        return location;
    }
}
