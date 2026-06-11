package com.justingoat.goat.client.module.movement;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import net.minecraft.client.MinecraftClient;

import java.util.Random;

public class FarmingMacro extends GoatModule {
    private final BooleanValue holdAttack;
    private final NumberValue delayMin;
    private final NumberValue delayMax;

    private enum MacroState {
        RIGHT,
        FORWARD_FROM_RIGHT,
        LEFT,
        FORWARD_FROM_LEFT,
        WAITING
    }

    private MacroState currentState = MacroState.RIGHT;
    private MacroState nextState = MacroState.RIGHT;
    
    private long waitUntil = 0;
    private final Random random = new Random();

    public FarmingMacro() {
        super("FarmingMacro", ModuleCategory.MOVEMENT, false);
        holdAttack = addBoolean("Hold Attack", true);
        delayMin = addNumber("Delay Min (ms)", 50.0, 0.0, 500.0);
        delayMax = addNumber("Delay Max (ms)", 150.0, 0.0, 500.0);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (enabled) {
            currentState = MacroState.RIGHT;
            waitUntil = 0;
            if (holdAttack.getValue()) {
                InputUtils.setAttack(true);
            }
        } else {
            InputUtils.releaseAll();
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null) return;
        
        if (FailsafeManager.getInstance().hasEmergency()) {
            this.setEnabled(false); // Emergency triggered, stop macro immediately
            return;
        }

        if (client.currentScreen != null) {
            InputUtils.releaseAll();
            return;
        }

        if (holdAttack.getValue()) {
            InputUtils.setAttack(true);
        }

        long now = System.currentTimeMillis();

        if (currentState == MacroState.WAITING) {
            if (now >= waitUntil) {
                currentState = nextState;
            } else {
                return; 
            }
        }

        boolean collided = client.player.horizontalCollision;

        switch (currentState) {
            case RIGHT:
                InputUtils.setRight(true);
                InputUtils.setLeft(false);
                InputUtils.setForward(false);
                if (collided) {
                    transitionTo(MacroState.FORWARD_FROM_RIGHT);
                }
                break;
            case FORWARD_FROM_RIGHT:
                InputUtils.setForward(true);
                InputUtils.setRight(false);
                InputUtils.setLeft(false);
                if (collided) {
                    transitionTo(MacroState.LEFT);
                }
                break;
            case LEFT:
                InputUtils.setLeft(true);
                InputUtils.setRight(false);
                InputUtils.setForward(false);
                if (collided) {
                    transitionTo(MacroState.FORWARD_FROM_LEFT);
                }
                break;
            case FORWARD_FROM_LEFT:
                InputUtils.setForward(true);
                InputUtils.setLeft(false);
                InputUtils.setRight(false);
                if (collided) {
                    transitionTo(MacroState.RIGHT);
                }
                break;
            default:
                break;
        }
    }

    private void transitionTo(MacroState next) {
        InputUtils.releaseAll(); 
        if (holdAttack.getValue()) {
            InputUtils.setAttack(true); 
        }
        
        long delay = (long) (delayMin.getValue() + random.nextFloat() * (delayMax.getValue() - delayMin.getValue()));
        this.waitUntil = System.currentTimeMillis() + delay;
        this.nextState = next;
        this.currentState = MacroState.WAITING;
    }
}
