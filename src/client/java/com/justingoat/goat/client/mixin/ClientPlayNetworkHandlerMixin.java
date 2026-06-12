package com.justingoat.goat.client.mixin;

import com.justingoat.goat.client.events.EventManager;
import com.justingoat.goat.client.events.impl.packet.ChatMessageEvent;
import com.justingoat.goat.client.events.impl.packet.SlotChangePacketEvent;
import com.justingoat.goat.client.events.impl.packet.TeleportPacketEvent;
import com.justingoat.goat.client.events.impl.packet.VelocityPacketEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onPlayerPositionLook", at = @At("HEAD"))
    private void onTeleportPacket(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        double fromX = client.player.getX();
        double fromY = client.player.getY();
        double fromZ = client.player.getZ();
        float fromYaw = client.player.getYaw();
        float fromPitch = client.player.getPitch();

        EventManager.INSTANCE.fire(new TeleportPacketEvent(
            fromX, fromY, fromZ,
            packet.change().position().x, packet.change().position().y, packet.change().position().z,
            fromYaw, fromPitch,
            packet.change().yaw(), packet.change().pitch()
        ));
    }

    @Inject(method = "onEntityVelocityUpdate", at = @At("HEAD"))
    private void onVelocityPacket(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (packet.getEntityId() != client.player.getId()) return;

        net.minecraft.util.math.Vec3d vel = packet.getVelocity();
        EventManager.INSTANCE.fire(new VelocityPacketEvent(
            packet.getEntityId(),
            vel.x, vel.y, vel.z
        ));
    }

    @Inject(method = "onUpdateSelectedSlot", at = @At("HEAD"))
    private void onSlotChangePacket(UpdateSelectedSlotS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.getInventory() == null) return;

        int currentSlot = client.player.getInventory().getSelectedSlot();
        int newSlot = packet.slot();
        if (currentSlot == newSlot) return;

        EventManager.INSTANCE.fire(new SlotChangePacketEvent(currentSlot, newSlot));
    }

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void onChatMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        String message = packet.content().getString();
        EventManager.INSTANCE.fire(new ChatMessageEvent(message, packet.overlay()));
    }
}
