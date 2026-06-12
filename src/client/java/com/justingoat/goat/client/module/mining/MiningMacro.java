package com.justingoat.goat.client.module.mining;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;

public class MiningMacro extends GoatModule {

    private final ModeValue miningType;
    private final BooleanValue movement;
    private final BooleanValue tickGlide;
    private final BooleanValue prioritizeTitanium;
    private final BooleanValue prioritizeGrayMithril;

    private final MiningBot miningBot;
    private final RotationUtils rotationHelper = new RotationUtils();

    public MiningMacro() {
        super("MiningBot", ModuleCategory.MACRO, false);
        miningType = addMode("Type", "Mithril", "Mithril", "Gemstone", "Ore");
        movement = addBoolean("Movement", true);
        tickGlide = addBoolean("TickGlide", true);
        prioritizeTitanium = addBoolean("PrioritizeTitanium", false);
        prioritizeGrayMithril = addBoolean("PrioritizeGrayMithril", false);

        miningBot = new MiningBot();
        miningBot.setRotationHelper(rotationHelper);
    }

    public MiningBot getBot() {
        return miningBot;
    }

    @Override
    public void setEnabled(boolean enabled) {
        boolean wasEnabled = isEnabled();
        super.setEnabled(enabled);
        if (enabled && !wasEnabled) {
            miningBot.setMovement(movement.getValue());
            miningBot.setPrioritizeTitanium(prioritizeTitanium.getValue());
            miningBot.setPrioritizeGrayMithril(prioritizeGrayMithril.getValue());
            updateCostMap();
            miningBot.setEnabled(true);
            message("§a[Goat] MiningBot enabled.");
        } else if (!enabled && wasEnabled) {
            miningBot.stop();
            message("§c[Goat] MiningBot disabled.");
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null) return;
        updateCostMap();
        miningBot.setMovement(movement.getValue());
        miningBot.setPrioritizeTitanium(prioritizeTitanium.getValue());
        miningBot.setPrioritizeGrayMithril(prioritizeGrayMithril.getValue());
        miningBot.tick(client);
    }

    private void updateCostMap() {
        String type = miningType.getValue();
        switch (type) {
            case "Mithril" -> miningBot.setCost(buildMithrilCosts());
            case "Gemstone" -> miningBot.setCost(MiningBot.GEMSTONE_COSTS);
            case "Ore" -> miningBot.setCost(MiningBot.ORE_COSTS);
        }
    }

    private Map<String, Integer> buildMithrilCosts() {
        Map<String, Integer> costs = new HashMap<>(MiningBot.MITHRIL_COSTS);
        if (prioritizeTitanium.getValue()) {
            costs.put("minecraft:stone", 8);
        }
        if (prioritizeGrayMithril.getValue()) {
            costs.put("minecraft:gray_wool", 1);
            costs.put("minecraft:light_blue_wool", 5);
            costs.put("minecraft:cyan_wool", 6);
        }
        return costs;
    }

    private void message(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg), false);
        }
    }
}
