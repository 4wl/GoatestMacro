package com.justingoat.goat.client.module.farming;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
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
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class PestCleaner extends GoatModule implements MacroHudInfo {

    // ── State machine ──────────────────────────────────────────────
    private enum State {
        IDLE,
        SCANNING,
        ACTIVATE_TRACKER,
        SEARCH_PLOTS,
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
    private final NumberValue rotSpeed;
    private final NumberValue flyHeight;
    private final BooleanValue autoReturn;
    private final BooleanValue pestTracker;
    private final BooleanValue plotSearch;
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
    private static final double GARDEN_PLOT_SIZE = 96.0;
    private static final double PLOT_SCAN_REACHED_DISTANCE = 10.0;
    private static final int PLOT_SCAN_SETTLE_TICKS = 15;
    private static final int TRACKER_WAIT_TICKS = 20;
    private static final Pattern PLOT_NUMBER_PATTERN = Pattern.compile("(?i)\\bplots?\\s*(?:-|#|:)?\\s*(\\d{1,2}(?:\\s*(?:,|/|&|and)\\s*\\d{1,2})*)");

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
    private final Deque<Vec3d> plotScanTargets = new ArrayDeque<>();
    private Vec3d currentPlotScanTarget = null;

    private int vacuumSlot = -1;
    private double vacuumRange = 5.0;
    private int killTicks = 0;
    private int equipDelay = 0;
    private int scanDelay = 0;
    private int stuckTicks = 0;
    private int killsThisSession = 0;
    private int previousSlot = -1;
    private int plotScanTicks = 0;
    private int trackerTicks = 0;
    private boolean trackerAttempted = false;

    // ── Render data (for PathRenderer) ─────────────────────────────
    private static volatile List<PestInfo> renderPests = null;
    private static volatile PestInfo renderTarget = null;

    public static List<PestInfo> getRenderPests() { return renderPests; }
    public static PestInfo getRenderTarget() { return renderTarget; }

    // ═══════════════════════════════════════════════════ Constructor

    public PestCleaner() {
        super("PestCleaner", ModuleCategory.MACRO, false);

        scanRadius = addNumber("ScanRadius", 60.0, 10.0, 120.0);
        rotSpeed   = addNumber("RotSpeed", 0.6, 0.1, 1.0);
        flyHeight  = addNumber("FlyHeight", 3.0, 1.0, 8.0);
        autoReturn = addBoolean("AutoReturn", true);
        pestTracker = addBoolean("PestTracker", true);
        plotSearch = addBoolean("PlotSearch", true);
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
            plotScanTargets.clear();
            currentPlotScanTarget = null;
            killsThisSession = 0;
            pathing = false;
            scanDelay = 0;
            plotScanTicks = 0;
            trackerTicks = 0;
            trackerAttempted = false;
            rotation.clear();
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
        plotScanTargets.clear();
        currentPlotScanTarget = null;
        plotScanTicks = 0;
        trackerTicks = 0;
        trackerAttempted = false;
        pathing = false;
        renderPests = null;
        renderTarget = null;
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

        switch (state) {
            case IDLE     -> {}
            case SCANNING -> tickScanning(client);
            case ACTIVATE_TRACKER -> tickActivateTracker(client);
            case SEARCH_PLOTS -> tickSearchPlots(client);
            case EQUIP_VACUUM -> tickEquipVacuum(client);
            case FLY_TO_PEST -> tickFlyToPest(client);
            case APPROACH_PEST -> tickApproachPest(client);
            case KILL_PEST -> tickKillPest(client);
            case CHECK_MORE -> tickCheckMore(client);
            case GO_BACK -> tickGoBack(client);
            case FINISHED -> {
                setEnabled(false);
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
            if (beginPestSearchFallback(client, "Searching for pest plot...")) return;
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

        currentPestTag = closest.nameTag;
        currentPestPos = closest.pestPos;
        renderTarget = closest;
        debugMsg("Target: " + closest.name + " at " + formatPos(currentPestPos));

        state = State.EQUIP_VACUUM;
        equipDelay = 0;
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
        if (plotSearch.getValue() && preparePlotSearch(client)) {
            state = State.SEARCH_PLOTS;
            debugMsg("Searching plot from Tab/Scoreboard/Pest Tracker...");
            return;
        }

        finishNoPests(client);
    }

    // ═══════════════════════════════════════════════════ SEARCH PLOTS

    private void tickSearchPlots(MinecraftClient client) {
        if (!detectedPests.isEmpty()) {
            PestInfo closest = findClosestPest(client.player);
            if (closest != null) {
                flyProcessor.stop();
                currentPlotScanTarget = null;
                targetPest(closest);
                state = State.EQUIP_VACUUM;
                equipDelay = 0;
                debugMsg("Found pest while searching plots: " + closest.name);
                return;
            }
        }

        if (currentPlotScanTarget == null) {
            currentPlotScanTarget = plotScanTargets.pollFirst();
            plotScanTicks = 0;
            flyProcessor.stop();

            if (currentPlotScanTarget == null) {
                debugMsg("No pests found in nearby plots");
                returnOrFinishNoPests();
                return;
            }

            debugMsg("Checking plot around " + formatPos(currentPlotScanTarget));
            requestFlyPathTo(client, currentPlotScanTarget);
        }

        double dist = playerPos(client).distanceTo(currentPlotScanTarget);
        if (dist <= PLOT_SCAN_REACHED_DISTANCE) {
            flyProcessor.stop();
            plotScanTicks++;
            if (plotScanTicks >= PLOT_SCAN_SETTLE_TICKS) {
                currentPlotScanTarget = null;
            }
            return;
        }

        if (flyProcessor.isDone() && !pathing) {
            requestFlyPathTo(client, currentPlotScanTarget);
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

        if (dist <= vacuumRange - 1.0) {
            flyProcessor.stop();
            state = State.APPROACH_PEST;
            killTicks = 0;
            initRotation(client);
            debugMsg("In vacuum range, approaching...");
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

        if (dist > vacuumRange * 0.4) {
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

        // Vertical adjustment
        double yErr = targetCenter.y - client.player.getY();
        InputUtils.setJump(yErr > 0.5);
        InputUtils.setSneak(yErr < -0.5);

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

        // Vertical adjustment to stay level
        double yErr = targetCenter.y - client.player.getY();
        InputUtils.setJump(yErr > 0.5);
        InputUtils.setSneak(yErr < -0.5);

        // Micro-movement to stay in range
        if (dist > vacuumRange * 0.7) {
            InputUtils.setForward(true);
        } else if (dist < 2.0) {
            InputUtils.setForward(false);
            InputUtils.setBack(true);
        } else {
            InputUtils.setForward(false);
            InputUtils.setBack(false);
        }

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

        if (beginPestSearchFallback(client, "Searching for more pest plots...")) return;

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
            detectedPests.add(new PestInfo(armorStand, pestName, pestPos));
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
        double yAdd = flyHeight.getValue();
        Vec3d target = currentPestPos.add(0, yAdd, 0);
        requestFlyPathTo(client, target);
    }

    private void requestFlyPathTo(MinecraftClient client, Vec3d target) {
        if (pathing || target == null) return;
        pathing = true;

        BlockPos start = client.player.getBlockPos();
        BlockPos end = BlockPos.ofFloored(target);

        FlyPathProcessor.computePathAsync(start, end, 30000)
            .thenAccept(path -> client.execute(() -> {
                pathing = false;
                if (path != null && !path.isEmpty()) {
                    flyProcessor.setPath(path);
                }
            }));
    }

    private boolean preparePlotSearch(MinecraftClient client) {
        if (startPosition == null || currentPlotScanTarget != null || !plotScanTargets.isEmpty()) return false;

        double y = client.player.getY() + flyHeight.getValue();
        List<Integer> pestPlots = getPestPlotsFromHud();
        for (int plot : pestPlots) {
            Vec3d plotCenter = getPlotCenter(plot, y);
            if (plotCenter != null) {
                addPlotScanTarget(plotCenter.x, plotCenter.y, plotCenter.z);
            }
        }

        if (!plotScanTargets.isEmpty()) return true;

        Vec3d basePlot = getPlotCenter(startPosition);

        addPlotScanTarget(basePlot.x, y, basePlot.z);
        addPlotScanTarget(basePlot.x + GARDEN_PLOT_SIZE, y, basePlot.z);
        addPlotScanTarget(basePlot.x - GARDEN_PLOT_SIZE, y, basePlot.z);
        addPlotScanTarget(basePlot.x, y, basePlot.z + GARDEN_PLOT_SIZE);
        addPlotScanTarget(basePlot.x, y, basePlot.z - GARDEN_PLOT_SIZE);

        return !plotScanTargets.isEmpty();
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
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.contains("plot")) continue;
            if (!lower.contains("pest") && !lower.contains("plots")) continue;

            var matcher = PLOT_NUMBER_PATTERN.matcher(line);
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

    private Vec3d getPlotCenter(int plot, double y) {
        int[] offset = getPlotGridOffset(plot);
        if (offset == null) return null;
        return new Vec3d(offset[0] * GARDEN_PLOT_SIZE, y, offset[1] * GARDEN_PLOT_SIZE);
    }

    private int[] getPlotGridOffset(int plot) {
        return switch (plot) {
            case 21 -> new int[]{-2, -2};
            case 13 -> new int[]{-1, -2};
            case 9  -> new int[]{0, -2};
            case 14 -> new int[]{1, -2};
            case 22 -> new int[]{2, -2};
            case 15 -> new int[]{-2, -1};
            case 5  -> new int[]{-1, -1};
            case 1  -> new int[]{0, -1};
            case 6  -> new int[]{1, -1};
            case 16 -> new int[]{2, -1};
            case 10 -> new int[]{-2, 0};
            case 2  -> new int[]{-1, 0};
            case 3  -> new int[]{1, 0};
            case 11 -> new int[]{2, 0};
            case 17 -> new int[]{-2, 1};
            case 7  -> new int[]{-1, 1};
            case 4  -> new int[]{0, 1};
            case 8  -> new int[]{1, 1};
            case 18 -> new int[]{2, 1};
            case 23 -> new int[]{-2, 2};
            case 19 -> new int[]{-1, 2};
            case 12 -> new int[]{0, 2};
            case 20 -> new int[]{1, 2};
            case 24 -> new int[]{2, 2};
            default -> null;
        };
    }

    private Vec3d getPlotCenter(Vec3d pos) {
        double x = Math.round(pos.x / GARDEN_PLOT_SIZE) * GARDEN_PLOT_SIZE;
        double z = Math.round(pos.z / GARDEN_PLOT_SIZE) * GARDEN_PLOT_SIZE;
        return new Vec3d(x, pos.y, z);
    }

    private void addPlotScanTarget(double x, double y, double z) {
        Vec3d target = new Vec3d(x, y, z);
        for (Vec3d existing : plotScanTargets) {
            if (existing.squaredDistanceTo(target) < 1.0) return;
        }
        plotScanTargets.add(target);
    }

    // ═══════════════════════════════════════════════════ State helpers

    private boolean beginPestSearchFallback(MinecraftClient client, String message) {
        if (pestTracker.getValue() && !trackerAttempted) {
            state = State.ACTIVATE_TRACKER;
            trackerTicks = 0;
            debugMsg("Activating Pest Tracker...");
            return true;
        }
        if (plotSearch.getValue() && preparePlotSearch(client)) {
            state = State.SEARCH_PLOTS;
            debugMsg(message);
            return true;
        }
        return false;
    }

    private void finishNoPests(MinecraftClient client) {
        if (plotSearch.getValue() && preparePlotSearch(client)) {
            state = State.SEARCH_PLOTS;
            debugMsg("Searching nearby Garden plots for pests...");
            return;
        }
        returnOrFinishNoPests();
    }

    private void returnOrFinishNoPests() {
        if (autoReturn.getValue() && startPosition != null) {
            state = State.GO_BACK;
        } else {
            state = State.FINISHED;
        }
    }

    private void targetPest(PestInfo pest) {
        currentPestTag = pest.nameTag;
        currentPestPos = pest.pestPos;
        renderTarget = pest;
    }

    private void onPestKilled() {
        InputUtils.setUse(false);
        InputUtils.releaseAll();
        releaseRotation(MinecraftClient.getInstance());
        flyProcessor.stop();

        if (currentPestTag != null) {
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
        return info;
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
}
