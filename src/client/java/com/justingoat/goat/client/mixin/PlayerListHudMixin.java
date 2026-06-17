package com.justingoat.goat.client.mixin;

import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.render.NicknameHider;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {

    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void onGetPlayerName(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        var module = ModuleManager.findByName("NicknameHider");
        if (module == null || !module.isEnabled()) return;

        Text original = cir.getReturnValue();
        if (original == null) return;

        Text replaced = NicknameHider.replaceInText(original);
        if (replaced != original) {
            cir.setReturnValue(replaced);
        }
    }
}
