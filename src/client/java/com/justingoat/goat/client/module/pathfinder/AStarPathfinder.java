package com.justingoat.goat.client.module.pathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.PriorityQueue;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.WorldChunk;

public class AStarPathfinder {

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
    private static final long PATH_CACHE_TTL_MS = 5_000L;
    private static final int PATH_CACHE_MAX_ENTRIES = 64;
    private static final LinkedHashMap<PathCacheKey, CachedPath> PATH_CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<PathCacheKey, CachedPath> eldest) {
            return size() > PATH_CACHE_MAX_ENTRIES;
        }
    };

    public static List<PathNode> computePath(BlockPos start, BlockPos end, int maxNodes) {
        return computePath(start, end, maxNodes, 3);
    }

    public static List<PathNode> computePath(BlockPos start, BlockPos end, int maxNodes, int maxDrop) {
        return computePath(start, end, maxNodes, maxDrop, false);
    }

    public static List<PathNode> computePath(BlockPos start, BlockPos end, int maxNodes, int maxDrop, boolean allowWater) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;

        SearchConfig config = new SearchConfig(maxDrop, allowWater, new BlockCache());
        start = snapToGround(client, config, start);
        end = snapToGround(client, config, end);
        if (start == null || end == null) return null;

        PathCacheKey cacheKey = new PathCacheKey(start.asLong(), end.asLong(), maxNodes, maxDrop, allowWater);
        List<PathNode> cached = getCachedPath(cacheKey);
        if (cached != null) return cached;

        PriorityQueue<PathNode> openSet =
            new PriorityQueue<>(Comparator.comparingDouble(PathNode::getFCost));
        Long2DoubleOpenHashMap bestCost = new Long2DoubleOpenHashMap();
        bestCost.defaultReturnValue(Double.POSITIVE_INFINITY);

        PathNode startNode = new PathNode(start, PathNode.MoveType.WALK);
        startNode.setGCost(0);
        startNode.setHCost(heuristic(start, end));
        openSet.add(startNode);
        bestCost.put(start.asLong(), 0.0);

        int evaluated = 0;

        while (!openSet.isEmpty() && evaluated < maxNodes) {
            PathNode current = openSet.poll();

            double best = bestCost.get(current.getPos().asLong());
            if (current.getGCost() > best) continue;

            if (isGoal(current.getPos(), end)) {
                List<PathNode> path = reconstructPath(current);
                putCachedPath(cacheKey, path);
                return path;
            }

            for (PathNode neighbor : getNeighbors(current, client, config)) {
                double newG = current.getGCost() + neighbor.getMoveCost();
                long neighborKey = neighbor.getPos().asLong();
                double existingG = bestCost.get(neighborKey);

                if (newG < existingG) {
                    bestCost.put(neighborKey, newG);
                    neighbor.setParent(current);
                    neighbor.setGCost(newG);
                    neighbor.setHCost(heuristic(neighbor.getPos(), end));
                    openSet.add(neighbor);
                }
            }
            evaluated++;
        }

        return null;
    }

    private record SearchConfig(int maxDropDistance, boolean allowWater, BlockCache blockCache) {}
    private record PathCacheKey(long start, long end, int maxNodes, int maxDrop, boolean allowWater) {}
    private record CachedPath(List<PathNode> path, long createdAtMs) {}
    private record BlockCacheEntry(boolean isSolid, boolean isPassable, boolean isWater, boolean isLava,
                                   double collisionMaxY,
                                   boolean isClimbable, boolean isInteractable) {}

    private static List<PathNode> getCachedPath(PathCacheKey key) {
        synchronized (PATH_CACHE) {
            CachedPath cached = PATH_CACHE.get(key);
            if (cached == null) return null;
            if (System.currentTimeMillis() - cached.createdAtMs() > PATH_CACHE_TTL_MS) {
                PATH_CACHE.remove(key);
                return null;
            }
            return copyPath(cached.path());
        }
    }

    private static void putCachedPath(PathCacheKey key, List<PathNode> path) {
        synchronized (PATH_CACHE) {
            PATH_CACHE.put(key, new CachedPath(copyPath(path), System.currentTimeMillis()));
        }
    }

    private static List<PathNode> copyPath(List<PathNode> path) {
        List<PathNode> copy = new ArrayList<>(path.size());
        PathNode previous = null;
        for (PathNode node : path) {
            PathNode cloned = new PathNode(node.getPos(), node.getMoveType());
            cloned.setGCost(node.getGCost());
            cloned.setHCost(node.getHCost());
            cloned.setMoveCost(node.getMoveCost());
            cloned.setParent(previous);
            copy.add(cloned);
            previous = cloned;
        }
        return copy;
    }

    private static boolean isGoal(BlockPos pos, BlockPos end) {
        return pos.getX() == end.getX()
            && pos.getZ() == end.getZ()
            && Math.abs(pos.getY() - end.getY()) <= 1;
    }

    private static BlockPos snapToGround(MinecraftClient client, SearchConfig config, BlockPos pos) {
        if (!isChunkLoaded(client, pos)) return null;
        if (isStandableGround(client, config, pos) && isSafePassable(client, config, pos.up(1))) return pos;

        BlockPos probe = pos;
        for (int i = 0; i < 5; i++) {
            probe = probe.down();
            if (!isChunkLoaded(client, probe)) return null;
            if (isStandableGround(client, config, probe) && isSafePassable(client, config, probe.up(1))) return probe;
        }
        probe = pos;
        for (int i = 0; i < 3; i++) {
            probe = probe.up();
            if (!isChunkLoaded(client, probe)) return null;
            if (isStandableGround(client, config, probe) && isSafePassable(client, config, probe.up(1))) return probe;
        }
        return null;
    }

    private static List<PathNode> getNeighbors(PathNode current, MinecraftClient client, SearchConfig config) {
        List<PathNode> out = new ArrayList<>();
        BlockPos pos = current.getPos();

        for (int[] d : CARDINALS) {
            tryWalk(out, client, config, pos, d[0], d[1]);
            tryStepUp(out, client, config, pos, d[0], d[1]);
            tryDrop(out, client, config, pos, d[0], d[1]);
            tryJumpAcross(out, client, config, pos, d[0], d[1]);
            trySprintJump(out, client, config, pos, d[0], d[1]);
            trySwim(out, client, config, pos, d[0], 0, d[1]);
        }
        for (int[] d : DIAGONALS) {
            tryWalkDiag(out, client, config, pos, d[0], d[1]);
            tryDropDiag(out, client, config, pos, d[0], d[1]);
            trySwim(out, client, config, pos, d[0], 0, d[1]);
        }
        trySwim(out, client, config, pos, 0, 1, 0);
        trySwim(out, client, config, pos, 0, -1, 0);

        tryClimb(out, client, config, pos);

        return out;
    }

    // ---- WALK (same level, cardinal) ----
    private static void tryWalk(List<PathNode> out, MinecraftClient c, SearchConfig config,
                                 BlockPos pos, int dx, int dz) {
        BlockPos t = pos.add(dx, 0, dz);
        if (!isChunkLoaded(c, t)) return;
        if (!isStandableGround(c, config, t)) return;
        if (!isSafePassable(c, config, t.up(1)) || !isSafePassable(c, config, t.up(2))) return;

        PathNode n = new PathNode(t, PathNode.MoveType.WALK);
        n.setMoveCost(1.0 + interactionPenalty(c, config, t.up(1), t.up(2)));
        out.add(n);
    }

    // ---- WALK (same level, diagonal with corner check) ----
    private static void tryWalkDiag(List<PathNode> out, MinecraftClient c, SearchConfig config,
                                     BlockPos pos, int dx, int dz) {
        BlockPos t = pos.add(dx, 0, dz);
        if (!isChunkLoaded(c, t)) return;
        if (!isStandableGround(c, config, t)) return;
        if (!isSafePassable(c, config, t.up(1)) || !isSafePassable(c, config, t.up(2))) return;

        if (!isSafePassable(c, config, pos.add(dx, 1, 0)) || !isSafePassable(c, config, pos.add(dx, 2, 0))) return;
        if (!isSafePassable(c, config, pos.add(0, 1, dz)) || !isSafePassable(c, config, pos.add(0, 2, dz))) return;

        PathNode n = new PathNode(t, PathNode.MoveType.WALK);
        n.setMoveCost(1.414 + interactionPenalty(c, config, t.up(1), t.up(2)));
        out.add(n);
    }

    // ---- STEP UP (Y+1, cardinal only) ----
    private static void tryStepUp(List<PathNode> out, MinecraftClient c, SearchConfig config,
                                   BlockPos pos, int dx, int dz) {
        BlockPos t = pos.add(dx, 1, dz);
        if (!isChunkLoaded(c, t)) return;
        if (!isStandableGround(c, config, t)) return;
        if (!isSafePassable(c, config, pos.up(3))) return;
        if (!isSafePassable(c, config, pos.add(dx, 2, dz))) return;
        if (!isSafePassable(c, config, t.up(1)) || !isSafePassable(c, config, t.up(2))) return;

        PathNode n = new PathNode(t, PathNode.MoveType.STEP_UP);
        n.setMoveCost(2.0 + interactionPenalty(c, config, t.up(1), t.up(2)));
        out.add(n);
    }

    // ---- DROP (Y-1..Y-maxDrop, cardinal) ----
    private static void tryDrop(List<PathNode> out, MinecraftClient c, SearchConfig config,
                                 BlockPos pos, int dx, int dz) {
        if (!isSafePassable(c, config, pos.add(dx, 1, dz)) || !isSafePassable(c, config, pos.add(dx, 2, dz))) return;

        for (int drop = 1; drop <= config.maxDropDistance(); drop++) {
            BlockPos t = pos.add(dx, -drop, dz);
            if (!isChunkLoaded(c, t)) break;
            if (isSolid(c, config, t)) {
                if (!isStandableGround(c, config, t)) break;
                if (!isSafePassable(c, config, t.up(1)) || !isSafePassable(c, config, t.up(2))) break;
                boolean clear = true;
                for (int y = 0; y >= -drop + 2; y--) {
                    if (!isSafePassable(c, config, pos.add(dx, y, dz))) { clear = false; break; }
                }
                if (!clear) break;

                PathNode n = new PathNode(t, PathNode.MoveType.DROP);
                n.setMoveCost(1.0 + drop * 0.5 + interactionPenalty(c, config, t.up(1), t.up(2)));
                out.add(n);
                break;
            }
        }
    }

    // ---- DROP (diagonal) ----
    private static void tryDropDiag(List<PathNode> out, MinecraftClient c, SearchConfig config,
                                     BlockPos pos, int dx, int dz) {
        if (!isSafePassable(c, config, pos.add(dx, 1, dz)) || !isSafePassable(c, config, pos.add(dx, 2, dz))) return;
        if (!isSafePassable(c, config, pos.add(dx, 1, 0)) || !isSafePassable(c, config, pos.add(dx, 2, 0))) return;
        if (!isSafePassable(c, config, pos.add(0, 1, dz)) || !isSafePassable(c, config, pos.add(0, 2, dz))) return;

        for (int drop = 1; drop <= config.maxDropDistance(); drop++) {
            BlockPos t = pos.add(dx, -drop, dz);
            if (!isChunkLoaded(c, t)) break;
            if (isSolid(c, config, t)) {
                if (!isStandableGround(c, config, t)) break;
                if (!isSafePassable(c, config, t.up(1)) || !isSafePassable(c, config, t.up(2))) break;
                boolean clear = true;
                for (int y = 0; y >= -drop + 2; y--) {
                    if (!isSafePassable(c, config, pos.add(dx, y, dz))) { clear = false; break; }
                }
                if (!clear) break;

                PathNode n = new PathNode(t, PathNode.MoveType.DROP);
                n.setMoveCost(1.414 + drop * 0.5 + interactionPenalty(c, config, t.up(1), t.up(2)));
                out.add(n);
                break;
            }
        }
    }

    // ---- JUMP ACROSS (1-gap and 2-gap, cardinal) ----
    private static void tryJumpAcross(List<PathNode> out, MinecraftClient c, SearchConfig config,
                                       BlockPos pos, int dx, int dz) {
        BlockPos gap1 = pos.add(dx, 0, dz);
        if (!isChunkLoaded(c, gap1)) return;
        if (isSolid(c, config, gap1)) return;

        if (!isSafePassable(c, config, gap1.up(1)) || !isSafePassable(c, config, gap1.up(2))) return;
        if (!isSafePassable(c, config, pos.up(3))) return;

        BlockPos land1 = pos.add(2 * dx, 0, 2 * dz);
        if (!isChunkLoaded(c, land1)) return;
        if (isStandableGround(c, config, land1) && isSafePassable(c, config, land1.up(1)) && isSafePassable(c, config, land1.up(2))) {
            PathNode n = new PathNode(land1, PathNode.MoveType.JUMP_ACROSS);
            n.setMoveCost(3.0 + interactionPenalty(c, config, land1.up(1), land1.up(2)));
            out.add(n);
            return;
        }

        if (isSolid(c, config, land1)) return;
        if (!isSafePassable(c, config, land1.up(1)) || !isSafePassable(c, config, land1.up(2))) return;

        BlockPos land2 = pos.add(3 * dx, 0, 3 * dz);
        if (!isChunkLoaded(c, land2)) return;
        if (isStandableGround(c, config, land2) && isSafePassable(c, config, land2.up(1)) && isSafePassable(c, config, land2.up(2))) {
            PathNode n = new PathNode(land2, PathNode.MoveType.JUMP_ACROSS);
            n.setMoveCost(4.5 + interactionPenalty(c, config, land2.up(1), land2.up(2)));
            out.add(n);
        }
    }

    // ---- SPRINT JUMP (3-gap and 4-gap, cardinal) ----
    private static void trySprintJump(List<PathNode> out, MinecraftClient c, SearchConfig config,
                                      BlockPos pos, int dx, int dz) {
        if (!isSafePassable(c, config, pos.up(2)) || !isSafePassable(c, config, pos.up(3))) return;

        for (int distance = 4; distance <= 5; distance++) {
            boolean clear = true;
            for (int i = 1; i < distance; i++) {
                BlockPos gap = pos.add(dx * i, 0, dz * i);
                if (!isChunkLoaded(c, gap)) { clear = false; break; }
                if (isSolid(c, config, gap)) { clear = false; break; }
                if (!isSafePassable(c, config, gap.up(1)) || !isSafePassable(c, config, gap.up(2))) { clear = false; break; }
                if (i <= 2 && !isSafePassable(c, config, gap.up(3))) { clear = false; break; }
            }
            if (!clear) continue;

            BlockPos land = pos.add(dx * distance, 0, dz * distance);
            if (!isChunkLoaded(c, land)) continue;
            if (!isStandableGround(c, config, land)) continue;
            if (!isSafePassable(c, config, land.up(1)) || !isSafePassable(c, config, land.up(2))) continue;

            PathNode n = new PathNode(land, PathNode.MoveType.SPRINT_JUMP);
            n.setMoveCost(distance == 4 ? 5.0 : 6.0);
            out.add(n);
        }
    }

    // ---- WATER MOVEMENT (allowWater only; preserves ground-node semantics where possible) ----
    private static void trySwim(List<PathNode> out, MinecraftClient c, SearchConfig config,
                                BlockPos pos, int dx, int dy, int dz) {
        if (!config.allowWater()) return;
        BlockPos feet = pos.up(1);
        if (!isWater(c, config, feet) && !isWater(c, config, pos)) return;

        BlockPos targetGround = pos.add(dx, dy, dz);
        if (!isChunkLoaded(c, targetGround)) return;
        BlockPos targetFeet = targetGround.up(1);
        if (!isWater(c, config, targetFeet) && !isWater(c, config, targetGround)) return;
        if (!isSafePassable(c, config, targetFeet) || !isSafePassable(c, config, targetFeet.up(1))) return;

        PathNode n = new PathNode(targetGround, PathNode.MoveType.SWIM);
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        n.setMoveCost(1.4 + horizontal * 0.9 + Math.abs(dy) * 1.1);
        out.add(n);
    }

    // ---- CLIMB (ladder/vine, up and down) ----
    private static void tryClimb(List<PathNode> out, MinecraftClient c, SearchConfig config, BlockPos pos) {
        // Climb up: check if the block above feet (pos.up(1) or pos.up(2)) is climbable
        BlockPos above = pos.up(1);
        if (isChunkLoaded(c, above) && isClimbable(c, config, above)) {
            // Can climb up — find the ground block one Y above
            BlockPos landGround = pos.up(1);
            if (isChunkLoaded(c, landGround)) {
                // For climbing, the node is the block we stand ON after climbing
                // We go up 1: stand on pos.up(1) if it's solid, or keep climbing
                // Actually: climb means moving vertically along the ladder.
                // Node position = ground block player stands on.
                // When climbing up, we need: climbable at feet level, and passable above.
                // Let's scan upward for contiguous climbable blocks.
                for (int dy = 1; dy <= 16; dy++) {
                    BlockPos climbCheck = pos.up(dy);
                    if (!isChunkLoaded(c, climbCheck)) break;

                    // feet position after climbing dy blocks up = pos.up(dy).up(1) = pos.up(dy+1)
                    // The climbable block should be at the feet level
                    if (!isClimbable(c, config, climbCheck.up(1))) {
                        // End of climbable column — check if we can stand here
                        // Ground = the block below feet = climbCheck
                        if (isStandableGround(c, config, climbCheck) && isSafePassable(c, config, climbCheck.up(1)) && isSafePassable(c, config, climbCheck.up(2))) {
                            PathNode n = new PathNode(climbCheck, PathNode.MoveType.CLIMB);
                            n.setMoveCost(1.0 + dy * 0.8);
                            out.add(n);
                        }
                        break;
                    }

                    // Also allow getting off the ladder at any point where there's solid ground
                    if (isStandableGround(c, config, climbCheck) && isSafePassable(c, config, climbCheck.up(1)) && isSafePassable(c, config, climbCheck.up(2))) {
                        PathNode n = new PathNode(climbCheck, PathNode.MoveType.CLIMB);
                        n.setMoveCost(1.0 + dy * 0.8);
                        out.add(n);
                    }
                }
            }
        }

        // Climb down: check if the block at current feet level or below is climbable
        for (int dy = 1; dy <= 16; dy++) {
            BlockPos below = pos.down(dy);
            if (!isChunkLoaded(c, below)) break;

            // Check climbable at the column going down
            BlockPos climbPos = pos.down(dy - 1).up(1); // feet position as we descend
            if (!isClimbable(c, config, pos.down(dy - 1).up(1)) && dy > 1) break;

            if (isStandableGround(c, config, below) && isSafePassable(c, config, below.up(1)) && isSafePassable(c, config, below.up(2))) {
                // Check the descent column has climbable blocks
                boolean hasClimbable = false;
                for (int check = 0; check < dy; check++) {
                    if (isClimbable(c, config, pos.down(check).up(1)) || isClimbable(c, config, pos.down(check))) {
                        hasClimbable = true;
                        break;
                    }
                }
                if (hasClimbable) {
                    PathNode n = new PathNode(below, PathNode.MoveType.CLIMB);
                    n.setMoveCost(1.0 + dy * 0.8);
                    out.add(n);
                }
                break;
            }
        }
    }

    // --------------------------------------------------------- heuristic
    private static double heuristic(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        int maxHz = Math.max(dx, dz);
        int minHz = Math.min(dx, dz);
        return maxHz + 0.414 * minHz + dy;
    }

    // -------------------------------------------------------- block checks

    private static boolean isChunkLoaded(MinecraftClient c, BlockPos pos) {
        if (c.world == null) return false;
        WorldChunk chunk = c.world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null;
    }

    private static boolean isSolid(MinecraftClient c, SearchConfig config, BlockPos pos) {
        return config.blockCache().get(c, pos).isSolid();
    }

    private static boolean isStandableGround(MinecraftClient c, SearchConfig config, BlockPos pos) {
        BlockCacheEntry entry = config.blockCache().get(c, pos);
        return entry.isSolid() && entry.collisionMaxY() <= 1.0;
    }

    private static boolean isPassable(MinecraftClient c, SearchConfig config, BlockPos pos) {
        BlockCacheEntry entry = config.blockCache().get(c, pos);
        return entry.isPassable() || entry.isInteractable();
    }

    private static boolean isWater(MinecraftClient c, SearchConfig config, BlockPos pos) {
        return config.blockCache().get(c, pos).isWater();
    }

    private static boolean isLava(MinecraftClient c, SearchConfig config, BlockPos pos) {
        return config.blockCache().get(c, pos).isLava();
    }

    private static boolean isSafePassable(MinecraftClient c, SearchConfig config, BlockPos pos) {
        if (!isChunkLoaded(c, pos)) return false;
        return isPassable(c, config, pos) && !isLava(c, config, pos) && (config.allowWater() || !isWater(c, config, pos));
    }

    private static boolean isClimbable(MinecraftClient c, SearchConfig config, BlockPos pos) {
        if (!isChunkLoaded(c, pos)) return false;
        return config.blockCache().get(c, pos).isClimbable();
    }

    private static double interactionPenalty(MinecraftClient c, SearchConfig config, BlockPos... positions) {
        for (BlockPos pos : positions) {
            if (isChunkLoaded(c, pos) && config.blockCache().get(c, pos).isInteractable()) {
                return 4.0;
            }
        }
        return 0.0;
    }

    private static class BlockCache {
        private final Long2ObjectOpenHashMap<BlockCacheEntry> entries = new Long2ObjectOpenHashMap<>();

        BlockCacheEntry get(MinecraftClient client, BlockPos pos) {
            long key = pos.asLong();
            BlockCacheEntry cached = entries.get(key);
            if (cached != null) return cached;

            BlockState state = client.world.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(client.world, pos);
            Block block = state.getBlock();
            boolean interactable = isOpenable(block, state);
            BlockCacheEntry entry = new BlockCacheEntry(
                !shape.isEmpty() && !interactable,
                shape.isEmpty(),
                state.getFluidState().isIn(FluidTags.WATER),
                state.getFluidState().isIn(FluidTags.LAVA),
                shape.isEmpty() ? 0.0 : shape.getMax(Direction.Axis.Y),
                block instanceof LadderBlock || block instanceof VineBlock,
                interactable
            );
            entries.put(key, entry);
            return entry;
        }
    }

    private static boolean isOpenable(Block block, BlockState state) {
        if (!(block instanceof DoorBlock || block instanceof TrapdoorBlock || block instanceof FenceGateBlock)) {
            return false;
        }
        return !state.contains(Properties.OPEN) || !state.get(Properties.OPEN);
    }

    // ----------------------------------------------------- path rebuild
    private static List<PathNode> reconstructPath(PathNode endNode) {
        List<PathNode> path = new ArrayList<>();
        PathNode cur = endNode;
        while (cur != null) {
            path.add(cur);
            cur = cur.getParent();
        }
        Collections.reverse(path);
        return path;
    }
}
