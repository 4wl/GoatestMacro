package com.justingoat.goat.client.module.farming;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.module.failsafe.impl.RotationFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.TeleportFailsafe;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.ActionTimer;
import com.justingoat.goat.client.utils.AimController;
import com.justingoat.goat.client.utils.AntiStuckController;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.BazaarPriceCache;
import com.justingoat.goat.client.utils.BazaarPriceCache.BazaarItem;
import com.justingoat.goat.client.utils.CommandUtils;
import com.justingoat.goat.client.utils.ContainerUtils;
import com.justingoat.goat.client.utils.EntitySearchUtils;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.ItemNameUtils;
import com.justingoat.goat.client.utils.MacroClock;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.RotationUtils;
import com.justingoat.goat.client.utils.SkyBlockUtils;
import com.justingoat.goat.client.utils.SkyBlockTextUtils;
import com.justingoat.goat.client.utils.TabUtils;
import com.justingoat.goat.client.utils.WorldUtils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VisitorsMacro extends GoatModule implements MacroHudInfo {
    private enum State {
        WAITING,
        TRAVELING,
        SELECT_VISITOR,
        MOVE_TO_VISITOR,
        OPEN_VISITOR,
        WAIT_FOR_GUI,
        HANDLE_GUI,
        BAZAAR_CAPTURE,
        BAZAAR_BUY,
        FINISHED
    }

    private enum BazaarBuyState {
        START,
        OPEN_PRODUCT,
        CLICK_INSTANT_BUY,
        SELECT_AMOUNT,
        ENTER_SIGN,
        CONFIRM_BUY,
        WARNING_CONFIRM,
        WAIT_AFTER_BUY
    }

    private record VisitorTarget(String name, Entity nameStand, Entity clickTarget) {
    }

    private record VisitorItem(String name, int amount) {
    }

    private record VisitorEconomics(List<VisitorItem> requiredItems,
                                    List<VisitorItem> missingItems,
                                    double requiredCost,
                                    double rewardValue,
                                    int copper,
                                    boolean hasUnknownPrice) {
        double profit() {
            return rewardValue - requiredCost;
        }

        double pricePerCopper() {
            return copper <= 0 ? 0.0 : requiredCost / copper;
        }
    }

    private final ModeValue mode;
    private final ModeValue autoBazaar;
    private final NumberValue interactRange;
    private final NumberValue scanRange;
    private final NumberValue actionDelay;
    private final NumberValue maxSpend;
    private final NumberValue minProfit;
    private final NumberValue maxCopperPrice;
    private final BooleanValue autoTravelBarn;
    private final BooleanValue profitFilter;
    private final BooleanValue rejectFiltered;
    private final BooleanValue antiStuck;
    private final BooleanValue debug;
    private final RotationUtils rotation = new RotationUtils();
    private final AimController aim = new AimController(rotation);
    private static final Pattern ITEM_AMOUNT_AFTER = Pattern.compile("^(?<name>.+?)\\s+x(?<amount>[\\d,]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_AMOUNT_BEFORE = Pattern.compile("^\\+?(?<amount>[\\d,]+)\\s+(?<name>.+)$", Pattern.CASE_INSENSITIVE);
    private static final double ANTI_STUCK_ESCAPE_CHECK_DISTANCE = 1.35;
    private static final float ROTATION_RETARGET_YAW_THRESHOLD = 3.0f;
    private static final float ROTATION_RETARGET_PITCH_THRESHOLD = 2.0f;
    private final List<String> visitors = new ArrayList<>();
    private final Set<String> servedVisitors = new HashSet<>();
    private final Set<String> skippedVisitors = new HashSet<>();
    private final Deque<VisitorItem> bazaarQueue = new ArrayDeque<>();

    private State state = State.WAITING;
    private BazaarBuyState bazaarBuyState = BazaarBuyState.START;
    private VisitorTarget currentVisitor = null;
    private VisitorItem activeBazaarItem = null;
    private int activeBazaarInitialCount = 0;
    private final ActionTimer actionTimer = new ActionTimer();
    private long lastVisitorRefresh = 0L;
    private long bazaarStateStarted = 0L;
    private int openAttempts = 0;
    private final MacroClock visitorRefreshClock = new MacroClock();
    private final MacroClock priceWaitClock = new MacroClock();
    private int recoveryTicks = 0;
    private int noProgressTicks = 0;
    private Vec3d lastProgressPos = null;
    private Float antiStuckEscapeYaw = null;
    private float lastLookTargetYaw = Float.NaN;
    private float lastLookTargetPitch = Float.NaN;
    private boolean traveled = false;
    private boolean bazaarCaptureAnnounced = false;
    private boolean signTyped = false;
    private boolean customAmountEntered = false;

    public VisitorsMacro() {
        super("VisitorsMacro", ModuleCategory.MACRO, false);
        mode = addMode("Mode", "AcceptReady", "AcceptReady", "SkipMissing", "RejectAll");
        autoBazaar = addMode("AutoBazaar", "AutoBuy", "Off", "CaptureOnly", "AutoBuy");
        interactRange = addNumber("InteractRange", 3.0, 2.0, 5.0);
        scanRange = addNumber("ScanRange", 18.0, 8.0, 32.0);
        actionDelay = addNumber("ActionDelay", 450.0, 150.0, 1500.0);
        maxSpend = addNumber("MaxSpend", 5_000_000.0, 0.0, 100_000_000.0);
        minProfit = addNumber("MinProfit", -100_000.0, -10_000_000.0, 100_000_000.0);
        maxCopperPrice = addNumber("MaxCopperPrice", 20_000.0, 0.0, 1_000_000.0);
        autoTravelBarn = addBoolean("AutoTravelBarn", true);
        profitFilter = addBoolean("ProfitFilter", true);
        rejectFiltered = addBoolean("RejectFiltered", false);
        antiStuck = addBoolean("AntiStuck", true);
        debug = addBoolean("Debug", false);
    }

    @Override
    public boolean requiresMovement() {
        return false;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled == isEnabled()) return;
        super.setEnabled(enabled);
        if (enabled) {
            reset();
            ChatUtils.sendSuccessMessage("[Visitors] enabled");
        } else {
            stop();
            ChatUtils.sendWarningMessage("[Visitors] disabled");
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null || client.interactionManager == null) return;

        BazaarPriceCache.updateIfNeeded();
        refreshVisitors(false);

        boolean needsRotation = state == State.MOVE_TO_VISITOR || state == State.OPEN_VISITOR || recoveryTicks > 0;
        if (!needsRotation && rotation.isActive()) {
            releaseRotation(client);
        }

        if (state == State.BAZAAR_CAPTURE) {
            handleBazaarCapture(client);
            return;
        }
        if (state == State.BAZAAR_BUY) {
            handleAutoBazaar(client);
            return;
        }

        if (client.currentScreen instanceof GenericContainerScreen containerScreen) {
            state = State.HANDLE_GUI;
            handleGui(containerScreen);
            return;
        }

        if (recoveryTicks > 0) {
            tickRecovery(client);
            return;
        }

        switch (state) {
            case WAITING -> handleWaiting(client);
            case TRAVELING -> handleTraveling();
            case SELECT_VISITOR -> selectVisitor(client);
            case MOVE_TO_VISITOR -> moveToVisitor(client);
            case OPEN_VISITOR -> openVisitor(client);
            case WAIT_FOR_GUI -> waitForVisitorGui();
            case BAZAAR_CAPTURE -> handleBazaarCapture(client);
            case BAZAAR_BUY -> handleAutoBazaar(client);
            case FINISHED -> finishIfDone();
            case HANDLE_GUI -> state = State.SELECT_VISITOR;
        }
    }

    private void handleWaiting(MinecraftClient client) {
        refreshVisitors(true);
        if (visitors.isEmpty()) {
            state = State.FINISHED;
            return;
        }

        if (autoTravelBarn.getValue() && !traveled && canAct()) {
            markAllowedTeleportCommand();
            CommandUtils.teleportToPlotBarn(client);
            traveled = true;
            actionTimer.markNow();
            state = State.TRAVELING;
            debugMsg("Traveling to barn");
            return;
        }

        state = State.SELECT_VISITOR;
    }

    private void markAllowedTeleportCommand() {
        TeleportFailsafe teleportFailsafe = FailsafeManager.getInstance().getFailsafe(TeleportFailsafe.class);
        if (teleportFailsafe != null) {
            teleportFailsafe.markCommand(5000);
        }

        RotationFailsafe rotationFailsafe = FailsafeManager.getInstance().getFailsafe(RotationFailsafe.class);
        if (rotationFailsafe != null) {
            rotationFailsafe.suppressFor(5000);
        }
    }

    private void handleTraveling() {
        if (canAct()) {
            state = State.SELECT_VISITOR;
        }
    }

    private void selectVisitor(MinecraftClient client) {
        refreshVisitors(true);
        if (visitors.isEmpty()) {
            state = State.FINISHED;
            return;
        }

        currentVisitor = findClosestVisitor(client).orElse(null);
        if (currentVisitor == null) {
            debugMsg("No matching visitor entity found");
            state = State.FINISHED;
            return;
        }

        openAttempts = 0;
        resetLookTarget();
        resetMovementProgress(client);
        state = State.MOVE_TO_VISITOR;
        debugMsg("Selected " + currentVisitor.name());
    }

    private void moveToVisitor(MinecraftClient client) {
        if (currentVisitor == null || currentVisitor.nameStand().isRemoved()) {
            state = State.SELECT_VISITOR;
            return;
        }

        Entity target = currentVisitor.clickTarget();
        double distanceSq = client.player.squaredDistanceTo(target);
        if (distanceSq <= interactRange.getValue() * interactRange.getValue()) {
            InputUtils.releaseAll();
            state = State.OPEN_VISITOR;
            return;
        }

        lookAt(client, target, 0.55f);
        InputUtils.setForward(true);
        InputUtils.setSprint(true);

        if (antiStuck.getValue()) {
            updateAntiStuck(client);
        }
    }

    private void openVisitor(MinecraftClient client) {
        if (currentVisitor == null) {
            state = State.SELECT_VISITOR;
            return;
        }
        if (!canAct()) return;

        Entity target = currentVisitor.clickTarget();
        if (client.player.squaredDistanceTo(target) > interactRange.getValue() * interactRange.getValue()) {
            state = State.MOVE_TO_VISITOR;
            return;
        }

        lookAt(client, target, 0.85f);
        if (!rotation.isRoughlyFacing() && openAttempts < 2) return;

        InputUtils.releaseAll();
        client.interactionManager.interactEntity(client.player, target, Hand.MAIN_HAND);
        client.player.swingHand(Hand.MAIN_HAND);
        openAttempts++;
        actionTimer.markNow();
        debugMsg("Interacted with " + currentVisitor.name());
        releaseRotation(client);

        if (openAttempts > 6) {
            skippedVisitors.add(normalizeName(currentVisitor.name()));
            currentVisitor = null;
            state = State.SELECT_VISITOR;
        } else {
            state = State.WAIT_FOR_GUI;
        }
    }

    private void waitForVisitorGui() {
        if (currentVisitor == null) {
            state = State.SELECT_VISITOR;
            return;
        }
        if (canAct()) {
            state = State.OPEN_VISITOR;
        }
    }

    private void handleGui(GenericContainerScreen screen) {
        if (!canAct()) return;
        if (currentVisitor == null) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.closeHandledScreen();
            }
            state = State.SELECT_VISITOR;
            actionTimer.markNow();
            return;
        }

        GenericContainerScreenHandler handler = screen.getScreenHandler();
        Integer acceptSlot = findOfferSlot(handler, "accept offer", true);
        Integer refuseSlot = findOfferSlot(handler, "refuse offer", false);
        if (refuseSlot == null) {
            refuseSlot = findOfferSlot(handler, "reject offer", false);
        }

        if ("RejectAll".equals(mode.getValue())) {
            if (refuseSlot != null && clickSlot(handler, refuseSlot)) {
                markCurrentServed("rejected");
            }
            return;
        }

        if (acceptSlot != null) {
            ItemStack acceptStack = getStack(handler, acceptSlot);
            List<String> lore = getLoreLines(acceptStack);
            VisitorEconomics economics = analyzeVisitorOffer(lore);

            debugEconomics(economics);
            if (profitFilter.getValue() && !BazaarPriceCache.hasPrices()) {
                waitForBazaarPrices();
                return;
            }
            if (!passesProfitFilter(economics)) {
                handleFilteredVisitor(handler, refuseSlot, economics);
                return;
            }

            if (canAcceptOffer(lore) && clickSlot(handler, acceptSlot)) {
                markCurrentServed("accepted");
                return;
            }

            if (!"Off".equals(autoBazaar.getValue()) && !economics.missingItems().isEmpty()) {
                startBazaarFlow(economics);
                return;
            }

            debugMsg("Visitor missing requirements: " + getMissingRequirements(lore));
        }

        closeAndSkipCurrent();
    }

    private void waitForBazaarPrices() {
        if (priceWaitClock.ready(5000L)) {
            ChatUtils.sendWarningMessage("[Visitors] waiting for Bazaar prices" + bazaarErrorSuffix());
            priceWaitClock.mark();
        }
        BazaarPriceCache.updateIfNeeded();
        actionTimer.markNow();
    }

    private void handleFilteredVisitor(GenericContainerScreenHandler handler, Integer refuseSlot, VisitorEconomics economics) {
        String reason = "cost " + SkyBlockUtils.formatCoins(Math.round(economics.requiredCost()))
            + ", reward " + SkyBlockUtils.formatCoins(Math.round(economics.rewardValue()))
            + ", profit " + SkyBlockUtils.formatCoins(Math.round(economics.profit()));
        if (rejectFiltered.getValue() && refuseSlot != null && clickSlot(handler, refuseSlot)) {
            ChatUtils.sendWarningMessage("[Visitors] rejected by profit filter: " + reason);
            markCurrentServed("filtered");
            return;
        }
        ChatUtils.sendWarningMessage("[Visitors] skipped by profit filter: " + reason);
        closeAndSkipCurrent();
    }

    private void startBazaarFlow(VisitorEconomics economics) {
        MinecraftClient client = MinecraftClient.getInstance();
        bazaarQueue.clear();
        bazaarQueue.addAll(economics.missingItems());
        bazaarCaptureAnnounced = false;
        if ("CaptureOnly".equals(autoBazaar.getValue()) && currentVisitor != null) {
            skippedVisitors.add(normalizeName(currentVisitor.name()));
        }
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
        actionTimer.resetReady();
        if ("AutoBuy".equals(autoBazaar.getValue())) {
            activeBazaarItem = null;
            bazaarBuyState = BazaarBuyState.START;
            state = State.BAZAAR_BUY;
            ChatUtils.sendInfoMessage("[Visitors] AutoBazaar: " + bazaarQueue.size() + " missing item(s)");
        } else {
            state = State.BAZAAR_CAPTURE;
            ChatUtils.sendInfoMessage("[Visitors] Bazaar capture: " + bazaarQueue.size() + " missing item(s)");
        }
    }

    private void handleBazaarCapture(MinecraftClient client) {
        InputUtils.releaseAll();
        if (!canAct()) return;

        if (bazaarQueue.isEmpty()) {
            ChatUtils.sendSuccessMessage("[Visitors] Bazaar capture complete. Buy items, then rerun VisitorsMacro.");
            currentVisitor = null;
            state = State.FINISHED;
            actionTimer.markNow();
            return;
        }

        VisitorItem item = bazaarQueue.pollFirst();
        copyToClipboard(String.valueOf(item.amount()));
        CommandUtils.openBazaar(client, item.name());
        ChatUtils.sendInfoMessage("[Visitors] opened Bazaar for " + item.name() + " x" + item.amount() + " (amount copied)");
        if (!bazaarQueue.isEmpty()) {
            ChatUtils.sendInfoMessage("[Visitors] remaining: " + formatShoppingList(bazaarQueue));
        }

        if (!bazaarCaptureAnnounced) {
            bazaarCaptureAnnounced = true;
            ChatUtils.sendWarningMessage("[Visitors] CaptureOnly does not auto-confirm purchases.");
        }

        bazaarQueue.clear();
        currentVisitor = null;
        state = State.FINISHED;
        actionTimer.markNow();
        setEnabled(false);
    }

    private void handleAutoBazaar(MinecraftClient client) {
        InputUtils.releaseAll();
        if (!canAct()) return;

        if (activeBazaarItem == null) {
            activeBazaarItem = bazaarQueue.pollFirst();
            if (activeBazaarItem == null) {
                ChatUtils.sendSuccessMessage("[Visitors] AutoBazaar complete, reopening visitor");
                if (client.currentScreen != null) client.player.closeHandledScreen();
                currentVisitor = null;
                state = State.SELECT_VISITOR;
                actionTimer.markNow();
                return;
            }
            activeBazaarInitialCount = countInventoryItem(activeBazaarItem.name());
            customAmountEntered = false;
            setBazaarBuyState(BazaarBuyState.START);
        }

        if (System.currentTimeMillis() - bazaarStateStarted > 15_000L) {
            failAutoBazaar("timeout at " + bazaarBuyState);
            return;
        }

        if (client.currentScreen instanceof AbstractSignEditScreen signScreen) {
            if (bazaarBuyState == BazaarBuyState.WAIT_AFTER_BUY) {
                signScreen.close();
                actionTimer.delayFromNow(600L);
                return;
            }
            if (bazaarBuyState == BazaarBuyState.CONFIRM_BUY) {
                signScreen.close();
                actionTimer.delayFromNow(500L);
                return;
            }
            handleSignInput(signScreen);
            return;
        }

        if (!(client.currentScreen instanceof GenericContainerScreen screen)) {
            if (bazaarBuyState == BazaarBuyState.START) {
                openBazaarProduct(client);
            } else if (bazaarBuyState == BazaarBuyState.WAIT_AFTER_BUY) {
                activeBazaarItem = null;
                actionTimer.markNow();
            }
            return;
        }

        GenericContainerScreenHandler handler = screen.getScreenHandler();
        String title = strip(screen.getTitle().getString());
        String lowerTitle = title.toLowerCase(Locale.ROOT);

        switch (bazaarBuyState) {
            case START -> openBazaarProduct(client);
            case OPEN_PRODUCT -> handleBazaarProductPage(handler, lowerTitle);
            case CLICK_INSTANT_BUY -> handleClickInstantBuy(handler);
            case SELECT_AMOUNT -> handleSelectAmount(handler, lowerTitle);
            case ENTER_SIGN -> {
                // Wait for sign screen.
            }
            case CONFIRM_BUY -> handleConfirmBuy(handler, lowerTitle);
            case WARNING_CONFIRM -> handleWarningConfirm(handler, lowerTitle);
            case WAIT_AFTER_BUY -> {
                if (client.player != null) client.player.closeHandledScreen();
                activeBazaarItem = null;
                actionTimer.markNow();
            }
        }
    }

    private void openBazaarProduct(MinecraftClient client) {
        if (client.player == null || activeBazaarItem == null) return;
        if (client.currentScreen != null) {
            client.player.closeHandledScreen();
        }
        CommandUtils.openBazaar(client, activeBazaarItem.name());
        setBazaarBuyState(BazaarBuyState.OPEN_PRODUCT);
        actionTimer.delayFromNow(900L);
    }

    private void handleBazaarProductPage(GenericContainerScreenHandler handler, String lowerTitle) {
        Integer buyInstantly = findContainerSlot(handler, "buy instantly", false);
        if (buyInstantly != null) {
            clickSlot(handler, buyInstantly);
            setBazaarBuyState(BazaarBuyState.CLICK_INSTANT_BUY);
            return;
        }

        Integer productSlot = findContainerSlot(handler, activeBazaarItem.name().toLowerCase(Locale.ROOT), true);
        if (productSlot != null) {
            clickSlot(handler, productSlot);
            setBazaarBuyState(BazaarBuyState.OPEN_PRODUCT);
            return;
        }

        if (!lowerTitle.contains("bazaar")) {
            setBazaarBuyState(BazaarBuyState.START);
        }
    }

    private void handleClickInstantBuy(GenericContainerScreenHandler handler) {
        Integer buyInstantly = findContainerSlot(handler, "buy instantly", false);
        if (buyInstantly != null) {
            clickSlot(handler, buyInstantly);
            setBazaarBuyState(BazaarBuyState.SELECT_AMOUNT);
            return;
        }
        setBazaarBuyState(BazaarBuyState.SELECT_AMOUNT);
    }

    private void handleSelectAmount(GenericContainerScreenHandler handler, String lowerTitle) {
        Integer exactSlot = findExactAmountSlot(handler, activeBazaarItem.amount());
        if (exactSlot != null) {
            clickSlot(handler, exactSlot);
            setBazaarBuyState(BazaarBuyState.CONFIRM_BUY);
            return;
        }

        Integer customAmount = findContainerSlot(handler, "custom amount", false);
        if (customAmount != null) {
            clickSlot(handler, customAmount);
            if (customAmountEntered) {
                setBazaarBuyState(BazaarBuyState.CONFIRM_BUY);
            } else {
                setBazaarBuyState(BazaarBuyState.ENTER_SIGN);
            }
            return;
        }

        if (!lowerTitle.contains("instant buy")) {
            setBazaarBuyState(BazaarBuyState.CLICK_INSTANT_BUY);
        }
    }

    private void handleSignInput(AbstractSignEditScreen signScreen) {
        if (bazaarBuyState != BazaarBuyState.ENTER_SIGN) return;
        if (!signTyped) {
            for (int i = 0; i < 16; i++) {
                signScreen.keyPressed(new KeyInput(GLFW.GLFW_KEY_BACKSPACE, 0, 0));
            }
            String amount = String.valueOf(activeBazaarItem.amount());
            for (int i = 0; i < amount.length(); i++) {
                signScreen.charTyped(new CharInput(amount.charAt(i), 0));
            }
            signTyped = true;
            actionTimer.delayFromNow(250L);
            return;
        }

        signScreen.close();
        customAmountEntered = true;
        setBazaarBuyState(BazaarBuyState.CONFIRM_BUY);
        actionTimer.delayFromNow(900L);
    }

    private void handleConfirmBuy(GenericContainerScreenHandler handler, String lowerTitle) {
        if (hasReceivedActiveBazaarItem()) {
            finishActiveBazaarPurchase();
            return;
        }

        Integer confirmSlot = findConfirmBuySlot(handler);
        if (confirmSlot == null) {
            if (lowerTitle.contains("instant buy") || lowerTitle.contains("how many")) {
                setBazaarBuyState(BazaarBuyState.SELECT_AMOUNT);
            } else if (!lowerTitle.contains("confirm")) {
                setBazaarBuyState(BazaarBuyState.CLICK_INSTANT_BUY);
            }
            return;
        }

        double actualCost = extractCoinCost(getLoreLines(getStack(handler, confirmSlot)));
        if (actualCost <= 0.0) {
            actualCost = estimateBazaarCost(activeBazaarItem);
        }
        if (maxSpend.getValue() > 0.0 && actualCost > maxSpend.getValue()) {
            failAutoBazaar("actual cost " + SkyBlockUtils.formatCoins(Math.round(actualCost)) + " exceeds MaxSpend");
            return;
        }

        clickSlot(handler, confirmSlot);
        ChatUtils.sendInfoMessage("[Visitors] bought " + activeBazaarItem.name() + " x" + activeBazaarItem.amount()
            + " for about " + SkyBlockUtils.formatCoins(Math.round(actualCost)));
        setBazaarBuyState(BazaarBuyState.WAIT_AFTER_BUY);
        actionTimer.delayFromNow(1200L);
    }

    private void handleWarningConfirm(GenericContainerScreenHandler handler, String lowerTitle) {
        if (!lowerTitle.contains("confirm")) {
            setBazaarBuyState(BazaarBuyState.CONFIRM_BUY);
            return;
        }
        Integer confirm = findContainerSlot(handler, "confirm", false);
        if (confirm != null) {
            clickSlot(handler, confirm);
            setBazaarBuyState(BazaarBuyState.WAIT_AFTER_BUY);
            actionTimer.delayFromNow(1200L);
        }
    }

    private Integer findExactAmountSlot(GenericContainerScreenHandler handler, int amount) {
        return ContainerUtils.findSlot(handler, stack -> {
            String name = ItemNameUtils.getStrippedName(stack).toLowerCase(Locale.ROOT);
            if (!name.startsWith("buy") && !name.startsWith("fill") && !name.startsWith("custom")) return false;
            String lore = String.join(" ", getLoreLines(stack)).replace(",", "");
            Matcher matcher = Pattern.compile("\\b" + amount + "\\s*x\\b", Pattern.CASE_INSENSITIVE).matcher(lore);
            if (matcher.find()) return true;
            matcher = Pattern.compile("\\b" + amount + "\\s+items?\\b", Pattern.CASE_INSENSITIVE).matcher(lore);
            return matcher.find();
        });
    }

    private Integer findConfirmBuySlot(GenericContainerScreenHandler handler) {
        Integer confirm = findContainerSlot(handler, "confirm", false);
        if (confirm != null) return confirm;
        return findContainerSlot(handler, "buy instantly", false);
    }

    private boolean hasReceivedActiveBazaarItem() {
        return activeBazaarItem != null
            && countInventoryItem(activeBazaarItem.name()) >= activeBazaarInitialCount + activeBazaarItem.amount();
    }

    private void finishActiveBazaarPurchase() {
        ChatUtils.sendInfoMessage("[Visitors] bought " + activeBazaarItem.name() + " x" + activeBazaarItem.amount()
            + " for about " + SkyBlockUtils.formatCoins(Math.round(estimateBazaarCost(activeBazaarItem))));
        setBazaarBuyState(BazaarBuyState.WAIT_AFTER_BUY);
        actionTimer.delayFromNow(1200L);
    }

    private Integer findContainerSlot(GenericContainerScreenHandler handler, String needle, boolean includeLore) {
        String normalizedNeedle = BazaarPriceCache.normalize(needle);
        return ContainerUtils.findSlot(handler, stack -> {
            String name = ItemNameUtils.getStrippedName(stack);
            if (BazaarPriceCache.normalize(name).contains(normalizedNeedle)) return true;
            return includeLore && BazaarPriceCache.normalize(String.join(" ", getLoreLines(stack))).contains(normalizedNeedle);
        });
    }

    private double extractCoinCost(List<String> lore) {
        return SkyBlockTextUtils.extractCoinCost(lore);
    }

    private double estimateBazaarCost(VisitorItem item) {
        return BazaarPriceCache.findByName(item.name())
            .map(bazaarItem -> bazaarItem.instantBuyPrice() * item.amount())
            .orElse(0.0);
    }

    private void setBazaarBuyState(BazaarBuyState next) {
        bazaarBuyState = next;
        bazaarStateStarted = System.currentTimeMillis();
        signTyped = false;
        debugMsg("AutoBazaar state: " + next + (activeBazaarItem == null ? "" : " " + activeBazaarItem));
    }

    private void failAutoBazaar(String reason) {
        ChatUtils.sendErrorMessage("[Visitors] AutoBazaar failed: " + reason);
        bazaarQueue.clear();
        activeBazaarItem = null;
        if (currentVisitor != null) {
            skippedVisitors.add(normalizeName(currentVisitor.name()));
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.closeHandledScreen();
        currentVisitor = null;
        state = State.SELECT_VISITOR;
        actionTimer.markNow();
    }

    private void markCurrentServed(String action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (currentVisitor != null) {
            servedVisitors.add(normalizeName(currentVisitor.name()));
            ChatUtils.sendInfoMessage("[Visitors] " + action + " " + currentVisitor.name());
        }
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
        currentVisitor = null;
        state = State.SELECT_VISITOR;
        actionTimer.markNow();
    }

    private void closeAndSkipCurrent() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (currentVisitor != null) {
            skippedVisitors.add(normalizeName(currentVisitor.name()));
            ChatUtils.sendWarningMessage("[Visitors] skipped " + currentVisitor.name());
        }
        if (client.player != null) {
            client.player.closeHandledScreen();
        }
        currentVisitor = null;
        state = State.SELECT_VISITOR;
        actionTimer.markNow();
    }

    private void finishIfDone() {
        InputUtils.releaseAll();
        if (visitors.isEmpty() || visitors.stream().allMatch(name -> {
            String normalized = normalizeName(name);
            return servedVisitors.contains(normalized) || skippedVisitors.contains(normalized);
        })) {
            ChatUtils.sendSuccessMessage("[Visitors] no more actionable visitors");
            setEnabled(false);
            return;
        }
        state = State.SELECT_VISITOR;
    }

    private void refreshVisitors(boolean force) {
        if (!force && !visitorRefreshClock.ready(1000L)) return;
        visitorRefreshClock.mark();

        List<String> tabLines = TabUtils.getTabLines();
        List<String> parsed = new ArrayList<>();
        boolean found = false;
        for (String line : tabLines) {
            String stripped = cleanName(line);
            if (stripped.contains("Visitors:")) {
                found = true;
                continue;
            }
            if (!found) continue;
            if (stripped.isBlank() || stripped.contains("Next Visitor") || parsed.size() >= 5) break;
            parsed.add(stripped.replace("NEW!", "").trim());
        }

        if (!parsed.equals(visitors)) {
            visitors.clear();
            visitors.addAll(parsed);
            if (visitors.isEmpty()) {
                servedVisitors.clear();
                skippedVisitors.clear();
            }
            debugMsg("Tab visitors: " + visitors);
        }
    }

    private Optional<VisitorTarget> findClosestVisitor(MinecraftClient client) {
        return EntitySearchUtils.closestArmorStand(client, scanRange.getValue(), stand -> {
            if (stand.getCustomName() == null) return false;

            String standName = cleanName(stand.getCustomName().getString());
            String matched = visitors.stream()
                .filter(visitor -> normalizeName(visitor).equals(normalizeName(standName)))
                .findFirst()
                .orElse(null);
            if (matched == null) return false;

            String normalized = normalizeName(matched);
            return !servedVisitors.contains(normalized) && !skippedVisitors.contains(normalized);
        }).map(stand -> {
            String standName = cleanName(stand.getCustomName().getString());
            String matched = visitors.stream()
                .filter(visitor -> normalizeName(visitor).equals(normalizeName(standName)))
                .findFirst()
                .orElse(standName);
            return new VisitorTarget(matched, stand, findClickTarget(client, stand));
        });
    }

    private Entity findClickTarget(MinecraftClient client, Entity nameStand) {
        Box searchBox = nameStand.getBoundingBox().expand(2.0, 3.0, 2.0);
        return EntitySearchUtils.closestLivingNear(client, nameStand, searchBox, entity -> true).orElse(nameStand);
    }

    private Integer findOfferSlot(GenericContainerScreenHandler handler, String itemNameNeedle, boolean accept) {
        return ContainerUtils.findSlot(handler, stack -> {
            String name = ItemNameUtils.getStrippedName(stack).toLowerCase(Locale.ROOT);
            String lore = String.join(" ", getLoreLines(stack)).toLowerCase(Locale.ROOT);
            if (name.contains(itemNameNeedle)) return true;
            if (accept && lore.contains("click to give")) return true;
            return !accept && (name.contains("refuse") || name.contains("reject") || lore.contains("refuse offer"));
        });
    }

    private boolean canAcceptOffer(List<String> lore) {
        for (String line : lore) {
            if (line.toLowerCase(Locale.ROOT).contains("click to give")) return true;
        }
        return false;
    }

    private String getMissingRequirements(List<String> lore) {
        List<String> missing = new ArrayList<>();
        boolean required = false;
        for (String line : lore) {
            String trimmed = line.trim();
            if (trimmed.contains("Required:")) {
                required = true;
                continue;
            }
            if (required && (trimmed.contains("Rewards:") || trimmed.isBlank())) break;
            if (required && !trimmed.isBlank()) missing.add(trimmed);
        }
        return missing.isEmpty() ? "unknown" : String.join(", ", missing);
    }

    private VisitorEconomics analyzeVisitorOffer(List<String> lore) {
        List<VisitorItem> required = new ArrayList<>();
        List<VisitorItem> missing = new ArrayList<>();
        double requiredCost = 0.0;
        double rewardValue = 0.0;
        int copper = 0;
        boolean unknownPrice = false;
        boolean readingRequired = false;
        boolean readingRewards = false;

        for (String line : lore) {
            String trimmed = cleanLoreLine(line);
            if (trimmed.isBlank()) continue;
            if (trimmed.contains("Required:")) {
                readingRequired = true;
                readingRewards = false;
                continue;
            }
            if (trimmed.contains("Rewards:")) {
                readingRequired = false;
                readingRewards = true;
                continue;
            }

            VisitorItem item = parseVisitorItem(trimmed);
            if (item == null) continue;

            if (readingRequired) {
                required.add(item);
                int owned = countInventoryItem(item.name());
                if (owned < item.amount()) {
                    missing.add(new VisitorItem(item.name(), item.amount() - owned));
                }
                Optional<BazaarItem> bz = BazaarPriceCache.findByName(item.name());
                if (bz.isPresent() && bz.get().instantBuyPrice() > 0.0) {
                    requiredCost += bz.get().instantBuyPrice() * item.amount();
                } else {
                    unknownPrice = true;
                }
            } else if (readingRewards) {
                if (item.name().equalsIgnoreCase("Copper")) {
                    copper += item.amount();
                    rewardValue += BazaarPriceCache.getCopperValue() * item.amount();
                    continue;
                }
                Optional<BazaarItem> bz = BazaarPriceCache.findByName(item.name());
                if (bz.isPresent()) {
                    double price = bz.get().instantSellPrice() > 0.0 ? bz.get().instantSellPrice() : bz.get().instantBuyPrice();
                    rewardValue += price * item.amount();
                }
            }
        }

        return new VisitorEconomics(List.copyOf(required), List.copyOf(missing), requiredCost, rewardValue, copper, unknownPrice);
    }

    private boolean passesProfitFilter(VisitorEconomics economics) {
        if (!profitFilter.getValue()) return true;
        if (!BazaarPriceCache.hasPrices()) {
            ChatUtils.sendWarningMessage("[Visitors] Bazaar prices not loaded yet" + bazaarErrorSuffix());
            return false;
        }
        if (economics.hasUnknownPrice()) return false;
        if (maxSpend.getValue() > 0.0 && economics.requiredCost() > maxSpend.getValue()) return false;
        if (economics.profit() < minProfit.getValue()) return false;
        return economics.copper() <= 0 || maxCopperPrice.getValue() <= 0.0 || economics.pricePerCopper() <= maxCopperPrice.getValue();
    }

    private String bazaarErrorSuffix() {
        String error = BazaarPriceCache.getLastError();
        return error == null || error.isBlank() ? "" : ": " + error;
    }

    private VisitorItem parseVisitorItem(String line) {
        String normalized = line
            .replace("Click to give", "")
            .replace("Click to accept!", "")
            .trim();
        if (normalized.isBlank()) return null;

        Matcher after = ITEM_AMOUNT_AFTER.matcher(normalized);
        if (after.matches()) {
            return new VisitorItem(after.group("name").trim(), parseAmount(after.group("amount")));
        }

        Matcher before = ITEM_AMOUNT_BEFORE.matcher(normalized);
        if (before.matches()) {
            String name = before.group("name").trim();
            if (isRewardOrCurrencyName(name)) {
                return new VisitorItem(name, parseAmount(before.group("amount")));
            }
        }

        if (looksLikeItemName(normalized)) {
            return new VisitorItem(normalized, 1);
        }
        return null;
    }

    private boolean isRewardOrCurrencyName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("copper")
            || lower.contains("bits")
            || BazaarPriceCache.findByName(name).isPresent();
    }

    private boolean looksLikeItemName(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return !lower.contains("click")
            && !lower.contains("not enough")
            && !lower.contains("required")
            && !lower.contains("rewards")
            && !lower.contains("garden experience")
            && !lower.contains("farming xp")
            && line.length() <= 48;
    }

    private int parseAmount(String raw) {
        return SkyBlockTextUtils.parseAmount(raw, 1);
    }

    private int countInventoryItem(String itemName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;
        String target = BazaarPriceCache.normalize(itemName);
        int total = 0;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            String name = ItemNameUtils.getStrippedName(stack);
            if (BazaarPriceCache.normalize(name).equals(target)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private String cleanLoreLine(String line) {
        return strip(line)
            .replace("§", "")
            .replace("☘", "")
            .replace("❤", "")
            .replace("◆", "")
            .trim();
    }

    private String formatShoppingList(Deque<VisitorItem> items) {
        List<String> parts = new ArrayList<>();
        for (VisitorItem item : items) {
            parts.add(item.name() + " x" + item.amount());
        }
        return String.join(", ", parts);
    }

    private void debugEconomics(VisitorEconomics economics) {
        debugMsg("Cost=" + Math.round(economics.requiredCost())
            + " Reward=" + Math.round(economics.rewardValue())
            + " Profit=" + Math.round(economics.profit())
            + " PPC=" + Math.round(economics.pricePerCopper())
            + " Missing=" + economics.missingItems());
    }

    private void lookAt(MinecraftClient client, Entity target, float speed) {
        if (client.player == null || target == null) return;
        aim.initIfNeeded(client);
        RotationInterpolator.setActive(rotation);

        Vec3d eye = client.player.getEyePos();
        Vec3d targetPos = target.getBoundingBox().getCenter();
        float[] look = RotationUtils.lookAt(eye.x, eye.y, eye.z, targetPos.x, targetPos.y, targetPos.z);
        if (shouldRetargetRotation(look[0], look[1])) {
            rotation.setTarget(look[0], look[1]);
            lastLookTargetYaw = look[0];
            lastLookTargetPitch = look[1];
        }
        rotation.setSpeed(speed);
        rotation.tick();
    }

    private void releaseRotation(MinecraftClient client) {
        aim.applyAndClear(client);
        RotationInterpolator.clearActive();
        resetLookTarget();
    }

    private boolean shouldRetargetRotation(float yaw, float pitch) {
        if (!rotation.isActive()) return true;
        if (Float.isNaN(lastLookTargetYaw) || Float.isNaN(lastLookTargetPitch)) return true;
        float yawDelta = Math.abs(MathHelper.wrapDegrees(yaw - lastLookTargetYaw));
        float pitchDelta = Math.abs(pitch - lastLookTargetPitch);
        return yawDelta >= ROTATION_RETARGET_YAW_THRESHOLD || pitchDelta >= ROTATION_RETARGET_PITCH_THRESHOLD;
    }

    private void resetLookTarget() {
        lastLookTargetYaw = Float.NaN;
        lastLookTargetPitch = Float.NaN;
    }

    private void updateAntiStuck(MinecraftClient client) {
        Vec3d pos = playerPos(client);
        if (lastProgressPos == null || pos.squaredDistanceTo(lastProgressPos) > 0.18 * 0.18) {
            lastProgressPos = pos;
            noProgressTicks = 0;
            return;
        }

        noProgressTicks++;
        if (noProgressTicks < 35) return;

        noProgressTicks = 0;
        recoveryTicks = 14;
        antiStuckEscapeYaw = AntiStuckController.chooseEscapeYaw(
            client,
            currentVisitor == null ? null : currentVisitor.clickTarget().getBoundingBox().getCenter(),
            ANTI_STUCK_ESCAPE_CHECK_DISTANCE
        );
        InputUtils.releaseAll();
        debugMsg("AntiStuck recovery");
    }

    private void tickRecovery(MinecraftClient client) {
        recoveryTicks--;
        if (currentVisitor != null) {
            lookAt(client, currentVisitor.clickTarget(), 0.45f);
        }
        if (antiStuckEscapeYaw == null) {
            antiStuckEscapeYaw = AntiStuckController.chooseEscapeYaw(
                client,
                currentVisitor == null ? null : currentVisitor.clickTarget().getBoundingBox().getCenter(),
                ANTI_STUCK_ESCAPE_CHECK_DISTANCE
            );
        }
        AntiStuckController.applyMovement(client, antiStuckEscapeYaw);
        InputUtils.setJump(recoveryTicks > 2);
        if (recoveryTicks <= 0) {
            InputUtils.releaseAll();
            antiStuckEscapeYaw = null;
            resetMovementProgress(client);
            state = currentVisitor == null ? State.SELECT_VISITOR : State.MOVE_TO_VISITOR;
        }
    }

    private boolean clickSlot(GenericContainerScreenHandler handler, int slot) {
        if (!ContainerUtils.clickSlot(handler, slot)) return false;
        actionTimer.markNow();
        return true;
    }

    private ItemStack getStack(GenericContainerScreenHandler handler, int slot) {
        return ContainerUtils.getStack(handler, slot);
    }

    private List<String> getLoreLines(ItemStack stack) {
        return ContainerUtils.getLoreLines(stack);
    }

    private boolean canAct() {
        return actionTimer.ready((long) actionDelay.getValue());
    }

    private void resetMovementProgress(MinecraftClient client) {
        lastProgressPos = client.player == null ? null : playerPos(client);
        noProgressTicks = 0;
        recoveryTicks = 0;
        antiStuckEscapeYaw = null;
    }

    private void reset() {
        state = State.WAITING;
        currentVisitor = null;
        visitors.clear();
        servedVisitors.clear();
        skippedVisitors.clear();
        actionTimer.resetReady();
        lastVisitorRefresh = 0L;
        openAttempts = 0;
        recoveryTicks = 0;
        noProgressTicks = 0;
        lastProgressPos = null;
        antiStuckEscapeYaw = null;
        resetLookTarget();
        traveled = false;
        bazaarCaptureAnnounced = false;
        customAmountEntered = false;
        priceWaitClock.resetReady();
        visitorRefreshClock.resetReady();
        releaseRotation(MinecraftClient.getInstance());
        InputUtils.releaseAll();
    }

    private void stop() {
        releaseRotation(MinecraftClient.getInstance());
        InputUtils.releaseAll();
        currentVisitor = null;
        state = State.WAITING;
        antiStuckEscapeYaw = null;
        resetLookTarget();
    }

    private String cleanName(String name) {
        return strip(name).replace("NEW!", "").trim();
    }

    private Vec3d playerPos(MinecraftClient client) {
        return WorldUtils.playerPos(client);
    }

    private String normalizeName(String name) {
        return cleanName(name).toLowerCase(Locale.ROOT);
    }

    private String strip(String text) {
        return SkyBlockTextUtils.strip(text);
    }

    private void copyToClipboard(String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.keyboard != null) {
            client.keyboard.setClipboard(text);
        }
    }

    private void debugMsg(String message) {
        if (debug.getValue()) {
            ChatUtils.sendDebugMessage("[Visitors] " + message);
        }
    }

    @Override
    public String getHudName() {
        return "Visitors";
    }

    @Override
    public String getHudState() {
        if (currentVisitor != null) {
            return state.name() + " " + currentVisitor.name();
        }
        return state.name();
    }
}
