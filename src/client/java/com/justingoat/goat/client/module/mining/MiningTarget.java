package com.justingoat.goat.client.module.mining;

import net.minecraft.util.math.BlockPos;

public class MiningTarget {

    public enum TargetMode { REACHABLE, APPROACH }

    public final BlockPos pos;
    public double aimX, aimY, aimZ;
    public double cost;
    public double dist;
    public String blockId;
    public TargetMode targetMode;

    public MiningTarget(BlockPos pos, double cost, String blockId, TargetMode mode) {
        this.pos = pos;
        this.cost = cost;
        this.blockId = blockId;
        this.targetMode = mode;
        this.aimX = pos.getX() + 0.5;
        this.aimY = pos.getY() + 0.5;
        this.aimZ = pos.getZ() + 0.5;
    }

    public MiningTarget withAim(double ax, double ay, double az, double distance) {
        this.aimX = ax;
        this.aimY = ay;
        this.aimZ = az;
        this.dist = distance;
        return this;
    }
}
