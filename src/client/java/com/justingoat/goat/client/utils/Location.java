package com.justingoat.goat.client.utils;

public enum Location {
    PRIVATE_ISLAND("Private Island"),
    GARDEN("Garden"),
    HUB("Hub"),
    THE_FARMING_ISLAND("farming_1"),
    THE_PARK("foraging_1"),
    SPIDERS_DEN("Spider's Den"),
    BLAZING_FORTRESS("combat_2"),
    THE_END("combat_3"),
    CRIMSON_ISLE("crimson_isle"),
    GOLD_MINE("mining_1"),
    DEEP_CAVERNS("mining_2"),
    DWARVEN_MINES("mining_3"),
    BACKWATER_BAYOU("fishing_1"),
    DUNGEON_HUB("dungeon_hub"),
    WINTER_ISLAND("Jerry's Workshop"),
    THE_RIFT("rift"),
    DARK_AUCTION("dark_auction"),
    CRYSTAL_HOLLOWS("crystal_hollows"),
    DUNGEON("Dungeons"),
    KUUDRAS_HOLLOW("Kuudra's Hollow"),
    GLACITE_MINESHAFT("mineshaft"),
    GALATEA("foraging_2"),
    UNKNOWN("unknown");

    private final String name;

    Location(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
