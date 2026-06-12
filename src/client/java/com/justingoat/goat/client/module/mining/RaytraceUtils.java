package com.justingoat.goat.client.module.mining;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class RaytraceUtils {

    private RaytraceUtils() {}

    private static final double RAY_STEP = 0.25;

    public static boolean isLineClear(ClientWorld world, double x1, double y1, double z1,
                                       double x2, double y2, double z2,
                                       BlockPos ignore) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001) return true;

        double invLen = 1.0 / len;
        double sx = dx * invLen * RAY_STEP;
        double sy = dy * invLen * RAY_STEP;
        double sz = dz * invLen * RAY_STEP;

        double cx = x1, cy = y1, cz = z1;
        int lastBx = Integer.MIN_VALUE, lastBy = Integer.MIN_VALUE, lastBz = Integer.MIN_VALUE;

        for (double d = 0; d < len; d += RAY_STEP) {
            int bx = MathHelper.floor(cx);
            int by = MathHelper.floor(cy);
            int bz = MathHelper.floor(cz);

            if (bx != lastBx || by != lastBy || bz != lastBz) {
                lastBx = bx; lastBy = by; lastBz = bz;

                if (ignore != null && bx == ignore.getX() && by == ignore.getY() && bz == ignore.getZ()) {
                    cx += sx; cy += sy; cz += sz;
                    continue;
                }

                BlockState state = world.getBlockState(new BlockPos(bx, by, bz));
                if (!state.isAir() && !state.getCollisionShape(world, new BlockPos(bx, by, bz)).isEmpty()) {
                    return false;
                }
            }
            cx += sx; cy += sy; cz += sz;
        }
        return true;
    }

    public static boolean isLineClear(ClientWorld world, Vec3d start, Vec3d end, BlockPos ignore) {
        return isLineClear(world, start.x, start.y, start.z, end.x, end.y, end.z, ignore);
    }

    private static final float AIM_POINT_INSET = 0.48f;
    private static final float AIM_POINT_FACE_INSET = 0.48f;
    private static final float AIM_POINT_LO = 0.02f;
    private static final float AIM_POINT_HI = 0.98f;
    private static final float AIM_POINT_MID_CAP = 0.35f;
    private static final float AIM_POINT_EDGE_MAG = 0.45f;

    public static double[] findVisibleAimPoint(ClientWorld world, int bx, int by, int bz,
                                                Vec3d eyePos, Vec3d lookVec,
                                                double maxReachSq, boolean checkFov) {
        double cx = bx + 0.5, cy = by + 0.5, cz = bz + 0.5;
        double vx = cx - eyePos.x, vy = cy - eyePos.y, vz = cz - eyePos.z;
        double vLenSq = vx * vx + vy * vy + vz * vz;
        if (vLenSq == 0) return null;

        if (checkFov && lookVec != null) {
            double vLen = Math.sqrt(vLenSq);
            double dot = (vx * lookVec.x + vy * lookVec.y + vz * lookVec.z) / vLen;
            if (dot < -0.05) return null;
        }

        double invX = vx == 0 ? Double.MAX_VALUE : 1.0 / vx;
        double invY = vy == 0 ? Double.MAX_VALUE : 1.0 / vy;
        double invZ = vz == 0 ? Double.MAX_VALUE : 1.0 / vz;

        double tx1 = (bx - eyePos.x) * invX, tx2 = (bx + 1 - eyePos.x) * invX;
        double ty1 = (by - eyePos.y) * invY, ty2 = (by + 1 - eyePos.y) * invY;
        double tz1 = (bz - eyePos.z) * invZ, tz2 = (bz + 1 - eyePos.z) * invZ;

        double tminX = Math.min(tx1, tx2);
        double tminY = Math.min(ty1, ty2);
        double tminZ = Math.min(tz1, tz2);

        int faceAxis = 0;
        double tEntry = tminX;
        if (tminY > tEntry) { tEntry = tminY; faceAxis = 1; }
        if (tminZ > tEntry) { tEntry = tminZ; faceAxis = 2; }

        double sign;
        if (faceAxis == 0) sign = vx > 0 ? -1 : 1;
        else if (faceAxis == 1) sign = vy > 0 ? -1 : 1;
        else sign = vz > 0 ? -1 : 1;

        BlockPos ignorePos = new BlockPos(bx, by, bz);

        for (int pass = 0; pass < 3; pass++) {
            int axis = pass == 0 ? faceAxis : (pass == 1 ? orthoAxis1(faceAxis) : orthoAxis2(faceAxis));
            double localSign = sign;
            if (pass > 0) {
                if (axis == 0) localSign = eyePos.x >= cx ? 1 : -1;
                else if (axis == 1) localSign = eyePos.y >= cy ? 1 : -1;
                else localSign = eyePos.z >= cz ? 1 : -1;
            }

            if (pass == 0) {
                if (!isFaceExposed(world, bx, by, bz, axis, localSign)) continue;
                double[][] samples = primarySamples(axis, localSign, bx, by, bz, cx, cy, cz, eyePos);
                for (double[] s : samples) {
                    if (isLineClear(world, eyePos.x, eyePos.y, eyePos.z, s[0], s[1], s[2], ignorePos)) {
                        double rx = s[3], ry = s[4], rz = s[5];
                        double dX = rx - eyePos.x, dY = ry - eyePos.y, dZ = rz - eyePos.z;
                        double dSq = dX * dX + dY * dY + dZ * dZ;
                        if (dSq > maxReachSq) continue;
                        double d = Math.sqrt(dSq);
                        double dot = (lookVec != null && d > 0)
                                ? (dX * lookVec.x + dY * lookVec.y + dZ * lookVec.z) / d : 1;
                        return new double[]{rx, ry, rz, d, dot};
                    }
                }
            } else {
                if (!isFaceExposed(world, bx, by, bz, axis, localSign)) continue;
                double[][] fallback = fallbackSamples(axis, localSign, bx, by, bz, cx, cy, cz);
                for (double[] s : fallback) {
                    if (isLineClear(world, eyePos.x, eyePos.y, eyePos.z, s[0], s[1], s[2], ignorePos)) {
                        double rx = s[3], ry = s[4], rz = s[5];
                        double dX = rx - eyePos.x, dY = ry - eyePos.y, dZ = rz - eyePos.z;
                        double dSq = dX * dX + dY * dY + dZ * dZ;
                        if (dSq > maxReachSq) continue;
                        double d = Math.sqrt(dSq);
                        double dot = (lookVec != null && d > 0)
                                ? (dX * lookVec.x + dY * lookVec.y + dZ * lookVec.z) / d : 1;
                        return new double[]{rx, ry, rz, d, dot};
                    }
                }
            }
        }
        return null;
    }

    public static boolean hasExposedFace(ClientWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.offset(direction);
            BlockState state = world.getBlockState(neighbor);
            if (state.isAir() || state.getCollisionShape(world, neighbor).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFaceExposed(ClientWorld world, int bx, int by, int bz, int axis, double sign) {
        Direction direction;
        if (axis == 0) {
            direction = sign > 0 ? Direction.EAST : Direction.WEST;
        } else if (axis == 1) {
            direction = sign > 0 ? Direction.UP : Direction.DOWN;
        } else {
            direction = sign > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        BlockPos neighbor = new BlockPos(bx, by, bz).offset(direction);
        BlockState state = world.getBlockState(neighbor);
        return state.isAir() || state.getCollisionShape(world, neighbor).isEmpty();
    }

    private static double[][] primarySamples(int axis, double sign, int bx, int by, int bz,
                                              double cx, double cy, double cz, Vec3d eye) {
        double[][] result = new double[4][6];

        for (int i = 0; i < 4; i++) {
            double u = 0, v = 0;
            if (i == 0) {
                u = clampMid(clampRange(axis == 0 ? eye.y : eye.x, axis == 0 ? by : bx) - (axis == 0 ? cy : cx));
                v = clampMid(clampRange(axis == 1 ? eye.z : eye.y, axis == 1 ? bz : by) - (axis == 1 ? cz : cy));
            } else if (i == 2) {
                u = clampRange(axis == 0 ? eye.y : eye.x, axis == 0 ? by : bx) - (axis == 0 ? cy : cx);
                u = u >= 0 ? AIM_POINT_EDGE_MAG : -AIM_POINT_EDGE_MAG;
            } else if (i == 3) {
                v = clampRange(axis == 1 ? eye.z : eye.y, axis == 1 ? bz : by) - (axis == 1 ? cz : cy);
                v = v >= 0 ? AIM_POINT_EDGE_MAG : -AIM_POINT_EDGE_MAG;
            }

            double tx, ty, tz, fx, fy, fz;
            if (axis == 0) {
                tx = cx + sign * AIM_POINT_INSET; ty = cy + u; tz = cz + v;
                fx = cx + sign * AIM_POINT_FACE_INSET; fy = ty; fz = tz;
                ty = clamp(ty, by + AIM_POINT_LO, by + AIM_POINT_HI);
                tz = clamp(tz, bz + AIM_POINT_LO, bz + AIM_POINT_HI);
                fy = clamp(fy, by + AIM_POINT_LO, by + AIM_POINT_HI);
                fz = clamp(fz, bz + AIM_POINT_LO, bz + AIM_POINT_HI);
            } else if (axis == 1) {
                tx = cx + u; ty = cy + sign * AIM_POINT_INSET; tz = cz + v;
                fx = tx; fy = cy + sign * AIM_POINT_FACE_INSET; fz = tz;
                tx = clamp(tx, bx + AIM_POINT_LO, bx + AIM_POINT_HI);
                tz = clamp(tz, bz + AIM_POINT_LO, bz + AIM_POINT_HI);
                fx = clamp(fx, bx + AIM_POINT_LO, bx + AIM_POINT_HI);
                fz = clamp(fz, bz + AIM_POINT_LO, bz + AIM_POINT_HI);
            } else {
                tx = cx + u; ty = cy + v; tz = cz + sign * AIM_POINT_INSET;
                fx = tx; fy = ty; fz = cz + sign * AIM_POINT_FACE_INSET;
                tx = clamp(tx, bx + AIM_POINT_LO, bx + AIM_POINT_HI);
                ty = clamp(ty, by + AIM_POINT_LO, by + AIM_POINT_HI);
                fx = clamp(fx, bx + AIM_POINT_LO, bx + AIM_POINT_HI);
                fy = clamp(fy, by + AIM_POINT_LO, by + AIM_POINT_HI);
            }
            result[i] = new double[]{tx, ty, tz, fx, fy, fz};
        }
        return result;
    }

    private static double[][] fallbackSamples(int axis, double sign, int bx, int by, int bz,
                                               double cx, double cy, double cz) {
        double[] offsets = {0, 0, 0.3, 0, -0.3, 0, 0, 0.3, 0, -0.3};
        int count = offsets.length / 2;
        double[][] result = new double[count][6];

        for (int i = 0; i < count; i++) {
            double u = offsets[i * 2], v = offsets[i * 2 + 1];
            double tx, ty, tz, fx, fy, fz;
            if (axis == 0) {
                tx = cx + sign * AIM_POINT_INSET; ty = cy + u; tz = cz + v;
                fx = cx + sign * AIM_POINT_FACE_INSET; fy = cy + u; fz = cz + v;
                ty = clamp(ty, by + AIM_POINT_LO, by + AIM_POINT_HI);
                tz = clamp(tz, bz + AIM_POINT_LO, bz + AIM_POINT_HI);
                fy = clamp(fy, by + AIM_POINT_LO, by + AIM_POINT_HI);
                fz = clamp(fz, bz + AIM_POINT_LO, bz + AIM_POINT_HI);
            } else if (axis == 1) {
                ty = cy + sign * AIM_POINT_INSET; tx = cx + u; tz = cz + v;
                fy = cy + sign * AIM_POINT_FACE_INSET; fx = cx + u; fz = cz + v;
                tx = clamp(tx, bx + AIM_POINT_LO, bx + AIM_POINT_HI);
                tz = clamp(tz, bz + AIM_POINT_LO, bz + AIM_POINT_HI);
                fx = clamp(fx, bx + AIM_POINT_LO, bx + AIM_POINT_HI);
                fz = clamp(fz, bz + AIM_POINT_LO, bz + AIM_POINT_HI);
            } else {
                tz = cz + sign * AIM_POINT_INSET; tx = cx + u; ty = cy + v;
                fz = cz + sign * AIM_POINT_FACE_INSET; fx = cx + u; fy = cy + v;
                tx = clamp(tx, bx + AIM_POINT_LO, bx + AIM_POINT_HI);
                ty = clamp(ty, by + AIM_POINT_LO, by + AIM_POINT_HI);
                fx = clamp(fx, bx + AIM_POINT_LO, bx + AIM_POINT_HI);
                fy = clamp(fy, by + AIM_POINT_LO, by + AIM_POINT_HI);
            }
            result[i] = new double[]{tx, ty, tz, fx, fy, fz};
        }
        return result;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private static double clampRange(double eyeCoord, int blockCoord) {
        return clamp(eyeCoord, blockCoord + AIM_POINT_LO, blockCoord + AIM_POINT_HI);
    }

    private static double clampMid(double val) {
        return clamp(val, -AIM_POINT_MID_CAP, AIM_POINT_MID_CAP);
    }

    private static int orthoAxis1(int axis) {
        return axis == 0 ? 1 : 0;
    }

    private static int orthoAxis2(int axis) {
        return axis == 2 ? 1 : 2;
    }
}
