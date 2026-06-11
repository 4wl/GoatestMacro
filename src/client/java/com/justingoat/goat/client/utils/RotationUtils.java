package com.justingoat.goat.client.utils;

import net.minecraft.util.math.MathHelper;

/**
 * Human-like rotation with:
 * - Fine GCD quantization (low sensitivity → small discrete steps)
 * - Exponential-decay easing (smooth camera-follow feel)
 * - Minimal micro-jitter (undetectable but breaks perfect-machine patterns)
 *
 * Designed for continuous target updates: setTarget() every tick is expected
 * and does NOT reset internal state. The rotation simply tracks the latest
 * target with smooth convergence.
 */
public class RotationUtils {

    private static final float SENSITIVITY = 0.25f;
    private static final float GCD;

    static {
        float f = SENSITIVITY * 0.6f + 0.2f;
        GCD = f * f * f * 8.0f; // ≈ 0.343°
    }

    private float targetYaw;
    private float targetPitch;
    private boolean active = false;
    private float speed = 0.50f;

    /**
     * Update target angles. Safe to call every tick — no internal state reset.
     */
    public void setTarget(float yaw, float pitch) {
        this.targetYaw = yaw;
        this.targetPitch = MathHelper.clamp(pitch, -90.0f, 90.0f);
        this.active = true;
    }

    public boolean isActive() {
        return active;
    }

    public void setSpeed(float speed) {
        this.speed = MathHelper.clamp(speed, 0.1f, 1.0f);
    }

    public void clear() {
        this.active = false;
    }

    /**
     * True if current rotation is close enough to the target for movement to start.
     */
    public boolean isRoughlyFacing(float currentYaw, float currentPitch) {
        if (!active) return true;
        float yd = Math.abs(MathHelper.wrapDegrees(targetYaw - currentYaw));
        float pd = Math.abs(targetPitch - currentPitch);
        return yd < 5.0f && pd < 5.0f;
    }

    /**
     * Advance one tick. Returns [newYaw, newPitch] or null if inactive.
     */
    public float[] tick(float currentYaw, float currentPitch) {
        if (!active) return null;

        float yawDiff = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDiff = targetPitch - currentPitch;
        float totalDist = (float) Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);

        // Close enough — snap to exact target
        if (totalDist < GCD) {
            return new float[]{
                currentYaw + yawDiff,
                MathHelper.clamp(currentPitch + pitchDiff, -90.0f, 90.0f)
            };
        }

        float factor = speed;

        // Slight random variation ±8% — human noise without visible stutter
        factor *= 1.0f + (float) (Math.random() - 0.5) * 0.16f;

        // Large turns (>60°): boost to prevent sluggish 180° turns
        if (totalDist > 60.0f) {
            factor = Math.min(factor * 1.25f, 0.65f);
        }

        float deltaYaw = yawDiff * factor;
        float deltaPitch = pitchDiff * factor;

        // GCD quantization — mimics real mouse input
        deltaYaw = snapToGCD(deltaYaw);
        deltaPitch = snapToGCD(deltaPitch);

        // Very subtle jitter: 10% of ticks, ±0.1° max
        if (Math.random() < 0.10) {
            deltaYaw += snapToGCD((float) (Math.random() - 0.5) * 0.15f);
            deltaPitch += snapToGCD((float) (Math.random() - 0.5) * 0.08f);
        }

        float newYaw = currentYaw + deltaYaw;
        float newPitch = MathHelper.clamp(currentPitch + deltaPitch, -90.0f, 90.0f);

        return new float[]{newYaw, newPitch};
    }

    // --- Static helpers ---

    public static float[] lookAt(double eyeX, double eyeY, double eyeZ,
                                  double targetX, double targetY, double targetZ) {
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;

        double horizDist = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizDist));
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;

        return new float[]{yaw, pitch};
    }

    private static float snapToGCD(float delta) {
        if (Math.abs(delta) < GCD * 0.2f) return 0.0f;
        return Math.round(delta / GCD) * GCD;
    }
}
