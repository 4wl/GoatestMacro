package com.justingoat.goat.client.utils;

import com.justingoat.goat.client.events.EventListener;
import com.justingoat.goat.client.events.EventManager;
import com.justingoat.goat.client.events.impl.hypixel.LocationUpdatePacketEvent;
import com.justingoat.goat.client.events.impl.skyblock.LocationChangedEvent;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkyBlockUtils {
    private static boolean isOnSkyblock;
    private static Location currentIsland;

    private static final Pattern ISLAND_PATTERN = Pattern.compile("Area: (.+)");
    private static final Pattern COINS_PATTERN = Pattern.compile("Purse: ([0-9,]+)");
    private static final Pattern BITS_PATTERN = Pattern.compile("Bits: ([0-9,]+)");
    private static final Pattern PROFILE_PATTERN = Pattern.compile("Profile: (.+)");

    @EventListener
    private void onLocationUpdate(LocationUpdatePacketEvent event) {
        isOnSkyblock = event.getServerType().isPresent() && event.getServerType().get().equals("SKYBLOCK");

        for (Location location : Location.values()) {
            if (event.getMap().isPresent() && event.getMap().get().equals(location.toString())) {
                currentIsland = location;
                EventManager.INSTANCE.fire(new LocationChangedEvent(currentIsland));
            }
        }
    }

    public static boolean isOnSkyBlock() {
        return isOnSkyblock;
    }

    public static String getCurrentIsland() {
        for (String line : TabUtils.getTabLines()) {
            Matcher matcher = ISLAND_PATTERN.matcher(line);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return "Unknown";
    }

    public static long getCoins() {
        for (String line : ScoreboardUtils.getScoreboardLines()) {
            Matcher matcher = COINS_PATTERN.matcher(line);
            if (matcher.find()) {
                return parseNumber(matcher.group(1));
            }
        }
        return 0;
    }

    public static long getBits() {
        for (String line : ScoreboardUtils.getScoreboardLines()) {
            Matcher matcher = BITS_PATTERN.matcher(line);
            if (matcher.find()) {
                return parseNumber(matcher.group(1));
            }
        }
        return 0;
    }

    public static String getCurrentProfile() {
        List<String> tabLines = TabUtils.getTabLines();
        for (String line : tabLines) {
            Matcher matcher = PROFILE_PATTERN.matcher(line);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return "Unknown";
    }

    private static long parseNumber(String numberStr) {
        try {
            return Long.parseLong(numberStr.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean isInDungeon() {
        return getCurrentIsland().toLowerCase().contains("dungeon") ||
               getCurrentIsland().toLowerCase().contains("catacombs");
    }

    public static boolean isInGarden() {
        return getCurrentIsland().equalsIgnoreCase(Location.GARDEN.toString());
    }

    public static boolean isInHub() {
        return getCurrentIsland().equalsIgnoreCase(Location.HUB.toString());
    }

    public static boolean isInPrivateIsland() {
        return getCurrentIsland().equalsIgnoreCase(Location.PRIVATE_ISLAND.toString());
    }

    public static boolean isInMiningArea() {
        String island = getCurrentIsland().toLowerCase();
        return island.contains("mines") ||
               island.contains("quarry") ||
               island.contains("tunnel") ||
               island.contains("cavern");
    }

    public static boolean isInFarmingArea() {
        String island = getCurrentIsland().toLowerCase();
        return island.contains("barn") ||
               island.contains("mushroom") ||
               island.contains("garden") ||
               island.contains("desert");
    }

    public static String formatCoins(long coins) {
        if (coins >= 1_000_000_000) {
            return String.format("%.1fB", coins / 1_000_000_000.0);
        } else if (coins >= 1_000_000) {
            return String.format("%.1fM", coins / 1_000_000.0);
        } else if (coins >= 1_000) {
            return String.format("%.1fK", coins / 1_000.0);
        } else {
            return String.valueOf(coins);
        }
    }
}
