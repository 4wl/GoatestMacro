package com.justingoat.goat.client.module.skills;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.InputUtils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

public class AutoExperiments extends GoatModule implements MacroHudInfo {

    private enum State {
        WAITING,
        DECIDING,
        ULTRASEQUENCER,
        CHRONOMATRON,
        SUPERPAIRS,
        EXPERIMENT_OVER,
        REOPENING,
        BUYING_XP,
        SUPERPAIRS_REWARDS
    }

    private static final int SLOT_CHRONOMATRON = 29;
    private static final int SLOT_ULTRASEQUENCER = 33;
    private static final int SLOT_SUPERPAIRS = 22;
    private static final int SLOT_RENEW = 31;
    private static final int SLOT_CONTROL = 49;
    private static final int SLOT_BOTTLE_MENU = 50;
    private static final int SLOT_GRAND_BOTTLE = 12;
    private static final int SLOT_TITANIC_BOTTLE = 14;

    private final NumberValue actionDelay;
    private final NumberValue serumCount;
    private final BooleanValue getMaxXp;

    private State state = State.WAITING;
    private final Map<Integer, Integer> ultrasequencerOrder = new HashMap<>();
    private final List<Integer> chronomatronOrder = new ArrayList<>();
    private boolean ultraPatternCaptured = false;
    private int clicks = 0;
    private String lastSlot49Item = null;
    private long lastClickTime = 0;
    private boolean reopeningStarted = false;
    private int buyXpTargetLevel = 0;
    private boolean boughtXP = false;
    private boolean maxEnchanting = false;
    private boolean superpairsRewardsClaimed = false;
    private int reopenUseTicks = 0;
    private int xpPurchaseAttempts = 0;
    private int lastObservedXpLevel = -1;
    private int lastChronomatronGlowSlot = -1;
    private boolean experimentOverClaimed = false;

    private final Map<Integer, String> superpairsMemory = new HashMap<>();
    private final Set<Integer> superpairsMatchedSlots = new HashSet<>();
    private final Deque<Integer> superpairsClickQueue = new ArrayDeque<>();
    private final List<Integer> superpairsBoardSlots = new ArrayList<>();
    private int lastSuperpairsClickedSlot = -1;
    private boolean superpairsInitialized = false;

    public AutoExperiments() {
        super("AutoExperiments", ModuleCategory.MACRO, false);
        actionDelay = addNumber("ActionDelay", 500, 75, 1000);
        serumCount = addNumber("SerumCount", 0, 0, 3);
        getMaxXp = addBoolean("GetMaxXP", false);
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled == isEnabled()) return;
        super.setEnabled(enabled);
        if (enabled) {
            reset();
            ChatUtils.sendSuccessMessage("AutoExperiments enabled");
        } else {
            reset();
            ChatUtils.sendWarningMessage("AutoExperiments disabled");
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null) return;

        tickUseRelease();

        if (state == State.REOPENING) {
            handleReopening(client);
            return;
        }

        if (!(client.currentScreen instanceof GenericContainerScreen containerScreen)) return;

        GenericContainerScreenHandler handler = containerScreen.getScreenHandler();
        String containerName = containerScreen.getTitle().getString();
        containerName = Formatting.strip(containerName);
        if (containerName == null) return;

        detectState(containerName);
        if (state == State.WAITING) return;

        switch (state) {
            case EXPERIMENT_OVER -> handleExperimentOver(handler);
            case DECIDING -> handleDeciding(handler, containerName);
            case ULTRASEQUENCER -> handleUltrasequencer(handler);
            case CHRONOMATRON -> handleChronomatron(handler);
            case BUYING_XP -> handleBuyingXp(handler);
            case SUPERPAIRS -> handleSuperpairs(handler);
            case SUPERPAIRS_REWARDS -> handleSuperpairsRewards(handler);
            default -> {}
        }
    }

    private void detectState(String name) {
        State newState = State.WAITING;

        if ("Experiment Over".equals(name)) {
            newState = State.EXPERIMENT_OVER;
        } else if (name.startsWith("Chronomatron (")) {
            newState = State.CHRONOMATRON;
        } else if (name.startsWith("Ultrasequencer (")) {
            newState = State.ULTRASEQUENCER;
        } else if (name.startsWith("Superpairs (")) {
            newState = State.SUPERPAIRS;
        } else if ("Superpairs Rewards".equals(name)) {
            newState = State.SUPERPAIRS_REWARDS;
        } else if ("Bottles of Enchanting".equals(name)) {
            newState = State.BUYING_XP;
        } else if ("Experimentation Table".equals(name) || name.endsWith("Stakes")) {
            newState = State.DECIDING;
        }

        if (newState == state) return;
        state = newState;
        lastClickTime = System.currentTimeMillis();

        switch (newState) {
            case CHRONOMATRON -> {
                chronomatronOrder.clear();
                clicks = 0;
                lastChronomatronGlowSlot = -1;
            }
            case ULTRASEQUENCER -> {
                ultraPatternCaptured = false;
                ultrasequencerOrder.clear();
                clicks = 0;
                lastSlot49Item = null;
            }
            case SUPERPAIRS -> resetSuperpairsRound();
            case DECIDING -> {
                lastSlot49Item = null;
                experimentOverClaimed = false;
            }
            default -> {}
        }
    }

    // ═══════════════════════════════════════════════════ DECIDING

    private void handleDeciding(GenericContainerScreenHandler handler, String containerName) {
        if (!canClick()) return;

        if (renewRequired(handler)) {
            renewExperiments(handler);
            return;
        }

        if (buyXpTargetLevel > 0) {
            clickSlot(handler, SLOT_BOTTLE_MENU);
            return;
        }

        ItemStack superpairsItem = getStack(handler, SLOT_SUPERPAIRS);
        if (onCooldown(superpairsItem)) {
            MinecraftClient.getInstance().player.closeHandledScreen();
            reset();
            ChatUtils.sendSuccessMessage("[Experiments] All experiments complete");
            return;
        }

        if (isStakeSelection("Chronomatron", containerName)) {
            selectHighestStake(handler, new int[]{24, 23, 22, 21, 20});
            return;
        }
        if (isStakeSelection("Ultrasequencer", containerName)) {
            selectHighestStake(handler, new int[]{23, 22, 21});
            return;
        }
        if (isStakeSelection("Superpairs", containerName)) {
            selectSuperpairsStake(handler);
            return;
        }

        if (!isCompleted(getStack(handler, 21))) {
            clickSlot(handler, SLOT_CHRONOMATRON);
            return;
        }
        if (!isCompleted(getStack(handler, 23))) {
            clickSlot(handler, SLOT_ULTRASEQUENCER);
            return;
        }

        clickSlot(handler, SLOT_SUPERPAIRS);
    }

    // ═══════════════════════════════════════════════════ ULTRASEQUENCER

    private void handleUltrasequencer(GenericContainerScreenHandler handler) {
        int maxDepth;
        int serum = (int) serumCount.getValue();
        if (getMaxXp.getValue()) maxDepth = 20;
        else if (maxEnchanting) maxDepth = 9 - serum;
        else maxDepth = 7 - serum;

        ControlState control = getControlState(handler);
        if (control == null) return;

        if (control.isGlow && !ultraPatternCaptured && !getStack(handler, 44).isEmpty()) {
            captureUltrasequencerOrder(handler);
            ultraPatternCaptured = true;
            clicks = 0;
            if (ultrasequencerOrder.size() > maxDepth) {
                MinecraftClient.getInstance().player.closeHandledScreen();
                return;
            }
        }

        if (control.isClock && ultraPatternCaptured && canClick() && ultrasequencerOrder.containsKey(clicks)) {
            if (clickSlot(handler, ultrasequencerOrder.get(clicks))) clicks++;
        }

        if (control.isGlow && control.wasClockLastFrame) ultraPatternCaptured = false;

        lastSlot49Item = control.name;
    }

    // ═══════════════════════════════════════════════════ CHRONOMATRON

    private void handleChronomatron(GenericContainerScreenHandler handler) {
        int maxDepth;
        int serum = (int) serumCount.getValue();
        if (getMaxXp.getValue()) maxDepth = 20;
        else if (maxEnchanting) maxDepth = 12 - serum;
        else maxDepth = 9 - serum;

        ControlState control = getControlState(handler);
        if (control == null) return;

        Integer guiRound = getChronomatronRound(handler);
        int expectedLen = Math.min(maxDepth, guiRound != null ? guiRound : chronomatronOrder.size() + 1);

        if (guiRound != null && guiRound - 1 == maxDepth) {
            MinecraftClient.getInstance().player.closeHandledScreen();
        }

        if (control.isClock && chronomatronOrder.size() < expectedLen) {
            clicks = 0;
            int glowSlot = findChronomatronGlowSlot(handler);
            if (glowSlot >= 0 && glowSlot != lastChronomatronGlowSlot) {
                chronomatronOrder.add(glowSlot);
                lastChronomatronGlowSlot = glowSlot;
            } else if (glowSlot < 0) {
                lastChronomatronGlowSlot = -1;
            }
        } else if (control.isClock && chronomatronOrder.size() > clicks && canClick()) {
            if (clickSlot(handler, chronomatronOrder.get(clicks))) clicks++;
        }

        if (control.isGlow && clicks >= chronomatronOrder.size() && !chronomatronOrder.isEmpty()) {
            clicks = 0;
        }

        lastSlot49Item = control.name;
    }

    // ═══════════════════════════════════════════════════ SUPERPAIRS

    private void handleSuperpairs(GenericContainerScreenHandler handler) {
        if (!superpairsInitialized) {
            initializeSuperpairsBoard(handler);
        }

        updateSuperpairsMemory(handler);
        if (!canClick()) return;

        Integer queuedSlot = pollNextSuperpairsClick(handler);
        if (queuedSlot != null) {
            clickSuperpairsSlot(handler, queuedSlot);
            return;
        }

        List<Integer> matchingPair = findKnownSuperpairsPair(handler);
        if (matchingPair != null) {
            superpairsClickQueue.addAll(matchingPair);
            queuedSlot = pollNextSuperpairsClick(handler);
            if (queuedSlot != null) clickSuperpairsSlot(handler, queuedSlot);
            return;
        }

        Integer unknownSlot = findNextUnknownSuperpairsSlot(handler);
        if (unknownSlot != null) {
            clickSuperpairsSlot(handler, unknownSlot);
        }
    }

    private void handleExperimentOver(GenericContainerScreenHandler handler) {
        if (experimentOverClaimed) {
            startReopenSequence();
            return;
        }

        Integer claimSlot = findClaimSlot(handler);
        if (claimSlot != null && canClick()) {
            ChatUtils.sendSuccessMessage("[Experiments] Claiming rewards...");
            if (clickSlot(handler, claimSlot)) {
                experimentOverClaimed = true;
            }
            return;
        }

        if (canClick()) {
            startReopenSequence();
        }
    }

    // ═══════════════════════════════════════════════════ SUPERPAIRS REWARDS

    private void handleSuperpairsRewards(GenericContainerScreenHandler handler) {
        if (superpairsRewardsClaimed) {
            superpairsRewardsClaimed = false;
            startReopenSequence();
            return;
        }

        ItemStack rewardItem = getStack(handler, 13);
        if (rewardItem.isEmpty()) return;

        String lore = getLoreString(rewardItem);
        if (canClick() && lore.contains("Click to claim rewards")) {
            ChatUtils.sendSuccessMessage("[Superpairs] Claiming rewards...");
            clickSlot(handler, 13);
            superpairsRewardsClaimed = true;
        }
    }

    // ═══════════════════════════════════════════════════ BUYING XP

    private void handleBuyingXp(GenericContainerScreenHandler handler) {
        if (buyXpTargetLevel == 0) return;

        int currentLevel = getCurrentXpLevel(handler);
        if (currentLevel > lastObservedXpLevel) {
            lastObservedXpLevel = currentLevel;
            xpPurchaseAttempts = 0;
        }

        if (currentLevel >= buyXpTargetLevel) {
            buyXpTargetLevel = 0;
            resetXpBuyingProgress();
            if (boughtXP) {
                boughtXP = false;
                startReopenSequence();
                return;
            }
            startReopenSequence();
            return;
        }

        int slot = buyXpTargetLevel <= 100 ? SLOT_GRAND_BOTTLE : SLOT_TITANIC_BOTTLE;
        ItemStack bottle = getStack(handler, slot);
        if (bottle.isEmpty()) return;

        if (hasPurchaseFailure(bottle)) {
            abortBuyingXp("[Experiments] Could not buy XP bottles");
            return;
        }

        if (xpPurchaseAttempts >= 8) {
            abortBuyingXp("[Experiments] XP purchase made no progress");
            return;
        }

        if (canClick()) {
            if (clickSlot(handler, slot)) {
                boughtXP = true;
                xpPurchaseAttempts++;
            }
        }
    }

    // ═══════════════════════════════════════════════════ REOPENING

    private void startReopenSequence() {
        reopeningStarted = false;
        reopenUseTicks = 0;
        lastClickTime = System.currentTimeMillis();
        state = State.REOPENING;
    }

    private void handleReopening(MinecraftClient client) {
        if (!canClick()) return;

        if (!reopeningStarted) {
            if (client.player != null) client.player.closeHandledScreen();
            reopeningStarted = true;
            lastClickTime = System.currentTimeMillis();
        } else {
            ChatUtils.sendInfoMessage("[Experiments] Reopening table...");
            InputUtils.setUse(true);
            reopenUseTicks = 2;
            ultrasequencerOrder.clear();
            chronomatronOrder.clear();
            ultraPatternCaptured = false;
            clicks = 0;
            lastSlot49Item = null;
            lastClickTime = System.currentTimeMillis();
        }
    }

    // ═══════════════════════════════════════════════════ Stake selection

    private void selectHighestStake(GenericContainerScreenHandler handler, int[] slots) {
        for (int slot : slots) {
            ItemStack stack = getStack(handler, slot);
            if (!stack.isEmpty() && !isLocked(stack)) {
                if (slot == 24) maxEnchanting = true;
                clickSlot(handler, slot);
                return;
            }
        }
    }

    private void selectSuperpairsStake(GenericContainerScreenHandler handler) {
        int[] stakeSlots = {32, 31, 30, 23, 22, 21};

        for (int slot : stakeSlots) {
            ItemStack stack = getStack(handler, slot);
            if (stack.isEmpty() || isLocked(stack)) continue;

            List<String> loreLines = getLoreLines(stack);
            if (loreLines.isEmpty()) continue;
            String lastLine = loreLines.get(loreLines.size() - 1);

            if (lastLine.contains("Click to play!")) {
                configureSuperpairsBoardFromLore(loreLines);
                clickSlot(handler, slot);
                return;
            }

            if (lastLine.contains("Not enough experience!")) {
                int requiredXp = extractStakeCost(stack);
                if (requiredXp > 0) {
                    buyXpTargetLevel = requiredXp;
                    resetXpBuyingProgress();
                    ChatUtils.sendWarningMessage("[Experiments] Need more XP for Superpairs. Buying bottles...");
                    startReopenSequence();
                    return;
                }
            }
        }
    }

    private boolean isStakeSelection(String game, String containerName) {
        return containerName.contains(game) && containerName.contains("Stakes");
    }

    private void configureSuperpairsBoardFromLore(List<String> loreLines) {
        for (String line : loreLines) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("Grid\\s+Size:\\s*(\\d+)x(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(line);
            if (matcher.find()) {
                configureSuperpairsBoard(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
                return;
            }
        }
        resetSuperpairs();
    }

    private void configureSuperpairsBoard(int width, int height) {
        superpairsBoardSlots.clear();
        int startColumn = Math.max(0, (9 - width) / 2);
        int startRow = height <= 2 ? 2 : 1;

        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                superpairsBoardSlots.add((startRow + row) * 9 + startColumn + column);
            }
        }

        superpairsInitialized = true;
        superpairsMemory.clear();
        superpairsMatchedSlots.clear();
        superpairsClickQueue.clear();
        lastSuperpairsClickedSlot = -1;
    }

    private void initializeSuperpairsBoard(GenericContainerScreenHandler handler) {
        if (superpairsBoardSlots.isEmpty()) {
            configureSuperpairsBoard(7, 4);
        }
        superpairsInitialized = true;
    }

    private void updateSuperpairsMemory(GenericContainerScreenHandler handler) {
        for (int slot : superpairsBoardSlots) {
            String fingerprint = getSuperpairsFingerprint(getStack(handler, slot));
            if (fingerprint != null) {
                superpairsMemory.put(slot, fingerprint);
            }
        }
    }

    private Integer pollNextSuperpairsClick(GenericContainerScreenHandler handler) {
        while (!superpairsClickQueue.isEmpty()) {
            int slot = superpairsClickQueue.pollFirst();
            if (superpairsBoardSlots.contains(slot) && !getStack(handler, slot).isEmpty()) {
                return slot;
            }
        }
        return null;
    }

    private List<Integer> findKnownSuperpairsPair(GenericContainerScreenHandler handler) {
        Map<String, Integer> firstByFingerprint = new HashMap<>();
        for (Map.Entry<Integer, String> entry : superpairsMemory.entrySet()) {
            int slot = entry.getKey();
            if (superpairsMatchedSlots.contains(slot)) continue;

            Integer firstSlot = firstByFingerprint.putIfAbsent(entry.getValue(), slot);
            if (firstSlot != null && firstSlot != slot) {
                superpairsMatchedSlots.add(firstSlot);
                superpairsMatchedSlots.add(slot);

                List<Integer> clicksNeeded = new ArrayList<>(2);
                if (!isCurrentlyVisibleSuperpairsCard(handler, firstSlot)) clicksNeeded.add(firstSlot);
                if (!isCurrentlyVisibleSuperpairsCard(handler, slot)) clicksNeeded.add(slot);
                return clicksNeeded.isEmpty() ? null : clicksNeeded;
            }
        }
        return null;
    }

    private Integer findNextUnknownSuperpairsSlot(GenericContainerScreenHandler handler) {
        Integer fallback = null;
        for (int slot : superpairsBoardSlots) {
            if (superpairsMemory.containsKey(slot) || superpairsMatchedSlots.contains(slot)) continue;
            if (getStack(handler, slot).isEmpty()) continue;
            if (slot == lastSuperpairsClickedSlot) {
                fallback = slot;
                continue;
            }
            return slot;
        }
        return fallback != null && fallback != lastSuperpairsClickedSlot ? fallback : null;
    }

    private void clickSuperpairsSlot(GenericContainerScreenHandler handler, int slot) {
        if (clickSlot(handler, slot)) {
            lastSuperpairsClickedSlot = slot;
        }
    }

    private String getSuperpairsFingerprint(ItemStack stack) {
        if (stack.isEmpty()) return null;
        String name = Formatting.strip(stack.getName().getString());
        if (name == null || isHiddenSuperpairsCard(name)) return null;

        String lore = getLoreString(stack).toLowerCase(Locale.ROOT);
        if (lore.contains("click any") || lore.contains("click to reveal")) return null;

        return stack.getItem() + "|" + stack.getCount() + "|" + name + "|" + lore;
    }

    private boolean isCurrentlyVisibleSuperpairsCard(GenericContainerScreenHandler handler, int slot) {
        String current = getSuperpairsFingerprint(getStack(handler, slot));
        String remembered = superpairsMemory.get(slot);
        return current != null && current.equals(remembered);
    }

    private boolean isHiddenSuperpairsCard(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("click any")
            || lower.contains("click to reveal")
            || lower.contains("unknown")
            || lower.contains("???")
            || lower.contains("glass pane");
    }

    private Integer findClaimSlot(GenericContainerScreenHandler handler) {
        int maxSlot = Math.min(54, handler.slots.size());
        for (int slot = 0; slot < maxSlot; slot++) {
            ItemStack stack = getStack(handler, slot);
            if (stack.isEmpty()) continue;
            String text = (stack.getName().getString() + " " + getLoreString(stack)).toLowerCase(Locale.ROOT);
            if (text.contains("click to claim") || text.contains("claim rewards") || text.contains("click to continue")) {
                return slot;
            }
        }
        return null;
    }

    private void resetSuperpairs() {
        superpairsMemory.clear();
        superpairsMatchedSlots.clear();
        superpairsClickQueue.clear();
        superpairsBoardSlots.clear();
        lastSuperpairsClickedSlot = -1;
        superpairsInitialized = false;
    }

    private void resetSuperpairsRound() {
        superpairsMemory.clear();
        superpairsMatchedSlots.clear();
        superpairsClickQueue.clear();
        lastSuperpairsClickedSlot = -1;
        superpairsInitialized = !superpairsBoardSlots.isEmpty();
    }

    // ═══════════════════════════════════════════════════ Renew

    private boolean renewRequired(GenericContainerScreenHandler handler) {
        ItemStack item = getStack(handler, SLOT_RENEW);
        if (item.isEmpty()) return false;
        String name = Formatting.strip(item.getName().getString());
        return name != null && name.contains("Renew Experiments");
    }

    private void renewExperiments(GenericContainerScreenHandler handler) {
        List<String> loreLines = getLoreLines(getStack(handler, SLOT_RENEW));
        for (String line : loreLines) {
            String lower = line.toLowerCase();
            if (lower.contains("click to purchase")) {
                clickSlot(handler, SLOT_RENEW);
                return;
            }
            if (lower.contains("cannot afford this!")) {
                buyXpTargetLevel = extractRenewCost(getStack(handler, SLOT_RENEW));
                resetXpBuyingProgress();
                clickSlot(handler, SLOT_BOTTLE_MENU);
                return;
            }
        }
    }

    // ═══════════════════════════════════════════════════ Control state

    private ControlState getControlState(GenericContainerScreenHandler handler) {
        ItemStack item = getStack(handler, SLOT_CONTROL);
        if (item.isEmpty()) return null;

        String name = Formatting.strip(item.getName().getString());
        if (name == null) return null;

        return new ControlState(
            name,
            "Remember the pattern!".equals(name),
            name.startsWith("Timer:"),
            lastSlot49Item != null && lastSlot49Item.startsWith("Timer:")
        );
    }

    // ═══════════════════════════════════════════════════ Ultrasequencer pattern

    private void captureUltrasequencerOrder(GenericContainerScreenHandler handler) {
        ultrasequencerOrder.clear();
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = getStack(handler, i);
            if (!stack.isEmpty() && isDye(stack)) {
                ultrasequencerOrder.put(stack.getCount() - 1, i);
            }
        }
    }

    // ═══════════════════════════════════════════════════ Chronomatron round

    private Integer getChronomatronRound(GenericContainerScreenHandler handler) {
        ItemStack item = getStack(handler, 4);
        if (item.isEmpty()) return null;
        String name = Formatting.strip(item.getName().getString());
        if (name == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Round:\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private int findChronomatronGlowSlot(GenericContainerScreenHandler handler) {
        for (int i = 9; i <= 44; i++) {
            ItemStack stack = getStack(handler, i);
            if (!stack.isEmpty() && stack.hasGlint()) return i;
        }
        return -1;
    }

    private int getCurrentXpLevel(GenericContainerScreenHandler handler) {
        return Math.max(
            extractXpLevel(getStack(handler, SLOT_GRAND_BOTTLE)),
            extractXpLevel(getStack(handler, SLOT_TITANIC_BOTTLE))
        );
    }

    private boolean hasPurchaseFailure(ItemStack stack) {
        String text = (stack.getName().getString() + " " + getLoreString(stack)).toLowerCase(Locale.ROOT);
        return text.contains("not enough")
            || text.contains("cannot afford")
            || text.contains("insufficient")
            || text.contains("need more");
    }

    private void abortBuyingXp(String message) {
        buyXpTargetLevel = 0;
        boughtXP = false;
        resetXpBuyingProgress();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.closeHandledScreen();
        state = State.WAITING;
        ChatUtils.sendWarningMessage(message);
    }

    private void resetXpBuyingProgress() {
        xpPurchaseAttempts = 0;
        lastObservedXpLevel = -1;
    }

    private void tickUseRelease() {
        if (reopenUseTicks <= 0) return;

        reopenUseTicks--;
        if (reopenUseTicks == 0) {
            InputUtils.setUse(false);
            if (state == State.REOPENING) {
                reopeningStarted = false;
                lastClickTime = System.currentTimeMillis();
                state = State.WAITING;
            }
        }
    }

    // ═══════════════════════════════════════════════════ Slot click

    private boolean clickSlot(GenericContainerScreenHandler handler, int slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager == null || client.player == null) return false;
        client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
        lastClickTime = System.currentTimeMillis();
        return true;
    }

    private boolean canClick() {
        return System.currentTimeMillis() - lastClickTime >= (long) actionDelay.getValue();
    }

    // ═══════════════════════════════════════════════════ Item helpers

    private ItemStack getStack(GenericContainerScreenHandler handler, int slot) {
        if (slot < 0 || slot >= handler.slots.size()) return ItemStack.EMPTY;
        return handler.slots.get(slot).getStack();
    }

    private boolean isDye(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String name = Formatting.strip(stack.getName().getString());
        return name != null && name.matches("^\\d+$");
    }

    private boolean isLocked(ItemStack stack) {
        return getLoreString(stack).contains("Enchanting level too low!");
    }

    private boolean isCompleted(ItemStack stack) {
        if (stack.isEmpty()) return true;
        String lore = getLoreString(stack);
        return lore.contains("Experiment completed") || lore.contains("Add-on locked!");
    }

    private boolean onCooldown(ItemStack stack) {
        if (stack.isEmpty()) return true;
        return getLoreString(stack).contains("Experiments on cooldown!");
    }

    private int extractStakeCost(ItemStack stack) {
        for (String line : getLoreLines(stack)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Starting\\s+cost:\\s*(\\d+)\\s*XP\\s*Levels?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private int extractRenewCost(ItemStack stack) {
        for (String line : getLoreLines(stack)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*XP\\s*Levels?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private int extractXpLevel(ItemStack stack) {
        for (String line : getLoreLines(stack)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Your\\s+Exp\\s+Level:\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private List<String> getLoreLines(ItemStack stack) {
        if (stack.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        List<Text> tooltip = stack.getTooltip(net.minecraft.item.Item.TooltipContext.DEFAULT, null, net.minecraft.item.tooltip.TooltipType.BASIC);
        for (int i = 1; i < tooltip.size(); i++) {
            String line = Formatting.strip(tooltip.get(i).getString());
            if (line != null) result.add(line);
        }
        return result;
    }

    private String getLoreString(ItemStack stack) {
        List<String> lines = getLoreLines(stack);
        return String.join(" ", lines);
    }

    // ═══════════════════════════════════════════════════ Reset

    private void reset() {
        ultrasequencerOrder.clear();
        chronomatronOrder.clear();
        ultraPatternCaptured = false;
        clicks = 0;
        lastSlot49Item = null;
        lastClickTime = System.currentTimeMillis();
        buyXpTargetLevel = 0;
        boughtXP = false;
        resetXpBuyingProgress();
        state = State.WAITING;
        maxEnchanting = false;
        superpairsRewardsClaimed = false;
        reopenUseTicks = 0;
        reopeningStarted = false;
        lastChronomatronGlowSlot = -1;
        experimentOverClaimed = false;
        resetSuperpairs();
        InputUtils.setUse(false);
    }

    // ═══════════════════════════════════════════════════ HUD

    @Override
    public String getHudName() {
        return "AutoExperiments";
    }

    @Override
    public String getHudState() {
        return state.name();
    }

    private record ControlState(String name, boolean isGlow, boolean isClock, boolean wasClockLastFrame) {}
}
