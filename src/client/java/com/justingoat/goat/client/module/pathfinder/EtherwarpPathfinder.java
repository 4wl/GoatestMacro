package com.justingoat.goat.client.module.pathfinder;

import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class EtherwarpPathfinder {

    public enum State { IDLE, SEARCHING, PREPARING, EXECUTING, DONE, FAILED }

    private State state = State.IDLE;
    private List<BlockPos> hopPositions;
    private List<float[]> hopAngles;
    private int currentHop;
    private int waitTicks;
    private int originalSlot = -1;
    private int retryCount = 0;
    private BlockPos goal;

    private static final int MAX_RETRIES = 5;
    private static final int PREPARE_TICKS = 3;
    private static final int WAIT_AFTER_HOP = 6;
    private static final int MAX_SEARCH_ITERATIONS = 50000;
    private static final float YAW_STEP = 5.0f;
    private static final float PITCH_STEP = 4.0f;
    private static final float YAW_SCAN_RANGE = 90.0f;
    private static final float PITCH_MIN = -55.0f;
    private static final float PITCH_MAX = 45.0f;

    // Render data
    private static volatile List<BlockPos> renderHops = null;
    private static volatile int renderCurrentHop = -1;

    public static List<BlockPos> getRenderHops() { return renderHops; }
    public static int getRenderCurrentHop() { return renderCurrentHop; }

    public State getState() { return state; }
    public boolean isActive() { return state != State.IDLE && state != State.DONE && state != State.FAILED; }

    public void findPath(BlockPos goal) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        if (findAotvSlot(client.player) < 0) {
            message("§c[Goat] No AOTV/AOTE in hotbar.");
            state = State.FAILED;
            return;
        }

        cancel();
        this.goal = goal;
        this.state = State.SEARCHING;
        this.retryCount = 0;

        message("§7[Goat] Searching etherwarp path to " + goal.toShortString() + "...");

        BlockPos start = getPlayerGround(client);
        if (start == null) {
            message("§c[Goat] Cannot determine standing position.");
            state = State.FAILED;
            return;
        }

        ClientWorld world = client.world;
        CompletableFuture.supplyAsync(() -> search(world, start, goal, MAX_SEARCH_ITERATIONS))
            .thenAccept(result -> client.execute(() -> onSearchComplete(result)));
    }

    public void cancel() {
        if (state == State.EXECUTING || state == State.PREPARING) {
            restoreState();
        }
        state = State.IDLE;
        hopPositions = null;
        hopAngles = null;
        goal = null;
        renderHops = null;
        renderCurrentHop = -1;
    }

    public void tick(MinecraftClient client) {
        if (client.player == null) return;

        switch (state) {
            case PREPARING -> tickPrepare(client);
            case EXECUTING -> tickExecute(client);
            default -> {}
        }
    }

    private void onSearchComplete(EtherwarpPath result) {
        if (state != State.SEARCHING) return;

        if (result == null) {
            if (retryCount < MAX_RETRIES) {
                retryCount++;
                message("§e[Goat] Etherwarp retry (" + retryCount + "/" + MAX_RETRIES + ")...");
                MinecraftClient client = MinecraftClient.getInstance();
                BlockPos start = getPlayerGround(client);
                if (start != null && client.world != null) {
                    ClientWorld world = client.world;
                    state = State.SEARCHING;
                    CompletableFuture.supplyAsync(() -> search(world, start, goal, MAX_SEARCH_ITERATIONS))
                        .thenAccept(r -> client.execute(() -> onSearchComplete(r)));
                    return;
                }
            }
            message("§c[Goat] No etherwarp path found.");
            state = State.FAILED;
            return;
        }

        this.hopPositions = result.positions;
        this.hopAngles = result.angles;
        this.currentHop = 0;
        this.waitTicks = PREPARE_TICKS;
        this.state = State.PREPARING;
        renderHops = result.positions;
        renderCurrentHop = 0;

        message("§a[Goat] Etherwarp path: " + result.positions.size() + " hops.");
    }

    private void tickPrepare(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) { fail("Player null"); return; }

        int slot = findAotvSlot(player);
        if (slot < 0) { fail("Lost AOTV/AOTE"); return; }

        if (originalSlot < 0) originalSlot = player.getInventory().getSelectedSlot();
        InputUtils.setHotbarSlot(slot);
        InputUtils.releaseAll();
        InputUtils.setSneak(true);

        waitTicks--;
        if (waitTicks <= 0) {
            state = State.EXECUTING;
            waitTicks = 0;
        }
    }

    private void tickExecute(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || hopPositions == null || hopAngles == null) {
            fail("Invalid state");
            return;
        }

        if (currentHop >= hopPositions.size()) {
            if (isAtGoal(player)) {
                message("§a[Goat] Etherwarp complete.");
                state = State.DONE;
            } else {
                fail("Did not reach destination");
            }
            restoreState();
            return;
        }

        // Waiting after a hop
        if (waitTicks > 0) {
            waitTicks--;
            InputUtils.setSneak(true);

            // Check if we arrived at current hop early
            if (currentHop > 0 && isAtPosition(player, hopPositions.get(currentHop - 1))) {
                waitTicks = 0;
            }
            return;
        }

        // Ensure AOTV held
        int slot = findAotvSlot(player);
        if (slot < 0) { fail("Lost AOTV/AOTE"); return; }
        InputUtils.setHotbarSlot(slot);
        InputUtils.setSneak(true);

        // Set rotation and click
        float[] angle = hopAngles.get(currentHop);
        applyInstantRotation(player, angle[0], angle[1]);
        InputUtils.setUse(true);

        renderCurrentHop = currentHop;
        currentHop++;
        waitTicks = WAIT_AFTER_HOP;

        // Release use next tick (via wait loop)
        MinecraftClient.getInstance().execute(() -> InputUtils.setUse(false));
    }

    private void fail(String reason) {
        message("§c[Goat] Etherwarp failed: " + reason);
        state = State.FAILED;
        restoreState();
    }

    private void restoreState() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && originalSlot >= 0 && originalSlot <= 8) {
            InputUtils.setHotbarSlot(originalSlot);
        }
        originalSlot = -1;
        InputUtils.setSneak(false);
        InputUtils.setUse(false);
        InputUtils.releaseAll();
        renderHops = null;
        renderCurrentHop = -1;
    }

    private boolean isAtGoal(ClientPlayerEntity player) {
        if (goal == null) return false;
        int px = MathHelper.floor(player.getX());
        int pz = MathHelper.floor(player.getZ());
        double yDelta = player.getY() - goal.getY() - 1.0;
        return px == goal.getX() && pz == goal.getZ() && yDelta >= -2 && yDelta <= 3;
    }

    private boolean isAtPosition(ClientPlayerEntity player, BlockPos pos) {
        int px = MathHelper.floor(player.getX());
        int pz = MathHelper.floor(player.getZ());
        double yDelta = player.getY() - pos.getY() - 1.0;
        return px == pos.getX() && pz == pos.getZ() && yDelta >= -2 && yDelta <= 3;
    }

    private static BlockPos getPlayerGround(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;
        BlockPos feet = client.player.getBlockPos();
        for (int dy = 0; dy >= -3; dy--) {
            BlockPos check = feet.add(0, dy - 1, 0);
            if (EtherwarpRaycast.isValidLanding(client.world, check)) return check;
        }
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos check = feet.add(0, dy - 1, 0);
            if (EtherwarpRaycast.isValidLanding(client.world, check)) return check;
        }
        return null;
    }

    private int findAotvSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString();
            if (name.contains("Aspect of the Void") || name.contains("Aspect of the End")) return i;
        }
        return -1;
    }

    private static final float SENSITIVITY = 0.25f;
    private static final float GCD;
    static {
        float f = SENSITIVITY * 0.6f + 0.2f;
        GCD = f * f * f * 8.0f;
    }

    private static void applyInstantRotation(ClientPlayerEntity player, float targetYaw, float targetPitch) {
        float yawDelta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float pitchDelta = targetPitch - player.getPitch();
        yawDelta = Math.round(yawDelta / GCD) * GCD;
        pitchDelta = Math.round(pitchDelta / GCD) * GCD;
        player.setYaw(player.getYaw() + yawDelta);
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchDelta, -90, 90));
    }

    private static void message(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg), false);
        }
    }

    // ─────────────────── A* Search ───────────────────

    static EtherwarpPath search(ClientWorld world, BlockPos start, BlockPos goal, int maxIterations) {
        PriorityQueue<ENode> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        Map<Long, Double> best = new HashMap<>();

        ENode startNode = new ENode(start, 0, heuristic(start, goal), null, 0, 0);
        open.add(startNode);
        best.put(start.asLong(), 0.0);

        int iterations = 0;

        while (!open.isEmpty() && iterations < maxIterations) {
            ENode current = open.poll();
            iterations++;

            Double b = best.get(current.pos.asLong());
            if (b != null && current.gCost > b + 0.001) continue;

            if (current.pos.equals(goal)) {
                return reconstructPath(world, current);
            }

            // Check if we're close enough (within 2 blocks horizontally)
            if (Math.abs(current.pos.getX() - goal.getX()) <= 1
                && Math.abs(current.pos.getZ() - goal.getZ()) <= 1
                && Math.abs(current.pos.getY() - goal.getY()) <= 2) {
                return reconstructPath(world, current);
            }

            double eyeX = EtherwarpRaycast.eyeX(current.pos);
            double eyeY = EtherwarpRaycast.eyeY(current.pos);
            double eyeZ = EtherwarpRaycast.eyeZ(current.pos);

            // Goal direction for focused scanning
            double gdx = goal.getX() - current.pos.getX();
            double gdz = goal.getZ() - current.pos.getZ();
            float goalYaw = (float) -Math.toDegrees(Math.atan2(gdx, gdz));

            Set<Long> seen = new HashSet<>();

            float yawStart = goalYaw - YAW_SCAN_RANGE;
            float yawEnd = goalYaw + YAW_SCAN_RANGE;

            for (float yaw = yawStart; yaw <= yawEnd; yaw += YAW_STEP) {
                for (float pitch = PITCH_MIN; pitch <= PITCH_MAX; pitch += PITCH_STEP) {
                    BlockPos landing = EtherwarpRaycast.findLanding(world, eyeX, eyeY, eyeZ, yaw, pitch);
                    if (landing == null || landing.equals(current.pos)) continue;
                    if (!seen.add(landing.asLong())) continue;

                    double newG = current.gCost + 1.0;
                    Double existing = best.get(landing.asLong());
                    if (existing != null && newG >= existing) continue;

                    best.put(landing.asLong(), newG);
                    double h = heuristic(landing, goal);
                    open.add(new ENode(landing, newG, newG + h, current, yaw, pitch));
                }
            }
        }

        return null;
    }

    private static EtherwarpPath reconstructPath(ClientWorld world, ENode end) {
        List<ENode> nodes = new ArrayList<>();
        ENode cur = end;
        while (cur.parent != null) {
            nodes.add(cur);
            cur = cur.parent;
        }
        Collections.reverse(nodes);

        List<BlockPos> positions = new ArrayList<>();
        List<float[]> angles = new ArrayList<>();

        // Re-compute precise angles for each hop
        BlockPos prevPos = cur.pos; // start position
        for (ENode node : nodes) {
            double eyeX = EtherwarpRaycast.eyeX(prevPos);
            double eyeY = EtherwarpRaycast.eyeY(prevPos);
            double eyeZ = EtherwarpRaycast.eyeZ(prevPos);

            float[] precise = EtherwarpRaycast.findPreciseAngle(world, eyeX, eyeY, eyeZ, node.pos);
            if (precise != null) {
                positions.add(node.pos);
                angles.add(precise);
            } else {
                positions.add(node.pos);
                angles.add(new float[]{node.yaw, node.pitch});
            }
            prevPos = node.pos;
        }

        return new EtherwarpPath(positions, angles);
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist / EtherwarpRaycast.MAX_RANGE;
    }

    static class EtherwarpPath {
        final List<BlockPos> positions;
        final List<float[]> angles;

        EtherwarpPath(List<BlockPos> positions, List<float[]> angles) {
            this.positions = positions;
            this.angles = angles;
        }
    }

    private static class ENode {
        final BlockPos pos;
        final double gCost;
        final double fCost;
        final ENode parent;
        final float yaw;
        final float pitch;

        ENode(BlockPos pos, double gCost, double fCost, ENode parent, float yaw, float pitch) {
            this.pos = pos;
            this.gCost = gCost;
            this.fCost = fCost;
            this.parent = parent;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
