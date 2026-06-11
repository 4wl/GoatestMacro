package com.justingoat.goat.client.utils;

import net.minecraft.util.math.MathHelper;

/**
 * Spring-damper rotation controller with render-frame interpolation,
 * Perlin noise humanization, and GCD mouse quantization.
 *
 * All yaw/pitch state is tracked internally. The only external writer
 * should be RotationInterpolator (per render frame). PathProcessor
 * must never call player.setYaw/setPitch directly.
 */
public class RotationUtils {

    // ── GCD quantization (simulates real mouse hardware) ──
    private static final float SENSITIVITY = 0.25f;
    private static final float GCD;
    static {
        float f = SENSITIVITY * 0.6f + 0.2f;
        GCD = f * f * f * 8.0f;
    }

    // ── Spring-damper parameters ──
    private float naturalFreq = 14.0f;
    private float dampingRatio = 0.72f;

    // ── Internal authoritative state ──
    private float currentYaw;
    private float currentPitch;
    private boolean initialized = false;

    // ── Interpolation snapshots ──
    private float tickYawStart;
    private float tickYawEnd;
    private float tickPitchStart;
    private float tickPitchEnd;

    // ── Spring velocity ──
    private float yawVelocity = 0.0f;
    private float pitchVelocity = 0.0f;

    // ── Target ──
    private float targetYaw;
    private float targetPitch;
    private boolean active = false;

    // ── Speed multiplier (GUI-exposed) ──
    private float speed = 0.50f;

    // ── Perlin noise humanizer ──
    private float noisePhaseYaw = (float)(Math.random() * 1000.0);
    private float noisePhasePitch = (float)(Math.random() * 1000.0);
    private static final float NOISE_SPEED = 0.07f;
    private static final float NOISE_AMP_YAW = 0.12f;
    private static final float NOISE_AMP_PITCH = 0.06f;

    // ── Settling detection ──
    private int settledTicks = 0;
    private static final int SETTLED_THRESHOLD = 3;

    // ────────────────────────────────────────── public API

    /**
     * Initialize internal state from the player's current rotation.
     * Must be called once before the first tick().
     */
    public void init(float yaw, float pitch) {
        this.currentYaw = yaw;
        this.currentPitch = pitch;
        this.tickYawStart = yaw;
        this.tickYawEnd = yaw;
        this.tickPitchStart = pitch;
        this.tickPitchEnd = pitch;
        this.initialized = true;
    }

    public void setTarget(float yaw, float pitch) {
        this.targetYaw = yaw;
        this.targetPitch = MathHelper.clamp(pitch, -90.0f, 90.0f);
        this.active = true;
        this.settledTicks = 0;
    }

    public boolean isActive() { return active; }

    public float getCurrentYaw() { return currentYaw; }
    public float getCurrentPitch() { return currentPitch; }

    public void setSpeed(float speed) {
        this.speed = MathHelper.clamp(speed, 0.1f, 1.0f);
        this.naturalFreq = 8.0f + speed * 14.0f;
        this.dampingRatio = 0.82f - speed * 0.20f;
    }

    public void clear() {
        this.active = false;
        this.initialized = false;
        this.yawVelocity = 0.0f;
        this.pitchVelocity = 0.0f;
        this.settledTicks = 0;
    }

    public boolean isRoughlyFacing() {
        if (!active || !initialized) return true;
        float yd = Math.abs(MathHelper.wrapDegrees(targetYaw - currentYaw));
        float pd = Math.abs(targetPitch - currentPitch);
        return yd < 5.0f && pd < 5.0f;
    }

    /**
     * Called once per client tick (20 TPS). Advances the spring-damper
     * using internal state. Stores start/end snapshots for interpolation.
     */
    public void tick() {
        if (!active || !initialized) return;

        tickYawStart = currentYaw;
        tickPitchStart = currentPitch;

        float dt = 1.0f / 20.0f;

        // ── Yaw (wrap-safe) ──
        float yawError = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float[] yawResult = springStep(yawError, yawVelocity, dt);
        float yawDelta = yawResult[0];
        yawVelocity = yawResult[1];

        // ── Pitch ──
        float pitchError = targetPitch - currentPitch;
        float[] pitchResult = springStep(pitchError, pitchVelocity, dt);
        float pitchDelta = pitchResult[0];
        pitchVelocity = pitchResult[1];

        // ── Perlin noise humanization ──
        noisePhaseYaw += NOISE_SPEED;
        noisePhasePitch += NOISE_SPEED;
        float totalError = Math.abs(yawError) + Math.abs(pitchError);

        float noiseScale = Math.min(1.0f, totalError / 15.0f);
        if (settledTicks > SETTLED_THRESHOLD) noiseScale *= 0.15f;

        yawDelta += perlinNoise1D(noisePhaseYaw) * NOISE_AMP_YAW * noiseScale;
        pitchDelta += perlinNoise1D(noisePhasePitch) * NOISE_AMP_PITCH * noiseScale;

        // ── GCD quantization ──
        yawDelta = snapToGCD(yawDelta);
        pitchDelta = snapToGCD(pitchDelta);

        // ── Settle check ──
        if (Math.abs(yawError) < GCD * 2.0f && Math.abs(pitchError) < GCD * 2.0f
                && Math.abs(yawVelocity) < 1.0f && Math.abs(pitchVelocity) < 1.0f) {
            settledTicks++;
        } else {
            settledTicks = 0;
        }

        if (Math.abs(yawError) < GCD && Math.abs(pitchError) < GCD && settledTicks > SETTLED_THRESHOLD) {
            yawDelta = snapToGCD(yawError);
            pitchDelta = snapToGCD(pitchError);
            yawVelocity = 0;
            pitchVelocity = 0;
        }

        currentYaw = currentYaw + yawDelta;
        currentPitch = MathHelper.clamp(currentPitch + pitchDelta, -90.0f, 90.0f);

        tickYawEnd = currentYaw;
        tickPitchEnd = currentPitch;
    }

    /**
     * Interpolate between tick snapshots for render-frame smoothing.
     * partialTick = 0.0 at tick start, 1.0 at tick end.
     */
    public float[] interpolate(float partialTick) {
        if (!active || !initialized) return null;

        float yaw = tickYawStart + MathHelper.wrapDegrees(tickYawEnd - tickYawStart) * partialTick;
        float pitch = MathHelper.lerp(partialTick, tickPitchStart, tickPitchEnd);
        pitch = MathHelper.clamp(pitch, -90.0f, 90.0f);

        return new float[]{yaw, pitch};
    }

    // ────────────────────────────────────────── spring-damper core

    private float[] springStep(float error, float velocity, float dt) {
        float wn = naturalFreq;
        float zeta = dampingRatio;

        float accel = wn * wn * error - 2.0f * zeta * wn * velocity;

        float newVelocity = velocity + accel * dt;

        float absError = Math.abs(error);
        if (absError > 120.0f) {
            newVelocity = MathHelper.clamp(newVelocity, -absError * 2.0f, absError * 2.0f);
        }

        float displacement = newVelocity * dt;

        if (Math.abs(displacement) > Math.abs(error) * 1.15f && Math.abs(error) > GCD) {
            displacement = error * 1.15f;
            newVelocity = displacement / dt;
        }

        return new float[]{displacement, newVelocity};
    }

    // ────────────────────────────────────────── humanizer (Perlin 1D)

    private static float perlinNoise1D(float x) {
        int xi = (int) Math.floor(x);
        float xf = x - xi;
        float u = fade(xf);
        float g0 = grad1D(hash(xi), xf);
        float g1 = grad1D(hash(xi + 1), xf - 1.0f);
        return lerp(g0, g1, u);
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f);
    }

    private static float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }

    private static float grad1D(int hash, float x) {
        return (hash & 1) == 0 ? x : -x;
    }

    private static int hash(int x) {
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = (x >> 16) ^ x;
        return x & 0xFF;
    }

    // ────────────────────────────────────────── GCD quantization

    private static float snapToGCD(float delta) {
        if (Math.abs(delta) < GCD * 0.2f) return 0.0f;
        return Math.round(delta / GCD) * GCD;
    }

    // ────────────────────────────────────────── static helpers

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
}
