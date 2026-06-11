package com.justingoat.goat.client.utils;

import com.justingoat.goat.client.events.EventManager;
import com.justingoat.goat.client.events.impl.hypixel.ErrorPacketEvent;
import com.justingoat.goat.client.events.impl.hypixel.HelloPacketEvent;
import com.justingoat.goat.client.events.impl.hypixel.LocationUpdatePacketEvent;
import com.justingoat.goat.client.events.impl.hypixel.PlayerInfoPacketEvent;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.azureaaron.hmapi.events.HypixelPacketEvents;
import net.azureaaron.hmapi.network.HypixelNetworking;
import net.azureaaron.hmapi.network.packet.s2c.ErrorS2CPacket;
import net.azureaaron.hmapi.network.packet.s2c.HelloS2CPacket;
import net.azureaaron.hmapi.network.packet.s2c.HypixelS2CPacket;
import net.azureaaron.hmapi.network.packet.v1.s2c.LocationUpdateS2CPacket;
import net.azureaaron.hmapi.network.packet.v1.s2c.PlayerInfoS2CPacket;
import net.minecraft.util.Util;

public class HypixelUtils {
    public static void init() {
        HypixelNetworking.registerToEvents(Util.make(new Object2IntOpenHashMap<>(), map ->
            map.put(LocationUpdateS2CPacket.ID, 1)
        ));
        HypixelPacketEvents.HELLO.register(HypixelUtils::onPacket);
        HypixelPacketEvents.LOCATION_UPDATE.register(HypixelUtils::onPacket);
        HypixelPacketEvents.PLAYER_INFO.register(HypixelUtils::onPacket);
    }

    private static void onPacket(HypixelS2CPacket packet) {
        switch (packet) {
            case HelloS2CPacket hello ->
                EventManager.INSTANCE.fire(new HelloPacketEvent(hello.environment()));

            case LocationUpdateS2CPacket loc ->
                EventManager.INSTANCE.fire(new LocationUpdatePacketEvent(
                    loc.serverName(), loc.serverType(), loc.lobbyName(), loc.mode(), loc.map()));

            case PlayerInfoS2CPacket info ->
                EventManager.INSTANCE.fire(new PlayerInfoPacketEvent(
                    info.playerRank(), info.packageRank(), info.monthlyPackageRank(), info.prefix()));

            case ErrorS2CPacket err ->
                EventManager.INSTANCE.fire(new ErrorPacketEvent(err.id(), err.reason()));

            default -> {}
        }
    }
}
