package com.justingoat.goat.client.module.failsafe;

import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.MacroClock;
import com.justingoat.goat.client.utils.MacroControls;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.RotationUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

public class FailsafeReactionController {
    private final MinecraftClient client = MinecraftClient.getInstance();
    private final Random random = new Random();
    private final RotationUtils rotation = new RotationUtils();
    private final MacroClock clock = new MacroClock();

    private boolean active = false;
    private long stopAt = 0;
    private long nextLookAt = 0;
    private long nextMicroLookAt = 0;
    private long nextMoveAt = 0;
    private long stopMoveAt = 0;
    private long reactAt = 0;

    public void start(Failsafe failsafe) {
        stop();

        if (client.player == null) return;

        long now = clock.now();
        active = true;
        stopAt = now + durationFor(failsafe);
        reactAt = now + randomBetween(180, 650);
        nextLookAt = reactAt + randomBetween(120, 520);
        nextMicroLookAt = reactAt + randomBetween(450, 1200);
        nextMoveAt = reactAt + randomBetween(650, 1900);
        stopMoveAt = 0;

        rotation.init(client.player.getYaw(), client.player.getPitch());
        rotation.setSpeed(randomBetweenFloat(0.16f, 0.34f));
        RotationInterpolator.setActive(rotation);
    }

    public void tick() {
        if (!active) return;

        if (client.player == null || client.world == null) {
            stop();
            return;
        }

        long now = clock.now();
        if (now >= stopAt) {
            stop();
            return;
        }

        if (now < reactAt) return;

        rotation.tick();

        if (now >= nextLookAt) {
            chooseNextLookTarget();
            nextLookAt = now + randomBetween(900, 2600);
        }

        if (now >= nextMicroLookAt) {
            chooseMicroLookAdjustment();
            nextMicroLookAt = now + randomBetween(240, 950);
        }

        if (stopMoveAt > 0 && now >= stopMoveAt) {
            MacroControls.stopAll();
            stopMoveAt = 0;
            nextMoveAt = now + randomBetween(220, 1500);
        }

        if (stopMoveAt == 0 && now >= nextMoveAt) {
            stopMoveAt = now + chooseNextMovementDuration();
        }
    }

    public void stop() {
        active = false;
        MacroControls.stopAll();
        rotation.clear();
        RotationInterpolator.clearActive();
    }

    public boolean isActive() {
        return active;
    }

    private long durationFor(Failsafe failsafe) {
        if (failsafe != null && "Rotation Check".equals(failsafe.getName())) {
            return randomBetween(12_000, 18_000);
        }
        return randomBetween(7_000, 12_000);
    }

    private void chooseNextLookTarget() {
        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();

        float roll = random.nextFloat();
        float yawOffset;
        float pitchOffset;
        if (roll < 0.18f) {
            yawOffset = random.nextBoolean()
                ? randomBetweenFloat(55.0f, 105.0f)
                : randomBetweenFloat(-105.0f, -55.0f);
            pitchOffset = randomCentered(10.0f);
        } else if (roll < 0.50f) {
            yawOffset = randomCentered(34.0f);
            pitchOffset = randomBetweenFloat(-18.0f, 8.0f);
        } else if (roll < 0.78f) {
            yawOffset = randomCentered(18.0f);
            pitchOffset = randomCentered(9.0f);
        } else {
            yawOffset = randomCentered(7.0f);
            pitchOffset = randomCentered(4.0f);
        }

        float targetYaw = currentYaw + yawOffset;
        float targetPitch = MathHelper.clamp(currentPitch + pitchOffset, -55.0f, 55.0f);
        float distance = Math.abs(yawOffset) + Math.abs(pitchOffset);

        rotation.setSpeed(MathHelper.clamp(
            randomBetweenFloat(0.14f, 0.30f) + distance / 260.0f,
            0.14f,
            0.56f
        ));
        rotation.setTarget(targetYaw, targetPitch);
    }

    private void chooseMicroLookAdjustment() {
        if (random.nextFloat() < 0.30f) return;

        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();
        float yawOffset = randomCentered(randomBetweenFloat(1.2f, 4.8f));
        float pitchOffset = randomCentered(randomBetweenFloat(0.8f, 2.8f));

        rotation.setSpeed(randomBetweenFloat(0.12f, 0.24f));
        rotation.setTarget(
            currentYaw + yawOffset,
            MathHelper.clamp(currentPitch + pitchOffset, -55.0f, 55.0f)
        );
    }

    private long chooseNextMovementDuration() {
        MacroControls.stopAll();

        float roll = random.nextFloat();
        int pattern;
        if (roll < 0.24f) {
            return randomBetween(180, 520);
        } else if (roll < 0.48f) {
            pattern = random.nextBoolean() ? 2 : 3;
        } else if (roll < 0.66f) {
            pattern = random.nextBoolean() ? 0 : 1;
        } else if (roll < 0.84f) {
            pattern = 4 + random.nextInt(3);
        } else {
            pattern = 7;
        }

        switch (pattern) {
            case 0 -> InputUtils.setForward(true);
            case 1 -> InputUtils.setBack(true);
            case 2 -> InputUtils.setLeft(true);
            case 3 -> InputUtils.setRight(true);
            case 4 -> {
                InputUtils.setForward(true);
                InputUtils.setLeft(true);
            }
            case 5 -> {
                InputUtils.setForward(true);
                InputUtils.setRight(true);
            }
            case 6 -> {
                InputUtils.setBack(true);
                InputUtils.setLeft(true);
            }
            case 7 -> {
                InputUtils.setSneak(true);
                if (random.nextBoolean()) {
                    InputUtils.setLeft(true);
                } else {
                    InputUtils.setRight(true);
                }
            }
            default -> {
            }
        }

        if (random.nextFloat() < 0.18f) {
            InputUtils.setJump(true);
        }

        if (pattern == 7) {
            return randomBetween(450, 1200);
        }
        if (pattern >= 4) {
            return randomBetween(260, 780);
        }
        return randomBetween(180, 620);
    }

    private long randomBetween(long min, long max) {
        return min + (long) (random.nextDouble() * (max - min));
    }

    private float randomBetweenFloat(float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    private float randomCentered(float magnitude) {
        return (random.nextFloat() + random.nextFloat() - 1.0f) * magnitude;
    }
}
