package com.justingoat.goat.client.mixin;

import com.justingoat.goat.client.utils.MouseUtils;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseLockMixin {
    @Inject(method = "lockCursor", at = @At("HEAD"), cancellable = true)
    private void goat$blockMacroCursorLock(CallbackInfo ci) {
        if (MouseUtils.shouldBlockCursorLock()) {
            ci.cancel();
        }
    }

    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void goat$blockMacroMouseLook(double timeDelta, CallbackInfo ci) {
        if (MouseUtils.isMouseUngrabbed()) {
            ci.cancel();
        }
    }
}
