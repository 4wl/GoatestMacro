package com.justingoat.goat.client.module.mining;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.utils.InputUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;

import java.util.*;

public class NukerMacro extends GoatModule {

    private final ModeValue targetMode;
    private final NumberValue customReach;
    private final NumberValue onGroundDelay;
    private final NumberValue offGroundDelay;
    private final NumberValue heightLimit;
    private final BooleanValue nukeBelow;
    private final BooleanValue onGroundOnly;
    private final BooleanValue autoChest;

    private static final int BLOCK_COOLDOWN = 20;

    private final List<String> customBlockList = new ArrayList<>();
    private final Map<String, Integer> minedBlocks = new HashMap<>();
    private BlockPos lastTarget = null;
    private int lastMineTick = 0;
    private int tickCounter = 0;

    public NukerMacro() {
        super("Nuker", ModuleCategory.WORLD, false);
        targetMode = addMode("TargetMode", "Random", "Random", "Closest", "Lowest", "Highest");
        customReach = addNumber("Reach", 4.5, 3.0, 6.0);
        onGroundDelay = addNumber("OnGroundDelay", 1, 1, 20);
        offGroundDelay = addNumber("OffGroundDelay", 1, 1, 20);
        heightLimit = addNumber("HeightLimit", 5, 1, 15);
        nukeBelow = addBoolean("NukeBelow", false);
        onGroundOnly = addBoolean("OnGroundOnly", false);
        autoChest = addBoolean("AutoChest", false);
    }

    public void addBlock(String blockId) {
        if (!customBlockList.contains(blockId)) {
            customBlockList.add(blockId);
        }
    }

    public void removeBlock(String blockId) {
        customBlockList.remove(blockId);
    }

    public void clearBlocks() {
        customBlockList.clear();
    }

    public List<String> getBlockList() {
        return Collections.unmodifiableList(customBlockList);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (enabled) {
            lastTarget = null;
            lastMineTick = 0;
            tickCounter = 0;
            minedBlocks.clear();
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null) return;

        tickCounter++;

        if (customBlockList.isEmpty()) return;

        if (client.currentScreen != null) return;

        if (onGroundOnly.getValue() && !client.player.isOnGround()) return;

        int delay = client.player.isOnGround()
                ? (int) onGroundDelay.getValue()
                : (int) offGroundDelay.getValue();
        if (tickCounter - lastMineTick < delay) return;

        lastMineTick = tickCounter;

        minedBlocks.entrySet().removeIf(e -> tickCounter - e.getValue() > BLOCK_COOLDOWN);

        BlockPos target = scanForBlock(client);
        if (target != null) {
            lastTarget = target;
            nukeBlock(client, target);

            String posKey = posKey(target);
            minedBlocks.put(posKey, tickCounter);

            String mode = targetMode.getValue();
            if ("Random".equals(mode) || "Lowest".equals(mode) || "Highest".equals(mode)) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            minedBlocks.put(posKey(target.add(dx, dy, dz)), tickCounter);
                        }
                    }
                }
            }
        }

        if (autoChest.getValue()) {
            tryOpenChest(client);
        }
    }

    private BlockPos scanForBlock(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        double reach = customReach.getValue();
        int scanRadius = MathHelper.ceil(reach);
        int px = MathHelper.floor(player.getX());
        int py = MathHelper.floor(player.getY());
        int pz = MathHelper.floor(player.getZ());
        int maxY = py + Math.max((int) heightLimit.getValue(), scanRadius);
        int minY = py - (nukeBelow.getValue() ? 0 : scanRadius);

        Vec3d eye = player.getEyePos();
        List<BlockPos> valid = new ArrayList<>();

        for (int x = px - scanRadius; x <= px + scanRadius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = pz - scanRadius; z <= pz + scanRadius; z++) {
                    String pk = x + "," + y + "," + z;
                    if (minedBlocks.containsKey(pk)) continue;

                    double distToBox = distanceToBlockBox(eye, x, y, z);
                    if (distToBox > reach) continue;

                    BlockPos bp = new BlockPos(x, y, z);
                    String blockId = getBlockId(world, bp);
                    if (blockId == null) continue;

                    if (customBlockList.stream().anyMatch(blockId::contains)) {
                        valid.add(bp);
                    }
                }
            }
        }

        if (valid.isEmpty()) return null;

        String mode = targetMode.getValue();
        switch (mode) {
            case "Closest":
                valid.sort(Comparator.comparingDouble(b ->
                        distanceToBlockBox(eye, b.getX(), b.getY(), b.getZ())));
                return valid.get(0);
            case "Lowest":
                int minBlock = valid.stream().mapToInt(BlockPos::getY).min().orElse(0);
                List<BlockPos> lowest = valid.stream().filter(b -> b.getY() == minBlock).toList();
                return lowest.get(new Random().nextInt(lowest.size()));
            case "Highest":
                int maxBlock = valid.stream().mapToInt(BlockPos::getY).max().orElse(0);
                List<BlockPos> highest = valid.stream().filter(b -> b.getY() == maxBlock).toList();
                return highest.get(new Random().nextInt(highest.size()));
            default:
                return valid.get(new Random().nextInt(valid.size()));
        }
    }

    private void nukeBlock(MinecraftClient client, BlockPos pos) {
        if (client.getNetworkHandler() == null) return;
        client.getNetworkHandler().sendPacket(
                new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                        pos, Direction.UP
                )
        );
        client.getNetworkHandler().sendPacket(
                new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                        pos, Direction.UP
                )
        );
    }

    private void tryOpenChest(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        int px = MathHelper.floor(player.getX());
        int py = MathHelper.floor(player.getY());
        int pz = MathHelper.floor(player.getZ());

        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos bp = new BlockPos(px + dx, py + dy, pz + dz);
                    BlockState state = client.world.getBlockState(bp);
                    String id = Registries.BLOCK.getId(state.getBlock()).toString();
                    if (id.contains("chest")) {
                        Vec3d hitVec = new Vec3d(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
                        if (hitVec.distanceTo(player.getEyePos()) > 6) continue;
                        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, bp, false);
                        if (client.getNetworkHandler() != null) {
                            client.getNetworkHandler().sendPacket(
                                    new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0)
                            );
                            return;
                        }
                    }
                }
            }
        }
    }

    private static double distanceToBlockBox(Vec3d from, int bx, int by, int bz) {
        double cx = Math.max(bx, Math.min(from.x, bx + 1));
        double cy = Math.max(by, Math.min(from.y, by + 1));
        double cz = Math.max(bz, Math.min(from.z, bz + 1));
        double dx = from.x - cx, dy = from.y - cy, dz = from.z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String getBlockId(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return null;
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    private static String posKey(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
