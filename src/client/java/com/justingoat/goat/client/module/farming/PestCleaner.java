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
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.RotationUtils;
import com.justingoat.goat.client.utils.ScoreboardUtils;
import com.justingoat.goat.client.utils.TabUtils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.regex.Pattern;

public class PestCleaner extends GoatModule implements MacroHudInfo {

    // ── State machine ──────────────────────────────────────────────
    private enum State {
        IDLE,
        SCANNING,
        WARP_TO_PLOT,
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
    private static final int TRACKER_WAIT_TICKS = 20;
    private static final int TRACKER_FOLLOW_TIMEOUT_TICKS = 140;
    private static final double TRACKER_REACHED_DISTANCE = 6.0;
    private static final double TRACKER_WAYPOINT_DISTANCE = 12.0;
    private static final int MAX_PATH_FAILURES = 2;
    private static final int MAX_REASONABLE_PATH_POINTS = 420;
    private static final double MAX_PATH_DETOUR_FACTOR = 3.0;
    private static final int PLOT_WARP_SETTLE_TICKS = 20;
    private static final double MIN_HOVER_ABOVE_PEST = 3.0;
    private static final double HOVER_Y_TOLERANCE = 0.45;
    private static final Pattern PLOT_NUMBER_PATTERN = Pattern.compile("(?i)\\bplots?\\s*(?:-|#|:)?\\s*(\\d{1,2}(?:\\s*(?:,|/|&|and)\\s*\\d{1,2})*)");
    private static final Pattern GARDEN_PEST_COUNT_PATTERN = Pattern.compile("(?i)\\bthe garden\\b.*?(?:x\\s*(\\d+)|(\\d+)\\s*x)\\s*$");
    private static final Pattern CURRENT_PLOT_PESTS_PATTERN = Pattern.compile("(?i)\\bplot\\s*-\\s*(.+?)\\s+.*?x(\\d+)\\s*$");
    private static final Pattern CURRENT_PLOT_NO_PESTS_PATTERN = Pattern.compile("(?i)\\bplot\\s*-\\s*(.+?)\\s*$");
    private static final Pattern ONE_PEST_SPAWN_PATTERN = Pattern.compile("(?i).*\\bA\\b.*\\bPest\\b has appeared in (?:Plot - )?(.+)!");
    private static final Pattern MULTI_PEST_SPAWN_PATTERN = Pattern.compile("(?i).*\\b(\\d+)\\b.*\\bPests?\\b have spawned in (?:Plot - )?(.+)!");
    private static final Pattern OFFLINE_PEST_SPAWN_PATTERN = Pattern.compile("(?i).*While you were offline, .*\\bPests?\\b spawned in Plots (.+)!");
    private static final Pattern NO_PESTS_CHAT_PATTERN = Pattern.compile("(?i).*There are not any Pests on your Garden right now.*");
    private static final int[][] PLOT_MAP = {
        {21, 13, 9, 14, 22},
        {15, 5, 1, 6, 16},
        {10, 2, 0, 3, 11},
        {17, 7, 4, 8, 18},
        {23, 19, 12, 20, 24}
    };

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
    private final FlyPathProcessor flyProcessor = new FlyPathProcessor();
    private volatile boolean pathing = false;

    private Vec3d startPosition = null;
    private Entity currentPestTag = null;
    private Vec3d currentPestPos = null;
    private final Set<Integer> killedPestIds = new HashSet<>();
    private final List<PestInfo> detectedPests = new ArrayList<>();
    private final List<Vec3d> trackerParticles = new ArrayList<>();
    private final LinkedHashMap<String, PestPlotInfo> knownPestPlots = new LinkedHashMap<>();
    private final Set<String> attemptedPestPlotWarps = new HashSet<>();
    private Vec3d trackerWaypoint = null;
    private PestPlotInfo currentWarpPlot = null;

    private int vacuumSlot = -1;
    private double vacuumRange = 5.0;
    private int killTicks = 0;
    private int equipDelay = 0;
    private int scanDelay = 0;
    private int stuckTicks = 0;
    private int killsThisSession = 0;
    private int previousSlot = -1;
    private int trackerTicks = 0;
    private int trackerFollowTicks = 0;
    private int plotWarpTicks = 0;
    private int pathFailures = 0;
    private int scoreboardPests = -1;
    private boolean trackerAttempted = false;
    private boolean autoRewarpSent = false;
    private Vec3d lastPathFailureTarget = null;

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
            pathFailures = 0;
            scoreboardPests = -1;
            trackerAttempted = false;
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
        if (rotation.isActive()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.setYaw(rotation.getCurrentYaw());
                client.player.setPitch(rotation.getCurrentPitch());
            }
        }
        rotation.clear();
        RotationInterpolator.clearActive();
        InputUtils.releaseAll();
        currentPestTag = null;
        currentPestPos = null;
        trackerParticles.clear();
        attemptedPestPlotWarps.clear();
        trackerWaypoint = null;
        currentWarpPlot = null;
        trackerTicks = 0;
        trackerFollowTicks = 0;
        plotWarpTicks = 0;
        pathFailures = 0;
        trackerAttempted = false;
        lastPathFailureTarget = null;
        pathing = false;
        renderPests = null;
        renderTarget = null;
        EventManager.INSTANCE.unregister(this);
        if (previousSlot >= 0) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.getInventory().setSelectedSlot(previousSlot);
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

        switch (state) {
            case IDLE     -> {}
            case SCANNING -> tickScanning(client);
            case WARP_TO_PLOT -> tickWarpToPlot(client);
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

    // ═══════════════════════════════════════════════════ ACTIVATE TRACKER

    private void tickActivateTracker(MinecraftClient client) {
        if (!detectedPests.isEmpty()) {
            PestInfo closest = findClosestPest(client.player);
            if (closest != null) {
                InputUtils.setAttack(false);
                targetPest(closest);
                state = State.EQUIP_VACUUM;
                equipDelay = 0;
                return;
            }
        }

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
            client.player.getInventory().setSelectedSlot(slot);
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
        client.player.getInventory().setSelectedSlot(slot);

        String vacName = getVacuumName(client, slot);
        vacuumRange = getVacuumRange(vacName);
        debugMsg("Equipped vacuum: " + vacName + " (range " + vacuumRange + ")");

        state = State.FLY_TO_PEST;
        stuckTicks = 0;
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

        if (!flyProcessor.isDone()) {
            flyProcessor.tick(client, (float) rotSpeed.getValue());
        }

        // Stuck check during flight
        if (lastFlyPos != null) {
            double moved = lastFlyPos.squaredDistanceTo(client.player.getX(), client.player.getY(), client.player.getZ());
            stuckTicks = moved < 0.005 ? stuckTicks + 1 : 0;
        }
        lastFlyPos = playerPos(client);

        if (stuckTicks > 100) {
            debugMsg("Stuck during flight, repathing...");
            stuckTicks = 0;
            flyProcessor.stop();
            requestFlyPath(client);
        }
    }

    private Vec3d lastFlyPos = null;

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
            stuckTicks = 0;
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

        // Micro-movement to stay in range
        if (horizontalDist > Math.max(1.5, vacuumRange * 0.45)) {
            InputUtils.setForward(true);
        } else {
            InputUtils.setForward(false);
        }
        InputUtils.setBack(false);

        killTicks++;
        if (killTicks > 200) {
            debugMsg("Pest kill timeout, moving on...");
            killedPestIds.add(currentPestTag.getId());
            onPestKilled();
        }
    }

    // ═══════════════════════════════════════════════════ CHECK MORE

    private void tickCheckMore(MinecraftClient client) {
        scanDelay++;
        if (scanDelay < 10) return;
        scanDelay = 0;

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
        double radiusSq = scanRadius.getValue() * scanRadius.getValue();

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof ArmorStandEntity armorStand)) continue;
            if (armorStand.getCustomName() == null) continue;
            if (killedPestIds.contains(armorStand.getId())) continue;

            String pestName = matchPestName(getEntityName(armorStand));
            if (pestName == null) continue;

            double distSq = armorStand.squaredDistanceTo(client.player);
            if (distSq > radiusSq) continue;
            if (armorStand.getY() < 50) continue;

            Vec3d pestPos = new Vec3d(armorStand.getX(), armorStand.getY() - 2.0, armorStand.getZ());
            Entity target = findPestBodyNear(client, armorStand, pestName);
            if (target != null) {
                pestPos = new Vec3d(target.getX(), target.getY(), target.getZ());
                detectedPests.add(new PestInfo(target, pestName, pestPos));
            } else {
                detectedPests.add(new PestInfo(armorStand, pestName, pestPos));
            }
        }

        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof ArmorStandEntity) continue;
            if (entity == client.player || entity.isRemoved()) continue;
            if (killedPestIds.contains(entity.getId())) continue;

            String pestName = matchPestName(getEntityName(entity));
            if (pestName == null) continue;

            double distSq = entity.squaredDistanceTo(client.player);
            if (distSq > radiusSq) continue;
            if (entity.getY() < 50) continue;

            Vec3d pestPos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
            if (isDuplicatePest(pestName, pestPos)) continue;
            detectedPests.add(new PestInfo(entity, pestName, pestPos));
        }

        if (renderESP.getValue()) {
            renderPests = detectedPests.isEmpty() ? null : new ArrayList<>(detectedPests);
        } else {
            renderPests = null;
        }
    }

    private String matchPestName(String stripped) {
        if (stripped == null || stripped.isEmpty()) return null;
        for (String name : PEST_NAMES) {
            if (stripped.contains(name)) return name;
        }
        return null;
    }

    private String getEntityName(Entity entity) {
        if (entity.getCustomName() != null) {
            return STRIP_COLOR.matcher(entity.getCustomName().getString()).replaceAll("").trim();
        }
        if (entity.getDisplayName() != null) {
            return STRIP_COLOR.matcher(entity.getDisplayName().getString()).replaceAll("").trim();
        }
        return "";
    }

    private boolean isDuplicatePest(String pestName, Vec3d pestPos) {
        for (PestInfo pest : detectedPests) {
            if (!pest.name.equals(pestName)) continue;
            if (pest.pestPos.squaredDistanceTo(pestPos) <= 9.0) return true;
        }
        return false;
    }

    private Entity findPestBodyNear(MinecraftClient client, Entity nameTag, String pestName) {
        Box searchBox = nameTag.getBoundingBox().expand(1.0, 2.5, 1.0);
        Entity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity nearby : client.world.getOtherEntities(nameTag, searchBox)) {
            if (nearby instanceof ArmorStandEntity) continue;
            if (!(nearby instanceof LivingEntity)) continue;
            if (nearby == client.player || nearby.isRemoved()) continue;
            if (killedPestIds.contains(nearby.getId())) continue;

            String nearbyName = getEntityName(nearby);
            String nearbyPest = matchPestName(nearbyName);
            if (nearbyPest != null && !nearbyPest.equals(pestName)) continue;

            double distSq = nearby.squaredDistanceTo(nameTag);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = nearby;
            }
        }
        return best;
    }

    private PestInfo findClosestPest(ClientPlayerEntity player) {
        PestInfo closest = null;
        double closestDistSq = Double.MAX_VALUE;

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
        for (int i = 0; i < 9; i++) {
            var stack = client.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString();
            for (String vacName : VACUUM_RANGES.keySet()) {
                if (name.contains(vacName) || name.contains("Vacuum")) return i;
            }
        }
        return -1;
    }

    private String getVacuumName(MinecraftClient client, int slot) {
        var stack = client.player.getInventory().getStack(slot);
        return stack.isEmpty() ? "" : stack.getName().getString();
    }

    private double getVacuumRange(String vacName) {
        for (Map.Entry<String, Double> entry : VACUUM_RANGES.entrySet()) {
            if (vacName.contains(entry.getKey())) return entry.getValue();
        }
        return 5.0;
    }

    // ═══════════════════════════════════════════════════ Pathfinding

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
                knownPestPlots.clear();
                attemptedPestPlotWarps.clear();
                return;
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
        trackerAttempted = false;
        flyProcessor.stop();
        InputUtils.releaseAll();
        markAllowedTeleportCommand();
        client.player.networkHandler.sendChatCommand("plottp " + plot.commandName);
        state = State.WARP_TO_PLOT;
        debugMsg("Warping to pest plot " + formatPlot(plot));
        return true;
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
        int xIndex = (int)Math.floor((pos.x + 48.0) / 96.0) + 2;
        int zIndex = (int)Math.floor((pos.z + 48.0) / 96.0) + 2;
        if (xIndex < 0 || xIndex >= PLOT_MAP[0].length || zIndex < 0 || zIndex >= PLOT_MAP.length) return null;
        return PLOT_MAP[zIndex][xIndex];
    }

    private void startTrackerActivation() {
        InputUtils.releaseAll();
        flyProcessor.stop();
        trackerTicks = 0;
        trackerParticles.clear();
        trackerWaypoint = null;
        pathFailures = 0;
        lastPathFailureTarget = null;
        state = State.ACTIVATE_TRACKER;
        debugMsg("Activating Pest Tracker...");
    }

    private Vec3d calculateTrackerWaypoint(MinecraftClient client) {
        if (trackerParticles.size() < 2) return null;

        Vec3d first = trackerParticles.get(0);
        Vec3d last = trackerParticles.get(trackerParticles.size() - 1);
        Vec3d direction = new Vec3d(last.x - first.x, 0.0, last.z - first.z);
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
        if (warpToKnownPestPlot(client)) {
            return;
        }
        finishNoPests(client);
    }

    @EventListener
    private void onParticlePacket(ParticlePacketEvent event) {
        if (!isEnabled()) return;
        if (state != State.ACTIVATE_TRACKER && state != State.FOLLOW_TRACKER_TRAIL) return;
        if (event.getParticle().getType() != ParticleTypes.ANGRY_VILLAGER) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Vec3d pos = new Vec3d(event.getX(), event.getY(), event.getZ());
        if (client.player.squaredDistanceTo(pos.x, pos.y, pos.z) > 80.0 * 80.0) return;

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
        if (!isEnabled() || event.isOverlay()) return;

        String message = cleanLine(event.getMessage());
        if (NO_PESTS_CHAT_PATTERN.matcher(message).matches()) {
            knownPestPlots.clear();
            attemptedPestPlotWarps.clear();
            scoreboardPests = 0;
            debugMsg("Cleared known pest plots from no-pests chat");
            return;
        }

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
        if (pestTracker.getValue() && !trackerAttempted) {
            startTrackerActivation();
            return true;
        }
        if (warpToKnownPestPlot(client)) {
            return true;
        }
        return false;
    }

    private void finishNoPests(MinecraftClient client) {
        returnOrFinishNoPests();
    }

    private void returnOrFinishNoPests() {
        if (autoReturn.getValue() && startPosition != null) {
            state = State.GO_BACK;
        } else {
            state = State.FINISHED;
        }
    }

    private void finishCompleted(MinecraftClient client) {
        if (autoRewarp.getValue() && !autoRewarpSent
            && client.player != null && client.player.networkHandler != null) {
            markAllowedTeleportCommand();
            client.player.networkHandler.sendChatCommand("warp garden");
            autoRewarpSent = true;
            debugMsg("PestCleaner complete, rewarping...");
        }
        setEnabled(false);
    }

    private void targetPest(PestInfo pest) {
        currentPestTag = pest.nameTag;
        currentPestPos = pest.pestPos;
        renderTarget = pest;
        pathFailures = 0;
        lastPathFailureTarget = null;
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
        state = State.CHECK_MORE;
        scanDelay = 0;
        trackerAttempted = false;
    }

    private void initRotation(MinecraftClient client) {
        if (!rotation.isActive()) {
            rotation.init(client.player.getYaw(), client.player.getPitch());
        }
        RotationInterpolator.setActive(rotation);
    }

    private void releaseRotation(MinecraftClient client) {
        if (rotation.isActive() && client.player != null) {
            client.player.setYaw(rotation.getCurrentYaw());
            client.player.setPitch(rotation.getCurrentPitch());
        }
        rotation.clear();
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

    private double horizontalDistanceTo(Vec3d target, MinecraftClient client) {
        if (target == null || client.player == null) return Double.MAX_VALUE;
        double dx = client.player.getX() - target.x;
        double dz = client.player.getZ() - target.z;
        return Math.sqrt(dx * dx + dz * dz);
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
        return new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
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
            var matcher = GARDEN_PEST_COUNT_PATTERN.matcher(cleanLine(line));
            if (!matcher.find()) continue;
            try {
                String count = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                return Integer.parseInt(count);
            } catch (NumberFormatException ignored) {}
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
            for (int z = 0; z < PLOT_MAP.length; z++) {
                for (int x = 0; x < PLOT_MAP[z].length; x++) {
                    if (PLOT_MAP[z][x] == id) {
                        return new Vec3d((x - 2) * 96.0, 84.0, (z - 2) * 96.0);
                    }
                }
            }
            return null;
        }
    }

}
