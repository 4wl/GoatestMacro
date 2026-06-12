package com.justingoat.goat.client.utils;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.settings.RotationSettings;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

/**
 * Shared rotation controller with render-frame interpolation.
 * Modes are configured through the RotationSettings module in the GUI.
 */
public class RotationUtils {
    private static final float SENSITIVITY = 0.25f;
    private static final float GCD;

    static {
        float f = SENSITIVITY * 0.6f + 0.2f;
        GCD = f * f * f * 8.0f;
    }

    private static final float NOISE_SPEED = 0.07f;
    private static final float NOISE_AMP_YAW = 0.12f;
    private static final float NOISE_AMP_PITCH = 0.06f;
    private static final int SETTLED_THRESHOLD = 3;

    private final Random random = new Random();

    private float naturalFreq = 14.0f;
    private float dampingRatio = 0.72f;

    private float currentYaw;
    private float currentPitch;
    private boolean initialized = false;

    private float tickYawStart;
    private float tickYawEnd;
    private float tickPitchStart;
    private float tickPitchEnd;

    private float yawVelocity = 0.0f;
    private float pitchVelocity = 0.0f;

    private float targetYaw;
    private float targetPitch;
    private boolean active = false;
    private float speed = 0.50f;

    private float noisePhaseYaw = (float) (Math.random() * 1000.0);
    private float noisePhasePitch = (float) (Math.random() * 1000.0);
    private int settledTicks = 0;

    private String lastCurveMode = "";
    private float curveStartYaw;
    private float curveStartPitch;
    private float curveControlYaw1;
    private float curveControlYaw2;
    private float curveControlPitch1;
    private float curveControlPitch2;
    private float curveProgress = 1.0f;
    private float curveDurationTicks = 12.0f;
    private float curveVelocityYaw = 0.0f;
    private float curveVelocityPitch = 0.0f;

    public void init(float yaw, float pitch) {
        this.currentYaw = yaw;
        this.currentPitch = pitch;
        this.tickYawStart = yaw;
        this.tickYawEnd = yaw;
        this.tickPitchStart = pitch;
        this.tickPitchEnd = pitch;
        this.curveStartYaw = yaw;
        this.curveStartPitch = pitch;
        this.initialized = true;
    }

    public void setTarget(float yaw, float pitch) {
        RotationSettings settings = getSettings();
        String mode = settings.getMode();
        float clampedPitch = MathHelper.clamp(pitch, -90.0f, 90.0f);
        float targetShift = active
            ? Math.abs(MathHelper.wrapDegrees(yaw - targetYaw)) + Math.abs(clampedPitch - targetPitch)
            : Float.MAX_VALUE;

        this.targetYaw = yaw;
        this.targetPitch = clampedPitch;
        this.active = true;
        this.settledTicks = 0;

        if (!"Spring".equals(mode) && initialized) {
            if (!mode.equals(lastCurveMode) || curveProgress >= 1.0f) {
                configureCurve(settings, mode);
            } else if (targetShift > 2.0f) {
                retargetCurve(settings, mode);
            }
        }
    }

    public boolean isActive() {
        return active;
    }

    public float getCurrentYaw() {
        return currentYaw;
    }

    public float getCurrentPitch() {
        return currentPitch;
    }

    public void setSpeed(float speed) {
        this.speed = MathHelper.clamp(speed, 0.1f, 1.0f);
    }

    public void clear() {
        this.active = false;
        this.initialized = false;
        this.yawVelocity = 0.0f;
        this.pitchVelocity = 0.0f;
        this.settledTicks = 0;
        this.curveProgress = 1.0f;
        this.curveVelocityYaw = 0.0f;
        this.curveVelocityPitch = 0.0f;
    }

    public boolean isRoughlyFacing() {
        if (!active || !initialized) return true;
        float yd = Math.abs(MathHelper.wrapDegrees(targetYaw - currentYaw));
        float pd = Math.abs(targetPitch - currentPitch);
        return yd < 5.0f && pd < 5.0f;
    }

    public void tick() {
        if (!active || !initialized) return;

        tickYawStart = currentYaw;
        tickPitchStart = currentPitch;

        RotationSettings settings = getSettings();
        if ("Spring".equals(settings.getMode())) {
            tickSpring(settings);
        } else {
            tickCurve(settings);
        }
    }

    public float[] interpolate(float partialTick) {
        if (!active || !initialized) return null;

        float yaw = tickYawStart + MathHelper.wrapDegrees(tickYawEnd - tickYawStart) * partialTick;
        float pitch = MathHelper.lerp(partialTick, tickPitchStart, tickPitchEnd);
        pitch = MathHelper.clamp(pitch, -90.0f, 90.0f);

        return new float[]{yaw, pitch};
    }

    private void tickSpring(RotationSettings settings) {
        float dt = 1.0f / 20.0f;
        float effectiveSpeed = getEffectiveSpeed(settings);
        float smoothness = settings.getSmoothness();
        naturalFreq = 7.0f + effectiveSpeed * 16.0f * (1.0f - smoothness * 0.35f);
        dampingRatio = 0.88f - Math.min(1.0f, effectiveSpeed) * 0.18f + smoothness * 0.12f;

        float yawError = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float[] yawResult = springStep(yawError, yawVelocity, dt);
        float yawDelta = yawResult[0];
        yawVelocity = yawResult[1];

        float pitchError = targetPitch - currentPitch;
        float[] pitchResult = springStep(pitchError, pitchVelocity, dt);
        float pitchDelta = pitchResult[0];
        pitchVelocity = pitchResult[1];

        noisePhaseYaw += NOISE_SPEED;
        noisePhasePitch += NOISE_SPEED;
        float totalError = Math.abs(yawError) + Math.abs(pitchError);
        float noiseScale = Math.min(1.0f, totalError / 15.0f);
        if (settledTicks > SETTLED_THRESHOLD) noiseScale *= 0.15f;

        yawDelta += perlinNoise1D(noisePhaseYaw) * NOISE_AMP_YAW * settings.getNoise() * noiseScale;
        pitchDelta += perlinNoise1D(noisePhasePitch) * NOISE_AMP_PITCH * settings.getNoise() * noiseScale;

        if (settings.shouldQuantize()) {
            yawDelta = snapToGCD(yawDelta);
            pitchDelta = snapToGCD(pitchDelta);
        }

        if (Math.abs(yawError) < GCD * 2.0f && Math.abs(pitchError) < GCD * 2.0f
            && Math.abs(yawVelocity) < 1.0f && Math.abs(pitchVelocity) < 1.0f) {
            settledTicks++;
        } else {
            settledTicks = 0;
        }

        if (Math.abs(yawError) < GCD && Math.abs(pitchError) < GCD && settledTicks > SETTLED_THRESHOLD) {
            yawDelta = settings.shouldQuantize() ? snapToGCD(yawError) : yawError;
            pitchDelta = settings.shouldQuantize() ? snapToGCD(pitchError) : pitchError;
            yawVelocity = 0.0f;
            pitchVelocity = 0.0f;
        }

        currentYaw += yawDelta;
        currentPitch = MathHelper.clamp(currentPitch + pitchDelta, -90.0f, 90.0f);

        tickYawEnd = currentYaw;
        tickPitchEnd = currentPitch;
    }

    private void tickCurve(RotationSettings settings) {
        String mode = settings.getMode();
        if (curveProgress >= 1.0f || !mode.equals(lastCurveMode)) {
            configureCurve(settings, mode);
        }

        float previousT = curveProgress;
        curveProgress = Math.min(1.0f, curveProgress + 1.0f / Math.max(1.0f, curveDurationTicks));
        float t = curveProgress;
        float yawDelta = MathHelper.wrapDegrees(targetYaw - curveStartYaw);
        float pitchDelta = targetPitch - curveStartPitch;

        float desiredYaw;
        float desiredPitch;
        if ("Linear".equals(mode)) {
            float eased = linearBlend(t, settings.getSmoothness());
            desiredYaw = curveStartYaw + yawDelta * eased;
            desiredPitch = curveStartPitch + pitchDelta * eased;
        } else {
            float eased = cubicBezier(0.0f, 0.18f, 0.92f, 1.0f, t);
            desiredYaw = curveStartYaw + cubicBezier(0.0f, curveControlYaw1, curveControlYaw2, yawDelta, eased);
            desiredPitch = curveStartPitch + cubicBezier(0.0f, curveControlPitch1, curveControlPitch2, pitchDelta, eased);
        }

        float remaining = Math.min(1.0f, (Math.abs(MathHelper.wrapDegrees(targetYaw - currentYaw))
            + Math.abs(targetPitch - currentPitch)) / 40.0f);
        noisePhaseYaw += NOISE_SPEED * 0.75f;
        noisePhasePitch += NOISE_SPEED * 0.75f;
        desiredYaw += perlinNoise1D(noisePhaseYaw) * NOISE_AMP_YAW * settings.getNoise() * remaining;
        desiredPitch += perlinNoise1D(noisePhasePitch) * NOISE_AMP_PITCH * settings.getNoise() * remaining;

        float stepYaw = MathHelper.wrapDegrees(desiredYaw - currentYaw);
        float stepPitch = desiredPitch - currentPitch;
        float maxStep = maxCurveStep(settings, previousT, t);
        stepYaw = MathHelper.clamp(stepYaw, -maxStep, maxStep);
        stepPitch = MathHelper.clamp(stepPitch, -maxStep * 0.72f, maxStep * 0.72f);

        if (settings.shouldQuantize()) {
            stepYaw = snapToGCD(stepYaw);
            stepPitch = snapToGCD(stepPitch);
        }

        currentYaw += stepYaw;
        currentPitch = MathHelper.clamp(currentPitch + stepPitch, -90.0f, 90.0f);
        curveVelocityYaw = stepYaw;
        curveVelocityPitch = stepPitch;

        tickYawEnd = currentYaw;
        tickPitchEnd = currentPitch;
    }

    private void configureCurve(RotationSettings settings, String mode) {
        lastCurveMode = mode;
        curveStartYaw = currentYaw;
        curveStartPitch = currentPitch;
        curveProgress = 0.0f;
        curveVelocityYaw = 0.0f;
        curveVelocityPitch = 0.0f;

        float yawDelta = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float pitchDelta = targetPitch - currentPitch;
        float distance = Math.abs(yawDelta) + Math.abs(pitchDelta);
        curveDurationTicks = MathHelper.clamp(
            (3.0f + settings.getSmoothness() * 9.0f + (float) Math.sqrt(distance) * 0.92f) / getEffectiveSpeed(settings),
            3.0f,
            24.0f
        );

        float overshoot = settings.getOvershoot() * Math.min(1.0f, distance / 55.0f);
        float wobbleScale = settings.getNoise() * Math.min(1.0f, distance / 30.0f);
        float yawWobble = randomBetween(-1.2f, 1.2f) * wobbleScale;
        float pitchWobble = randomBetween(-0.8f, 0.8f) * wobbleScale;

        curveControlYaw1 = yawDelta * randomBetween(0.28f, 0.46f) + yawWobble;
        curveControlYaw2 = yawDelta * (0.86f + overshoot * randomBetween(0.08f, 0.24f)) - yawWobble * 0.5f;
        curveControlPitch1 = pitchDelta * randomBetween(0.28f, 0.46f) + pitchWobble;
        curveControlPitch2 = pitchDelta * (0.86f + overshoot * randomBetween(0.06f, 0.20f)) - pitchWobble * 0.5f;
    }

    private void retargetCurve(RotationSettings settings, String mode) {
        lastCurveMode = mode;

        float remainingYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float remainingPitch = targetPitch - currentPitch;
        float distance = Math.abs(remainingYaw) + Math.abs(remainingPitch);

        curveStartYaw = currentYaw;
        curveStartPitch = currentPitch;
        curveProgress = Math.min(0.35f, curveProgress * 0.5f);
        curveDurationTicks = MathHelper.clamp(
            (2.0f + settings.getSmoothness() * 6.0f + (float) Math.sqrt(distance) * 0.72f) / getEffectiveSpeed(settings),
            2.0f,
            18.0f
        );

        float leadYaw = curveVelocityYaw * 0.55f;
        float leadPitch = curveVelocityPitch * 0.45f;
        float wobbleScale = settings.getNoise() * Math.min(1.0f, distance / 35.0f);
        float yawWobble = randomBetween(-0.8f, 0.8f) * wobbleScale;
        float pitchWobble = randomBetween(-0.55f, 0.55f) * wobbleScale;

        curveControlYaw1 = remainingYaw * randomBetween(0.22f, 0.38f) + leadYaw + yawWobble;
        curveControlYaw2 = remainingYaw * randomBetween(0.82f, 0.96f) + leadYaw * 0.25f - yawWobble * 0.35f;
        curveControlPitch1 = remainingPitch * randomBetween(0.22f, 0.38f) + leadPitch + pitchWobble;
        curveControlPitch2 = remainingPitch * randomBetween(0.82f, 0.96f) + leadPitch * 0.25f - pitchWobble * 0.35f;
    }

    private float[] springStep(float error, float velocity, float dt) {
        float accel = naturalFreq * naturalFreq * error - 2.0f * dampingRatio * naturalFreq * velocity;
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

    private RotationSettings getSettings() {
        GoatModule module = ModuleManager.findByName("RotationSettings");
        if (module instanceof RotationSettings settings) {
            return settings;
        }
        return new RotationSettings();
    }

    private float getEffectiveSpeed(RotationSettings settings) {
        return MathHelper.clamp(0.45f + speed * settings.getGlobalSpeed() * 1.65f, 0.35f, 4.0f);
    }

    private float maxCurveStep(RotationSettings settings, float previousT, float currentT) {
        float base = 7.0f + getEffectiveSpeed(settings) * 8.5f;
        float progressBoost = (float) Math.sin(Math.PI * Math.max(0.0f, Math.min(1.0f, (previousT + currentT) * 0.5f)));
        return base * (0.72f + progressBoost * 0.55f);
    }

    private static float linearBlend(float t, float smoothness) {
        float smooth = t * t * (3.0f - 2.0f * t);
        return MathHelper.lerp(smoothness * 0.45f, t, smooth);
    }

    private float randomBetween(float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    private static float cubicBezier(float p0, float p1, float p2, float p3, float t) {
        float u = 1.0f - t;
        return u * u * u * p0
            + 3.0f * u * u * t * p1
            + 3.0f * u * t * t * p2
            + t * t * t * p3;
    }

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

    private static float snapToGCD(float delta) {
        if (Math.abs(delta) < GCD * 0.2f) return 0.0f;
        return Math.round(delta / GCD) * GCD;
    }

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
