package com.justingoat.goat.client.module.movement;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.pathfinder.AStarPathfinder;
import com.justingoat.goat.client.module.pathfinder.PathNode;
import com.justingoat.goat.client.module.pathfinder.PathProcessor;
import com.justingoat.goat.client.module.pathfinder.PathSmoother;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.RotationUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ForagingMacro extends GoatModule implements MacroHudInfo {

    private enum MacroState {
        SCANNING,
        PATHFINDING,
        NAVIGATING,
        ROTATING,
        BREAKING,
        NUDGING,
        WAITING_CD
    }

    private final ModeValue logType;
    private final NumberValue scanRadius;
    private final NumberValue treecapCooldown;
    private final NumberValue breakTimeout;
    private final NumberValue rotSpeed;
    private final BooleanValue renderPath;

    private MacroState currentState = MacroState.SCANNING;
    private BlockPos targetBase = null;
    private BlockPos targetLog = null;
    private BlockPos activeBreakLog = null;
    private long breakStartTime = 0;
    private long cooldownUntil = 0;
    private long rotateStartTime = 0;
    private int repositionAttempts = 0;
    private int nudgeAttempts = 0;
    private int nudgeTicks = 0;
    private double lastMoveX = Double.NaN;
    private double lastMoveZ = Double.NaN;
    private double bestTargetDistSq = Double.MAX_VALUE;
    private int macroStuckTicks = 0;

    private final PathProcessor pathProcessor = new PathProcessor();
    private volatile boolean computing = false;

    private final RotationUtils rotation = new RotationUtils();

    private final Map<BlockPos, Long> blacklist = new HashMap<>();
    private static final long BLACKLIST_DURATION_MS = 5 * 60 * 1000;
    private static final long SKIP_BLACKLIST_DURATION_MS = 15 * 1000;

    private int scanCooldownTicks = 0;
    private long lastNoTargetMessage = 0;
    private long lastGuiWaitMessage = 0;
    private int lastScanMatchedLogs = 0;
    private static final int MACRO_STUCK_TICKS = 24;

    private static final Set<Block> ALL_LOGS = Set.of(
        Blocks.OAK_LOG, Blocks.DARK_OAK_LOG, Blocks.JUNGLE_LOG,
        Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.ACACIA_LOG,
        Blocks.CHERRY_LOG, Blocks.MANGROVE_LOG, Blocks.PALE_OAK_LOG,
        Blocks.OAK_WOOD, Blocks.DARK_OAK_WOOD, Blocks.JUNGLE_WOOD,
        Blocks.SPRUCE_WOOD, Blocks.BIRCH_WOOD, Blocks.ACACIA_WOOD,
        Blocks.CHERRY_WOOD, Blocks.MANGROVE_WOOD, Blocks.PALE_OAK_WOOD,
        Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_DARK_OAK_LOG, Blocks.STRIPPED_JUNGLE_LOG,
        Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_BIRCH_LOG, Blocks.STRIPPED_ACACIA_LOG,
        Blocks.STRIPPED_CHERRY_LOG, Blocks.STRIPPED_MANGROVE_LOG, Blocks.STRIPPED_PALE_OAK_LOG,
        Blocks.STRIPPED_OAK_WOOD, Blocks.STRIPPED_DARK_OAK_WOOD, Blocks.STRIPPED_JUNGLE_WOOD,
        Blocks.STRIPPED_SPRUCE_WOOD, Blocks.STRIPPED_BIRCH_WOOD, Blocks.STRIPPED_ACACIA_WOOD,
        Blocks.STRIPPED_CHERRY_WOOD, Blocks.STRIPPED_MANGROVE_WOOD, Blocks.STRIPPED_PALE_OAK_WOOD
    );

    public ForagingMacro() {
        super("ForagingMacro", ModuleCategory.MOVEMENT, false);
        logType = addMode("Log Type", "Dark Oak",
            "Dark Oak", "Oak", "Jungle", "Spruce", "Birch", "Acacia", "Cherry", "Mangrove", "Pale Oak", "Any");
        scanRadius = addNumber("Scan Radius", 30.0, 10.0, 50.0);
        treecapCooldown = addNumber("Treecap CD (ms)", 2000.0, 500.0, 5000.0);
        breakTimeout = addNumber("Break Timeout (s)", 5.0, 2.0, 10.0);
        rotSpeed = addNumber("RotSpeed", 0.5, 0.1, 1.0);
        renderPath = addBoolean("RenderPath", true);
    }

    public PathProcessor getPathProcessor() { return pathProcessor; }
    public boolean shouldRenderPath() { return renderPath.getValue(); }
    public BlockPos getRenderTarget() { return targetLog != null ? targetLog : targetBase; }
    @Override public String getHudName() { return "Foraging"; }
    @Override public String getHudState() { return currentState.name(); }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (enabled) {
            FailsafeManager.getInstance().reset();
            currentState = MacroState.SCANNING;
            targetBase = null;
            targetLog = null;
            activeBreakLog = null;
            computing = false;
            breakStartTime = 0;
            rotateStartTime = 0;
            repositionAttempts = 0;
            nudgeAttempts = 0;
            nudgeTicks = 0;
            resetMovementMonitor();
            scanCooldownTicks = 0;
            cooldownUntil = 0;
            lastNoTargetMessage = 0;
            lastGuiWaitMessage = 0;
            lastScanMatchedLogs = 0;
            rotation.clear();
            blacklist.clear();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("\u00A7a[Goat] Foraging macro enabled."), false);
            }
        } else {
            pathProcessor.stop();
            rotation.clear();
            RotationInterpolator.clearActive();
            InputUtils.releaseAll();
            computing = false;
            targetBase = null;
            targetLog = null;
            activeBreakLog = null;
            repositionAttempts = 0;
            nudgeAttempts = 0;
            nudgeTicks = 0;
            resetMovementMonitor();
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null) return;

        if (FailsafeManager.getInstance().hasEmergency()) {
            this.setEnabled(false);
            return;
        }

        if (client.currentScreen != null) {
            InputUtils.releaseAll();
            maybeSendGuiWaitMessage(client);
            return;
        }

        purgeExpiredBlacklist();

        switch (currentState) {
            case SCANNING:     tickScanning(client);    break;
            case PATHFINDING:  break; // async — waiting for CompletableFuture callback
            case NAVIGATING:   tickNavigating(client);  break;
            case ROTATING:     tickRotating(client);    break;
            case BREAKING:     tickBreaking(client);    break;
            case NUDGING:      tickNudging(client);     break;
            case WAITING_CD:   tickWaitingCd(client);   break;
        }
    }

    // ────────────────────────────────── State: SCANNING

    private void tickScanning(MinecraftClient client) {
        if (scanCooldownTicks > 0) {
            scanCooldownTicks--;
            return;
        }

        List<BlockPos> treeBases = scanForTrees(client);

        if (treeBases.isEmpty()) {
            maybeSendNoTargetMessage(client);
            scanCooldownTicks = 40;
            return;
        }

        targetBase = treeBases.get(0);
        targetLog = findBestBreakLog(client, targetBase, false);
        repositionAttempts = 0;
        nudgeAttempts = 0;
        resetMovementMonitor();
        double distSq = playerDistSq(client, targetBase);

        if (distSq < 4.5 * 4.5) {
            enterRotating(client);
        } else {
            enterPathfinding(client);
        }
    }

    // ────────────────────────────────── State: PATHFINDING

    private void enterPathfinding(MinecraftClient client) {
        currentState = MacroState.PATHFINDING;
        computing = true;

        BlockPos start = client.player.getBlockPos().down();
        BlockPos goal = findPathGoal(client, targetBase);
        if (goal == null) {
            client.player.sendMessage(Text.literal("\u00A7c[Goat] No standable spot near tree. Blacklisting."), false);
            addToBlacklist(targetBase);
            targetBase = null;
            targetLog = null;
            currentState = MacroState.SCANNING;
            computing = false;
            return;
        }

        client.player.sendMessage(
            Text.literal("\u00A77[Goat] Pathing to " + logType.getValue() + " tree at " + targetBase.toShortString() + "..."), false);

        CompletableFuture.supplyAsync(() -> {
            java.util.List<PathNode> raw = AStarPathfinder.computePath(start, goal, 100000, 3);
            return raw != null ? PathSmoother.smooth(raw) : null;
        }).thenAccept(path -> client.execute(() -> {
            computing = false;
            if (!isEnabled()) return;
            if (path != null) {
                pathProcessor.setPath(path);
                currentState = MacroState.NAVIGATING;
                client.player.sendMessage(
                    Text.literal("§a[Goat] Path found — " + path.size() + " nodes."), false);
            } else {
                client.player.sendMessage(
                    Text.literal("\u00A7c[Goat] No path to tree. Blacklisting."), false);
                addToBlacklist(targetBase);
                targetBase = null;
                targetLog = null;
                currentState = MacroState.SCANNING;
            }
        }));
    }

    // ────────────────────────────────── State: NAVIGATING

    private void tickNavigating(MinecraftClient client) {
        if (targetBase == null) {
            pathProcessor.stop();
            currentState = MacroState.SCANNING;
            return;
        }

        if (!isLogBlock(client, targetBase) && findBestBreakLog(client, targetBase, false) == null) {
            client.player.sendMessage(
                Text.literal("\u00A7e[Goat] Target tree gone. Re-scanning."), false);
            pathProcessor.stop();
            targetBase = null;
            targetLog = null;
            currentState = MacroState.SCANNING;
            return;
        }

        if (isMacroStuck(client)) {
            client.player.sendMessage(Text.literal("\u00A7e[Goat] No path progress. Repositioning."), false);
            repositionOrSkip(client);
            return;
        }

        PathfinderTest pathSettings = getPathfinderModule();
        if (pathSettings != null) {
            pathProcessor.tick(client, pathSettings);
        }

        double distSq = playerDistSq(client, targetBase);
        if (pathProcessor.isDone()) {
            if (distSq < 5.5 * 5.5) {
                enterRotating(client);
            } else {
                client.player.sendMessage(
                    Text.literal("\u00A7e[Goat] Path ended too far. Skipping tree."), false);
                skipCurrentTree();
                currentState = MacroState.SCANNING;
            }
        }
    }

    // ────────────────────────────────── State: ROTATING

    private void enterRotating(MinecraftClient client) {
        pathProcessor.stop();
        InputUtils.releaseAll();
        currentState = MacroState.ROTATING;
        rotateStartTime = System.currentTimeMillis();

        rotation.init(client.player.getYaw(), client.player.getPitch());
        RotationInterpolator.setActive(rotation);
    }

    private void tickRotating(MinecraftClient client) {
        if (targetBase == null) {
            releaseRotation();
            targetBase = null;
            targetLog = null;
            currentState = MacroState.SCANNING;
            return;
        }

        targetLog = findBestBreakLog(client, targetBase, true);
        if (targetLog == null || !isLogBlock(client, targetLog)) {
            releaseRotation();
            addToBlacklist(targetBase);
            targetBase = null;
            targetLog = null;
            currentState = MacroState.SCANNING;
            return;
        }

        aimAtTarget(client);

        if (System.currentTimeMillis() < cooldownUntil) {
            InputUtils.setAttack(false);
            return;
        }

        if (!rotation.isRoughlyFacing()) {
            InputUtils.setAttack(false);
            return;
        }

        if (isCrosshairOnTreeLog(client)) {
            targetLog = ((BlockHitResult) client.crosshairTarget).getBlockPos().toImmutable();
            startBreakingTree(client);
            return;
        }

        if (System.currentTimeMillis() - rotateStartTime > 2500) {
            targetLog = findBestBreakLog(client, targetBase, false);
            if (targetLog != null && isCrosshairOnTreeLog(client)) {
                targetLog = ((BlockHitResult) client.crosshairTarget).getBlockPos().toImmutable();
                startBreakingTree(client);
            } else {
                client.player.sendMessage(Text.literal("\u00A7e[Goat] Could not see wood. Backing up."), false);
                enterNudging(client);
            }
        }
    }

    // ────────────────────────────────── State: BREAKING

    private void tickBreaking(MinecraftClient client) {
        if (targetLog == null) {
            InputUtils.setAttack(false);
            releaseRotation();
            targetBase = null;
            activeBreakLog = null;
            currentState = MacroState.SCANNING;
            return;
        }

        if (!isLogBlock(client, targetLog)) {
            InputUtils.setAttack(false);
            client.player.sendMessage(Text.literal("\u00A7a[Goat] Tree hit. Searching next."), false);
            skipCurrentTree();
            currentState = MacroState.SCANNING;
            scanCooldownTicks = 0;
            return;
        }

        long elapsed = System.currentTimeMillis() - breakStartTime;
        if (elapsed > (long) (breakTimeout.getValue() * 1000)) {
            InputUtils.setAttack(false);
            client.player.sendMessage(
                Text.literal("\u00A7c[Goat] Break timeout. Blacklisting."), false);
            if (targetBase != null) addToBlacklist(targetBase);
            releaseRotation();
            targetBase = null;
            targetLog = null;
            activeBreakLog = null;
            currentState = MacroState.SCANNING;
            return;
        }

        aimAtTarget(client);

        if (isCrosshairOnTreeLog(client)) {
            targetLog = ((BlockHitResult) client.crosshairTarget).getBlockPos().toImmutable();
            breakTarget(client);
            InputUtils.setAttack(true);
        } else {
            InputUtils.setAttack(false);
        }
    }

    // ────────────────────────────────── State: WAITING_CD

    private void enterNudging(MinecraftClient client) {
        if (targetBase == null || nudgeAttempts >= 2) {
            client.player.sendMessage(Text.literal("\u00A7e[Goat] Repositioning to another angle."), false);
            repositionOrSkip(client);
            return;
        }

        nudgeAttempts++;
        nudgeTicks = 10;
        pathProcessor.stop();
        releaseRotation();
        InputUtils.releaseAll();
        currentState = MacroState.NUDGING;
    }

    private void tickNudging(MinecraftClient client) {
        if (targetBase == null) {
            InputUtils.releaseAll();
            currentState = MacroState.SCANNING;
            return;
        }

        if (nudgeTicks-- > 0) {
            InputUtils.setBack(true);
            InputUtils.setForward(false);
            InputUtils.setAttack(false);
            InputUtils.setLeft(nudgeAttempts % 2 == 0);
            InputUtils.setRight(nudgeAttempts % 2 != 0);
            return;
        }

        InputUtils.releaseAll();
        targetLog = findBestBreakLog(client, targetBase, true);
        enterRotating(client);
    }

    private void tickWaitingCd(MinecraftClient client) {
        InputUtils.releaseAll();

        if (System.currentTimeMillis() >= cooldownUntil) {
            currentState = MacroState.SCANNING;
            scanCooldownTicks = 0;
            return;
        }

        List<BlockPos> treeBases = scanForTrees(client);
        if (treeBases.isEmpty()) return;

        targetBase = treeBases.get(0);
        targetLog = findBestBreakLog(client, targetBase, false);
        resetMovementMonitor();
        double distSq = playerDistSq(client, targetBase);

        if (distSq >= 4.5 * 4.5) {
            enterPathfinding(client);
        } else {
            enterRotating(client);
        }
    }

    // ────────────────────────────────── Tree scanning

    private List<BlockPos> scanForTrees(MinecraftClient client) {
        BlockPos playerPos = client.player.getBlockPos();
        int radius = (int) scanRadius.getValue();
        Set<Block> targetBlocks = getTargetLogBlocks();
        Set<BlockPos> seenBases = new HashSet<>();
        List<BlockPos> treeBases = new ArrayList<>();
        int matchedLogs = 0;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -radius; y <= radius; y++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    if (!targetBlocks.contains(state.getBlock())) continue;
                    matchedLogs++;
                    if (isBlacklisted(pos)) continue;

                    BlockPos base = findTreeBase(client, pos, targetBlocks);
                    if (base != null && !isBlacklisted(base) && seenBases.add(base)) {
                        treeBases.add(base);
                    }
                }
            }
        }

        lastScanMatchedLogs = matchedLogs;
        treeBases.sort(Comparator.comparingDouble(pos -> playerDistSq(client, pos)));
        return treeBases;
    }

    private BlockPos findTreeBase(MinecraftClient client, BlockPos logPos, Set<Block> targetBlocks) {
        BlockPos current = logPos;
        for (int i = 0; i < 40; i++) {
            BlockPos below = current.down();
            BlockState state = client.world.getBlockState(below);
            if (targetBlocks.contains(state.getBlock())) {
                current = below;
            } else {
                break;
            }
        }
        return current;
    }

    private boolean isLogBlock(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;
        return ALL_LOGS.contains(client.world.getBlockState(pos).getBlock());
    }

    private BlockPos findPathGoal(MinecraftClient client, BlockPos base) {
        if (base == null || client.world == null) return null;

        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 1; radius <= 3; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;

                    BlockPos candidate = base.add(x, -1, z);
                    for (int y = -2; y <= 2; y++) {
                        BlockPos ground = candidate.add(0, y, 0);
                        if (!isStandableGround(client, ground)) continue;
                        candidates.add(ground.toImmutable());
                    }
                }
            }
            if (!candidates.isEmpty()) break;
        }

        candidates.sort(Comparator.comparingDouble(pos -> playerDistSq(client, pos) + pos.getSquaredDistance(base) * 0.25));
        if (candidates.isEmpty()) return null;
        return candidates.get(Math.min(repositionAttempts, candidates.size() - 1));
    }

    private boolean isStandableGround(MinecraftClient client, BlockPos ground) {
        if (client.world == null) return false;
        if (client.world.getBlockState(ground).getCollisionShape(client.world, ground).isEmpty()) return false;
        return client.world.getBlockState(ground.up())
            .getCollisionShape(client.world, ground.up()).isEmpty()
            && client.world.getBlockState(ground.up(2))
            .getCollisionShape(client.world, ground.up(2)).isEmpty();
    }

    private BlockPos findBestBreakLog(MinecraftClient client, BlockPos base, boolean requireReach) {
        if (base == null || client.world == null || client.player == null) return null;

        Set<Block> targetBlocks = getTargetLogBlocks();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 10; y++) {
                    BlockPos pos = base.add(x, y, z);
                    if (!targetBlocks.contains(client.world.getBlockState(pos).getBlock())) continue;
                    if (requireReach && !canReachLog(client, pos)) continue;

                    double score = client.player.getEyePos().squaredDistanceTo(
                        pos.getX() + 0.5,
                        pos.getY() + 0.5,
                        pos.getZ() + 0.5
                    );
                    score += y * 0.15;
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean canReachLog(MinecraftClient client, BlockPos pos) {
        if (client.player == null) return false;
        return client.player.getEyePos().squaredDistanceTo(
            pos.getX() + 0.5,
            pos.getY() + 0.5,
            pos.getZ() + 0.5
        ) <= 5.2 * 5.2;
    }

    private void breakTarget(MinecraftClient client) {
        if (client.interactionManager == null || client.player == null || targetLog == null) return;
        if (!isCrosshairOnTreeLog(client)) return;

        targetLog = ((BlockHitResult) client.crosshairTarget).getBlockPos().toImmutable();
        Vec3d eyePos = client.player.getEyePos();
        Direction side = Direction.getFacing(
            targetLog.getX() + 0.5 - eyePos.x,
            targetLog.getY() + 0.5 - eyePos.y,
            targetLog.getZ() + 0.5 - eyePos.z
        ).getOpposite();
        if (!targetLog.equals(activeBreakLog)) {
            client.interactionManager.attackBlock(targetLog, side);
            activeBreakLog = targetLog.toImmutable();
        } else {
            client.interactionManager.updateBlockBreakingProgress(targetLog, side);
        }
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private void startBreakingTree(MinecraftClient client) {
        currentState = MacroState.BREAKING;
        breakStartTime = System.currentTimeMillis();
        activeBreakLog = null;
        breakTarget(client);
        InputUtils.setAttack(true);
    }

    private void skipCurrentTree() {
        if (targetBase != null) {
            addToBlacklist(targetBase, SKIP_BLACKLIST_DURATION_MS);
        }
        pathProcessor.stop();
        releaseRotation();
        InputUtils.releaseAll();
        targetBase = null;
        targetLog = null;
        activeBreakLog = null;
        resetMovementMonitor();
    }

    private void repositionOrSkip(MinecraftClient client) {
        if (targetBase == null || repositionAttempts >= 3) {
            skipCurrentTree();
            currentState = MacroState.SCANNING;
            return;
        }

        repositionAttempts++;
        nudgeAttempts = 0;
        nudgeTicks = 0;
        activeBreakLog = null;
        targetLog = findBestBreakLog(client, targetBase, false);
        releaseRotation();
        InputUtils.releaseAll();
        resetMovementMonitor();
        enterPathfinding(client);
    }

    private boolean isMacroStuck(MinecraftClient client) {
        if (client.player == null || targetBase == null) return false;

        double x = client.player.getX();
        double z = client.player.getZ();
        double distSq = playerDistSq(client, targetBase);

        if (distSq < bestTargetDistSq - 0.35) {
            bestTargetDistSq = distSq;
            macroStuckTicks = 0;
        } else {
            macroStuckTicks++;
        }

        if (!Double.isNaN(lastMoveX)) {
            double dx = x - lastMoveX;
            double dz = z - lastMoveZ;
            if (dx * dx + dz * dz < 0.0025) {
                macroStuckTicks++;
            } else {
                macroStuckTicks = Math.max(0, macroStuckTicks - 2);
            }
        }
        lastMoveX = x;
        lastMoveZ = z;
        return macroStuckTicks >= MACRO_STUCK_TICKS;
    }

    private void resetMovementMonitor() {
        lastMoveX = Double.NaN;
        lastMoveZ = Double.NaN;
        bestTargetDistSq = Double.MAX_VALUE;
        macroStuckTicks = 0;
    }

    private Set<Block> getTargetLogBlocks() {
        String mode = logType.getValue();
        return switch (mode) {
            case "Oak" -> Set.of(Blocks.OAK_LOG, Blocks.OAK_WOOD, Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_OAK_WOOD);
            case "Dark Oak" -> Set.of(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD, Blocks.STRIPPED_DARK_OAK_LOG, Blocks.STRIPPED_DARK_OAK_WOOD);
            case "Jungle" -> Set.of(Blocks.JUNGLE_LOG, Blocks.JUNGLE_WOOD, Blocks.STRIPPED_JUNGLE_LOG, Blocks.STRIPPED_JUNGLE_WOOD);
            case "Spruce" -> Set.of(Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD, Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_SPRUCE_WOOD);
            case "Birch" -> Set.of(Blocks.BIRCH_LOG, Blocks.BIRCH_WOOD, Blocks.STRIPPED_BIRCH_LOG, Blocks.STRIPPED_BIRCH_WOOD);
            case "Acacia" -> Set.of(Blocks.ACACIA_LOG, Blocks.ACACIA_WOOD, Blocks.STRIPPED_ACACIA_LOG, Blocks.STRIPPED_ACACIA_WOOD);
            case "Cherry" -> Set.of(Blocks.CHERRY_LOG, Blocks.CHERRY_WOOD, Blocks.STRIPPED_CHERRY_LOG, Blocks.STRIPPED_CHERRY_WOOD);
            case "Mangrove" -> Set.of(Blocks.MANGROVE_LOG, Blocks.MANGROVE_WOOD, Blocks.STRIPPED_MANGROVE_LOG, Blocks.STRIPPED_MANGROVE_WOOD);
            case "Pale Oak" -> Set.of(Blocks.PALE_OAK_LOG, Blocks.PALE_OAK_WOOD, Blocks.STRIPPED_PALE_OAK_LOG, Blocks.STRIPPED_PALE_OAK_WOOD);
            default -> ALL_LOGS;
        };
    }

    private void maybeSendGuiWaitMessage(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - lastGuiWaitMessage < 3000) return;
        lastGuiWaitMessage = now;
        client.player.sendMessage(Text.literal("\u00A77[Goat] Close the GUI to start scanning for " + logType.getValue() + " trees."), false);
    }

    private void maybeSendNoTargetMessage(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - lastNoTargetMessage < 5000) return;
        lastNoTargetMessage = now;
        client.player.sendMessage(Text.literal(
            "\u00A7e[Goat] No " + logType.getValue() + " trees found within " +
                (int) scanRadius.getValue() + " blocks. Matched logs: " + lastScanMatchedLogs + "."
        ), false);
    }

    // ────────────────────────────────── Rotation helpers

    private void aimAtTarget(MinecraftClient client) {
        Vec3d eyePos = client.player.getEyePos();
        float[] angles = RotationUtils.lookAt(
            eyePos.x, eyePos.y, eyePos.z,
            targetLog.getX() + 0.5, targetLog.getY() + 0.5, targetLog.getZ() + 0.5
        );
        rotation.setTarget(angles[0], angles[1]);
        rotation.setSpeed((float) rotSpeed.getValue());
        rotation.tick();
    }

    private void releaseRotation() {
        rotation.clear();
        RotationInterpolator.clearActive();
    }

    // ────────────────────────────────── Crosshair check

    private boolean isCrosshairOnTarget(MinecraftClient client, BlockPos target) {
        if (client.crosshairTarget == null) return false;
        if (client.crosshairTarget.getType() != HitResult.Type.BLOCK) return false;
        BlockHitResult hit = (BlockHitResult) client.crosshairTarget;
        return hit.getBlockPos().equals(target);
    }

    private boolean isCrosshairOnTreeLog(MinecraftClient client) {
        if (client.world == null || client.crosshairTarget == null) return false;
        if (client.crosshairTarget.getType() != HitResult.Type.BLOCK) return false;

        BlockPos hitPos = ((BlockHitResult) client.crosshairTarget).getBlockPos();
        if (!getTargetLogBlocks().contains(client.world.getBlockState(hitPos).getBlock())) return false;
        if (targetBase == null) return true;

        return Math.abs(hitPos.getX() - targetBase.getX()) <= 2
            && Math.abs(hitPos.getZ() - targetBase.getZ()) <= 2
            && hitPos.getY() >= targetBase.getY()
            && hitPos.getY() <= targetBase.getY() + 12;
    }

    // ────────────────────────────────── Blacklist

    private void addToBlacklist(BlockPos pos) {
        addToBlacklist(pos, BLACKLIST_DURATION_MS);
    }

    private void addToBlacklist(BlockPos pos, long durationMs) {
        if (pos == null) return;
        blacklist.put(pos.toImmutable(), System.currentTimeMillis() + durationMs);
    }

    private boolean isBlacklisted(BlockPos pos) {
        Long expiry = blacklist.get(pos);
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            blacklist.remove(pos);
            return false;
        }
        return true;
    }

    private void purgeExpiredBlacklist() {
        long now = System.currentTimeMillis();
        blacklist.entrySet().removeIf(e -> now >= e.getValue());
    }

    // ────────────────────────────────── Helpers

    private double playerDistSq(MinecraftClient client, BlockPos pos) {
        return client.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private PathfinderTest getPathfinderModule() {
        GoatModule mod = ModuleManager.findByName("Pathfinder");
        if (mod instanceof PathfinderTest pf) return pf;
        return null;
    }
}
