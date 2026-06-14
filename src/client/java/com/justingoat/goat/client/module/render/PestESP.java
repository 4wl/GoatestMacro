package com.justingoat.goat.client.module.render;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.farming.PestCleaner;
import com.justingoat.goat.client.module.value.NumberValue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public final class PestESP extends GoatModule {
    private final NumberValue scanRadius;

    private static volatile List<PestCleaner.PestInfo> renderPests = null;

    public PestESP() {
        super("PestESP", ModuleCategory.RENDER, true);
        scanRadius = addNumber("ScanRadius", 160.0, 20.0, 240.0);
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null) {
            renderPests = null;
            return;
        }

        List<PestCleaner.PestInfo> pests = PestCleaner.scanVisiblePests(client, scanRadius.getValue(), null);
        renderPests = pests.isEmpty() ? null : pests;
    }

    public static List<PestCleaner.PestInfo> getRenderPests() {
        return renderPests;
    }

    public static List<String> getHudLines(MinecraftClient client) {
        List<String> lines = new ArrayList<>();
        List<PestCleaner.PestInfo> pests = renderPests;
        int visible = pests == null ? 0 : pests.size();
        lines.add("Visible Pests: " + visible + (visible == 0 ? " (no entity loaded)" : ""));

        if (visible == 0 || client == null || client.player == null) {
            return lines;
        }

        PestCleaner.PestInfo nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (PestCleaner.PestInfo pest : pests) {
            if (pest == null || pest.nameTag.isRemoved()) continue;
            double distSq = client.player.squaredDistanceTo(pest.pestPos.x, pest.pestPos.y, pest.pestPos.z);
            if (nearest == null || distSq < nearestDistSq) {
                nearest = pest;
                nearestDistSq = distSq;
            }
        }

        if (nearest != null) {
            Vec3d pos = nearest.pestPos;
            lines.add("Nearest Pest: " + nearest.name + " "
                + Math.round(Math.sqrt(nearestDistSq)) + "m @ "
                + Math.round(pos.x) + ", " + Math.round(pos.y) + ", " + Math.round(pos.z));
        }
        return lines;
    }
}
