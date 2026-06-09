package com.justingoat.goat.client.module.render;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.ModeValue;
import net.minecraft.client.MinecraftClient;

public final class TimeChanger extends GoatModule {
    private final ModeValue mode;

    public TimeChanger(){
        super("TimeChanger", ModuleCategory.RENDER, false);
        mode = addMode("Mode", "Day", "Day", "Night");
    }

    @Override
    public void tick(MinecraftClient client){
        if (!isEnabled()) {
            return;
        }
        if (mode.getValue().equals("Day")){
            client.world.setTime(1000,1000, true);
        } else {
            client.world.setTime(15000,15000, true);
        }

    }
}
