package com.justingoat.goat.client.module.mining;

import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.BlockScanner;
import com.justingoat.goat.client.utils.RotationUtils;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.WorldUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class MiningBot {

    public enum State { WAITING, ABILITY, MINING }

    private static final double MINE_REACH = 4.5;
    private static final double APPROACH_SCAN_REACH = 8.0;
    private static final int BFS_PAD = 1;
    private static final int REACHABLE_EVAL_BUDGET = 40;
    private static final int REACHABLE_VISIBLE_STOP = 8;
    private static final int REACHABLE_VISIBLE_BUDGET = 12;
    private static final int APPROACH_BUDGET = 5;
    private static final double FACE_REACH = 4.8;

    private static final double STRAFE_THRESHOLD = 15.0;
    private static final double STOP_YAW_THRESHOLD = 10.0;
    private static final double MOVE_IN_MIN = 1.5;
    private static final double MOVE_IN_MAX = 3.0;
    private static final double UNSNEAK_LARGE_MOVE = 5.5;
    private static final double UNSNEAK_DROP_Y = 0.5;
    private static final int STUCK_TIMEOUT_TICKS = 60;
    private static final int STUCK_SKIP_TICKS = 120;
    private static final int BEDROCK_COOLDOWN_TICKS = 5;

    private static final double[][] VISIBILITY_OFFSETS = {
        {0.3, 0, 0}, {-0.3, 0, 0}, {0, 0, 0.3}, {0, 0, -0.3}
    };
    private static final int VISIBILITY_SAMPLE_COUNT = 5;

    private State state = State.WAITING;
    private boolean enabled = false;
    private boolean movement = true;
    private boolean fovPenalty = true;
    private boolean prioritizeTitanium = false;
    private boolean prioritizeGrayMithril = false;
    private boolean tickGlide = true;
    private int additionalLagComp = 0;

    private Map<String, Integer> costMap = new HashMap<>();
    private List<MiningTarget> foundLocations = new ArrayList<>();
    private MiningTarget currentTarget = null;
    private int lowestCostBlockIndex = 0;
    private boolean allowScan = true;

    private BlockPos lastBlockPos = null;
    private String lastBlockType = null;
    private int tickCount = 0;
    private int mineTickCount = 0;
    private int totalTicks = 0;
    private int sameBlockTicks = 0;
    private final Set<Long> bedrockBlacklist = new HashSet<>();
    private final Map<Long, Integer> skippedTargets = new HashMap<>();
    private int bedrockCooldown = 0;

    private RotationUtils rotationHelper;

    // ── Mithril cost map ──
    public static final Map<String, Integer> MITHRIL_COSTS = new LinkedHashMap<>();
    static {
        MITHRIL_COSTS.put("minecraft:gray_wool", 3);
        MITHRIL_COSTS.put("minecraft:light_blue_wool", 4);
        MITHRIL_COSTS.put("minecraft:cyan_wool", 5);
        MITHRIL_COSTS.put("minecraft:prismarine", 2);
    }

    public static final Map<String, Integer> GEMSTONE_COSTS = new LinkedHashMap<>();
    static {
        GEMSTONE_COSTS.put("minecraft:orange_stained_glass", 4);
        GEMSTONE_COSTS.put("minecraft:orange_stained_glass_pane", 4);
        GEMSTONE_COSTS.put("minecraft:purple_stained_glass", 4);
        GEMSTONE_COSTS.put("minecraft:purple_stained_glass_pane", 4);
        GEMSTONE_COSTS.put("minecraft:lime_stained_glass", 4);
        GEMSTONE_COSTS.put("minecraft:lime_stained_glass_pane", 4);
        GEMSTONE_COSTS.put("minecraft:magenta_stained_glass", 4);
        GEMSTONE_COSTS.put("minecraft:magenta_stained_glass_pane", 4);
        GEMSTONE_COSTS.put("minecraft:red_stained_glass", 4);
        GEMSTONE_COSTS.put("minecraft:red_stained_glass_pane", 4);
        GEMSTONE_COSTS.put("minecraft:light_blue_stained_glass", 4);
        GEMSTONE_COSTS.put("minecraft:light_blue_stained_glass_pane", 4);
        GEMSTONE_COSTS.put("minecraft:yellow_stained_glass", 4);
        GEMSTONE_COSTS.put("minecraft:yellow_stained_glass_pane", 4);
    }

    public static final Map<String, Integer> ORE_COSTS = new LinkedHashMap<>();
    static {
        ORE_COSTS.put("minecraft:coal_block", 1);
        ORE_COSTS.put("minecraft:quartz_block", 1);
        ORE_COSTS.put("minecraft:iron_block", 1);
        ORE_COSTS.put("minecraft:redstone_block", 1);
        ORE_COSTS.put("minecraft:gold_block", 1);
        ORE_COSTS.put("minecraft:diamond_block", 1);
        ORE_COSTS.put("minecraft:lapis_block", 1);
        ORE_COSTS.put("minecraft:emerald_block", 1);
    }

    public MiningBot() {
    }

    public void setRotationHelper(RotationUtils rotHelper) {
        this.rotationHelper = rotHelper;
    }

    public boolean isEnabled() { return enabled; }
    public State getState() { return state; }
    public MiningTarget getCurrentTarget() { return currentTarget; }
    public List<MiningTarget> getFoundLocations() { return foundLocations; }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (enabled) {
            state = State.ABILITY;
            allowScan = true;
            fovPenalty = true;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && rotationHelper != null) {
                rotationHelper.init(client.player.getYaw(), client.player.getPitch());
                RotationInterpolator.setActive(rotationHelper);
            }
        } else {
            state = State.WAITING;
            if (rotationHelper != null) {
                rotationHelper.clear();
                RotationInterpolator.clearActive();
            }
            InputUtils.releaseAll();
        }
    }

    public void setMovement(boolean movement) { this.movement = movement; }
    public void setFovPenalty(boolean fovPenalty) { this.fovPenalty = fovPenalty; }
    public void setPrioritizeTitanium(boolean v) { this.prioritizeTitanium = v; }
    public void setPrioritizeGrayMithril(boolean v) { this.prioritizeGrayMithril = v; }
    public void setCost(Map<String, Integer> cost) { if (cost != null) this.costMap = cost; }

    public void tick(MinecraftClient client) {
        if (!enabled || client.player == null || client.world == null) return;

        switch (state) {
            case ABILITY -> handleAbility(client);
            case MINING -> handleMining(client);
            default -> {}
        }
    }

    private void handleAbility(MinecraftClient client) {
        state = State.MINING;
    }

    private void handleMining(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) return;

        tickCount++;
        if (bedrockCooldown > 0) bedrockCooldown--;
        tickSkippedTargets();
        bedrockBlacklist.removeIf(pos -> {
            BlockPos bp = BlockPos.fromLong(pos);
            String id = getBlockId(world, bp);
            return id == null || !id.contains("bedrock");
        });

        if (shouldScanForNewBlock(world)) {
            scanForBlock(world, player);
            allowScan = false;
        }

        MiningTarget target = currentTarget != null ? currentTarget :
                (lowestCostBlockIndex < foundLocations.size() ? foundLocations.get(lowestCostBlockIndex) : null);

        if (target == null) {
            stopMining();
            return;
        }

        String blockId = getBlockId(world, target.pos);
        if (blockId == null || blockId.contains("air") || blockId.contains("bedrock")) {
            if (blockId != null && blockId.contains("bedrock")) {
                bedrockBlacklist.add(target.pos.asLong());
            }
            skippedTargets.remove(target.pos.asLong());
            currentTarget = null;
            allowScan = true;
            scanForBlock(world, player);
            return;
        }

        if (!updateBlockTracking(target, blockId)) return;
        currentTarget = target;

        sameBlockTicks++;
        if (sameBlockTicks > STUCK_TIMEOUT_TICKS) {
            skipCurrentTarget(world, player);
            return;
        }

        boolean isApproach = target.targetMode == MiningTarget.TargetMode.APPROACH;
        if (isApproach && !movement) {
            currentTarget = null;
            foundLocations.clear();
            lowestCostBlockIndex = 0;
            return;
        }

        boolean hasAim = refreshAimPoint(world, player);
        if (!hasAim) {
            if (isApproach && movement) {
                allowScan = true;
                handleVeinMovement(player);
                rotateToTarget(player);
                return;
            }
            skipCurrentTarget(world, player);
            return;
        }

        if (movement) {
            handleVeinMovement(player);
        }

        incrementMiningCounters(player);

        InputUtils.setAttack(true);

        if (shouldGlideToNext(world, blockId)) {
            resetTickCounters();
            scanForBlock(world, player);
        }

        rotateToTarget(player);
    }

    private boolean shouldScanForNewBlock(ClientWorld world) {
        if (currentTarget == null || allowScan) return true;
        String id = getBlockId(world, currentTarget.pos);
        return id == null || id.contains("air") || id.contains("bedrock");
    }

    public void scanForBlock(ClientWorld world, ClientPlayerEntity player) {
        if (costMap == null || costMap.isEmpty()) return;

        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0f);
        BlockPos start = player.getBlockPos();

        double mineReachSq = MINE_REACH * MINE_REACH;
        double reach = MINE_REACH + BFS_PAD;
        double reachSq = reach * reach;

        List<BfsCandidate> reachable = new ArrayList<>();
        List<MiningTarget> approach = new ArrayList<>();

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(start);
        visited.add(start.asLong());

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();

            String blockId = getBlockId(world, pos);
            Integer targetCost = blockId != null ? costMap.get(blockId) : null;

            if (targetCost != null && targetCost > 0
                    && !bedrockBlacklist.contains(pos.asLong())
                    && !isSkipped(pos)
                    && RaytraceUtils.hasExposedFace(world, pos)) {
                double dx = pos.getX() + 0.5 - eyePos.x;
                double dy = pos.getY() + 0.5 - eyePos.y;
                double dz = pos.getZ() + 0.5 - eyePos.z;
                double distSq = dx * dx + dy * dy + dz * dz;

                double candidateReachSq = (MINE_REACH + BFS_PAD) * (MINE_REACH + BFS_PAD);
                if (distSq <= candidateReachSq) {
                    double dist = Math.sqrt(distSq);
                    double dot = dist > 0 ? (dx * lookVec.x + dy * lookVec.y + dz * lookVec.z) / dist : 1;
                    double cheapCost = calculateBlockCost(targetCost, dist, dot);
                    reachable.add(new BfsCandidate(pos, cheapCost, blockId, targetCost));
                }

                if (movement && distSq <= APPROACH_SCAN_REACH * APPROACH_SCAN_REACH) {
                    double dist = Math.sqrt(distSq);
                    double approachCost = targetCost + dist * 2;
                    if (approach.size() < APPROACH_BUDGET) {
                        approach.add(new MiningTarget(pos, approachCost, blockId, MiningTarget.TargetMode.APPROACH));
                    }
                }
            }

            for (int i = 0; i < 6; i++) {
                BlockPos next = pos.offset(net.minecraft.util.math.Direction.values()[i]);
                double ndx = next.getX() + 0.5 - eyePos.x;
                double ndy = next.getY() + 0.5 - eyePos.y;
                double ndz = next.getZ() + 0.5 - eyePos.z;
                if (ndx * ndx + ndy * ndy + ndz * ndz <= reachSq && visited.add(next.asLong())) {
                    queue.add(next);
                }
            }
        }

        List<MiningTarget> visible = evaluateCandidates(world, reachable, eyePos, lookVec, mineReachSq);

        if (visible.isEmpty() && !approach.isEmpty()) {
            approach.sort(Comparator.comparingDouble(t -> t.cost));
            visible = approach;
        }

        if (!visible.isEmpty()) {
            foundLocations = visible;
            currentTarget = foundLocations.get(0);
            lowestCostBlockIndex = 0;
        } else {
            currentTarget = null;
            foundLocations.clear();
            lowestCostBlockIndex = 0;
        }
    }

    private List<MiningTarget> evaluateCandidates(ClientWorld world, List<BfsCandidate> candidates,
                                                   Vec3d eyePos, Vec3d lookVec, double maxReachSq) {
        if (candidates.isEmpty()) return new ArrayList<>();
        candidates.sort(Comparator.comparingDouble(c -> c.cheapCost));

        List<MiningTarget> results = new ArrayList<>();
        int evaluated = 0;

        for (BfsCandidate c : candidates) {
            if (evaluated >= REACHABLE_EVAL_BUDGET && results.size() >= REACHABLE_VISIBLE_STOP) break;
            evaluated++;

            double[] aim = RaytraceUtils.findVisibleAimPoint(world,
                    c.pos.getX(), c.pos.getY(), c.pos.getZ(),
                    eyePos, lookVec, maxReachSq, fovPenalty);
            if (aim == null) continue;

            double baseCost = calculateBlockCost(c.targetCost, aim[3], aim[4]);
            double stability = calculateVisibilityStability(world, c.pos, eyePos, maxReachSq);
            double cost = baseCost + (1.0 - stability) * 18.0;

            MiningTarget t = new MiningTarget(c.pos, cost, c.blockId, MiningTarget.TargetMode.REACHABLE);
            t.withAim(aim[0], aim[1], aim[2], aim[3]);

            insertSorted(results, t, REACHABLE_VISIBLE_BUDGET);
        }
        return results;
    }

    private double calculateVisibilityStability(ClientWorld world, BlockPos pos, Vec3d eyePos, double maxReachSq) {
        int visible = 1;
        for (double[] off : VISIBILITY_OFFSETS) {
            Vec3d sampleEye = new Vec3d(eyePos.x + off[0], eyePos.y, eyePos.z + off[2]);
            double[] aim = RaytraceUtils.findVisibleAimPoint(world,
                    pos.getX(), pos.getY(), pos.getZ(), sampleEye, null, maxReachSq, false);
            if (aim != null) visible++;
        }
        return (double) visible / VISIBILITY_SAMPLE_COUNT;
    }

    private boolean refreshAimPoint(ClientWorld world, ClientPlayerEntity player) {
        if (currentTarget == null) return false;
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0f);
        double faceReachSq = FACE_REACH * FACE_REACH;

        double[] aim = RaytraceUtils.findVisibleAimPoint(world,
                currentTarget.pos.getX(), currentTarget.pos.getY(), currentTarget.pos.getZ(),
                eyePos, lookVec, faceReachSq, false);
        if (aim == null) return false;

        currentTarget.aimX = aim[0];
        currentTarget.aimY = aim[1];
        currentTarget.aimZ = aim[2];
        currentTarget.dist = aim[3];
        currentTarget.targetMode = MiningTarget.TargetMode.REACHABLE;
        return true;
    }

    private void rotateToTarget(ClientPlayerEntity player) {
        if (currentTarget == null || rotationHelper == null) return;
        Vec3d eye = player.getEyePos();
        float[] look = RotationUtils.lookAt(eye.x, eye.y, eye.z, currentTarget.aimX, currentTarget.aimY, currentTarget.aimZ);
        rotationHelper.setTarget(look[0], look[1]);
        rotationHelper.tick();
    }

    private void handleVeinMovement(ClientPlayerEntity player) {
        if (!movement || currentTarget == null) {
            InputUtils.setForward(false);
            InputUtils.setBack(false);
            InputUtils.setLeft(false);
            InputUtils.setRight(false);
            InputUtils.setJump(false);
            return;
        }

        if (isTargetDirectlyUnder(player) || isTargetAbove(player)) {
            InputUtils.setForward(false);
            InputUtils.setBack(false);
            InputUtils.setLeft(false);
            InputUtils.setRight(false);
            InputUtils.setSneak(true);
            InputUtils.setJump(false);
            return;
        }

        double dx = currentTarget.aimX - player.getX();
        double dz = currentTarget.aimZ - player.getZ();
        double distFlat = Math.sqrt(dx * dx + dz * dz);
        double dy = currentTarget.aimY - player.getEyePos().y;
        double dist3d = Math.sqrt(dx * dx + dy * dy + dz * dz);

        float targetYaw = WorldUtils.yawTo(new Vec3d(player.getX(), player.getY(), player.getZ()),
                new Vec3d(currentTarget.aimX, player.getY(), currentTarget.aimZ));
        float yawDelta = MathHelper.wrapDegrees(targetYaw - player.getYaw());

        boolean moveRight = yawDelta > STRAFE_THRESHOLD;
        boolean moveLeft = yawDelta < -STRAFE_THRESHOLD;
        boolean moveForward = distFlat > MOVE_IN_MAX;
        boolean moveBack = distFlat < MOVE_IN_MIN;

        boolean aligned = yawDelta >= -STOP_YAW_THRESHOLD && yawDelta <= STOP_YAW_THRESHOLD && dist3d <= 4;
        boolean inBand = distFlat >= 2.5 && distFlat <= 3.25;
        if (aligned || inBand) {
            moveRight = false;
            moveLeft = false;
            moveForward = false;
            moveBack = false;
        }

        InputUtils.setRight(moveRight);
        InputUtils.setLeft(moveLeft);
        InputUtils.setForward(moveForward);
        InputUtils.setBack(moveBack);

        boolean isMoving = moveRight || moveLeft || moveForward || moveBack;
        double playerFeetY = player.getY();
        double targetTopY = currentTarget.pos.getY() + 1;
        double dropAmount = playerFeetY - targetTopY;
        boolean requiresDrop = dropAmount > UNSNEAK_DROP_Y && distFlat > 0.35;
        boolean requiresLargeMove = distFlat > UNSNEAK_LARGE_MOVE;
        boolean shouldUnsneak = isMoving && (requiresDrop || requiresLargeMove);
        InputUtils.setSneak(!shouldUnsneak);

        boolean blockedForward = moveForward && hasForwardObstacle(player);
        boolean shouldJump = player.isOnGround() && blockedForward;
        InputUtils.setJump(shouldJump);
    }

    private boolean isTargetDirectlyUnder(ClientPlayerEntity player) {
        if (currentTarget == null) return false;
        int px = MathHelper.floor(player.getX());
        int py = MathHelper.floor(player.getY());
        int pz = MathHelper.floor(player.getZ());
        return currentTarget.pos.getX() == px && currentTarget.pos.getZ() == pz
                && currentTarget.pos.getY() <= py - 1;
    }

    private boolean isTargetAbove(ClientPlayerEntity player) {
        if (currentTarget == null) return false;
        double dx = currentTarget.aimX - player.getX();
        double dy = currentTarget.aimY - player.getEyePos().y;
        double dz = currentTarget.aimZ - player.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        if (horizDist < 0.001) return dy > 0;
        float[] look = RotationUtils.lookAt(
                player.getX(), player.getEyeY(), player.getZ(),
                currentTarget.aimX, currentTarget.aimY, currentTarget.aimZ
        );
        return look[1] <= -60;
    }

    private boolean hasForwardObstacle(ClientPlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return false;
        Vec3d look = player.getRotationVec(1.0f);
        double fx = player.getX() + look.x * 0.8;
        double fz = player.getZ() + look.z * 0.8;
        int fy = MathHelper.floor(player.getY());
        BlockPos feet = BlockPos.ofFloored(fx, fy, fz);
        BlockPos head = feet.up();
        return BlockScanner.isSolid(client.world, feet) || BlockScanner.isSolid(client.world, head);
    }

    private boolean isSolid(ClientWorld world, BlockPos pos) {
        return BlockScanner.isSolid(world, pos);
    }

    private boolean updateBlockTracking(MiningTarget target, String blockId) {
        boolean same = lastBlockPos != null
                && lastBlockPos.getX() == target.pos.getX()
                && lastBlockPos.getY() == target.pos.getY()
                && lastBlockPos.getZ() == target.pos.getZ();

        if (same && lastBlockType != null && !lastBlockType.equals(blockId)) {
            if (!blockId.contains("air") && !blockId.contains("bedrock")) {
                skippedTargets.remove(target.pos.asLong());
                lastBlockType = blockId;
                resetTickCounters();
                return false;
            }
        }

        if (!same) {
            resetTickCounters();
            sameBlockTicks = 0;
            lastBlockPos = target.pos;
            lastBlockType = blockId;
        }
        return true;
    }

    private void skipCurrentTarget(ClientWorld world, ClientPlayerEntity player) {
        if (currentTarget != null) {
            skippedTargets.put(currentTarget.pos.asLong(), STUCK_SKIP_TICKS);
            foundLocations.removeIf(t -> t.pos.equals(currentTarget.pos));
        }
        currentTarget = null;
        sameBlockTicks = 0;
        allowScan = true;
        scanForBlock(world, player);
    }

    private boolean isSkipped(BlockPos pos) {
        Integer ticks = skippedTargets.get(pos.asLong());
        return ticks != null && ticks > 0;
    }

    private void tickSkippedTargets() {
        skippedTargets.replaceAll((pos, ticks) -> ticks - 1);
        skippedTargets.entrySet().removeIf(entry -> entry.getValue() <= 0);
    }

    private void incrementMiningCounters(ClientPlayerEntity player) {
        mineTickCount++;
    }

    private boolean shouldGlideToNext(ClientWorld world, String blockId) {
        if (blockId.contains("air") || blockId.contains("bedrock")) return true;
        if (!tickGlide) return allowScan;
        if (totalTicks <= 0) return sameBlockTicks >= STUCK_TIMEOUT_TICKS;
        return mineTickCount >= totalTicks || tickCount > totalTicks * 2 || allowScan;
    }

    private void stopMining() {
        InputUtils.setAttack(false);
        if (movement) {
            InputUtils.setForward(false);
            InputUtils.setBack(false);
            InputUtils.setLeft(false);
            InputUtils.setRight(false);
            InputUtils.setJump(false);
        }
    }

    private void resetTickCounters() {
        tickCount = 0;
        mineTickCount = 0;
    }

    private static double calculateBlockCost(double baseCost, double distance, double dotProduct) {
        return baseCost + distance * 2 - dotProduct * 50;
    }

    private static void insertSorted(List<MiningTarget> list, MiningTarget candidate, int maxCount) {
        int insertAt = list.size();
        while (insertAt > 0 && list.get(insertAt - 1).cost > candidate.cost) insertAt--;
        if (insertAt >= maxCount) return;
        list.add(insertAt, candidate);
        if (list.size() > maxCount) list.remove(list.size() - 1);
    }

    private static String getBlockId(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return "minecraft:air";
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        return id.toString();
    }

    public void stop() {
        enabled = false;
        state = State.WAITING;
        currentTarget = null;
        foundLocations.clear();
        lowestCostBlockIndex = 0;
        allowScan = true;
        lastBlockPos = null;
        lastBlockType = null;
        sameBlockTicks = 0;
        bedrockBlacklist.clear();
        skippedTargets.clear();
        bedrockCooldown = 0;
        resetTickCounters();
        if (rotationHelper != null) {
            rotationHelper.clear();
            RotationInterpolator.clearActive();
        }
        InputUtils.releaseAll();
    }

    private static class BfsCandidate {
        final BlockPos pos;
        final double cheapCost;
        final String blockId;
        final int targetCost;

        BfsCandidate(BlockPos pos, double cheapCost, String blockId, int targetCost) {
            this.pos = pos;
            this.cheapCost = cheapCost;
            this.blockId = blockId;
            this.targetCost = targetCost;
        }
    }
}
