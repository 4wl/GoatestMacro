package com.justingoat.goat.client.module.failsafe;

import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.RotationUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

public class FailsafeReactionController {
    private static final String[] FIRST_MESSAGES = {
        "hi", "?", "what", "yo?", "huh", "lol what"
    };

    private final MinecraftClient client = MinecraftClient.getInstance();
    private final Random random = new Random();
    private final RotationUtils rotation = new RotationUtils();

    private boolean active = false;
    private long stopAt = 0;
    private long nextLookAt = 0;
    private long nextMoveAt = 0;
    private long stopMoveAt = 0;
    private long chatAt = 0;
    private boolean sentChat = false;

    public void start(Failsafe failsafe) {
        stop();

        if (client.player == null) return;

        long now = System.currentTimeMillis();
        active = true;
        stopAt = now + durationFor(failsafe);
        nextLookAt = now + randomBetween(250, 700);
        nextMoveAt = now + randomBetween(600, 1400);
        stopMoveAt = 0;
        chatAt = now + randomBetween(2200, 5200);
        sentChat = false;

        rotation.init(client.player.getYaw(), client.player.getPitch());
        rotation.setSpeed(0.25f + random.nextFloat() * 0.25f);
        RotationInterpolator.setActive(rotation);
    }

    public void tick() {
        if (!active) return;

        if (client.player == null || client.world == null) {
            stop();
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= stopAt) {
            stop();
            return;
        }

        rotation.tick();

        if (now >= nextLookAt) {
            chooseNextLookTarget();
            nextLookAt = now + randomBetween(650, 1600);
        }

        if (stopMoveAt > 0 && now >= stopMoveAt) {
            InputUtils.releaseAll();
            stopMoveAt = 0;
            nextMoveAt = now + randomBetween(300, 1200);
        }

        if (stopMoveAt == 0 && now >= nextMoveAt) {
            chooseNextMovement();
            stopMoveAt = now + randomBetween(350, 1100);
        }

        if (!sentChat && now >= chatAt) {
            sendShortChatMessage();
            sentChat = true;
        }
    }

    public void stop() {
        active = false;
        InputUtils.releaseAll();
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

        float yawOffset = randomBetweenFloat(-85.0f, 85.0f);
        float pitchOffset = randomBetweenFloat(-22.0f, 20.0f);
        float targetYaw = currentYaw + yawOffset;
        float targetPitch = MathHelper.clamp(currentPitch + pitchOffset, -55.0f, 55.0f);

        rotation.setSpeed(0.18f + random.nextFloat() * 0.32f);
        rotation.setTarget(targetYaw, targetPitch);
    }

    private void chooseNextMovement() {
        InputUtils.releaseAll();

        int pattern = random.nextInt(8);
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
    }

    private void sendShortChatMessage() {
        if (client.player == null || client.player.networkHandler == null) return;

        String message = FIRST_MESSAGES[random.nextInt(FIRST_MESSAGES.length)];
        client.player.networkHandler.sendChatMessage(message);
    }

    private long randomBetween(long min, long max) {
        return min + (long) (random.nextDouble() * (max - min));
    }

    private float randomBetweenFloat(float min, float max) {
        return min + random.nextFloat() * (max - min);
    }
}
