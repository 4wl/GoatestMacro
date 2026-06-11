package com.justingoat.goat.client.module.pathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.WorldChunk;

public class AStarPathfinder {

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};

    private static int maxDropDistance = 3;

    public static List<PathNode> computePath(BlockPos start, BlockPos end, int maxNodes) {
        return computePath(start, end, maxNodes, 3);
    }

    public static List<PathNode> computePath(BlockPos start, BlockPos end, int maxNodes, int maxDrop) {
        maxDropDistance = maxDrop;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;

        start = snapToGround(client, start);
        end = snapToGround(client, end);
        if (start == null || end == null) return null;

        PriorityQueue<PathNode> openSet =
            new PriorityQueue<>(Comparator.comparingDouble(PathNode::getFCost));
        Map<BlockPos, Double> bestCost = new HashMap<>();

        PathNode startNode = new PathNode(start, PathNode.MoveType.WALK);
        startNode.setGCost(0);
        startNode.setHCost(heuristic(start, end));
        openSet.add(startNode);
        bestCost.put(start, 0.0);

        int evaluated = 0;

        while (!openSet.isEmpty() && evaluated < maxNodes) {
            PathNode current = openSet.poll();

            Double best = bestCost.get(current.getPos());
            if (best != null && current.getGCost() > best) continue;

            if (isGoal(current.getPos(), end)) {
                return reconstructPath(current);
            }

            for (PathNode neighbor : getNeighbors(current, client)) {
                double newG = current.getGCost() + neighbor.getMoveCost();
                Double existingG = bestCost.get(neighbor.getPos());

                if (existingG == null || newG < existingG) {
                    bestCost.put(neighbor.getPos(), newG);
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

    private static boolean isGoal(BlockPos pos, BlockPos end) {
        return pos.getX() == end.getX()
            && pos.getZ() == end.getZ()
            && Math.abs(pos.getY() - end.getY()) <= 1;
    }

    private static BlockPos snapToGround(MinecraftClient client, BlockPos pos) {
        if (!isChunkLoaded(client, pos)) return null;
        if (isSolid(client, pos) && isPassable(client, pos.up(1))) return pos;

        BlockPos probe = pos;
        for (int i = 0; i < 5; i++) {
            probe = probe.down();
            if (!isChunkLoaded(client, probe)) return null;
            if (isSolid(client, probe) && isPassable(client, probe.up(1))) return probe;
        }
        probe = pos;
        for (int i = 0; i < 3; i++) {
            probe = probe.up();
            if (!isChunkLoaded(client, probe)) return null;
            if (isSolid(client, probe) && isPassable(client, probe.up(1))) return probe;
        }
        return null;
    }

    private static List<PathNode> getNeighbors(PathNode current, MinecraftClient client) {
        List<PathNode> out = new ArrayList<>();
        BlockPos pos = current.getPos();

        for (int[] d : CARDINALS) {
            tryWalk(out, client, pos, d[0], d[1]);
            tryStepUp(out, client, pos, d[0], d[1]);
            tryDrop(out, client, pos, d[0], d[1]);
            tryJumpAcross(out, client, pos, d[0], d[1]);
        }
        for (int[] d : DIAGONALS) {
            tryWalkDiag(out, client, pos, d[0], d[1]);
            tryDropDiag(out, client, pos, d[0], d[1]);
        }

        tryClimb(out, client, pos);

        return out;
    }

    // ---- WALK (same level, cardinal) ----
    private static void tryWalk(List<PathNode> out, MinecraftClient c,
                                 BlockPos pos, int dx, int dz) {
        BlockPos t = pos.add(dx, 0, dz);
        if (!isChunkLoaded(c, t)) return;
        if (!isSolid(c, t)) return;
        if (!isSafePassable(c, t.up(1)) || !isSafePassable(c, t.up(2))) return;

        PathNode n = new PathNode(t, PathNode.MoveType.WALK);
        n.setMoveCost(1.0);
        out.add(n);
    }

    // ---- WALK (same level, diagonal with corner check) ----
    private static void tryWalkDiag(List<PathNode> out, MinecraftClient c,
                                     BlockPos pos, int dx, int dz) {
        BlockPos t = pos.add(dx, 0, dz);
        if (!isChunkLoaded(c, t)) return;
        if (!isSolid(c, t)) return;
        if (!isSafePassable(c, t.up(1)) || !isSafePassable(c, t.up(2))) return;

        if (!isSafePassable(c, pos.add(dx, 1, 0)) || !isSafePassable(c, pos.add(dx, 2, 0))) return;
        if (!isSafePassable(c, pos.add(0, 1, dz)) || !isSafePassable(c, pos.add(0, 2, dz))) return;

        PathNode n = new PathNode(t, PathNode.MoveType.WALK);
        n.setMoveCost(1.414);
        out.add(n);
    }

    // ---- STEP UP (Y+1, cardinal only) ----
    private static void tryStepUp(List<PathNode> out, MinecraftClient c,
                                   BlockPos pos, int dx, int dz) {
        BlockPos t = pos.add(dx, 1, dz);
        if (!isChunkLoaded(c, t)) return;
        if (!isSolid(c, t)) return;
        if (!isSafePassable(c, t.up(1)) || !isSafePassable(c, t.up(2))) return;
        if (!isSafePassable(c, pos.up(3))) return;
        if (!isSafePassable(c, pos.add(dx, 2, dz))) return;

        PathNode n = new PathNode(t, PathNode.MoveType.STEP_UP);
        n.setMoveCost(2.0);
        out.add(n);
    }

    // ---- DROP (Y-1..Y-maxDrop, cardinal) ----
    private static void tryDrop(List<PathNode> out, MinecraftClient c,
                                 BlockPos pos, int dx, int dz) {
        if (!isSafePassable(c, pos.add(dx, 1, dz)) || !isSafePassable(c, pos.add(dx, 2, dz))) return;

        for (int drop = 1; drop <= maxDropDistance; drop++) {
            BlockPos t = pos.add(dx, -drop, dz);
            if (!isChunkLoaded(c, t)) break;
            if (isSolid(c, t)) {
                if (!isSafePassable(c, t.up(1)) || !isSafePassable(c, t.up(2))) break;
                boolean clear = true;
                for (int y = 0; y >= -drop + 2; y--) {
                    if (!isSafePassable(c, pos.add(dx, y, dz))) { clear = false; break; }
                }
                if (!clear) break;

                PathNode n = new PathNode(t, PathNode.MoveType.DROP);
                n.setMoveCost(1.0 + drop * 0.5);
                out.add(n);
                break;
            }
        }
    }

    // ---- DROP (diagonal) ----
    private static void tryDropDiag(List<PathNode> out, MinecraftClient c,
                                     BlockPos pos, int dx, int dz) {
        if (!isSafePassable(c, pos.add(dx, 1, dz)) || !isSafePassable(c, pos.add(dx, 2, dz))) return;
        if (!isSafePassable(c, pos.add(dx, 1, 0)) || !isSafePassable(c, pos.add(dx, 2, 0))) return;
        if (!isSafePassable(c, pos.add(0, 1, dz)) || !isSafePassable(c, pos.add(0, 2, dz))) return;

        for (int drop = 1; drop <= maxDropDistance; drop++) {
            BlockPos t = pos.add(dx, -drop, dz);
            if (!isChunkLoaded(c, t)) break;
            if (isSolid(c, t)) {
                if (!isSafePassable(c, t.up(1)) || !isSafePassable(c, t.up(2))) break;
                boolean clear = true;
                for (int y = 0; y >= -drop + 2; y--) {
                    if (!isSafePassable(c, pos.add(dx, y, dz))) { clear = false; break; }
                }
                if (!clear) break;

                PathNode n = new PathNode(t, PathNode.MoveType.DROP);
                n.setMoveCost(1.414 + drop * 0.5);
                out.add(n);
                break;
            }
        }
    }

    // ---- JUMP ACROSS (1-gap and 2-gap, cardinal) ----
    private static void tryJumpAcross(List<PathNode> out, MinecraftClient c,
                                       BlockPos pos, int dx, int dz) {
        BlockPos gap1 = pos.add(dx, 0, dz);
        if (!isChunkLoaded(c, gap1)) return;
        if (isSolid(c, gap1)) return;

        if (!isSafePassable(c, gap1.up(1)) || !isSafePassable(c, gap1.up(2))) return;
        if (!isSafePassable(c, pos.up(3))) return;

        BlockPos land1 = pos.add(2 * dx, 0, 2 * dz);
        if (!isChunkLoaded(c, land1)) return;
        if (isSolid(c, land1) && isSafePassable(c, land1.up(1)) && isSafePassable(c, land1.up(2))) {
            PathNode n = new PathNode(land1, PathNode.MoveType.JUMP_ACROSS);
            n.setMoveCost(3.0);
            out.add(n);
            return;
        }

        if (isSolid(c, land1)) return;
        if (!isSafePassable(c, land1.up(1)) || !isSafePassable(c, land1.up(2))) return;

        BlockPos land2 = pos.add(3 * dx, 0, 3 * dz);
        if (!isChunkLoaded(c, land2)) return;
        if (isSolid(c, land2) && isSafePassable(c, land2.up(1)) && isSafePassable(c, land2.up(2))) {
            PathNode n = new PathNode(land2, PathNode.MoveType.JUMP_ACROSS);
            n.setMoveCost(4.5);
            out.add(n);
        }
    }

    // ---- CLIMB (ladder/vine, up and down) ----
    private static void tryClimb(List<PathNode> out, MinecraftClient c, BlockPos pos) {
        // Climb up: check if the block above feet (pos.up(1) or pos.up(2)) is climbable
        BlockPos above = pos.up(1);
        if (isChunkLoaded(c, above) && isClimbable(c, above)) {
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
                    if (!isClimbable(c, climbCheck.up(1))) {
                        // End of climbable column — check if we can stand here
                        // Ground = the block below feet = climbCheck
                        if (isSolid(c, climbCheck) && isSafePassable(c, climbCheck.up(1)) && isSafePassable(c, climbCheck.up(2))) {
                            PathNode n = new PathNode(climbCheck, PathNode.MoveType.CLIMB);
                            n.setMoveCost(1.0 + dy * 0.8);
                            out.add(n);
                        }
                        break;
                    }

                    // Also allow getting off the ladder at any point where there's solid ground
                    if (isSolid(c, climbCheck) && isSafePassable(c, climbCheck.up(1)) && isSafePassable(c, climbCheck.up(2))) {
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
            if (!isClimbable(c, pos.down(dy - 1).up(1)) && dy > 1) break;

            if (isSolid(c, below) && isSafePassable(c, below.up(1)) && isSafePassable(c, below.up(2))) {
                // Check the descent column has climbable blocks
                boolean hasClimbable = false;
                for (int check = 0; check < dy; check++) {
                    if (isClimbable(c, pos.down(check).up(1)) || isClimbable(c, pos.down(check))) {
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
        return maxHz + 0.414 * minHz + dy * 1.5;
    }

    // -------------------------------------------------------- block checks

    private static boolean isChunkLoaded(MinecraftClient c, BlockPos pos) {
        if (c.world == null) return false;
        WorldChunk chunk = c.world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null;
    }

    private static boolean isSolid(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(c.world, pos);
        return !shape.isEmpty();
    }

    private static boolean isPassable(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(c.world, pos);
        return shape.isEmpty();
    }

    private static boolean isLiquid(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        return state.getBlock() instanceof FluidBlock
            || state.isOf(Blocks.WATER)
            || state.isOf(Blocks.LAVA);
    }

    private static boolean isSafePassable(MinecraftClient c, BlockPos pos) {
        if (!isChunkLoaded(c, pos)) return false;
        return isPassable(c, pos) && !isLiquid(c, pos);
    }

    private static boolean isClimbable(MinecraftClient c, BlockPos pos) {
        if (!isChunkLoaded(c, pos)) return false;
        BlockState state = c.world.getBlockState(pos);
        return state.getBlock() instanceof LadderBlock
            || state.getBlock() instanceof VineBlock;
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
