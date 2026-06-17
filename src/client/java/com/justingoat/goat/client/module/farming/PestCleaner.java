package com.justingoat.goat.client.module.farming;

import com.justingoat.goat.client.events.EventListener;
import com.justingoat.goat.client.events.EventManager;
import com.justingoat.goat.client.events.impl.packet.ChatMessageEvent;
import com.justingoat.goat.client.events.impl.packet.ParticlePacketEvent;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.module.failsafe.impl.RotationFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.TeleportFailsafe;
import com.justingoat.goat.client.module.pathfinder.FlyPathProcessor;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.AimController;
import com.justingoat.goat.client.utils.AntiStuckController;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.CommandUtils;
import com.justingoat.goat.client.utils.EntitySearchUtils;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.InventoryUtils;
import com.justingoat.goat.client.utils.ItemNameUtils;
import com.justingoat.goat.client.utils.PathMath;
import com.justingoat.goat.client.utils.PlotUtils;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.RotationUtils;
import com.justingoat.goat.client.utils.ScoreboardUtils;
import com.justingoat.goat.client.utils.SkyBlockToolUtils;
import com.justingoat.goat.client.utils.TabUtils;
import com.justingoat.goat.client.utils.ToolSelector;
import com.justingoat.goat.client.utils.WorldUtils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.TrailParticleEffect;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.*;
import java.util.regex.Pattern;

public class PestCleaner extends GoatModule implements MacroHudInfo {

    // ── State machine ──────────────────────────────────────────────
    private enum State {
        IDLE,
        SCANNING,
        WARP_TO_PLOT,
        SEARCH_PLOT,
        ACTIVATE_TRACKER,
        FOLLOW_TRACKER_TRAIL,
        EQUIP_VACUUM,
        FLY_TO_PEST,
        APPROACH_PEST,
        KILL_PEST,
        CHECK_MORE,
        GO_BACK,
        FINISHED
    }

    private State state = State.IDLE;

    // ── Settings ──────────────────────────────────────────────────
    private final NumberValue scanRadius;
    private final NumberValue startAtPests;
    private final NumberValue rotSpeed;
    private final NumberValue flyHeight;
    private final BooleanValue autoReturn;
    private final BooleanValue autoRewarp;
    private final BooleanValue pestTracker;
    private final BooleanValue plotTeleport;
    private final BooleanValue renderESP;
    private final BooleanValue debug;

    // ── Pest detection ─────────────────────────────────────────────
    private static final String[] PEST_NAMES = {
        "Praying Mantis", "Field Mouse", "Lunar Moth",
        "Dragonfly", "Earthworm", "Firefly", "Mosquito",
        "Beetle", "Cricket", "Locust", "Mite", "Moth",
        "Rat", "Slug", "Fly"
    };
    private static final Pattern STRIP_COLOR = Pattern.compile("§[0-9a-fk-orx]");
    private static final int TRACKER_WAIT_TICKS = 30;
    private static final int TRACKER_FOLLOW_TIMEOUT_TICKS = 140;
    private static final double TRACKER_REACHED_DISTANCE = 6.0;
    private static final double TRACKER_WAYPOINT_DISTANCE = 12.0;
    private static final double TRACKER_MAX_PARTICLE_DISTANCE = 96.0;
    private static final double TRACKER_MIN_PARTICLE_STEP_SQ = 0.16;
    private static final int MAX_PATH_FAILURES = 2;
    private static final int MAX_REASONABLE_PATH_POINTS = 420;
    private static final double MAX_PATH_DETOUR_FACTOR = 3.0;
    private static final int PLOT_WARP_SETTLE_TICKS = 20;
    private static final int PLOT_SEARCH_POINT_SETTLE_TICKS = 12;
    private static final int PLOT_SEARCH_TIMEOUT_TICKS = 420;
    private static final int PLOT_SEARCH_TRACKER_INTERVAL_TICKS = 45;
    private static final double PLOT_SEARCH_REACHED_DISTANCE = 8.0;
    private static final double PLOT_SEARCH_INNER_OFFSET = 30.0;
    private static final int ANTI_STUCK_TICKS = 35;
    private static final int ANTI_STUCK_NUDGE_TICKS = 12;
    private static final int ANTI_STUCK_MAX_NUDGES = 2;
    private static final double ANTI_STUCK_MIN_MOVE_SQ = 0.006;
    private static final double ANTI_STUCK_TARGET_CHANGE_SQ = 64.0;
    private static final double ANTI_STUCK_ESCAPE_CHECK_DISTANCE = 1.25;
    private static final int VACUUM_BLOCKED_RECOVERY_TICKS = 35;
    private static final int VACUUM_STALL_RECOVERY_TICKS = 95;
    private static final int VACUUM_MAX_RECOVERY_ATTEMPTS = 2;
    private static final double MIN_HOVER_ABOVE_PEST = 3.0;
    private static final double HOVER_Y_TOLERANCE = 0.45;
    private static final Pattern PLOT_NUMBER_PATTERN = Pattern.compile("(?i)\\bplots?\\s*(?:-|#|:)?\\s*(\\d{1,2}(?:\\s*(?:,|/|&|and)\\s*\\d{1,2})*)");
    private static final Pattern GARDEN_PEST_COUNT_PATTERN = Pattern.compile("(?i)\\bthe garden\\b.*?(?:x\\s*(\\d+)|(\\d+)\\s*x)\\s*$");
    private static final Pattern GARDEN_PEST_LABEL_COUNT_PATTERN = Pattern.compile("(?i).*\\bpests?\\b\\s*(?::|-)?\\s*(?:x\\s*)?(\\d+)\\s*x?\\b.*");
    private static final Pattern CURRENT_PLOT_PESTS_PATTERN = Pattern.compile("(?i)\\bplot\\s*-\\s*(.+?)\\s+.*?x(\\d+)\\s*$");
    private static final Pattern CURRENT_PLOT_NO_PESTS_PATTERN = Pattern.compile("(?i)\\bplot\\s*-\\s*(.+?)\\s*$");
    private static final Pattern ONE_PEST_SPAWN_PATTERN = Pattern.compile("(?i).*\\bA\\b.*\\bPest\\b has appeared in (?:Plot - )?(.+)!");
    private static final Pattern MULTI_PEST_SPAWN_PATTERN = Pattern.compile("(?i).*\\b(\\d+)\\b.*\\bPests?\\b have spawned in (?:Plot - )?(.+)!");
    private static final Pattern OFFLINE_PEST_SPAWN_PATTERN = Pattern.compile("(?i).*While you were offline, .*\\bPests?\\b spawned in Plots (.+)!");
    private static final Pattern NO_PESTS_CHAT_PATTERN = Pattern.compile("(?i).*There are not any Pests on your Garden right now.*");
    // ── Vacuum data ────────────────────────────────────────────────
    private static final Map<String, Double> VACUUM_RANGES = new LinkedHashMap<>();
    static {
        VACUUM_RANGES.put("InfiniVacuum™ Hooverius", 15.0);
        VACUUM_RANGES.put("InfiniVacuum", 12.5);
        VACUUM_RANGES.put("Hyper Pest Vacuum", 10.0);
        VACUUM_RANGES.put("Turbo Pest Vacuum", 7.5);
        VACUUM_RANGES.put("SkyMart Vacuum", 5.0);
    }

    // ── State data ─────────────────────────────────────────────────
    private final RotationUtils rotation = new RotationUtils();
    private final AimController aim = new AimController(rotation);
    private final FlyPathProcessor flyProcessor = new FlyPathProcessor();
    private volatile boolean pathing = false;

    private Vec3d startPosition = null;
    private Entity currentPestTag = null;
    private Vec3d currentPestPos = null;
    private final Set<Integer> killedPestIds = new HashSet<>();
    private final List<PestInfo> detectedPests = new ArrayList<>();
    private final List<Vec3d> trackerParticles = new ArrayList<>();
    private final List<Vec3d> plotSearchPoints = new ArrayList<>();
    private final LinkedHashMap<String, PestPlotInfo> knownPestPlots = new LinkedHashMap<>();
    private final Set<String> attemptedPestPlotWarps = new HashSet<>();
    private Vec3d trackerWaypoint = null;
    private PestPlotInfo currentWarpPlot = null;

    private int vacuumSlot = -1;
    private double vacuumRange = 5.0;
    private int killTicks = 0;
    private int equipDelay = 0;
    private int scanDelay = 0;
    private int killsThisSession = 0;
    private int previousSlot = -1;
    private int trackerTicks = 0;
    private int trackerFollowTicks = 0;
    private int plotWarpTicks = 0;
    private int plotSearchTicks = 0;
    private int plotSearchSettleTicks = 0;
    private int plotSearchIndex = 0;
    private int plotSearchTrackerCooldown = 0;
    private int pathFailures = 0;
    private int antiStuckTicks = 0;
    private int antiStuckNudgeTicks = 0;
    private int antiStuckAttempts = 0;
    private int vacuumBlockedTicks = 0;
    private int vacuumRecoveryAttempts = 0;
    private int scoreboardPests = -1;
    private boolean trackerAttempted = false;
    private boolean trackerFromPlotSearch = false;
    private boolean autoRewarpSent = false;
    private Vec3d lastPathFailureTarget = null;
    private Vec3d antiStuckLastPos = null;
    private Vec3d antiStuckTarget = null;
    private Float antiStuckEscapeYaw = null;

    // ── Render data (for PathRenderer) ─────────────────────────────
    private static volatile List<PestInfo> renderPests = null;
    private static volatile PestInfo renderTarget = null;

    public static List<PestInfo> getRenderPests() { return renderPests; }
    public static PestInfo getRenderTarget() { return renderTarget; }

    // ═══════════════════════════════════════════════════ Constructor

    public PestCleaner() {
        super("PestCleaner", ModuleCategory.MACRO, false);

        scanRadius = addNumber("ScanRadius", 60.0, 10.0, 120.0);
        startAtPests = addNumber("StartAtPests", 1.0, 1.0, 5.0);
        rotSpeed   = addNumber("RotSpeed", 0.6, 0.1, 1.0);
        flyHeight  = addNumber("FlyHeight", 4.5, 2.5, 12.0);
        autoReturn = addBoolean("AutoReturn", true);
        autoRewarp = addBoolean("AutoRewarp", true);
        pestTracker = addBoolean("PestTracker", true);
        plotTeleport = addBoolean("PlotTeleport", true);
        renderESP  = addBoolean("RenderESP", true);
        debug      = addBoolean("Debug", false);
    }

    // ═══════════════════════════════════════════════════ Lifecycle

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled == isEnabled()) return;
        super.setEnabled(enabled);

        if (enabled) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) {
                super.setEnabled(false);
                return;
            }
            FailsafeManager.getInstance().reset();
            startPosition = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
            previousSlot = client.player.getInventory().getSelectedSlot();
            state = State.SCANNING;
            currentPestTag = null;
            currentPestPos = null;
            killedPestIds.clear();
            detectedPests.clear();
            trackerParticles.clear();
            plotSearchPoints.clear();
            knownPestPlots.clear();
            attemptedPestPlotWarps.clear();
            trackerWaypoint = null;
            currentWarpPlot = null;
            killsThisSession = 0;
            pathing = false;
            scanDelay = 0;
            trackerTicks = 0;
            trackerFollowTicks = 0;
            plotWarpTicks = 0;
            plotSearchTicks = 0;
            plotSearchSettleTicks = 0;
            plotSearchIndex = 0;
            plotSearchTrackerCooldown = 0;
            pathFailures = 0;
            resetAntiStuck();
            vacuumBlockedTicks = 0;
            vacuumRecoveryAttempts = 0;
            scoreboardPests = -1;
            trackerAttempted = false;
            trackerFromPlotSearch = false;
            autoRewarpSent = false;
            lastPathFailureTarget = null;
            rotation.clear();
            if (!EventManager.INSTANCE.isRegistered(this)) {
                EventManager.INSTANCE.register(this);
            }
            syncKnownPestsFromHud();
            ChatUtils.sendSuccessMessage("PestCleaner enabled");
        } else {
            stopAll();
            ChatUtils.sendWarningMessage("PestCleaner disabled — killed " + killsThisSession + " pests");
        }
    }

    private void stopAll() {
        flyProcessor.stop();
        aim.applyAndClear(MinecraftClient.getInstance());
        RotationInterpolator.clearActive();
        InputUtils.releaseAll();
        currentPestTag = null;
        currentPestPos = null;
        trackerParticles.clear();
        plotSearchPoints.clear();
        attemptedPestPlotWarps.clear();
        trackerWaypoint = null;
        currentWarpPlot = null;
        trackerTicks = 0;
        trackerFollowTicks = 0;
        plotWarpTicks = 0;
        plotSearchTicks = 0;
        plotSearchSettleTicks = 0;
        plotSearchIndex = 0;
        plotSearchTrackerCooldown = 0;
        pathFailures = 0;
        resetAntiStuck();
        vacuumBlockedTicks = 0;
        vacuumRecoveryAttempts = 0;
        trackerAttempted = false;
        trackerFromPlotSearch = false;
        lastPathFailureTarget = null;
        pathing = false;
        renderPests = null;
        renderTarget = null;
        EventManager.INSTANCE.unregister(this);
        if (previousSlot >= 0) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                InventoryUtils.equipHotbarSlot(client, previousSlot);
            }
            previousSlot = -1;
        }
    }

    // ═══════════════════════════════════════════════════ Main tick

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled()) return;
        if (client.player == null || client.world == null) return;
        if (client.currentScreen != null) {
            InputUtils.releaseAll();
            return;
        }

        if (FailsafeManager.getInstance().hasEmergency()) {
            setEnabled(false);
            return;
        }

        scanForPests(client);
        syncKnownPestsFromHud();
        if (stopIfGardenPestsConfirmedEmpty()) {
            return;
        }

        switch (state) {
            case IDLE     -> {}
            case SCANNING -> tickScanning(client);
            case WARP_TO_PLOT -> tickWarpToPlot(client);
            case SEARCH_PLOT -> tickSearchPlot(client);
            case ACTIVATE_TRACKER -> tickActivateTracker(client);
            case FOLLOW_TRACKER_TRAIL -> tickFollowTrackerTrail(client);
            case EQUIP_VACUUM -> tickEquipVacuum(client);
            case FLY_TO_PEST -> tickFlyToPest(client);
            case APPROACH_PEST -> tickApproachPest(client);
            case KILL_PEST -> tickKillPest(client);
            case CHECK_MORE -> tickCheckMore(client);
            case GO_BACK -> tickGoBack(client);
            case FINISHED -> {
                finishCompleted(client);
            }
        }

    }

    // ═══════════════════════════════════════════════════ SCANNING

    private void tickScanning(MinecraftClient client) {
        scanDelay++;
        if (scanDelay < 10) return;
        scanDelay = 0;

        if (shouldActivateTrackerBeforeSearch()) {
            startTrackerActivation();
            return;
        }

        if (detectedPests.isEmpty()) {
            debugMsg("No pests found within range");
            if (beginPestSearchFallback(client)) return;
            if (autoReturn.getValue() && startPosition != null) {
                state = State.GO_BACK;
            } else {
                state = State.FINISHED;
            }
            return;
        }

        PestInfo closest = findClosestPest(client.player);
        if (closest == null) {
            state = State.FINISHED;
            return;
        }

        targetPest(closest);
        debugMsg("Target: " + closest.name + " at " + formatPos(currentPestPos));

        state = State.EQUIP_VACUUM;
        equipDelay = 0;
    }

    // ═══════════════════════════════════════════════════ WARP TO PLOT

    private void tickWarpToPlot(MinecraftClient client) {
        InputUtils.releaseAll();

        if (!detectedPests.isEmpty()) {
            PestInfo closest = findClosestPest(client.player);
            if (closest != null) {
                targetPest(closest);
                state = State.EQUIP_VACUUM;
                equipDelay = 0;
                debugMsg("Found pest after plot warp: " + closest.name);
                return;
            }
        }

        plotWarpTicks++;
        if (plotWarpTicks < PLOT_WARP_SETTLE_TICKS) return;

        if (startPlotSearch(client)) {
            debugMsg("No nearby pest after warping to " + formatPlot(currentWarpPlot) + ", sweeping plot");
            return;
        }

        debugMsg("No nearby pest after warping to " + formatPlot(currentWarpPlot) + ", using Pest Tracker");
        trackerAttempted = false;
        if (pestTracker.getValue()) {
            startTrackerActivation();
            return;
        }

        currentWarpPlot = null;
        state = State.SCANNING;
        scanDelay = 10;
    }

    // ????????????????????????????????????????????????????SEARCH PLOT

    private void tickSearchPlot(MinecraftClient client) {
        if (!detectedPests.isEmpty()) {
            PestInfo closest = findClosestPest(client.player);
            if (closest != null) {
                flyProcessor.stop();
                targetPest(closest);
                state = State.EQUIP_VACUUM;
                equipDelay = 0;
                debugMsg("Found pest during plot sweep: " + closest.name);
                return;
            }
        }

        if (pestTracker.getValue()) {
            if (plotSearchTrackerCooldown > 0) {
                plotSearchTrackerCooldown--;
            }
            if (plotSearchTrackerCooldown <= 0) {
                plotSearchTrackerCooldown = PLOT_SEARCH_TRACKER_INTERVAL_TICKS;
                debugMsg("Activating Pest Tracker during plot sweep");
                startTrackerActivation(true);
                return;
            }
        }

        if (plotSearchPoints.isEmpty() || plotSearchIndex >= plotSearchPoints.size()) {
            finishPlotSearch(client);
            return;
        }

        plotSearchTicks++;
        if (plotSearchTicks > PLOT_SEARCH_TIMEOUT_TICKS) {
            debugMsg("Plot sweep timed out");
            finishPlotSearch(client);
            return;
        }

        Vec3d target = plotSearchPoints.get(plotSearchIndex);
        double dist = playerPos(client).distanceTo(target);

        if (dist <= PLOT_SEARCH_REACHED_DISTANCE) {
            flyProcessor.stop();
            InputUtils.releaseAll();
            plotSearchSettleTicks++;
            if (plotSearchSettleTicks < PLOT_SEARCH_POINT_SETTLE_TICKS) return;

            plotSearchSettleTicks = 0;
            plotSearchIndex++;
            plotSearchTrackerCooldown = 0;
            if (plotSearchIndex >= plotSearchPoints.size()) {
                finishPlotSearch(client);
                return;
            }
            requestFlyPathTo(client, plotSearchPoints.get(plotSearchIndex));
            return;
        }

        plotSearchSettleTicks = 0;

        if (flyProcessor.didFail()) {
            handlePathFailure(client, target, "plot sweep path failed");
            return;
        }

        if (flyProcessor.isDone() && !pathing) {
            requestFlyPathTo(client, target);
        }

        if (tickAntiStuck(client, target, "plot sweep")) {
            return;
        }

        if (!flyProcessor.isDone()) {
            flyProcessor.tick(client, (float) rotSpeed.getValue());
        }
    }

    // ═══════════════════════════════════════════════════ ACTIVATE TRACKER

    private void tickActivateTracker(MinecraftClient client) {
        trackerTicks++;
        if (trackerTicks == 1) {
            int slot = findVacuumSlot(client);
            if (slot == -1) {
                ChatUtils.sendErrorMessage("No vacuum found in hotbar!");
                trackerAttempted = true;
                finishNoPests(client);
                return;
            }
            vacuumSlot = slot;
            InventoryUtils.equipHotbarSlot(client, slot);
            debugMsg("Equipped vacuum for Pest Tracker");
        }

        if (trackerTicks == 3) {
            InputUtils.setAttack(true);
        } else if (trackerTicks == 4) {
            InputUtils.setAttack(false);
            debugMsg("Activated Pest Tracker");
        }

        if (trackerTicks < TRACKER_WAIT_TICKS) return;

        trackerAttempted = true;
        InputUtils.setAttack(false);

        if (!detectedPests.isEmpty()) {
            PestInfo closest = findClosestPest(client.player);
            if (closest != null) {
                trackerFromPlotSearch = false;
                targetPest(closest);
                state = State.EQUIP_VACUUM;
                equipDelay = 0;
                debugMsg("Found visible pest after tracker: " + closest.name);
                return;
            }
        }

        Vec3d waypoint = calculateTrackerWaypoint(client);
        if (waypoint != null) {
            trackerWaypoint = waypoint;
            trackerFollowTicks = 0;
            flyProcessor.stop();
            requestFlyPathTo(client, trackerWaypoint);
            state = State.FOLLOW_TRACKER_TRAIL;
            debugMsg("Following Pest Tracker trail to " + formatPos(trackerWaypoint));
            return;
        }

        if (trackerFromPlotSearch && resumePlotSearchAfterTracker(client)) {
            return;
        }
        trackerFromPlotSearch = false;

        if (warpToKnownPestPlot(client)) {
            return;
        }

        finishNoPests(client);
    }

    // ═══════════════════════════════════════════════════ FOLLOW TRACKER TRAIL

    private void tickFollowTrackerTrail(MinecraftClient client) {
        if (!detectedPests.isEmpty()) {
            PestInfo closest = findClosestPest(client.player);
            if (closest != null) {
                trackerFromPlotSearch = false;
                flyProcessor.stop();
                targetPest(closest);
                state = State.EQUIP_VACUUM;
                equipDelay = 0;
                debugMsg("Found pest from tracker trail: " + closest.name);
                return;
            }
        }

        if (trackerWaypoint == null) {
            finishTrackerTrail(client);
            return;
        }

        trackerFollowTicks++;
        double dist = playerPos(client).distanceTo(trackerWaypoint);
        if (dist <= TRACKER_REACHED_DISTANCE || trackerFollowTicks > TRACKER_FOLLOW_TIMEOUT_TICKS) {
            flyProcessor.stop();
            finishTrackerTrail(client);
            return;
        }

        if (flyProcessor.didFail()) {
            handlePathFailure(client, trackerWaypoint, "tracker trail path failed");
            return;
        }

        if (flyProcessor.isDone() && !pathing) {
            requestFlyPathTo(client, trackerWaypoint);
        }

        if (tickAntiStuck(client, trackerWaypoint, "tracker trail")) {
            return;
        }

        if (!flyProcessor.isDone()) {
            flyProcessor.tick(client, (float) rotSpeed.getValue());
        }
    }

    // ═══════════════════════════════════════════════════ EQUIP VACUUM

    private void tickEquipVacuum(MinecraftClient client) {
        equipDelay++;
        if (equipDelay < 3) return;

        int slot = findVacuumSlot(client);
        if (slot == -1) {
            ChatUtils.sendErrorMessage("No vacuum found in hotbar!");
            state = State.FINISHED;
            return;
        }
        vacuumSlot = slot;
        InventoryUtils.equipHotbarSlot(client, slot);

        String vacName = getVacuumName(client, slot);
        vacuumRange = getVacuumRange(vacName);
        debugMsg("Equipped vacuum: " + vacName + " (range " + vacuumRange + ")");

        state = State.FLY_TO_PEST;
        resetAntiStuck();
        requestFlyPath(client);
    }

    // ═══════════════════════════════════════════════════ FLY TO PEST

    private void tickFlyToPest(MinecraftClient client) {
        if (currentPestTag == null || currentPestTag.isRemoved()) {
            onPestKilled();
            return;
        }

        currentPestPos = getPestPosition(currentPestTag);
        double dist = playerPos(client).distanceTo(currentPestPos);

        if (dist <= vacuumRange - 1.0 && isFlying(client)) {
            flyProcessor.stop();
            state = State.APPROACH_PEST;
            killTicks = 0;
            initRotation(client);
            debugMsg("In vacuum range, approaching...");
            return;
        }

        if (flyProcessor.didFail()) {
            Vec3d target = currentPestPos == null ? null : getPestHoverTarget(currentPestPos);
            handlePathFailure(client, target, "pest path failed");
            return;
        }

        if (flyProcessor.isDone() && !pathing) {
            requestFlyPath(client);
        }

        Vec3d target = currentPestPos == null ? null : getPestHoverTarget(currentPestPos);
        if (tickAntiStuck(client, target, "pest flight")) {
            return;
        }

        if (!flyProcessor.isDone()) {
            flyProcessor.tick(client, (float) rotSpeed.getValue());
        }
    }

    // ═══════════════════════════════════════════════════ APPROACH PEST

    private void tickApproachPest(MinecraftClient client) {
        if (currentPestTag == null || currentPestTag.isRemoved()) {
            onPestKilled();
            return;
        }

        currentPestPos = getPestPosition(currentPestTag);
        double dist = playerPos(client).distanceTo(currentPestPos);
        double horizontalDist = horizontalDistanceTo(currentPestPos, client);

        if (dist > vacuumRange + 2.0) {
            state = State.FLY_TO_PEST;
            releaseRotation(client);
            resetAntiStuck();
            requestFlyPath(client);
            return;
        }

        initRotation(client);
        Vec3d targetCenter = currentPestPos.add(0, 1.0, 0);
        float[] look = RotationUtils.lookAt(
                client.player.getX(), client.player.getEyeY(), client.player.getZ(),
                targetCenter.x, targetCenter.y, targetCenter.z);
        rotation.setTarget(look[0], look[1]);
        rotation.setSpeed((float) rotSpeed.getValue());
        rotation.tick();

        // Fly toward pest if still outside comfortable range
        float moveYaw = look[0];
        float currentYaw = rotation.getCurrentYaw();

        if (horizontalDist > Math.max(1.5, vacuumRange * 0.35)) {
            float angle = Math.abs(MathHelper.wrapDegrees(moveYaw - currentYaw));
            if (angle < 45.0f) {
                InputUtils.setForward(true);
                InputUtils.setSprint(false);
            } else {
                InputUtils.setForward(false);
            }
        } else {
            InputUtils.setForward(false);
        }
        InputUtils.setBack(false);

        maintainHoverAbovePest(client);

        if (dist <= vacuumRange - 0.5 && rotation.isRoughlyFacing()) {
            state = State.KILL_PEST;
            killTicks = 0;
            debugMsg("Vacuuming pest...");
        }
    }

    // ═══════════════════════════════════════════════════ KILL PEST

    private void tickKillPest(MinecraftClient client) {
        if (currentPestTag == null || currentPestTag.isRemoved()) {
            onPestKilled();
            return;
        }

        currentPestPos = getPestPosition(currentPestTag);
        double dist = playerPos(client).distanceTo(currentPestPos);
        double horizontalDist = horizontalDistanceTo(currentPestPos, client);

        if (dist > vacuumRange + 1.0) {
            InputUtils.setUse(false);
            state = State.APPROACH_PEST;
            return;
        }

        // Keep aiming at pest
        Vec3d targetCenter = currentPestPos.add(0, 1.0, 0);
        float[] look = RotationUtils.lookAt(
                client.player.getX(), client.player.getEyeY(), client.player.getZ(),
                targetCenter.x, targetCenter.y, targetCenter.z);
        rotation.setTarget(look[0], look[1]);
        rotation.setSpeed((float) rotSpeed.getValue());
        rotation.tick();

        // Hold right-click to vacuum
        InputUtils.setUse(true);

        maintainHoverAbovePest(client);

        boolean blockedLine = !hasLineOfSightTo(client, targetCenter);
        if (blockedLine && dist <= vacuumRange) {
            vacuumBlockedTicks++;
        } else {
            vacuumBlockedTicks = 0;
        }

        if (vacuumBlockedTicks > VACUUM_BLOCKED_RECOVERY_TICKS
            || killTicks > VACUUM_STALL_RECOVERY_TICKS + vacuumRecoveryAttempts * 45) {
            recoverFromVacuumStall(client, blockedLine ? "line blocked" : "vacuum stalled");
            return;
        }

        // Micro-movement to stay in range
        if (horizontalDist > Math.max(1.5, vacuumRange * 0.45)) {
            InputUtils.setForward(true);
        } else {
            InputUtils.setForward(false);
        }
        InputUtils.setBack(false);

        killTicks++;
        if (killTicks > 200) {
            recoverFromVacuumStall(client, "kill timeout");
        }
    }

    // ═══════════════════════════════════════════════════ CHECK MORE

    private void tickCheckMore(MinecraftClient client) {
        scanDelay++;
        if (scanDelay < 10) return;
        scanDelay = 0;

        if (shouldActivateTrackerBeforeSearch()) {
            startTrackerActivation();
            return;
        }

        if (!detectedPests.isEmpty()) {
            PestInfo next = findClosestPest(client.player);
            if (next != null) {
                targetPest(next);
                state = State.EQUIP_VACUUM;
                equipDelay = 0;
                debugMsg("Next pest: " + next.name);
                return;
            }
        }

        if (beginPestSearchFallback(client)) return;

        if (autoReturn.getValue() && startPosition != null) {
            state = State.GO_BACK;
            debugMsg("No more pests, returning...");
        } else {
            state = State.FINISHED;
        }
    }

    // ═══════════════════════════════════════════════════ GO BACK

    private void tickGoBack(MinecraftClient client) {
        if (startPosition == null) {
            state = State.FINISHED;
            return;
        }

        double dist = playerPos(client).distanceTo(startPosition);
        if (dist < 3.0) {
            flyProcessor.stop();
            state = State.FINISHED;
            debugMsg("Returned to start");
            return;
        }

        if (flyProcessor.isDone() && !pathing) {
            BlockPos start = client.player.getBlockPos();
            BlockPos end = BlockPos.ofFloored(startPosition);
            pathing = true;
            FlyPathProcessor.computePathAsync(start, end, 30000)
                .thenAccept(path -> client.execute(() -> {
                    pathing = false;
                    if (path != null) flyProcessor.setPath(path);
                }));
        }

        if (!flyProcessor.isDone()) {
            flyProcessor.tick(client, (float) rotSpeed.getValue());
        }
    }

    // ═══════════════════════════════════════════════════ Pest detection

    private void scanForPests(MinecraftClient client) {
        detectedPests.clear();
        detectedPests.addAll(scanVisiblePests(client, scanRadius.getValue(), killedPestIds));

        if (renderESP.getValue()) {
            renderPests = detectedPests.isEmpty() ? null : new ArrayList<>(detectedPests);
        } else {
            renderPests = null;
        }
    }

    public static List<PestInfo> scanVisiblePests(MinecraftClient client, double radius, Set<Integer> ignoredEntityIds) {
        List<PestInfo> pests = new ArrayList<>();
        if (client == null || client.player == null || client.world == null) return pests;

        double radiusSq = radius * radius;
        Set<Integer> ignored = ignoredEntityIds == null ? Collections.emptySet() : ignoredEntityIds;

        for (Entity entity : EntitySearchUtils.entities(client, entity ->
                entity instanceof ArmorStandEntity
                        && entity.getCustomName() != null
                        && !ignored.contains(entity.getId())
        )) {
            if (!(entity instanceof ArmorStandEntity armorStand)) continue;

            String pestName = matchPestName(getEntityName(armorStand));
            if (pestName == null) continue;

            double distSq = armorStand.squaredDistanceTo(client.player);
            if (distSq > radiusSq) continue;
            if (armorStand.getY() < 50) continue;

            Vec3d pestPos = new Vec3d(armorStand.getX(), armorStand.getY() - 2.0, armorStand.getZ());
            Entity target = findPestBodyNear(client, armorStand, pestName, ignored);
            if (target != null) {
                pestPos = new Vec3d(target.getX(), target.getY(), target.getZ());
                pests.add(new PestInfo(target, pestName, pestPos));
            } else {
                pests.add(new PestInfo(armorStand, pestName, pestPos));
            }
        }

        for (Entity entity : EntitySearchUtils.entities(client, entity ->
                !(entity instanceof ArmorStandEntity)
                        && entity != client.player
                        && !entity.isRemoved()
                        && !ignored.contains(entity.getId())
        )) {
            String pestName = matchPestName(getEntityName(entity));
            if (pestName == null) continue;

            double distSq = entity.squaredDistanceTo(client.player);
            if (distSq > radiusSq) continue;
            if (entity.getY() < 50) continue;

            Vec3d pestPos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
            if (isDuplicatePest(pests, pestName, pestPos)) continue;
            pests.add(new PestInfo(entity, pestName, pestPos));
        }

        return pests;
    }

    private static String matchPestName(String stripped) {
        if (stripped == null || stripped.isEmpty()) return null;
        for (String name : PEST_NAMES) {
            if (stripped.contains(name)) return name;
        }
        return null;
    }

    private static String getEntityName(Entity entity) {
        return EntitySearchUtils.displayName(entity);
    }

    private static boolean isDuplicatePest(List<PestInfo> pests, String pestName, Vec3d pestPos) {
        for (PestInfo pest : pests) {
            if (!pest.name.equals(pestName)) continue;
            if (pest.pestPos.squaredDistanceTo(pestPos) <= 9.0) return true;
        }
        return false;
    }

    private static Entity findPestBodyNear(MinecraftClient client, Entity nameTag, String pestName, Set<Integer> ignoredEntityIds) {
        return EntitySearchUtils.closestLivingNear(client, nameTag, nameTag.getBoundingBox().expand(1.0, 2.5, 1.0), nearby -> {
            if (ignoredEntityIds.contains(nearby.getId())) return false;
            String nearbyName = getEntityName(nearby);
            String nearbyPest = matchPestName(nearbyName);
            return nearbyPest == null || nearbyPest.equals(pestName);
        }).orElse(null);
    }

    private PestInfo findClosestPest(ClientPlayerEntity player) {
        PestInfo closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (PestInfo pest : detectedPests) {
            if (pest.nameTag.isRemoved()) continue;
            if (currentWarpPlot != null && currentWarpPlot.numericId >= 0
                && !PlotUtils.isInsidePlot(currentWarpPlot.numericId, pest.pestPos)) {
                continue;
            }
            double dSq = player.squaredDistanceTo(pest.pestPos.x, pest.pestPos.y, pest.pestPos.z);
            if (dSq < closestDistSq) {
                closestDistSq = dSq;
                closest = pest;
            }
        }
        if (closest != null) return closest;

        for (PestInfo pest : detectedPests) {
            if (pest.nameTag.isRemoved()) continue;
            double dSq = player.squaredDistanceTo(pest.pestPos.x, pest.pestPos.y, pest.pestPos.z);
            if (dSq < closestDistSq) {
                closestDistSq = dSq;
                closest = pest;
            }
        }
        return closest;
    }

    // ═══════════════════════════════════════════════════ Vacuum helpers

    private int findVacuumSlot(MinecraftClient client) {
        return ToolSelector.findBest(client, ToolSelector.Category.VACUUM);
    }

    private String getVacuumName(MinecraftClient client, int slot) {
        var stack = client.player.getInventory().getStack(slot);
        return ItemNameUtils.getStrippedName(stack);
    }

    private double getVacuumRange(String vacName) {
        for (Map.Entry<String, Double> entry : VACUUM_RANGES.entrySet()) {
            if (vacName.contains(entry.getKey())) return entry.getValue();
        }
        return 5.0;
    }

    // ═══════════════════════════════════════════════════ Pathfinding

    private boolean tickAntiStuck(MinecraftClient client, Vec3d target, String reason) {
        if (client.player == null) return false;

        if (antiStuckNudgeTicks > 0) {
            antiStuckNudgeTicks--;
            float escapeYaw = antiStuckEscapeYaw != null ? antiStuckEscapeYaw : client.player.getYaw() + 180.0f;
            AntiStuckController.applyMovement(client, escapeYaw);
            InputUtils.setSprint(false);
            InputUtils.setJump(true);
            InputUtils.setSneak(false);

            if (antiStuckNudgeTicks == 0) {
                InputUtils.releaseAll();
                antiStuckTicks = 0;
                antiStuckLastPos = null;
                requestFlyPathTo(client, target);
            }
            return true;
        }

        if (target == null || pathing || flyProcessor.isDone()) {
            antiStuckTicks = 0;
            antiStuckLastPos = null;
            return false;
        }

        if (antiStuckTarget == null || antiStuckTarget.squaredDistanceTo(target) > ANTI_STUCK_TARGET_CHANGE_SQ) {
            antiStuckTarget = target;
            antiStuckAttempts = 0;
            antiStuckTicks = 0;
            antiStuckLastPos = null;
        }

        Vec3d pos = playerPos(client);
        if (antiStuckLastPos != null) {
            double moved = antiStuckLastPos.squaredDistanceTo(pos);
            if (moved < ANTI_STUCK_MIN_MOVE_SQ) {
                antiStuckTicks++;
            } else {
                antiStuckTicks = 0;
                antiStuckAttempts = 0;
            }
        }
        antiStuckLastPos = pos;

        if (antiStuckTicks < ANTI_STUCK_TICKS) {
            return false;
        }

        flyProcessor.stop();
        InputUtils.releaseAll();
        antiStuckTicks = 0;
        antiStuckLastPos = null;

        if (antiStuckAttempts < ANTI_STUCK_MAX_NUDGES) {
            antiStuckAttempts++;
            antiStuckEscapeYaw = AntiStuckController.chooseEscapeYaw(client, target, ANTI_STUCK_ESCAPE_CHECK_DISTANCE);
            antiStuckNudgeTicks = ANTI_STUCK_NUDGE_TICKS;
            debugMsg("Anti-stuck nudge during " + reason);
            return true;
        }

        antiStuckAttempts = 0;
        debugMsg("Anti-stuck repath during " + reason);
        requestFlyPathTo(client, target);
        return true;
    }

    private void resetAntiStuck() {
        antiStuckTicks = 0;
        antiStuckNudgeTicks = 0;
        antiStuckAttempts = 0;
        antiStuckLastPos = null;
        antiStuckTarget = null;
        antiStuckEscapeYaw = null;
    }

    private static float yawTo(Vec3d from, Vec3d to) {
        return WorldUtils.yawTo(from, to);
    }

    private static boolean isPassable(MinecraftClient client, BlockPos pos) {
        return WorldUtils.isPassable(client, pos);
    }

    private void requestFlyPath(MinecraftClient client) {
        if (pathing || currentPestPos == null) return;
        requestFlyPathTo(client, getPestHoverTarget(currentPestPos));
    }

    private void requestFlyPathTo(MinecraftClient client, Vec3d target) {
        if (pathing || target == null) return;
        pathing = true;

        BlockPos start = client.player.getBlockPos();
        BlockPos end = BlockPos.ofFloored(target);

        FlyPathProcessor.computePathAsync(start, end, 30000)
            .thenAccept(path -> client.execute(() -> {
                pathing = false;
                if (path == null || path.isEmpty()) {
                    handlePathFailure(client, target, "no fly path");
                    return;
                }
                if (!isReasonablePath(client, target, path)) {
                    handlePathFailure(client, target, "unreasonable fly path");
                    return;
                }
                pathFailures = 0;
                lastPathFailureTarget = null;
                if (path != null && !path.isEmpty()) {
                    flyProcessor.setPath(path);
                }
            }));
    }

    private boolean isReasonablePath(MinecraftClient client, Vec3d target, List<Vec3d> path) {
        if (client.player == null || target == null || path == null || path.isEmpty()) return false;
        if (path.size() > MAX_REASONABLE_PATH_POINTS) return false;

        Vec3d player = playerPos(client);
        Vec3d end = path.get(path.size() - 1);
        if (end.distanceTo(target) > 8.0) return false;

        double straight = Math.max(1.0, player.distanceTo(target));
        double total = player.distanceTo(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            Vec3d prev = path.get(i - 1);
            Vec3d next = path.get(i);
            if (next.y < 45.0 || next.y > 260.0) return false;
            total += prev.distanceTo(next);
        }

        return total <= straight * MAX_PATH_DETOUR_FACTOR + 80.0;
    }

    private void handlePathFailure(MinecraftClient client, Vec3d target, String reason) {
        flyProcessor.stop();
        InputUtils.releaseAll();

        if (target == null) {
            pathFailures = MAX_PATH_FAILURES;
        } else if (lastPathFailureTarget == null || lastPathFailureTarget.squaredDistanceTo(target) > 64.0) {
            lastPathFailureTarget = target;
            pathFailures = 1;
        } else {
            pathFailures++;
        }

        debugMsg("Path failure (" + pathFailures + "/" + MAX_PATH_FAILURES + "): " + reason);
        if (pathFailures < MAX_PATH_FAILURES) return;

        pathFailures = 0;
        lastPathFailureTarget = null;

        if (state == State.FOLLOW_TRACKER_TRAIL) {
            debugMsg("Tracker trail path failed");
            finishTrackerTrail(client);
            return;
        }

        if (state == State.SEARCH_PLOT) {
            plotSearchIndex++;
            plotSearchSettleTicks = 0;
            if (plotSearchIndex < plotSearchPoints.size()) {
                requestFlyPathTo(client, plotSearchPoints.get(plotSearchIndex));
            } else {
                debugMsg("Plot sweep path failed");
                finishPlotSearch(client);
            }
            return;
        }

        if (state == State.FLY_TO_PEST) {
            debugMsg("Could not path to pest, trying tracker fallback");
            currentPestTag = null;
            currentPestPos = null;
            renderTarget = null;
            if (beginPestSearchFallback(client)) return;
            state = State.CHECK_MORE;
            scanDelay = 0;
        }
    }

    private List<Integer> getPestPlotsFromHud() {
        LinkedHashSet<Integer> plots = new LinkedHashSet<>();
        addPestPlotsFromLines(plots, TabUtils.getTabLines());
        addPestPlotsFromLines(plots, ScoreboardUtils.getScoreboardLines());
        return new ArrayList<>(plots);
    }

    private void addPestPlotsFromLines(Set<Integer> plots, List<String> lines) {
        for (String line : lines) {
            if (line == null) continue;
            String lower = cleanLine(line).toLowerCase(Locale.ROOT);
            if (!lower.contains("plot")) continue;
            if (!lower.contains("pest") && !lower.contains("plots")) continue;

            var matcher = PLOT_NUMBER_PATTERN.matcher(cleanLine(line));
            while (matcher.find()) {
                for (String token : matcher.group(1).split("\\D+")) {
                    if (token.isEmpty()) continue;
                    try {
                        int plot = Integer.parseInt(token);
                        if (plot >= 1 && plot <= 24) plots.add(plot);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
    }

    private void syncKnownPestsFromHud() {
        int gardenPests = getGardenPestsCountFromHud();
        if (gardenPests >= 0) {
            scoreboardPests = gardenPests;
            if (gardenPests == 0) {
                if (getPestPlotsFromHud().isEmpty()) {
                    knownPestPlots.clear();
                    attemptedPestPlotWarps.clear();
                    return;
                }
                debugMsg("Garden pest count parsed as 0, but pest plots are still listed; continuing search");
            }
        }

        List<Integer> tabPlots = getPestPlotsFromHud();
        if (!tabPlots.isEmpty()) {
            Set<String> tabKeys = new HashSet<>();
            for (int plotId : tabPlots) {
                PestPlotInfo plot = PestPlotInfo.fromId(plotId, 1, true);
                tabKeys.add(plot.key);
                knownPestPlots.merge(plot.key, plot, (oldPlot, newPlot) -> {
                    oldPlot.inaccurate = oldPlot.inaccurate || oldPlot.pests <= 0;
                    if (oldPlot.pests <= 0) oldPlot.pests = 1;
                    return oldPlot;
                });
            }

            knownPestPlots.entrySet().removeIf(entry ->
                entry.getValue().numericId > 0 && !tabKeys.contains(entry.getKey()));
        }

        syncCurrentPlotPests(ScoreboardUtils.getScoreboardLines());
    }

    private void syncCurrentPlotPests(List<String> lines) {
        for (String rawLine : lines) {
            if (rawLine == null) continue;
            String line = cleanLine(rawLine);

            var pestsMatcher = CURRENT_PLOT_PESTS_PATTERN.matcher(line);
            if (pestsMatcher.find()) {
                String plotName = normalizePlotName(pestsMatcher.group(1));
                int pests = parseIntSafe(pestsMatcher.group(2), -1);
                if (pests >= 0) {
                    PestPlotInfo plot = PestPlotInfo.fromName(plotName, pests, false);
                    if (plot.numericId >= 0) {
                        plot = PestPlotInfo.fromId(plot.numericId, pests, false);
                    }
                    if (pests > 0) {
                        knownPestPlots.put(plot.key, plot);
                    } else {
                        knownPestPlots.remove(plot.key);
                    }
                }
                continue;
            }

            var noPestsMatcher = CURRENT_PLOT_NO_PESTS_PATTERN.matcher(line);
            if (noPestsMatcher.find() && !line.toLowerCase(Locale.ROOT).contains("x")) {
                String plotName = normalizePlotName(noPestsMatcher.group(1));
                PestPlotInfo plot = PestPlotInfo.fromName(plotName, 0, false);
                if (plot.numericId >= 0) {
                    plot = PestPlotInfo.fromId(plot.numericId, 0, false);
                }
                knownPestPlots.remove(plot.key);
            }
        }
    }

    private void markPestSpawn(String plotName, int amount, boolean inaccurate) {
        PestPlotInfo incoming = PestPlotInfo.fromName(plotName, Math.max(1, amount), inaccurate);
        if (incoming.numericId >= 0) {
            incoming = PestPlotInfo.fromId(incoming.numericId, Math.max(1, amount), inaccurate);
        }

        PestPlotInfo finalIncoming = incoming;
        knownPestPlots.merge(finalIncoming.key, finalIncoming, (oldPlot, newPlot) -> {
            oldPlot.pests = inaccurate ? Math.max(1, oldPlot.pests) : Math.max(0, oldPlot.pests) + amount;
            oldPlot.inaccurate = oldPlot.inaccurate || inaccurate;
            return oldPlot;
        });
        if (!inaccurate && scoreboardPests >= 0) {
            scoreboardPests += amount;
        }
        debugMsg("Known pest plot: " + formatPlot(incoming));
    }

    private void markPestKilledFromKnownPlots() {
        PestPlotInfo plot = null;
        if (currentPestPos != null) {
            Integer plotId = getPlotIdAt(currentPestPos);
            if (plotId != null) {
                plot = knownPestPlots.get(PestPlotInfo.keyForId(plotId));
            }
        }
        if (plot == null && currentWarpPlot != null) {
            plot = knownPestPlots.get(currentWarpPlot.key);
        }
        if (plot == null) {
            plot = getNearestKnownPestPlot(MinecraftClient.getInstance());
        }
        if (plot == null) return;

        if (!plot.inaccurate) {
            plot.pests--;
        }
        if (plot.pests <= 0 || plot.inaccurate) {
            knownPestPlots.remove(plot.key);
        }
        if (scoreboardPests > 0) {
            scoreboardPests--;
        }
    }

    private boolean warpToKnownPestPlot(MinecraftClient client) {
        if (!plotTeleport.getValue() || client.player == null || client.player.networkHandler == null) return false;

        PestPlotInfo plot = chooseNextPestPlot(client);
        if (plot == null) return false;

        attemptedPestPlotWarps.add(plot.key);
        currentWarpPlot = plot;
        plotWarpTicks = 0;
        plotSearchPoints.clear();
        plotSearchTicks = 0;
        plotSearchSettleTicks = 0;
        plotSearchIndex = 0;
        plotSearchTrackerCooldown = 0;
        trackerAttempted = false;
        trackerFromPlotSearch = false;
        resetAntiStuck();
        flyProcessor.stop();
        InputUtils.releaseAll();
        markAllowedTeleportCommand();
        CommandUtils.plotTeleport(client, plot.commandName);
        state = State.WARP_TO_PLOT;
        debugMsg("Warping to pest plot " + formatPlot(plot));
        return true;
    }

    private boolean startPlotSearch(MinecraftClient client) {
        if (currentWarpPlot == null || currentWarpPlot.center == null || client.player == null) return false;

        Vec3d center = currentWarpPlot.center;
        double y = client.player.getY() + getEffectiveHoverOffset();
        double o = PLOT_SEARCH_INNER_OFFSET;

        plotSearchPoints.clear();
        addPlotSearchPoint(center, 0.0, 0.0, y);
        addPlotSearchPoint(center, o, 0.0, y);
        addPlotSearchPoint(center, -o, 0.0, y);
        addPlotSearchPoint(center, 0.0, o, y);
        addPlotSearchPoint(center, 0.0, -o, y);
        addPlotSearchPoint(center, o, o, y);
        addPlotSearchPoint(center, -o, o, y);
        addPlotSearchPoint(center, o, -o, y);
        addPlotSearchPoint(center, -o, -o, y);

        plotSearchIndex = 0;
        plotSearchTicks = 0;
        plotSearchSettleTicks = 0;
        plotSearchTrackerCooldown = 0;
        pathFailures = 0;
        lastPathFailureTarget = null;
        resetAntiStuck();
        vacuumBlockedTicks = 0;
        vacuumRecoveryAttempts = 0;
        flyProcessor.stop();
        InputUtils.releaseAll();
        state = State.SEARCH_PLOT;
        requestFlyPathTo(client, plotSearchPoints.get(0));
        return true;
    }

    private void addPlotSearchPoint(Vec3d center, double xOffset, double zOffset, double y) {
        plotSearchPoints.add(new Vec3d(center.x + xOffset, y, center.z + zOffset));
    }

    private void finishPlotSearch(MinecraftClient client) {
        flyProcessor.stop();
        InputUtils.releaseAll();
        plotSearchPoints.clear();
        plotSearchIndex = 0;
        plotSearchTicks = 0;
        plotSearchSettleTicks = 0;
        plotSearchTrackerCooldown = 0;
        resetAntiStuck();

        if (currentWarpPlot != null) {
            knownPestPlots.remove(currentWarpPlot.key);
            debugMsg("No pest found in " + formatPlot(currentWarpPlot) + ", removing it from known plots");
        }
        currentWarpPlot = null;
        if (warpToKnownPestPlot(client)) {
            return;
        }
        finishNoPests(client);
    }

    private void markAllowedTeleportCommand() {
        TeleportFailsafe teleportFailsafe = FailsafeManager.getInstance().getFailsafe(TeleportFailsafe.class);
        if (teleportFailsafe != null) {
            teleportFailsafe.markCommand(5000);
        }

        RotationFailsafe rotationFailsafe = FailsafeManager.getInstance().getFailsafe(RotationFailsafe.class);
        if (rotationFailsafe != null) {
            rotationFailsafe.suppressFor(5000);
        }
    }

    private PestPlotInfo chooseNextPestPlot(MinecraftClient client) {
        PestPlotInfo best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (PestPlotInfo plot : knownPestPlots.values()) {
            if (attemptedPestPlotWarps.contains(plot.key)) continue;
            if (plot.pests <= 0 && !plot.inaccurate) continue;

            double distSq = plot.center == null || client.player == null
                ? Double.MAX_VALUE - knownPestPlots.size()
                : client.player.squaredDistanceTo(plot.center.x, client.player.getY(), plot.center.z);
            if (best == null || distSq < bestDistSq) {
                best = plot;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    private PestPlotInfo getNearestKnownPestPlot(MinecraftClient client) {
        if (client == null || client.player == null) return knownPestPlots.values().stream().findFirst().orElse(null);

        PestPlotInfo best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (PestPlotInfo plot : knownPestPlots.values()) {
            double distSq = plot.center == null
                ? Double.MAX_VALUE - knownPestPlots.size()
                : client.player.squaredDistanceTo(plot.center.x, client.player.getY(), plot.center.z);
            if (best == null || distSq < bestDistSq) {
                best = plot;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    private Integer getPlotIdAt(Vec3d pos) {
        return PlotUtils.getPlotIdAt(pos);
    }

    private void startTrackerActivation() {
        startTrackerActivation(false);
    }

    private void startTrackerActivation(boolean fromPlotSearch) {
        InputUtils.releaseAll();
        flyProcessor.stop();
        trackerTicks = 0;
        trackerParticles.clear();
        trackerWaypoint = null;
        pathFailures = 0;
        lastPathFailureTarget = null;
        resetAntiStuck();
        trackerFromPlotSearch = fromPlotSearch;
        state = State.ACTIVATE_TRACKER;
        debugMsg(fromPlotSearch ? "Activating Pest Tracker during plot sweep..." : "Activating Pest Tracker...");
    }

    private Vec3d calculateTrackerWaypoint(MinecraftClient client) {
        if (trackerParticles.size() < 2) return null;

        Vec3d last = trackerParticles.get(trackerParticles.size() - 1);
        Vec3d direction = Vec3d.ZERO;
        for (int i = 1; i < trackerParticles.size(); i++) {
            Vec3d prev = trackerParticles.get(i - 1);
            Vec3d next = trackerParticles.get(i);
            Vec3d delta = new Vec3d(next.x - prev.x, 0.0, next.z - prev.z);
            if (delta.lengthSquared() >= TRACKER_MIN_PARTICLE_STEP_SQ) {
                direction = direction.add(delta.normalize());
            }
        }

        if (direction.lengthSquared() < 1.0) {
            double bestDistSq = 0.0;
            Vec3d bestDirection = Vec3d.ZERO;
            for (int i = 0; i < trackerParticles.size() - 1; i++) {
                Vec3d from = trackerParticles.get(i);
                for (int j = i + 1; j < trackerParticles.size(); j++) {
                    Vec3d to = trackerParticles.get(j);
                    Vec3d delta = new Vec3d(to.x - from.x, 0.0, to.z - from.z);
                    double distSq = delta.lengthSquared();
                    if (distSq > bestDistSq) {
                        bestDistSq = distSq;
                        bestDirection = delta;
                    }
                }
            }
            direction = bestDirection;
        }

        if (direction.lengthSquared() < 1.0) return null;

        direction = direction.normalize();
        Vec3d waypoint = new Vec3d(
            last.x + direction.x * TRACKER_WAYPOINT_DISTANCE,
            client.player.getY() + flyHeight.getValue(),
            last.z + direction.z * TRACKER_WAYPOINT_DISTANCE
        );

        if (playerPos(client).squaredDistanceTo(waypoint) < 9.0) {
            waypoint = waypoint.add(direction.x * TRACKER_WAYPOINT_DISTANCE, 0.0, direction.z * TRACKER_WAYPOINT_DISTANCE);
        }
        return waypoint;
    }

    private void finishTrackerTrail(MinecraftClient client) {
        trackerWaypoint = null;
        trackerParticles.clear();
        if (trackerFromPlotSearch && resumePlotSearchAfterTracker(client)) {
            return;
        }
        trackerFromPlotSearch = false;
        if (warpToKnownPestPlot(client)) {
            return;
        }
        finishNoPests(client);
    }

    private boolean resumePlotSearchAfterTracker(MinecraftClient client) {
        if (currentWarpPlot == null || plotSearchPoints.isEmpty()) {
            trackerFromPlotSearch = false;
            return false;
        }

        trackerFromPlotSearch = false;
        trackerWaypoint = null;
        trackerParticles.clear();
        flyProcessor.stop();
        InputUtils.releaseAll();
        state = State.SEARCH_PLOT;
        resetAntiStuck();

        if (plotSearchIndex >= plotSearchPoints.size()) {
            finishPlotSearch(client);
            return true;
        }

        requestFlyPathTo(client, plotSearchPoints.get(plotSearchIndex));
        debugMsg("Pest Tracker found no direct trail, resuming plot sweep");
        return true;
    }

    @EventListener
    private void onParticlePacket(ParticlePacketEvent event) {
        if (!isEnabled()) return;
        if (state != State.ACTIVATE_TRACKER && state != State.FOLLOW_TRACKER_TRAIL) return;
        if (!isTrackerParticle(event)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Vec3d pos = new Vec3d(event.getX(), event.getY(), event.getZ());
        addTrackerParticle(client, pos);

        if (event.getParticle() instanceof TrailParticleEffect trail) {
            addTrackerParticle(client, trail.target());
        } else if (event.getCount() == 0) {
            Vec3d velocityHint = new Vec3d(event.getOffsetX(), event.getOffsetY(), event.getOffsetZ());
            Vec3d horizontalHint = new Vec3d(velocityHint.x, 0.0, velocityHint.z);
            if (horizontalHint.lengthSquared() >= 0.0625) {
                double distance = MathHelper.clamp(horizontalHint.length() * 4.0 + event.getSpeed() * 4.0, 2.0, 8.0);
                addTrackerParticle(client, pos.add(horizontalHint.normalize().multiply(distance)));
            }
        }
    }

    private boolean isTrackerParticle(ParticlePacketEvent event) {
        return event.getParticle().getType() == ParticleTypes.ANGRY_VILLAGER
            || event.getParticle().getType() == ParticleTypes.FIREWORK
            || event.getParticle().getType() == ParticleTypes.TRAIL;
    }

    private void addTrackerParticle(MinecraftClient client, Vec3d pos) {
        if (client.player.squaredDistanceTo(pos.x, pos.y, pos.z)
            > TRACKER_MAX_PARTICLE_DISTANCE * TRACKER_MAX_PARTICLE_DISTANCE) return;

        if (!trackerParticles.isEmpty()) {
            Vec3d last = trackerParticles.get(trackerParticles.size() - 1);
            if (last.squaredDistanceTo(pos) < 0.25) return;
        }
        trackerParticles.add(pos);
        if (trackerParticles.size() > 32) {
            trackerParticles.remove(0);
        }
    }

    @EventListener
    private void onChatMessage(ChatMessageEvent event) {
        if (!isEnabled()) return;

        String message = cleanLine(event.getMessage());
        if (NO_PESTS_CHAT_PATTERN.matcher(message).matches()) {
            finishGardenEmptyFromTracker();
            return;
        }

        if (event.isOverlay()) return;

        var oneMatcher = ONE_PEST_SPAWN_PATTERN.matcher(message);
        if (oneMatcher.matches()) {
            markPestSpawn(oneMatcher.group(1), 1, false);
            return;
        }

        var multiMatcher = MULTI_PEST_SPAWN_PATTERN.matcher(message);
        if (multiMatcher.matches()) {
            markPestSpawn(multiMatcher.group(2), parseIntSafe(multiMatcher.group(1), 1), false);
            return;
        }

        var offlineMatcher = OFFLINE_PEST_SPAWN_PATTERN.matcher(message);
        if (offlineMatcher.matches()) {
            for (String plot : splitPlotList(offlineMatcher.group(1))) {
                markPestSpawn(plot, 1, true);
            }
        }
    }

    // ═══════════════════════════════════════════════════ State helpers

    private boolean beginPestSearchFallback(MinecraftClient client) {
        syncKnownPestsFromHud();
        if (stopIfGardenPestsConfirmedEmpty()) {
            return true;
        }
        if (shouldActivateTrackerBeforeSearch()) {
            startTrackerActivation();
            return true;
        }
        if (warpToKnownPestPlot(client)) {
            return true;
        }
        return false;
    }

    private void finishNoPests(MinecraftClient client) {
        syncKnownPestsFromHud();
        int gardenPests = getGardenPestsCountFromHud();
        boolean hasHudPestPlots = !getPestPlotsFromHud().isEmpty();
        if (gardenPests == 0 && !hasHudPestPlots) {
            finishGardenEmpty("Garden reports 0 pests, stopping PestCleaner search");
            return;
        }
        boolean hasFreshGardenCount = gardenPests >= 0;
        boolean shouldContinue = gardenPests > 0
            || hasHudPestPlots
            || (!hasFreshGardenCount && scoreboardPests > 0)
            || !knownPestPlots.isEmpty();
        if (shouldContinue) {
            debugMsg("Garden still reports pests, continuing search...");
            currentWarpPlot = null;
            trackerAttempted = false;
            attemptedPestPlotWarps.clear();
            trackerParticles.clear();
            trackerWaypoint = null;
            flyProcessor.stop();
            InputUtils.releaseAll();

            if (shouldActivateTrackerBeforeSearch()) {
                startTrackerActivation();
                return;
            }
            if (warpToKnownPestPlot(client)) {
                return;
            }
            state = State.SCANNING;
            scanDelay = 10;
            return;
        }

        if (pestTracker.getValue()) {
            trackerAttempted = false;
            startTrackerActivation();
            debugMsg("No local pests found, but garden empty was not confirmed; rechecking with Pest Tracker");
            return;
        }

        state = State.SCANNING;
        scanDelay = 0;
        debugMsg("No local pests found, but garden empty was not confirmed; continuing scan");
    }

    private boolean stopIfGardenPestsConfirmedEmpty() {
        if (state == State.GO_BACK || state == State.FINISHED || state == State.IDLE) return false;
        if (getGardenPestsCountFromHud() != 0 || !getPestPlotsFromHud().isEmpty()) return false;
        finishGardenEmpty("Garden reports 0 pests, stopping PestCleaner search");
        return true;
    }

    private void finishGardenEmptyFromTracker() {
        finishGardenEmpty("Pest Tracker confirmed garden has no pests");
    }

    private void finishGardenEmpty(String reason) {
        clearKnownPests();
        flyProcessor.stop();
        InputUtils.releaseAll();
        releaseRotation(MinecraftClient.getInstance());
        currentPestTag = null;
        currentPestPos = null;
        renderTarget = null;
        trackerAttempted = true;
        returnOrFinishNoPests();
        debugMsg(reason);
    }

    private void clearKnownPests() {
        knownPestPlots.clear();
        attemptedPestPlotWarps.clear();
        trackerParticles.clear();
        plotSearchPoints.clear();
        trackerWaypoint = null;
        currentWarpPlot = null;
        plotSearchTicks = 0;
        plotSearchSettleTicks = 0;
        plotSearchIndex = 0;
        plotSearchTrackerCooldown = 0;
        trackerFromPlotSearch = false;
        resetAntiStuck();
        scoreboardPests = 0;
    }

    private void returnOrFinishNoPests() {
        if (autoReturn.getValue() && startPosition != null) {
            state = State.GO_BACK;
        } else {
            state = State.FINISHED;
        }
    }

    private void finishCompleted(MinecraftClient client) {
        if (pestsStillReported()) {
            debugMsg("Completion blocked because HUD still reports pests");
            currentPestTag = null;
            currentPestPos = null;
            renderTarget = null;
            trackerAttempted = false;
            state = State.CHECK_MORE;
            scanDelay = 10;
            return;
        }

        if (autoRewarp.getValue() && !autoRewarpSent
            && client.player != null && client.player.networkHandler != null) {
            markAllowedTeleportCommand();
            CommandUtils.warpGarden(client);
            autoRewarpSent = true;
            debugMsg("PestCleaner complete, rewarping...");
        }
        setEnabled(false);
    }

    private boolean pestsStillReported() {
        syncKnownPestsFromHud();
        int gardenPests = getGardenPestsCountFromHud();
        return gardenPests > 0
            || !getPestPlotsFromHud().isEmpty()
            || scoreboardPests > 0
            || !knownPestPlots.isEmpty()
            || !detectedPests.isEmpty();
    }

    private void targetPest(PestInfo pest) {
        currentPestTag = pest.nameTag;
        currentPestPos = pest.pestPos;
        renderTarget = pest;
        pathFailures = 0;
        lastPathFailureTarget = null;
        resetAntiStuck();
    }

    private void onPestKilled() {
        InputUtils.setUse(false);
        InputUtils.releaseAll();
        releaseRotation(MinecraftClient.getInstance());
        flyProcessor.stop();

        if (currentPestTag != null) {
            markPestKilledFromKnownPlots();
            killedPestIds.add(currentPestTag.getId());
            killsThisSession++;
            ChatUtils.sendSuccessMessage("Pest killed! (" + killsThisSession + " total)");
        }
        currentPestTag = null;
        currentPestPos = null;
        renderTarget = null;
        scanDelay = 0;
        vacuumBlockedTicks = 0;
        vacuumRecoveryAttempts = 0;
        trackerAttempted = false;
        if (shouldActivateTrackerBeforeSearch()) {
            startTrackerActivation();
        } else {
            state = State.CHECK_MORE;
        }
    }

    private boolean shouldActivateTrackerBeforeSearch() {
        return pestTracker.getValue();
    }

    private void initRotation(MinecraftClient client) {
        aim.initIfNeeded(client);
        RotationInterpolator.setActive(rotation);
    }

    private void releaseRotation(MinecraftClient client) {
        aim.applyAndClear(client);
        RotationInterpolator.clearActive();
    }

    private Vec3d getPestPosition(Entity target) {
        if (target instanceof ArmorStandEntity) {
            return new Vec3d(target.getX(), target.getY() - 2.0, target.getZ());
        }
        return new Vec3d(target.getX(), target.getY(), target.getZ());
    }

    private Vec3d getPestHoverTarget(Vec3d pestPos) {
        return pestPos.add(0.0, getEffectiveHoverOffset(), 0.0);
    }

    private double getEffectiveHoverOffset() {
        double configured = Math.max(MIN_HOVER_ABOVE_PEST, flyHeight.getValue());
        double rangeLimited = Math.max(MIN_HOVER_ABOVE_PEST, vacuumRange - 1.0);
        return Math.min(configured, rangeLimited);
    }

    private void maintainHoverAbovePest(MinecraftClient client) {
        if (currentPestPos == null || client.player == null) return;
        double targetY = getPestHoverTarget(currentPestPos).y;
        double yErr = targetY - client.player.getY();
        InputUtils.setJump(yErr > HOVER_Y_TOLERANCE);
        InputUtils.setSneak(yErr < -HOVER_Y_TOLERANCE);
    }

    private void recoverFromVacuumStall(MinecraftClient client, String reason) {
        InputUtils.setUse(false);
        InputUtils.releaseAll();
        vacuumBlockedTicks = 0;
        killTicks = 0;
        vacuumRecoveryAttempts++;
        debugMsg("Vacuum recovery (" + vacuumRecoveryAttempts + "/" + VACUUM_MAX_RECOVERY_ATTEMPTS + "): " + reason);

        if (vacuumRecoveryAttempts <= VACUUM_MAX_RECOVERY_ATTEMPTS && currentPestPos != null) {
            state = State.FLY_TO_PEST;
            resetAntiStuck();
            flyProcessor.stop();
            requestFlyPath(client);
            return;
        }

        vacuumRecoveryAttempts = 0;
        currentPestTag = null;
        currentPestPos = null;
        renderTarget = null;
        if (beginPestSearchFallback(client)) return;
        state = State.CHECK_MORE;
        scanDelay = 0;
    }

    private boolean hasLineOfSightTo(MinecraftClient client, Vec3d target) {
        if (client.player == null || client.world == null || target == null) return true;
        Vec3d eye = client.player.getEyePos();
        HitResult result = client.world.raycast(new RaycastContext(
            eye,
            target,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        return result == null || result.getType() == HitResult.Type.MISS || result.getPos().squaredDistanceTo(target) < 1.0;
    }

    private double horizontalDistanceTo(Vec3d target, MinecraftClient client) {
        return PathMath.horizontalDistance(playerPos(client), target);
    }

    private boolean isFlying(MinecraftClient client) {
        return client.player != null && client.player.getAbilities().flying;
    }

    private void debugMsg(String msg) {
        if (debug.getValue()) {
            ChatUtils.sendDebugMessage("[Pest] " + msg);
        }
    }

    private static Vec3d playerPos(MinecraftClient client) {
        return WorldUtils.playerPos(client);
    }

    private static String formatPos(Vec3d pos) {
        return String.format("%.0f, %.0f, %.0f", pos.x, pos.y, pos.z);
    }

    // ═══════════════════════════════════════════════════ Public API

    public FlyPathProcessor getFlyProcessor() { return flyProcessor; }
    public boolean shouldRenderESP() { return renderESP.getValue(); }
    public int getKillsThisSession() { return killsThisSession; }

    public boolean consumeAutoRewarpSent() {
        boolean sent = autoRewarpSent;
        autoRewarpSent = false;
        return sent;
    }

    public boolean shouldStartFromRewarp() {
        int gardenPests = getGardenPestsCountFromHud();
        if (gardenPests >= 0) {
            return gardenPests >= startAtPests.getValue();
        }
        return !getPestPlotsFromHud().isEmpty();
    }

    private int getGardenPestsCountFromHud() {
        int count = readGardenPestsCount(TabUtils.getTabLines());
        if (count >= 0) return count;
        return readGardenPestsCount(ScoreboardUtils.getScoreboardLines());
    }

    private int readGardenPestsCount(List<String> lines) {
        for (String line : lines) {
            if (line == null) continue;
            String clean = cleanLine(line);
            var matcher = GARDEN_PEST_COUNT_PATTERN.matcher(clean);
            if (matcher.find()) {
                try {
                    String count = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    return Integer.parseInt(count);
                } catch (NumberFormatException ignored) {}
                continue;
            }

            String lower = clean.toLowerCase(Locale.ROOT);
            if (!lower.contains("pest") || lower.contains("plot")) continue;
            matcher = GARDEN_PEST_LABEL_COUNT_PATTERN.matcher(clean);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════ HUD

    @Override
    public String getHudName() {
        return "PestCleaner";
    }

    @Override
    public String getHudState() {
        String info = state.name();
        if (currentPestTag != null && currentPestTag.getCustomName() != null) {
            String name = STRIP_COLOR.matcher(currentPestTag.getCustomName().getString()).replaceAll("").trim();
            info += " | " + name;
        }
        info += " | Kills: " + killsThisSession;
        if (!detectedPests.isEmpty()) {
            info += " | Found: " + detectedPests.size();
        }
        if (!knownPestPlots.isEmpty()) {
            info += " | Plots: " + formatKnownPestPlots();
        }
        return info;
    }

    private static String cleanLine(String line) {
        if (line == null) return "";
        return STRIP_COLOR.matcher(line).replaceAll("").trim();
    }

    private static String normalizePlotName(String plotName) {
        String name = cleanLine(plotName).trim();
        while (name.endsWith("!") || name.endsWith(".") || name.endsWith(",")) {
            name = name.substring(0, name.length() - 1).trim();
        }
        return name;
    }

    private static List<String> splitPlotList(String plots) {
        List<String> result = new ArrayList<>();
        String normalized = cleanLine(plots).replace(" and ", ", ");
        for (String token : normalized.split(",")) {
            String plot = normalizePlotName(token);
            if (!plot.isEmpty()) result.add(plot);
        }
        return result;
    }

    private static int parseIntSafe(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String formatKnownPestPlots() {
        List<String> plots = new ArrayList<>();
        for (PestPlotInfo plot : knownPestPlots.values()) {
            String count = plot.inaccurate ? "?" : String.valueOf(plot.pests);
            plots.add(plot.displayName + " x" + count);
        }
        return String.join(", ", plots);
    }

    private static String formatPlot(PestPlotInfo plot) {
        if (plot == null) return "unknown";
        return plot.displayName + (plot.inaccurate ? " (unknown pests)" : " (" + plot.pests + " pests)");
    }

    // ═══════════════════════════════════════════════════ Pest info

    public static class PestInfo {
        public final Entity nameTag;
        public final String name;
        public final Vec3d pestPos;

        PestInfo(Entity nameTag, String name, Vec3d pestPos) {
            this.nameTag = nameTag;
            this.name = name;
            this.pestPos = pestPos;
        }
    }

    private static class PestPlotInfo {
        final String key;
        final String displayName;
        final String commandName;
        final int numericId;
        final Vec3d center;
        int pests;
        boolean inaccurate;

        private PestPlotInfo(String key, String displayName, String commandName, int numericId,
                             Vec3d center, int pests, boolean inaccurate) {
            this.key = key;
            this.displayName = displayName;
            this.commandName = commandName;
            this.numericId = numericId;
            this.center = center;
            this.pests = pests;
            this.inaccurate = inaccurate;
        }

        static PestPlotInfo fromId(int id, int pests, boolean inaccurate) {
            String display = id == 0 ? "The Barn" : String.valueOf(id);
            String command = id == 0 ? "barn" : String.valueOf(id);
            return new PestPlotInfo(keyForId(id), display, command, id, centerForId(id), pests, inaccurate);
        }

        static PestPlotInfo fromName(String name, int pests, boolean inaccurate) {
            String normalized = normalizePlotName(name);
            if (normalized.equalsIgnoreCase("The Barn") || normalized.equalsIgnoreCase("Barn")) {
                return fromId(0, pests, inaccurate);
            }
            int id = parseIntSafe(normalized, -1);
            if (id >= 0 && id <= 24) {
                return fromId(id, pests, inaccurate);
            }
            return new PestPlotInfo("name:" + normalized.toLowerCase(Locale.ROOT), normalized, normalized, -1, null, pests, inaccurate);
        }

        static String keyForId(int id) {
            return "id:" + id;
        }

        private static Vec3d centerForId(int id) {
            return PlotUtils.getPlotCenter(id, 84.0);
        }
    }

}
