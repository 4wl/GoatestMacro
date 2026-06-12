package com.justingoat.goat.client.module.mining;

import java.util.*;

public final class CommissionData {

    private CommissionData() {}

    public static final int[][] EMISSARY_LOCATIONS = {
        {129, 195, 196},  // King (must be first)
        {18, 196, 212},
        {-7, 196, 197},
        {-15, 196, 212},
        {171, 195, 210},
        {131, 174, 26},
        {0, 174, -3},
    };

    public static final Set<String> TRASH_ITEMS = Set.of(
        "Mithril", "Titanium", "Rune", "Glacite", "Goblin", "Cobblestone", "Stone"
    );

    public enum CommissionType { MINING, SLAYER }

    public record MobConfig(List<String> names, double visibility, int[][] boundaries) {}

    public static final Map<String, MobConfig> MOB_CONFIGS = new LinkedHashMap<>();
    static {
        MOB_CONFIGS.put("goblin", new MobConfig(
            List.of("Goblin", "Weakling", "Knifethrower"),
            10.0, null
        ));
        MOB_CONFIGS.put("icewalker", new MobConfig(
            List.of("Ice Walker", "Glacite"), 10.0, null
        ));
        MOB_CONFIGS.put("treasure", new MobConfig(
            List.of("Treasure Hoarder"), 10.0, null
        ));
    }

    public record CommissionEntry(
        List<String> names,
        CommissionType type,
        int cost,
        int[][] waypoints,
        boolean useAllMiningWaypoints
    ) {}

    public static final List<CommissionEntry> COMMISSIONS = new ArrayList<>();
    static {
        COMMISSIONS.add(new CommissionEntry(
            List.of("Mithril Miner"), CommissionType.MINING, 1,
            new int[][]{{88, 195, 178}, {113, 189, 181}, {151, 190, 185}, {55, 197, 241}},
            false
        ));
        COMMISSIONS.add(new CommissionEntry(
            List.of("Titanium Miner"), CommissionType.MINING, 2,
            new int[][]{{88, 195, 178}, {113, 189, 181}, {151, 190, 185}, {55, 197, 241}},
            false
        ));
        COMMISSIONS.add(new CommissionEntry(
            List.of("Mithril Powder Collector"), CommissionType.MINING, 1,
            new int[][]{{88, 195, 178}, {113, 189, 181}},
            true
        ));
        COMMISSIONS.add(new CommissionEntry(
            List.of("Star Sentry Puncher", "Upper Mines Mithril"), CommissionType.MINING, 3,
            new int[][]{{151, 190, 185}, {128, 190, 173}},
            false
        ));
        COMMISSIONS.add(new CommissionEntry(
            List.of("Royal Mines Mithril"), CommissionType.MINING, 2,
            new int[][]{{52, 197, 240}, {55, 197, 241}},
            false
        ));
        COMMISSIONS.add(new CommissionEntry(
            List.of("Cliffside Veins Mithril"), CommissionType.MINING, 3,
            new int[][]{{69, 195, 149}, {51, 197, 139}},
            false
        ));
        COMMISSIONS.add(new CommissionEntry(
            List.of("Rampart's Quarry Mithril"), CommissionType.MINING, 3,
            new int[][]{{-34, 197, 234}, {-48, 193, 220}},
            false
        ));
        COMMISSIONS.add(new CommissionEntry(
            List.of("Goblin Slayer"), CommissionType.SLAYER, 4,
            new int[][]{{-21, 175, 37}, {-44, 176, 82}},
            false
        ));
        COMMISSIONS.add(new CommissionEntry(
            List.of("Glacite Walker Slayer", "Mines Slayer", "Treasure Hoarder Puncher"), CommissionType.SLAYER, 5,
            new int[][]{{18, 193, 206}, {128, 190, 173}},
            false
        ));
    }

    public static CommissionEntry findByName(String name) {
        for (CommissionEntry entry : COMMISSIONS) {
            if (entry.names().contains(name)) return entry;
        }
        return null;
    }

    public static boolean isKnownCommission(String name) {
        return findByName(name) != null;
    }
}
