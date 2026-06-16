package com.justingoat.goat.client.mixin;

import com.justingoat.goat.client.gui.shader.StarfieldTextureRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void goat$renderStarfieldTitleBackground(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        StarfieldTextureRenderer.draw(context);
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), 0x12000000);
        ci.cancel();
    }
}
