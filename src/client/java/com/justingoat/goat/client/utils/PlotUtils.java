package com.justingoat.goat.client.utils;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.List;

public final class PlotUtils {
    private static final int[][] PLOT_MAP = {
        {21, 13, 9, 14, 22},
        {15, 5, 1, 6, 16},
        {10, 2, 0, 3, 11},
        {17, 7, 4, 8, 18},
        {23, 19, 12, 20, 24}
    };

    private static final int[] PLOT_ORDER = {
        21, 13, 9, 14, 22,
        15, 5, 1, 6, 16,
        10, 2, 0, 3, 11,
        17, 7, 4, 8, 18,
        23, 19, 12, 20, 24
    };

    private PlotUtils() {}

    public static List<Integer> getPlotOrder() {
        return Arrays.stream(PLOT_ORDER).boxed().toList();
    }

    public static Integer getPlotIdAt(Vec3d pos) {
        if (pos == null) return null;
        return getPlotIdAt(BlockPos.ofFloored(pos));
    }

    public static Integer getPlotIdAt(BlockPos pos) {
        if (pos == null) return null;

        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        int xIndex = Math.floorDiv(chunkX + 15, 6);
        int zIndex = Math.floorDiv(chunkZ + 15, 6);

        if (xIndex < 0 || xIndex >= PLOT_MAP[0].length || zIndex < 0 || zIndex >= PLOT_MAP.length) {
            return null;
        }

        return PLOT_MAP[zIndex][xIndex];
    }

    public static Vec3d getPlotCenter(int plotId, double y) {
        for (int z = 0; z < PLOT_MAP.length; z++) {
            for (int x = 0; x < PLOT_MAP[z].length; x++) {
                if (PLOT_MAP[z][x] == plotId) {
                    return new Vec3d((x - 2) * 96.0, y, (z - 2) * 96.0);
                }
            }
        }
        return null;
    }

    public static Box getPlotBounds(int plotId, double minY, double maxY) {
        for (int z = 0; z < PLOT_MAP.length; z++) {
            for (int x = 0; x < PLOT_MAP[z].length; x++) {
                if (PLOT_MAP[z][x] != plotId) continue;

                double minX = (x - 2) * 96.0 - 48.0;
                double minZ = (z - 2) * 96.0 - 48.0;
                return new Box(minX, minY, minZ, minX + 96.0, maxY, minZ + 96.0);
            }
        }
        return null;
    }

    public static boolean isInsidePlot(int plotId, Vec3d pos) {
        Box bounds = getPlotBounds(plotId, -64.0, 320.0);
        return bounds != null && pos != null && bounds.contains(pos);
    }
}
