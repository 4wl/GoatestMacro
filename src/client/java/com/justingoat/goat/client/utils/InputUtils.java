package com.justingoat.goat.client.utils;

import com.justingoat.goat.client.mixin.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class InputUtils {
    private static boolean macroAttackPressed = false;
    private static boolean macroUsePressed = false;
    private static BlockPos ungrabbedBreakTarget = null;
    private static Direction ungrabbedBreakSide = null;

    public static void setForward(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.forwardKey, pressed);
    }

    public static void setBack(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.backKey, pressed);
    }

    public static void setLeft(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.leftKey, pressed);
    }

    public static void setRight(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.rightKey, pressed);
    }

    public static void setAttack(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        boolean wasPressed = macroAttackPressed;
        macroAttackPressed = pressed;
        if (MouseUtils.isMouseUngrabbed()) {
            MouseUtils.setCursorLockedForMacroInput(pressed);
        }
        setKey(client.options.attackKey, pressed);
        if (!pressed) {
            stopUngrabbedBlockBreaking(client);
        } else if (pressed && !wasPressed) {
            triggerAttackWhenUngrabbed(client);
        }
    }

    public static void clickAttack() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null || client.currentScreen != null) return;
        setKey(client.options.attackKey, true);
        if (MouseUtils.isMouseUngrabbed()) {
            triggerAttackWhenUngrabbed(client);
        } else {
            ((MinecraftClientAccessor) client).invokeDoAttack();
        }
    }

    public static void setJump(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.jumpKey, pressed);
    }

    public static void setSprint(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.sprintKey, pressed);
    }

    public static void setSneak(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        setKey(client.options.sneakKey, pressed);
    }

    public static void setUse(boolean pressed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;
        macroUsePressed = pressed;
        setKey(client.options.useKey, pressed);
        if (pressed) {
            triggerUseWhenUngrabbed(client);
        }
    }

    public static void setHotbarSlot(int slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        client.player.getInventory().setSelectedSlot(Math.max(0, Math.min(8, slot)));
    }

    public static void releaseAll() {
        setSneak(false);
        setUse(false);
        setForward(false);
        setBack(false);
        setLeft(false);
        setRight(false);
        setAttack(false);
        setJump(false);
        setSprint(false);
    }

    private static void setKey(KeyBinding keyBinding, boolean pressed) {
        if (keyBinding != null) {
            keyBinding.setPressed(pressed);
        }
    }

    public static void tickUngrabbedMouseInputs(MinecraftClient client) {
        if (!canSendMouseInputWhenUngrabbed(client)) return;

        if (macroAttackPressed) {
            triggerAttackWhenUngrabbed(client);
        } else {
            stopUngrabbedBlockBreaking(client);
        }

        if (macroUsePressed) {
            triggerUseWhenUngrabbed(client);
        }
    }

    private static void triggerAttackWhenUngrabbed(MinecraftClient client) {
        if (!canSendMouseInputWhenUngrabbed(client)) return;
        if (tryBreakBlockWhenUngrabbed(client)) return;

        ((MinecraftClientAccessor) client).invokeDoAttack();
    }

    private static void triggerUseWhenUngrabbed(MinecraftClient client) {
        if (!canSendMouseInputWhenUngrabbed(client)) return;
        ((MinecraftClientAccessor) client).invokeDoItemUse();
    }

    private static boolean canSendMouseInputWhenUngrabbed(MinecraftClient client) {
        return client != null
            && client.currentScreen == null
            && client.mouse != null
            && MouseUtils.isMouseUngrabbed()
            && !client.mouse.isCursorLocked();
    }

    private static boolean tryBreakBlockWhenUngrabbed(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            resetUngrabbedBlockBreaking();
            return false;
        }

        if (ungrabbedBreakTarget != null && ungrabbedBreakSide != null) {
            if (client.world.isAir(ungrabbedBreakTarget)) {
                resetUngrabbedBlockBreaking();
                return false;
            }
            client.interactionManager.updateBlockBreakingProgress(ungrabbedBreakTarget, ungrabbedBreakSide);
            client.player.swingHand(Hand.MAIN_HAND);
            return true;
        }

        if (client.crosshairTarget == null || client.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            stopUngrabbedBlockBreaking(client);
            return false;
        }

        BlockHitResult hit = (BlockHitResult) client.crosshairTarget;
        BlockPos pos = hit.getBlockPos();
        Direction side = hit.getSide();

        client.interactionManager.attackBlock(pos, side);
        ungrabbedBreakTarget = pos.toImmutable();
        ungrabbedBreakSide = side;
        client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private static void stopUngrabbedBlockBreaking(MinecraftClient client) {
        if (ungrabbedBreakTarget == null) return;
        resetUngrabbedBlockBreaking();
        if (client != null) {
            if (client.interactionManager != null) {
                client.interactionManager.cancelBlockBreaking();
            } else {
                ((MinecraftClientAccessor) client).invokeHandleBlockBreaking(false);
            }
        }
    }

    private static void resetUngrabbedBlockBreaking() {
        ungrabbedBreakTarget = null;
        ungrabbedBreakSide = null;
    }
}
