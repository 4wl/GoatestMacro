package com.justingoat.goat.client.mixin;

import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.render.NicknameHider;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatHud.class)
public class ChatHudMixin {

    @ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Text modifyMessage(Text message) {
        var module = ModuleManager.findByName("NicknameHider");
        if (module == null || !module.isEnabled()) return message;
        return NicknameHider.replaceInText(message);
    }
}
