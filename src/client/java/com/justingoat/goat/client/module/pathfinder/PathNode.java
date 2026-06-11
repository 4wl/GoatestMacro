package com.justingoat.goat.client.module.pathfinder;

import net.minecraft.util.math.BlockPos;

public class PathNode {

    public enum MoveType {
        WALK,
        STEP_UP,
        DROP,
        JUMP_ACROSS
    }

    private final BlockPos pos;
    private final MoveType moveType;
    private PathNode parent;
    private double gCost;
    private double hCost;
    private double moveCost;

    public PathNode(BlockPos pos, MoveType moveType) {
        this.pos = pos;
        this.moveType = moveType;
        this.gCost = 0;
        this.hCost = 0;
        this.moveCost = 1.0;
    }

    public BlockPos getPos() {
        return pos;
    }

    public MoveType getMoveType() {
        return moveType;
    }

    public PathNode getParent() {
        return parent;
    }

    public void setParent(PathNode parent) {
        this.parent = parent;
    }

    public double getGCost() {
        return gCost;
    }

    public void setGCost(double gCost) {
        this.gCost = gCost;
    }

    public double getHCost() {
        return hCost;
    }

    public void setHCost(double hCost) {
        this.hCost = hCost;
    }

    public double getFCost() {
        return gCost + hCost;
    }

    public double getMoveCost() {
        return moveCost;
    }

    public void setMoveCost(double moveCost) {
        this.moveCost = moveCost;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PathNode)) return false;
        return pos.equals(((PathNode) obj).pos);
    }

    @Override
    public int hashCode() {
        return pos.hashCode();
    }
}
