package com.justingoat.goat.client.module;

import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.InputUtils;
import net.minecraft.client.MinecraftClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class MacroScheduler extends GoatModule implements MacroHudInfo {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private enum Phase {
        WAITING_FOR_WORLD,
        RUNNING,
        RESTING
    }

    private final ModeValue targetMacro;
    private final NumberValue runMinHours;
    private final NumberValue runMaxHours;
    private final NumberValue restMinMinutes;
    private final NumberValue restMaxMinutes;

    private Phase phase = Phase.WAITING_FOR_WORLD;
    private String controlledTargetName = "";
    private long phaseStartedAt = 0;
    private long phaseEndsAt = 0;
    private long plannedDurationMillis = 0;

    public MacroScheduler() {
        super("MacroScheduler", ModuleCategory.MACRO, false);
        targetMacro = addMode("Target Macro", "FarmingMacro",
            "FarmingMacro",
            "ForagingMacro",
            "CombatMacro",
            "MiningBot",
            "NukerMacro",
            "OreMacro",
            "GemstoneMacro",
            "PowderMacro",
            "CommissionMacro",
            "AutoExperiments",
            "PestCleaner"
        );
        runMinHours = addNumber("Run Min (hr)", 1.8, 0.1, 24.0);
        runMaxHours = addNumber("Run Max (hr)", 2.3, 0.1, 24.0);
        restMinMinutes = addNumber("Rest Min (min)", 10.0, 1.0, 180.0);
        restMaxMinutes = addNumber("Rest Max (min)", 20.0, 1.0, 180.0);
    }

    @Override
    public void setEnabled(boolean enabled) {
        boolean wasEnabled = isEnabled();
        super.setEnabled(enabled);

        if (enabled && !wasEnabled) {
            phase = Phase.WAITING_FOR_WORLD;
            phaseStartedAt = 0;
            phaseEndsAt = 0;
            plannedDurationMillis = 0;
            controlledTargetName = targetMacro.getValue();
            ChatUtils.sendInfoMessage("MacroScheduler enabled. Waiting for world...");
        } else if (!enabled && wasEnabled) {
            stopControlledTarget();
            phase = Phase.WAITING_FOR_WORLD;
            phaseStartedAt = 0;
            phaseEndsAt = 0;
            plannedDurationMillis = 0;
            ChatUtils.sendWarningMessage("MacroScheduler disabled");
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled()) return;

        if (FailsafeManager.getInstance().hasEmergency()) {
            stopControlledTarget();
            return;
        }

        if (client.player == null || client.world == null) {
            stopControlledTarget();
            phase = Phase.WAITING_FOR_WORLD;
            return;
        }

        if (!targetMacro.getValue().equals(controlledTargetName)) {
            stopControlledTarget();
            phase = Phase.WAITING_FOR_WORLD;
            controlledTargetName = targetMacro.getValue();
        }

        long now = System.currentTimeMillis();
        if (phase == Phase.WAITING_FOR_WORLD) {
            enterRunning(now);
            return;
        }

        GoatModule target = getTargetModule();
        if (target == null) {
            setEnabled(false);
            ChatUtils.sendErrorMessage("MacroScheduler target not found: " + targetMacro.getValue());
            return;
        }

        if (phase == Phase.RUNNING) {
            if (!target.isEnabled()) {
                target.setEnabled(true);
            }
            if (now >= phaseEndsAt) {
                enterResting(now);
            }
            return;
        }

        if (phase == Phase.RESTING && now >= phaseEndsAt) {
            enterRunning(now);
        }
    }

    private void enterRunning(long now) {
        GoatModule target = getTargetModule();
        if (target == null || target == this) {
            setEnabled(false);
            ChatUtils.sendErrorMessage("MacroScheduler target is invalid: " + targetMacro.getValue());
            return;
        }

        controlledTargetName = target.getName();
        plannedDurationMillis = randomDurationMillis(
            runMinHours.getValue() * 60.0 * 60.0 * 1000.0,
            runMaxHours.getValue() * 60.0 * 60.0 * 1000.0
        );
        phaseStartedAt = now;
        phaseEndsAt = now + plannedDurationMillis;
        phase = Phase.RUNNING;

        target.setEnabled(true);
        ChatUtils.sendSuccessMessage("MacroScheduler running " + target.getName() + " for " + formatDuration(plannedDurationMillis));
    }

    private void enterResting(long now) {
        stopControlledTarget();
        InputUtils.releaseAll();

        plannedDurationMillis = randomDurationMillis(
            restMinMinutes.getValue() * 60.0 * 1000.0,
            restMaxMinutes.getValue() * 60.0 * 1000.0
        );
        phaseStartedAt = now;
        phaseEndsAt = now + plannedDurationMillis;
        phase = Phase.RESTING;

        ChatUtils.sendWarningMessage("MacroScheduler resting " + controlledTargetName + " for " + formatDuration(plannedDurationMillis));
    }

    private GoatModule getTargetModule() {
        return ModuleManager.findByName(targetMacro.getValue());
    }

    public boolean isRunningTarget() {
        return phase == Phase.RUNNING;
    }

    private void stopControlledTarget() {
        if (controlledTargetName == null || controlledTargetName.isEmpty()) {
            controlledTargetName = targetMacro.getValue();
        }

        GoatModule target = ModuleManager.findByName(controlledTargetName);
        if (target != null && target != this && target.isEnabled()) {
            target.setEnabled(false);
        }
    }

    private long randomDurationMillis(double minMillis, double maxMillis) {
        double min = Math.min(minMillis, maxMillis);
        double max = Math.max(minMillis, maxMillis);
        if (max <= min) {
            return Math.max(1000L, Math.round(min));
        }
        return Math.max(1000L, Math.round(ThreadLocalRandom.current().nextDouble(min, max)));
    }

    @Override
    public String getHudName() {
        return "Scheduler";
    }

    @Override
    public String getHudState() {
        return switch (phase) {
            case WAITING_FOR_WORLD -> "Waiting for world";
            case RUNNING -> "Running";
            case RESTING -> "Resting";
        };
    }

    @Override
    public List<String> getHudExtraLines() {
        String target = controlledTargetName == null || controlledTargetName.isEmpty()
            ? targetMacro.getValue()
            : controlledTargetName;

        if (phaseStartedAt <= 0 || plannedDurationMillis <= 0) {
            return List.of("Target: " + target);
        }

        String phaseName = phase == Phase.RESTING ? "Rest" : "Run";
        return List.of(
            "Target: " + target,
            "Started: " + formatTime(phaseStartedAt),
            "Planned " + phaseName + ": " + formatDuration(plannedDurationMillis),
            "Remaining: " + formatDuration(Math.max(0, phaseEndsAt - System.currentTimeMillis()))
        );
    }

    @Override
    public int getHudPriority() {
        return 100;
    }

    private String formatTime(long millis) {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(millis));
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }
}
