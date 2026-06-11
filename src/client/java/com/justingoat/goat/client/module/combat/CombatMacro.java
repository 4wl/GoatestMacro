package com.justingoat.goat.client.module.combat;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.module.pathfinder.AStarPathfinder;
import com.justingoat.goat.client.module.pathfinder.PathNode;
import com.justingoat.goat.client.module.pathfinder.PathProcessor;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.MouseUtils;
import com.justingoat.goat.client.utils.RandomUtils;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.RotationUtils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;



import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Hypixel Skyblock combat macro -- full state machine with entity filtering,
 * hybrid A-star / direct pursuit, humanized aura (Perlin jitter), melee/mage
 * modes, auto-heal, and anti-player failsafe.
 */
public class CombatMacro extends GoatModule implements MacroHudInfo {

    // ── State machine ───────────────────────────────────────────────────
    private enum State {
        SEARCHING, PURSUING, ATTACKING, HEALING, WAITING
    }

    private State state = State.SEARCHING;

    // ── Settings ────────────────────────────────────────────────────────
    private final NumberValue scanRadius;
    private final NumberValue attackRange;
    private final NumberValue healThreshold;
    private final NumberValue healSlot;
    private final NumberValue playerSafeRadius;
    private final NumberValue playerStareSeconds;
    private final NumberValue rotSpeed;
    private final ModeValue attackMode;
    private final BooleanValue sprint;
    private final BooleanValue autoHeal;
    private final BooleanValue antiPlayer;

    // Mob name whitelist — empty = accept all hostile entities
    // Populated via config; comma-separated in GUI, parsed to Set
    private final Set<String> mobWhitelist = new HashSet<>();
    private final ModeValue mobFilter;

    // ── Rotation ────────────────────────────────────────────────────────
    private final RotationUtils rotation = new RotationUtils();

    // ── Pathfinder (for far pursuit) ────────────────────────────────────
    private final PathProcessor pathProcessor = new PathProcessor();
    private volatile boolean pathing = false;

    // ── Target tracking ─────────────────────────────────────────────────
    private LivingEntity currentTarget = null;
    private int targetLostTicks = 0;
    private static final int TARGET_LOST_THRESHOLD = 40; // 2 seconds

    // ── Attack timing ───────────────────────────────────────────────────
    private long lastAttackMs = 0;
    private int nextAttackDelayMs = 0;

    // ── Heal state ──────────────────────────────────────────────────────
    private int healTicksRemaining = 0;
    private int previousSlot = -1;
    private static final int HEAL_DURATION_TICKS = 15; // 0.75s hold

    // ── Anti-player ─────────────────────────────────────────────────────
    private final java.util.Map<AbstractClientPlayerEntity, Long> playerStareMap = new java.util.HashMap<>();
    private int waitingTicks = 0;
    private static final int WAITING_CLEAR_TICKS = 60; // 3 seconds of no threat → resume

    // ── Pursuit mode transition ─────────────────────────────────────────
    private static final double FAR_PURSUIT_THRESHOLD = 10.0;
    private static final double DIRECT_STRAFE_THRESHOLD = 10.0;
    private int directStrafeStuckTicks = 0;

    // ═══════════════════════════════════════════════════ Constructor

    public CombatMacro() {
        super("CombatMacro", ModuleCategory.COMBAT, false);

        scanRadius       = addNumber("ScanRadius", 40.0, 5.0, 60.0);
        attackRange      = addNumber("AttackRange", 3.0, 2.5, 6.0);
        healThreshold    = addNumber("HealHP%", 40.0, 10.0, 80.0);
        healSlot         = addNumber("HealSlot", 4.0, 1.0, 9.0);
        playerSafeRadius = addNumber("PlayerRadius", 3.0, 1.0, 10.0);
        playerStareSeconds = addNumber("StareSec", 3.0, 1.0, 10.0);
        rotSpeed         = addNumber("RotSpeed", 0.6, 0.1, 1.0);

        attackMode = addMode("AttackMode", "Melee", "Melee", "Mage");
        mobFilter  = addMode("MobFilter", "All", "All", "Zealot", "Revenant",
                "Sven", "Tarantula", "Voidgloom", "Custom");

        sprint     = addBoolean("Sprint", true);
        autoHeal   = addBoolean("AutoHeal", true);
        antiPlayer = addBoolean("AntiPlayer", true);
    }

    // ═══════════════════════════════════════════════════ Lifecycle

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled == isEnabled()) return;
        super.setEnabled(enabled);

        if (enabled) {
            state = State.SEARCHING;
            currentTarget = null;
            targetLostTicks = 0;
            lastAttackMs = 0;
            nextAttackDelayMs = 0;
            healTicksRemaining = 0;
            previousSlot = -1;
            waitingTicks = 0;
            directStrafeStuckTicks = 0;
            playerStareMap.clear();
            pathing = false;
            rotation.clear();
            parseMobFilter();
            ChatUtils.sendSuccessMessage("CombatMacro enabled");
        } else {
            stopAllActions();
            ChatUtils.sendWarningMessage("CombatMacro disabled");
        }
    }

    private void stopAllActions() {
        if (pathProcessor.getPath() != null) pathProcessor.stop();
        if (rotation.isActive()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.setYaw(rotation.getCurrentYaw());
                client.player.setPitch(rotation.getCurrentPitch());
            }
        }
        rotation.clear();
        RotationInterpolator.clearActive();
        InputUtils.releaseAll();
        currentTarget = null;
        pathing = false;
    }

    // ═══════════════════════════════════════════════════ Main tick

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled()) return;
        if (client.player == null || client.world == null) return;
        if (client.currentScreen != null) return;

        // Global failsafe check
        if (FailsafeManager.getInstance().hasEmergency()) {
            setEnabled(false);
            return;
        }

        switch (state) {
            case SEARCHING  -> tickSearching(client);
            case PURSUING   -> tickPursuing(client);
            case ATTACKING  -> tickAttacking(client);
            case HEALING    -> tickHealing(client);
            case WAITING    -> tickWaiting(client);
        }
    }

    // ═══════════════════════════════════════════════════ SEARCHING

    private void tickSearching(MinecraftClient client) {
        // Anti-player check before scanning
        if (antiPlayer.getValue() && checkPlayerThreat(client)) {
            enterWaiting();
            return;
        }

        // Health check
        if (shouldHeal(client)) {
            enterHealing(client);
            return;
        }

        LivingEntity target = findBestTarget(client);
        if (target == null) return;

        currentTarget = target;
        targetLostTicks = 0;
        state = State.PURSUING;
    }

    // ═══════════════════════════════════════════════════ PURSUING

    private void tickPursuing(MinecraftClient client) {
        // Anti-player check
        if (antiPlayer.getValue() && checkPlayerThreat(client)) {
            abortPursuit();
            enterWaiting();
            return;
        }

        // Health check
        if (shouldHeal(client)) {
            abortPursuit();
            enterHealing(client);
            return;
        }

        // Validate target still alive and in range
        if (!isValidTarget(client, currentTarget)) {
            targetLostTicks++;
            if (targetLostTicks > TARGET_LOST_THRESHOLD) {
                abortPursuit();
                state = State.SEARCHING;
                return;
            }
            // Try to find a new target
            LivingEntity newTarget = findBestTarget(client);
            if (newTarget != null) {
                currentTarget = newTarget;
                targetLostTicks = 0;
            } else {
                // Keep pursuing last known position briefly
                return;
            }
        } else {
            targetLostTicks = 0;
        }

        double dist = client.player.distanceTo(currentTarget);

        // Within attack range → transition to ATTACKING
        if (dist <= attackRange.getValue()) {
            abortPursuit();
            state = State.ATTACKING;
            initRotationForTarget(client);
            return;
        }

        // Hybrid pursuit: A* for far, direct strafe for close
        if (dist > FAR_PURSUIT_THRESHOLD) {
            tickFarPursuit(client);
        } else {
            tickDirectStrafe(client, dist);
        }
    }

    /**
     * Far pursuit: A* pathfinding to target's block position.
     * Repath when target moves >5 blocks from last path destination.
     */
    private void tickFarPursuit(MinecraftClient client) {
        // If PathProcessor is active, let it drive movement
        if (pathProcessor.getPath() != null && !pathProcessor.isDone()) {
            // Check if target has moved significantly — repath
            BlockPos targetBlock = currentTarget.getBlockPos().down();
            List<PathNode> currentPath = pathProcessor.getPath();
            if (currentPath != null && !currentPath.isEmpty()) {
                BlockPos pathEnd = currentPath.get(currentPath.size() - 1).getPos();
                double drift = Math.sqrt(targetBlock.getSquaredDistance(pathEnd));
                if (drift > 5.0 && !pathing) {
                    requestRepath(client, targetBlock);
                }
            }

            // PathProcessor needs a PathfinderTest for settings — we create a minimal shim
            pathProcessor.tick(client, createPathSettings());
            return;
        }

        // No active path — compute one
        if (!pathing) {
            BlockPos targetBlock = currentTarget.getBlockPos().down();
            requestRepath(client, targetBlock);
        }
    }

    /**
     * Close pursuit: direct vector strafe toward the target entity.
     * Uses RotationUtils to face the mob and walks forward.
     */
    private void tickDirectStrafe(MinecraftClient client, double dist) {
        // Kill any active A* path — we're close enough for direct movement
        if (pathProcessor.getPath() != null) {
            pathProcessor.stop();
        }

        initRotationForTarget(client);

        // Aim at target's bounding box center
        Vec3d targetCenter = getTargetCenter(currentTarget);
        float[] look = RotationUtils.lookAt(
                client.player.getX(), client.player.getEyeY(), client.player.getZ(),
                targetCenter.x, targetCenter.y, targetCenter.z
        );
        rotation.setTarget(look[0], look[1]);
        rotation.setSpeed((float) rotSpeed.getValue());
        rotation.tick();

        // Movement: walk toward target
        float currentYaw = rotation.getCurrentYaw();
        float targetYaw = look[0];
        float angleDiff = Math.abs(MathHelper.wrapDegrees(targetYaw - currentYaw));

        if (angleDiff > 60.0f) {
            InputUtils.setForward(false);
            InputUtils.setSprint(false);
        } else {
            InputUtils.setForward(true);
            if (sprint.getValue() && angleDiff < 30.0f) {
                InputUtils.setSprint(true);
            } else {
                InputUtils.setSprint(false);
            }

            // Strafe correction
            float rel = MathHelper.wrapDegrees(targetYaw - currentYaw);
            if (Math.abs(rel) < 8.0f) {
                InputUtils.setLeft(false);
                InputUtils.setRight(false);
            } else if (rel < 0) {
                InputUtils.setLeft(true);
                InputUtils.setRight(false);
            } else {
                InputUtils.setLeft(false);
                InputUtils.setRight(true);
            }
        }

        // Stuck detection for direct strafe
        double speed = client.player.getVelocity().horizontalLength();
        if (speed < 0.01) {
            directStrafeStuckTicks++;
            if (directStrafeStuckTicks > 10) {
                InputUtils.setJump(true);
                if (directStrafeStuckTicks > 20) {
                    directStrafeStuckTicks = 0;
                }
            }
        } else {
            directStrafeStuckTicks = 0;
            InputUtils.setJump(false);
        }
    }

    private void abortPursuit() {
        if (pathProcessor.getPath() != null) pathProcessor.stop();
        InputUtils.releaseAll();
        directStrafeStuckTicks = 0;
    }

    // ═══════════════════════════════════════════════════ ATTACKING

    private void tickAttacking(MinecraftClient client) {
        // Anti-player check
        if (antiPlayer.getValue() && checkPlayerThreat(client)) {
            releaseAttackState();
            enterWaiting();
            return;
        }

        // Health check
        if (shouldHeal(client)) {
            releaseAttackState();
            enterHealing(client);
            return;
        }

        // Validate target
        if (!isValidTarget(client, currentTarget)) {
            releaseAttackState();
            state = State.SEARCHING;
            return;
        }

        double dist = client.player.distanceTo(currentTarget);

        // Target moved out of range → back to pursuing
        if (dist > attackRange.getValue() + 1.0) {
            releaseAttackState();
            state = State.PURSUING;
            return;
        }

        // Track target's bounding box center with Perlin jitter (handled by RotationUtils internally)
        Vec3d targetCenter = getTargetCenter(currentTarget);
        float[] look = RotationUtils.lookAt(
                client.player.getX(), client.player.getEyeY(), client.player.getZ(),
                targetCenter.x, targetCenter.y, targetCenter.z
        );
        rotation.setTarget(look[0], look[1]);
        rotation.setSpeed((float) rotSpeed.getValue());
        rotation.tick();

        // Micro-movement: stay within range but don't stand still
        if (dist > attackRange.getValue() * 0.8) {
            InputUtils.setForward(true);
        } else if (dist < 1.5) {
            InputUtils.setForward(false);
            InputUtils.setBack(true);
        } else {
            InputUtils.setForward(false);
            InputUtils.setBack(false);
        }

        // Attack only when crosshair is roughly on target and within range
        if (!rotation.isRoughlyFacing()) return;
        if (dist > attackRange.getValue()) return;

        // Raycast validation: check if crosshair target is our entity
        if (client.crosshairTarget != null
                && client.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.ENTITY) {
            net.minecraft.util.hit.EntityHitResult ehr =
                    (net.minecraft.util.hit.EntityHitResult) client.crosshairTarget;
            if (ehr.getEntity() != currentTarget) return;
        }

        // CPS timing
        long now = System.currentTimeMillis();
        if (now - lastAttackMs < nextAttackDelayMs) return;

        // Execute attack
        String mode = attackMode.getValue();
        if ("Melee".equals(mode)) {
            executeMeleeAttack(client);
        } else {
            executeMageAttack(client);
        }

        lastAttackMs = now;
        scheduleNextAttackDelay();
    }

    private void executeMeleeAttack(MinecraftClient client) {
        // Use interactionManager for proper attack packet + swing
        client.interactionManager.attackEntity(client.player, currentTarget);
        client.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
    }

    private void executeMageAttack(MinecraftClient client) {
        // Right-click ability (Hyperion, Spirit Sceptre, etc.)
        // Non-blocking: use KeyBinding press for one tick
        InputUtils.setAttack(false); // ensure left click is off
        // Right-click via use key for one tick — avoids MouseUtils thread sleep
        net.minecraft.client.option.KeyBinding useKey = client.options.useKey;
        useKey.setPressed(true);
        // Release next tick handled by mage release tracking
    }

    private boolean mageRightClickActive = false;

    private void scheduleNextAttackDelay() {
        String mode = attackMode.getValue();
        if ("Melee".equals(mode)) {
            // 8-12 CPS → 83-125ms interval
            nextAttackDelayMs = RandomUtils.randomInt(83, 125);
        } else {
            // Mage: 4-6 CPS for right-click abilities (ability cooldown considerations)
            nextAttackDelayMs = RandomUtils.randomInt(166, 250);
        }
    }

    private void releaseAttackState() {
        InputUtils.releaseAll();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options != null) {
            client.options.useKey.setPressed(false);
        }
    }

    // ═══════════════════════════════════════════════════ HEALING

    private void tickHealing(MinecraftClient client) {
        if (healTicksRemaining <= 0) {
            // Healing complete — switch back to weapon slot
            if (previousSlot >= 0) {
                setHotbarSlot(client, previousSlot);
                previousSlot = -1;
            }
            client.options.useKey.setPressed(false);
            state = State.SEARCHING;
            return;
        }

        healTicksRemaining--;

        // First tick: swap to heal slot and right-click
        if (healTicksRemaining == HEAL_DURATION_TICKS - 1) {
            previousSlot = client.player.getInventory().getSelectedSlot();
            int slot = MathHelper.clamp((int) healSlot.getValue() - 1, 0, 8);
            setHotbarSlot(client, slot);

            // Hold right-click for the duration
            client.options.useKey.setPressed(true);
        }

        // Keep holding right-click
        if (healTicksRemaining > 2) {
            client.options.useKey.setPressed(true);
        } else {
            client.options.useKey.setPressed(false);
        }
    }

    private void enterHealing(MinecraftClient client) {
        InputUtils.releaseAll();
        state = State.HEALING;
        healTicksRemaining = HEAL_DURATION_TICKS;
        ChatUtils.sendInfoMessage("HP low — healing");
    }

    private boolean shouldHeal(MinecraftClient client) {
        if (!autoHeal.getValue()) return false;
        if (client.player == null) return false;
        float hp = client.player.getHealth();
        float maxHp = client.player.getMaxHealth();
        if (maxHp <= 0) return false;
        double pct = (hp / maxHp) * 100.0;
        return pct < healThreshold.getValue();
    }

    // ═══════════════════════════════════════════════════ WAITING (Anti-player)

    private void tickWaiting(MinecraftClient client) {
        InputUtils.releaseAll();

        if (!checkPlayerThreat(client)) {
            waitingTicks++;
            if (waitingTicks >= WAITING_CLEAR_TICKS) {
                waitingTicks = 0;
                state = State.SEARCHING;
                ChatUtils.sendInfoMessage("Threat clear — resuming");
            }
        } else {
            waitingTicks = 0;
        }
    }

    private void enterWaiting() {
        abortPursuit();
        releaseAttackState();
        InputUtils.releaseAll();
        state = State.WAITING;
        waitingTicks = 0;
        ChatUtils.sendWarningMessage("Player detected — pausing");
    }

    // ═══════════════════════════════════════════════════ Entity Filtering

    private LivingEntity findBestTarget(MinecraftClient client) {
        double radius = scanRadius.getValue();
        double radiusSq = radius * radius;
        Vec3d playerPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());

        LivingEntity best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!passesFilter(client, living)) continue;

            double distSq = living.squaredDistanceTo(client.player.getX(), client.player.getY(), client.player.getZ());
            if (distSq > radiusSq) continue;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = living;
            }
        }

        return best;
    }

    private boolean passesFilter(MinecraftClient client, LivingEntity entity) {
        // Self check
        if (entity == client.player) return false;

        // Dead or removed
        if (!entity.isAlive() || entity.isDead()) return false;

        // Invulnerable
        if (entity.isInvulnerable()) return false;

        // ArmorStands (Skyblock damage numbers, nametags, holograms)
        if (entity instanceof ArmorStandEntity) return false;

        // Other players — never target
        if (entity instanceof AbstractClientPlayerEntity) return false;

        // Must be hostile-type (HostileEntity covers zombies, skeletons, endermen, etc.)
        // Also include Slime (extends MobEntity, not HostileEntity) and tamed wolves
        boolean isHostile = entity instanceof HostileEntity
                || entity instanceof SlimeEntity;

        // Skyblock mobs sometimes use non-hostile entity types with custom names.
        // If mob has a custom name and matches our whitelist, accept it regardless.
        String entityName = getStrippedName(entity);

        if (!isHostile) {
            // Reject pets — wolves following players, etc.
            if (entity instanceof WolfEntity wolf) {
                if (wolf.isTamed()) return false;
            }

            // Only accept non-hostile entities if they have a matching custom name
            if (entityName.isEmpty()) return false;
            if (!matchesMobFilter(entityName)) return false;
        }

        // Mob name whitelist check (when not "All")
        if (!"All".equals(mobFilter.getValue())) {
            if (entityName.isEmpty()) return false;
            if (!matchesMobFilter(entityName)) return false;
        }

        return true;
    }

    private boolean isValidTarget(MinecraftClient client, LivingEntity entity) {
        if (entity == null) return false;
        if (!entity.isAlive() || entity.isDead()) return false;
        if (entity.isRemoved()) return false;
        if (entity.isInvulnerable()) return false;

        double distSq = entity.squaredDistanceTo(client.player.getX(), client.player.getY(), client.player.getZ());
        double maxRange = scanRadius.getValue() + 10.0; // generous keepalive range
        return distSq <= maxRange * maxRange;
    }

    private boolean matchesMobFilter(String name) {
        String filterMode = mobFilter.getValue();
        if ("All".equals(filterMode)) return true;

        // Built-in presets
        if ("Custom".equals(filterMode)) {
            // Check against parsed whitelist
            if (mobWhitelist.isEmpty()) return true;
            for (String entry : mobWhitelist) {
                if (name.toLowerCase().contains(entry.toLowerCase())) return true;
            }
            return false;
        }

        // Single-mob presets
        return name.toLowerCase().contains(filterMode.toLowerCase());
    }

    /**
     * Strip Minecraft formatting codes (§x) from entity display name.
     */
    private String getStrippedName(LivingEntity entity) {
        if (entity.getCustomName() == null && entity.getDisplayName() == null) return "";
        String raw;
        if (entity.getCustomName() != null) {
            raw = entity.getCustomName().getString();
        } else {
            raw = entity.getDisplayName().getString();
        }
        return raw.replaceAll("§[0-9a-fk-or]", "").trim();
    }

    // ═══════════════════════════════════════════════════ Anti-Player

    /**
     * Returns true if a player threat is detected:
     * 1. Any non-self player within playerSafeRadius blocks.
     * 2. Any player staring at us for > playerStareSeconds.
     */
    private boolean checkPlayerThreat(MinecraftClient client) {
        double safeRadius = playerSafeRadius.getValue();
        double safeRadiusSq = safeRadius * safeRadius;
        long stareThresholdMs = (long) (playerStareSeconds.getValue() * 1000.0);
        long now = System.currentTimeMillis();

        boolean threatened = false;
        Set<AbstractClientPlayerEntity> activePlayers = new HashSet<>();

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;

            activePlayers.add(player);

            // Proximity check
            double distSq = player.squaredDistanceTo(client.player.getX(), client.player.getY(), client.player.getZ());
            if (distSq <= safeRadiusSq) {
                threatened = true;
                break;
            }

            // Stare check: is the player looking at us?
            if (isLookingAtUs(player, client)) {
                Long stareStart = playerStareMap.get(player);
                if (stareStart == null) {
                    playerStareMap.put(player, now);
                } else if (now - stareStart > stareThresholdMs) {
                    threatened = true;
                    break;
                }
            } else {
                playerStareMap.remove(player);
            }
        }

        // Clean up stale entries
        playerStareMap.keySet().retainAll(activePlayers);

        return threatened;
    }

    /**
     * Check if another player's look vector passes within 5° of our position.
     */
    private boolean isLookingAtUs(AbstractClientPlayerEntity other, MinecraftClient client) {
        Vec3d otherEye = other.getEyePos();
        Vec3d myPos = new Vec3d(client.player.getX(), client.player.getY() + client.player.getStandingEyeHeight() * 0.5, client.player.getZ());
        Vec3d toUs = myPos.subtract(otherEye).normalize();
        Vec3d otherLook = other.getRotationVec(1.0f);

        double dot = otherLook.dotProduct(toUs);
        // cos(5°) ≈ 0.9962
        return dot > 0.9962;
    }

    // ═══════════════════════════════════════════════════ Rotation Helpers

    private void initRotationForTarget(MinecraftClient client) {
        if (!rotation.isActive()) {
            rotation.init(client.player.getYaw(), client.player.getPitch());
            RotationInterpolator.setActive(rotation);
        }
    }

    /**
     * Get the center of the target's bounding box for aiming.
     */
    private Vec3d getTargetCenter(LivingEntity entity) {
        Box box = entity.getBoundingBox();
        return new Vec3d(
                (box.minX + box.maxX) * 0.5,
                (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5
        );
    }

    // ═══════════════════════════════════════════════════ Pathfinder Integration

    private void requestRepath(MinecraftClient client, BlockPos target) {
        if (pathing) return;
        pathing = true;

        BlockPos start = client.player.getBlockPos().down();
        CompletableFuture.supplyAsync(() ->
                AStarPathfinder.computePath(start, target, 50000, 3)
        ).thenAccept(newPath -> client.execute(() -> {
            pathing = false;
            if (newPath != null && !newPath.isEmpty()) {
                pathProcessor.setPath(newPath);
            }
        }));
    }

    /**
     * Minimal PathfinderTest shim so PathProcessor can read settings.
     * PathProcessor.tick() reads settings from PathfinderTest —
     * we create a lightweight adapter.
     */
    private com.justingoat.goat.client.module.movement.PathfinderTest createPathSettings() {
        // PathProcessor requires a PathfinderTest instance for settings reads.
        // We use the registered PathfinderTest module if available.
        GoatModule pfTest = com.justingoat.goat.client.module.ModuleManager.findByName("PathfinderTest");
        if (pfTest instanceof com.justingoat.goat.client.module.movement.PathfinderTest pt) {
            return pt;
        }
        // Fallback: should never happen since PathfinderTest is always registered
        return null;
    }

    // ═══════════════════════════════════════════════════ Config Parsing

    private void parseMobFilter() {
        mobWhitelist.clear();
        // Users can add custom mob names to the whitelist via the config file
        // Format: comma-separated in the "MobWhitelist" value
        // For now, presets cover the common cases. Custom mode uses the whitelist set.
    }

    /**
     * Programmatic API: add mob names to the custom whitelist at runtime.
     */
    public void addMobToWhitelist(String... names) {
        mobWhitelist.addAll(Arrays.asList(names));
    }

    public void clearMobWhitelist() {
        mobWhitelist.clear();
    }

    // ═══════════════════════════════════════════════════ HUD

    @Override
    public String getHudName() {
        return "Combat";
    }

    @Override
    public String getHudState() {
        String targetName = currentTarget != null ? getStrippedName(currentTarget) : "none";
        String mode = attackMode.getValue();
        return state.name() + " | " + mode + " | T: " + targetName;
    }

    // ═══════════════════════════════════════════════════ Hotbar Slot

    /**
     * Change the selected hotbar slot by simulating a hotbar key press (1-9).
     * Identical to the player pressing the number key — the vanilla client
     * handles the inventory update and any required server sync internally.
     */
    private void setHotbarSlot(MinecraftClient client, int slot) {
        slot = MathHelper.clamp(slot, 0, 8);
        net.minecraft.client.option.KeyBinding key = client.options.hotbarKeys[slot];
        key.setPressed(true);
        net.minecraft.client.option.KeyBinding.onKeyPressed(net.minecraft.client.util.InputUtil.fromTranslationKey(key.getBoundKeyTranslationKey()));
        key.setPressed(false);
    }
}
