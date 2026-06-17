package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class EntitySearchUtils {
    private EntitySearchUtils() {
    }

    public static Optional<ArmorStandEntity> closestArmorStand(MinecraftClient client, double radius,
                                                               Predicate<ArmorStandEntity> predicate) {
        if (client == null || client.player == null || client.world == null) return Optional.empty();
        double radiusSq = radius * radius;
        ArmorStandEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof ArmorStandEntity stand)) continue;
            if (predicate != null && !predicate.test(stand)) continue;
            double distSq = client.player.squaredDistanceTo(stand);
            if (distSq > radiusSq || distSq >= closestDistSq) continue;
            closestDistSq = distSq;
            closest = stand;
        }
        return Optional.ofNullable(closest);
    }

    public static List<Entity> entities(MinecraftClient client, Predicate<Entity> predicate) {
        List<Entity> matches = new ArrayList<>();
        if (client == null || client.world == null) return matches;
        for (Entity entity : client.world.getEntities()) {
            if (predicate == null || predicate.test(entity)) {
                matches.add(entity);
            }
        }
        return matches;
    }

    public static Optional<Entity> closestLivingNear(MinecraftClient client, Entity anchor, Box searchBox,
                                                     Predicate<Entity> predicate) {
        if (client == null || client.player == null || client.world == null || anchor == null || searchBox == null) {
            return Optional.empty();
        }
        Entity closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (Entity entity : client.world.getOtherEntities(anchor, searchBox)) {
            if (entity instanceof ArmorStandEntity) continue;
            if (!(entity instanceof LivingEntity)) continue;
            if (entity == client.player || entity.isRemoved()) continue;
            if (predicate != null && !predicate.test(entity)) continue;
            double distSq = entity.squaredDistanceTo(anchor);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = entity;
            }
        }
        return Optional.ofNullable(closest);
    }

    public static Optional<ArmorStandEntity> closestArmorStandNear(MinecraftClient client, Entity anchor, Box searchBox,
                                                                   Predicate<ArmorStandEntity> predicate) {
        if (client == null || client.world == null || anchor == null || searchBox == null) return Optional.empty();
        ArmorStandEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (Entity entity : client.world.getOtherEntities(anchor, searchBox)) {
            if (!(entity instanceof ArmorStandEntity stand)) continue;
            if (predicate != null && !predicate.test(stand)) continue;
            double distSq = stand.squaredDistanceTo(anchor);
            if (distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = stand;
            }
        }
        return Optional.ofNullable(closest);
    }

    public static Optional<LivingEntity> closestLiving(MinecraftClient client, double radius,
                                                       Predicate<LivingEntity> predicate) {
        if (client == null || client.player == null || client.world == null) return Optional.empty();
        double radiusSq = radius * radius;
        LivingEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (predicate != null && !predicate.test(living)) continue;
            double distSq = living.squaredDistanceTo(client.player.getX(), client.player.getY(), client.player.getZ());
            if (distSq > radiusSq || distSq >= closestDistSq) continue;
            closestDistSq = distSq;
            closest = living;
        }
        return Optional.ofNullable(closest);
    }

    public static <T> Optional<T> nearest(Collection<T> values, Vec3d origin, Predicate<T> predicate,
                                          java.util.function.Function<T, Vec3d> position) {
        if (values == null || origin == null || position == null) return Optional.empty();
        T best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (T value : values) {
            if (value == null || (predicate != null && !predicate.test(value))) continue;
            Vec3d pos = position.apply(value);
            if (pos == null) continue;
            double distSq = pos.squaredDistanceTo(origin);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = value;
            }
        }
        return Optional.ofNullable(best);
    }

    public static String displayName(Entity entity) {
        if (entity == null) return "";
        if (entity.getCustomName() != null) return ItemNameUtils.strip(entity.getCustomName().getString()).trim();
        if (entity.getDisplayName() != null) return ItemNameUtils.strip(entity.getDisplayName().getString()).trim();
        return "";
    }
}
