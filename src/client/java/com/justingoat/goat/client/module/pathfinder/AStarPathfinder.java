package com.justingoat.goat.client.module.pathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;

/**
 * A* pathfinder for Minecraft 1.21.11.
 *
 * Coordinate convention: PathNode position = the solid ground block the player
 * stands ON. Player feet occupy pos.up(1), head occupies pos.up(2).
 *
 * Move types:
 *   WALK         — horizontal same-Y (cardinal + diagonal with corner check)
 *   STEP_UP      — cardinal Y+1 (requires jump)
 *   DROP         — cardinal/diagonal Y-1..Y-3 (safe fall)
 *   JUMP_ACROSS  — cardinal 1-gap or 2-gap (sprint-jump)
 */
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

            // Stale entry — we already found a cheaper route to this pos
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

    // ------------------------------------------------------------------ goal
    private static boolean isGoal(BlockPos pos, BlockPos end) {
        return pos.getX() == end.getX()
            && pos.getZ() == end.getZ()
            && Math.abs(pos.getY() - end.getY()) <= 1;
    }

    // --------------------------------------------------------- ground snap
    private static BlockPos snapToGround(MinecraftClient client, BlockPos pos) {
        if (isSolid(client, pos) && isPassable(client, pos.up(1))) return pos;

        // Search down up to 5 blocks
        BlockPos probe = pos;
        for (int i = 0; i < 5; i++) {
            probe = probe.down();
            if (isSolid(client, probe) && isPassable(client, probe.up(1))) return probe;
        }
        // Search up up to 3 blocks
        probe = pos;
        for (int i = 0; i < 3; i++) {
            probe = probe.up();
            if (isSolid(client, probe) && isPassable(client, probe.up(1))) return probe;
        }
        return null;
    }

    // ------------------------------------------------------- neighbor gen
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

        return out;
    }

    // ---- WALK (same level, cardinal) ----
    private static void tryWalk(List<PathNode> out, MinecraftClient c,
                                 BlockPos pos, int dx, int dz) {
        BlockPos t = pos.add(dx, 0, dz);
        if (!isSolid(c, t)) return;
        if (!isPassable(c, t.up(1)) || !isPassable(c, t.up(2))) return;

        PathNode n = new PathNode(t, PathNode.MoveType.WALK);
        n.setMoveCost(1.0);
        out.add(n);
    }

    // ---- WALK (same level, diagonal with corner check) ----
    private static void tryWalkDiag(List<PathNode> out, MinecraftClient c,
                                     BlockPos pos, int dx, int dz) {
        BlockPos t = pos.add(dx, 0, dz);
        if (!isSolid(c, t)) return;
        if (!isPassable(c, t.up(1)) || !isPassable(c, t.up(2))) return;

        // Corner clearance — both adjacent cardinal columns must be passable
        if (!isPassable(c, pos.add(dx, 1, 0)) || !isPassable(c, pos.add(dx, 2, 0))) return;
        if (!isPassable(c, pos.add(0, 1, dz)) || !isPassable(c, pos.add(0, 2, dz))) return;

        PathNode n = new PathNode(t, PathNode.MoveType.WALK);
        n.setMoveCost(1.414);
        out.add(n);
    }

    // ---- STEP UP (Y+1, cardinal only, requires jump) ----
    private static void tryStepUp(List<PathNode> out, MinecraftClient c,
                                   BlockPos pos, int dx, int dz) {
        BlockPos t = pos.add(dx, 1, dz);
        if (!isSolid(c, t)) return;
        if (!isPassable(c, t.up(1)) || !isPassable(c, t.up(2))) return;
        // Head clearance for the jump arc at current position
        if (!isPassable(c, pos.up(3))) return;
        // Also need the column in front at current level to be passable (feet walk into it)
        if (!isPassable(c, pos.add(dx, 2, dz))) return;

        PathNode n = new PathNode(t, PathNode.MoveType.STEP_UP);
        n.setMoveCost(2.0);
        out.add(n);
    }

    // ---- DROP (Y-1..Y-3, cardinal) ----
    private static void tryDrop(List<PathNode> out, MinecraftClient c,
                                 BlockPos pos, int dx, int dz) {
        // Entry clearance — must be able to walk into the adjacent column
        if (!isPassable(c, pos.add(dx, 1, dz)) || !isPassable(c, pos.add(dx, 2, dz))) return;

        for (int drop = 1; drop <= maxDropDistance; drop++) {
            BlockPos t = pos.add(dx, -drop, dz);
            if (isSolid(c, t)) {
                if (!isPassable(c, t.up(1)) || !isPassable(c, t.up(2))) break;
                // Fall column clear
                boolean clear = true;
                for (int y = 0; y >= -drop + 2; y--) {
                    if (!isPassable(c, pos.add(dx, y, dz))) { clear = false; break; }
                }
                if (!clear) break;

                PathNode n = new PathNode(t, PathNode.MoveType.DROP);
                n.setMoveCost(1.0 + drop * 0.5);
                out.add(n);
                break; // found landing
            }
        }
    }

    // ---- DROP (diagonal) ----
    private static void tryDropDiag(List<PathNode> out, MinecraftClient c,
                                     BlockPos pos, int dx, int dz) {
        // Entry + corner clearance
        if (!isPassable(c, pos.add(dx, 1, dz)) || !isPassable(c, pos.add(dx, 2, dz))) return;
        if (!isPassable(c, pos.add(dx, 1, 0)) || !isPassable(c, pos.add(dx, 2, 0))) return;
        if (!isPassable(c, pos.add(0, 1, dz)) || !isPassable(c, pos.add(0, 2, dz))) return;

        for (int drop = 1; drop <= maxDropDistance; drop++) {
            BlockPos t = pos.add(dx, -drop, dz);
            if (isSolid(c, t)) {
                if (!isPassable(c, t.up(1)) || !isPassable(c, t.up(2))) break;
                boolean clear = true;
                for (int y = 0; y >= -drop + 2; y--) {
                    if (!isPassable(c, pos.add(dx, y, dz))) { clear = false; break; }
                }
                if (!clear) break;

                PathNode n = new PathNode(t, PathNode.MoveType.DROP);
                n.setMoveCost(1.414 + drop * 0.5);
                out.add(n);
                break;
            }
        }
    }

    // ---- JUMP ACROSS (1-gap and 2-gap, cardinal only) ----
    private static void tryJumpAcross(List<PathNode> out, MinecraftClient c,
                                       BlockPos pos, int dx, int dz) {
        // Must have a gap at the adjacent block (no ground)
        BlockPos gap1 = pos.add(dx, 0, dz);
        if (isSolid(c, gap1)) return;

        // Air clearance through the gap
        if (!isPassable(c, gap1.up(1)) || !isPassable(c, gap1.up(2))) return;
        // Jump arc clearance
        if (!isPassable(c, pos.up(3))) return;

        // --- 1-gap: land at 2*dir ---
        BlockPos land1 = pos.add(2 * dx, 0, 2 * dz);
        if (isSolid(c, land1) && isPassable(c, land1.up(1)) && isPassable(c, land1.up(2))) {
            PathNode n = new PathNode(land1, PathNode.MoveType.JUMP_ACROSS);
            n.setMoveCost(3.0);
            out.add(n);
            return;
        }

        // --- 2-gap: gap extends to 2*dir, land at 3*dir ---
        if (isSolid(c, land1)) return;
        if (!isPassable(c, land1.up(1)) || !isPassable(c, land1.up(2))) return;

        BlockPos land2 = pos.add(3 * dx, 0, 3 * dz);
        if (isSolid(c, land2) && isPassable(c, land2.up(1)) && isPassable(c, land2.up(2))) {
            PathNode n = new PathNode(land2, PathNode.MoveType.JUMP_ACROSS);
            n.setMoveCost(4.5);
            out.add(n);
        }
    }

    // --------------------------------------------------------- heuristic
    private static double heuristic(BlockPos a, BlockPos b) {
        // Octile distance — tighter admissible bound than Manhattan
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        int maxHz = Math.max(dx, dz);
        int minHz = Math.min(dx, dz);
        return maxHz + 0.414 * minHz + dy * 1.5;
    }

    // -------------------------------------------------------- block checks
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
