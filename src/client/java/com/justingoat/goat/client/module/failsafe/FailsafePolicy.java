package com.justingoat.goat.client.module.failsafe;

import com.justingoat.goat.client.module.failsafe.impl.BadEffectsFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.BanwaveFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.BedrockCageFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.ChatMentionFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.CobwebFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.DirtFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.DisconnectFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.EvacuateFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.FullInventoryFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.GuestVisitFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.ItemChangeFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.JacobFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.KnockbackFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.LowerAvgBpsFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.PlayerGriefFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.RotationFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.SlotChangeFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.TeleportFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.VelocityFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.WorldChangeFailsafe;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class FailsafePolicy {
    private static final Set<Class<? extends Failsafe>> SERVER_SAFETY = Set.of(
        DisconnectFailsafe.class,
        EvacuateFailsafe.class,
        BanwaveFailsafe.class,
        ChatMentionFailsafe.class,
        TeleportFailsafe.class,
        VelocityFailsafe.class,
        KnockbackFailsafe.class,
        SlotChangeFailsafe.class,
        WorldChangeFailsafe.class,
        PlayerGriefFailsafe.class
    );

    private static final Set<Class<? extends Failsafe>> ROTATION_SENSITIVE = Set.of(
        RotationFailsafe.class
    );

    private static final Set<Class<? extends Failsafe>> GARDEN_TRAP = Set.of(
        BedrockCageFailsafe.class,
        CobwebFailsafe.class,
        DirtFailsafe.class,
        GuestVisitFailsafe.class
    );

    private static final Set<Class<? extends Failsafe>> FARMING = Set.of(
        FullInventoryFailsafe.class,
        ItemChangeFailsafe.class,
        JacobFailsafe.class,
        LowerAvgBpsFailsafe.class
    );

    private static final Set<Class<? extends Failsafe>> GATHERING = Set.of(
        BadEffectsFailsafe.class,
        FullInventoryFailsafe.class,
        LowerAvgBpsFailsafe.class
    );

    private static final Set<Class<? extends Failsafe>> COMBAT = Set.of(
        BadEffectsFailsafe.class,
        FullInventoryFailsafe.class
    );

    private static final Set<Class<? extends Failsafe>> GUI_MACRO = Set.of(
        DisconnectFailsafe.class,
        EvacuateFailsafe.class,
        BanwaveFailsafe.class,
        ChatMentionFailsafe.class,
        WorldChangeFailsafe.class
    );

    private static final Map<String, Set<Class<? extends Failsafe>>> PROFILES = new HashMap<>();

    static {
        profile("FarmingMacro", SERVER_SAFETY, ROTATION_SENSITIVE, GARDEN_TRAP, FARMING);
        profile("VisitorsMacro", GUI_MACRO, Set.of(TeleportFailsafe.class, RotationFailsafe.class, GuestVisitFailsafe.class));
        profile("PestCleaner", SERVER_SAFETY, ROTATION_SENSITIVE, GARDEN_TRAP, GATHERING);
        profile("PlotCleaningHelper", SERVER_SAFETY, ROTATION_SENSITIVE, GARDEN_TRAP, GATHERING);
        profile("ForagingMacro", SERVER_SAFETY, ROTATION_SENSITIVE, GATHERING);
        profile("CombatMacro", SERVER_SAFETY, ROTATION_SENSITIVE, COMBAT);
        profile("MiningBot", SERVER_SAFETY, ROTATION_SENSITIVE, GATHERING);
        profile("MiningMacro", SERVER_SAFETY, ROTATION_SENSITIVE, GATHERING);
        profile("Nuker", SERVER_SAFETY, ROTATION_SENSITIVE, GATHERING);
        profile("NukerMacro", SERVER_SAFETY, ROTATION_SENSITIVE, GATHERING);
        profile("OreMacro", SERVER_SAFETY, ROTATION_SENSITIVE, GATHERING);
        profile("GemstoneMacro", SERVER_SAFETY, ROTATION_SENSITIVE, GATHERING);
        profile("PowderMacro", SERVER_SAFETY, ROTATION_SENSITIVE, GATHERING);
        profile("CommissionMacro", SERVER_SAFETY, ROTATION_SENSITIVE, GATHERING);
        profile("AutoExperiments", GUI_MACRO);
        profile("Pathfinder", SERVER_SAFETY, ROTATION_SENSITIVE, GATHERING);
        profile("FailsafeTestMacro", SERVER_SAFETY, ROTATION_SENSITIVE, GARDEN_TRAP, FARMING, GATHERING, COMBAT);
    }

    private FailsafePolicy() {
    }

    @SafeVarargs
    private static void profile(String macroName, Set<Class<? extends Failsafe>>... groups) {
        Set<Class<? extends Failsafe>> profile = new java.util.HashSet<>();
        for (Set<Class<? extends Failsafe>> group : groups) {
            profile.addAll(group);
        }
        PROFILES.put(macroName, Set.copyOf(profile));
    }

    static boolean isRequired(String macroName, Failsafe failsafe) {
        Set<Class<? extends Failsafe>> profile = PROFILES.get(macroName);
        if (profile == null) return false;

        Class<? extends Failsafe> actual = failsafe.getClass();
        for (Class<? extends Failsafe> required : profile) {
            if (required.isAssignableFrom(actual) || actual.isAssignableFrom(required)) {
                return true;
            }
        }
        return false;
    }
}
