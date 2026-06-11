package com.justingoat.goat.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.justingoat.goat.client.config.GoatConfigManager;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.ModuleValue;
import com.justingoat.goat.client.module.value.NumberValue;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class GoatMacroScreen extends Screen {
    private static final int OVERLAY = 0x66000000;
    private static final int OUTLINE = 0x50505050;
    private static final int BG = 0xFFF5F5F5;
    private static final int LEFT = 0xFFFFFFFF;
    private static final int THEME = 0xFF0095FF;
    private static final int TEXT = 0xFF000000;
    private static final int MUTED = 0xFF969696;
    private static final int DISABLED = 0xFFB4B4B4;
    private static final int LINE = 0x46B2B2B2;
    private static final int MODULE_BOX = 0xFFFFFFFF;
    private static final int OPTION_BG = 0xFFE8E8E8;
    private static final int OPTION_ON = 0xFF0095FF;
    private static final int OPTION_OFF = 0xFF969696;

    private static int windowX = -1;
    private static int windowY = -1;
    private static int windowWidth = 450;
    private static int windowHeight = 380;
    private static ModuleCategory selectedCategory = ModuleCategory.COMBAT;
    private static GoatModule selectedModule;
    private static int moduleScroll;
    private static int valueScroll;

    private final List<GoatModule> modules = new ArrayList<>();
    private boolean dragging;
    private int dragX;
    private int dragY;
    private boolean resizing;
    private int resizeX;
    private int resizeY;
    private NumberValue draggingSlider;
    private Double sliderValueBeforeDrag;
    private ModeValue expandedMode;
    private CustomFontRenderer fontRenderer;

    public GoatMacroScreen() {
        super(Text.literal("Goat"));
        modules.addAll(ModuleManager.getModules());
    }

    @Override
    protected void init() {
        if (fontRenderer == null) {
            fontRenderer = new CustomFontRenderer();
        }
        if (windowX < 1 || windowY < 1) {
            windowX = (width - windowWidth) / 2;
            windowY = (height - windowHeight) / 2;
        }
        clampWindow();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, OVERLAY);

        if (dragging) {
            windowX = mouseX - dragX;
            windowY = mouseY - dragY;
            clampWindow();
        }
        if (resizing) {
            windowWidth = clamp(mouseX + resizeX - windowX, 400, 700);
            windowHeight = clamp(mouseY + resizeY - windowY, 310, 400);
        }
        if (draggingSlider != null) {
            updateNumberValue(draggingSlider, mouseX);
        }

        drawShell(context);
        drawCategories(context, mouseX, mouseY);
        if (selectedModule == null) {
            drawModuleList(context, mouseX, mouseY);
        } else {
            drawValueList(context, mouseX, mouseY);
        }
        drawResizeHandle(context);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mouseX = (int) click.x();
        int mouseY = (int) click.y();
        int button = click.button();

        if (button == 0 && isInside(mouseX, mouseY, windowX, windowY, leftWidth(), 34)) {
            dragging = true;
            dragX = mouseX - windowX;
            dragY = mouseY - windowY;
            return true;
        }
        if (button == 0 && isInside(mouseX, mouseY, windowX + windowWidth - 20, windowY + windowHeight - 20, 16, 16)) {
            resizing = true;
            resizeX = windowX + windowWidth - mouseX;
            resizeY = windowY + windowHeight - mouseY;
            return true;
        }

        int categoryY = windowY + 80;
        for (ModuleCategory category : ModuleCategory.values()) {
            if (isInside(mouseX, mouseY, windowX, categoryY - 5, leftWidth() - 10, 22)) {
                selectedCategory = category;
                selectedModule = category == ModuleCategory.SETTINGS ? ModuleManager.findByName("ClientSettings") : null;
                moduleScroll = 0;
                valueScroll = 0;
                expandedMode = null;
                return true;
            }
            categoryY += 30;
        }

        if (selectedModule != null) {
            return clickValue(mouseX, mouseY, button);
        }
        return clickModule(mouseX, mouseY, button) || super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = false;
        resizing = false;
        if (draggingSlider != null && sliderValueBeforeDrag != null && draggingSlider.getValue() != sliderValueBeforeDrag) {
            GoatConfigManager.save();
        }
        draggingSlider = null;
        sliderValueBeforeDrag = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int delta = verticalAmount > 0 ? 16 : -16;
        if (selectedModule == null) {
            moduleScroll = clamp(moduleScroll + delta, -Math.max(0, visibleModuleCount() * 35 - (windowHeight - 40)), 0);
        } else {
            valueScroll = clamp(valueScroll + delta, -Math.max(0, selectedModule.getValues().size() * 28 - (windowHeight - 64)), 0);
        }
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        if (fontRenderer != null) {
            fontRenderer.close();
            fontRenderer = null;
        }
        super.removed();
    }

    private void drawShell(DrawContext context) {
        rounded(context, windowX - 1, windowY - 1, windowWidth + 2, windowHeight + 2, OUTLINE);
        rounded(context, windowX, windowY, windowWidth, windowHeight, BG);
        rounded(context, windowX, windowY, leftWidth(), windowHeight, LEFT);
        context.fill(windowX + leftWidth(), windowY, windowX + leftWidth() + 1, windowY + windowHeight, LINE);

        fontRenderer.drawText(context, "Goat", windowX + 14, windowY + 27, THEME);
        fontRenderer.drawText(context, "1.0", windowX + 55, windowY + 27, MUTED);
    }

    private void drawCategories(DrawContext context, int mouseX, int mouseY) {
        int y = windowY + 80;
        for (ModuleCategory category : ModuleCategory.values()) {
            boolean selected = category == selectedCategory;
            if (selected) {
                rounded(context, windowX, y - 4, leftWidth(), 20, THEME);
            }
            int color = selected ? 0xFFFFFFFF : MUTED;
            fontRenderer.drawText(context, category.getIcon(), windowX + 10, y, color);
            fontRenderer.drawText(context, category.getLabel(), windowX + 28, y, color);
            y += 30;
        }
    }

    private void drawModuleList(DrawContext context, int mouseX, int mouseY) {
        int y = windowY + 10 + moduleScroll;
        for (GoatModule module : modules) {
            if (module.getCategory() != selectedCategory) {
                continue;
            }
            if (y > windowY - 35 && y < windowY + windowHeight - 20) {
                boolean hover = isInside(mouseX, mouseY, windowX + leftWidth() + 8, y, windowWidth - leftWidth() - 16, 30);
                rounded(context, windowX + leftWidth() + 8, y, windowWidth - leftWidth() - 16, 30, hover ? 0xFFF9F9F9 : MODULE_BOX);
                fontRenderer.drawText(context, module.getName(), windowX + leftWidth() + 14, y + 11, module.isEnabled() ? TEXT : DISABLED);
                if (!module.getValues().isEmpty()) {
                    fontRenderer.drawText(context, "...", windowX + windowWidth - 68, y + 11, MUTED);
                }
                if (module.canToggle()) {
                    drawToggle(context, windowX + windowWidth - 50, y + 10, module.isEnabled());
                }
            }
            y += 35;
        }
    }

    private void drawValueList(DrawContext context, int mouseX, int mouseY) {
        fontRenderer.drawText(context, selectedModule.getName(), windowX + leftWidth() + 25, windowY + 14, TEXT);
        context.fill(windowX + leftWidth() + 10, windowY + 26, windowX + windowWidth, windowY + 27, LINE);

        int y = windowY + 40 + valueScroll;
        for (ModuleValue value : selectedModule.getValues()) {
            if (y > windowY - 35 && y < windowY + windowHeight - 20) {
                fontRenderer.drawText(context, value.getName(), windowX + leftWidth() + 26, y, TEXT);
                if (value instanceof BooleanValue booleanValue) {
                    drawCheck(context, windowX + leftWidth() + 15, y, booleanValue.getValue());
                } else if (value instanceof NumberValue numberValue) {
                    drawNumber(context, numberValue, y);
                } else if (value instanceof ModeValue modeValue) {
                    drawMode(context, modeValue, y);
                    if (expandedMode == modeValue) {
                        y += (modeValue.getModes().size() - 1) * 15;
                    }
                }
                context.fill(windowX + leftWidth() + 10, y + 14, windowX + windowWidth - 10, y + 15, LINE);
            }
            y += value instanceof ModeValue modeValue && expandedMode == modeValue ? 40 : 20;
        }
        fontRenderer.drawText(context, "Right click to go back", windowX + leftWidth() + 25, windowY + windowHeight - 22, MUTED);
    }

    private void drawResizeHandle(DrawContext context) {
        int x = windowX + windowWidth - 18;
        int y = windowY + windowHeight - 18;
        context.fill(x + 10, y + 4, x + 12, y + 12, MUTED);
        context.fill(x + 6, y + 8, x + 8, y + 12, MUTED);
        context.fill(x + 2, y + 12, x + 12, y + 14, MUTED);
    }

    private boolean clickModule(int mouseX, int mouseY, int button) {
        int y = windowY + 10 + moduleScroll;
        for (GoatModule module : modules) {
            if (module.getCategory() == selectedCategory) {
                if (isInside(mouseX, mouseY, windowX + leftWidth() + 8, y, windowWidth - leftWidth() - 16, 30)) {
                    if (button == 0 && module.canToggle()) {
                        module.toggle();
                        GoatConfigManager.save();
                    } else if (button == 1 && !module.getValues().isEmpty()) {
                        selectedModule = module;
                        valueScroll = 0;
                        expandedMode = null;
                    }
                    return true;
                }
                y += 35;
            }
        }
        return false;
    }

    private boolean clickValue(int mouseX, int mouseY, int button) {
        if (button == 1) {
            selectedModule = selectedCategory == ModuleCategory.SETTINGS ? selectedModule : null;
            expandedMode = null;
            return true;
        }

        int y = windowY + 40 + valueScroll;
        for (ModuleValue value : selectedModule.getValues()) {
            if (value instanceof BooleanValue booleanValue && isInside(mouseX, mouseY, windowX + leftWidth() + 10, y - 2, windowWidth - leftWidth() - 40, 16)) {
                booleanValue.toggle();
                GoatConfigManager.save();
                return true;
            }
            if (value instanceof NumberValue numberValue && isInside(mouseX, mouseY, windowX + windowWidth - 110, y, 80, 12)) {
                draggingSlider = numberValue;
                sliderValueBeforeDrag = numberValue.getValue();
                updateNumberValue(numberValue, mouseX);
                return true;
            }
            if (value instanceof ModeValue modeValue && isInside(mouseX, mouseY, windowX + windowWidth - 110, y, 80, 15)) {
                expandedMode = expandedMode == modeValue ? null : modeValue;
                return true;
            }
            if (value instanceof ModeValue modeValue && expandedMode == modeValue) {
                int optionY = y + 15;
                for (String mode : modeValue.getModes()) {
                    if (!mode.equals(modeValue.getValue()) && isInside(mouseX, mouseY, windowX + windowWidth - 110, optionY, 80, 15)) {
                        modeValue.setValue(mode);
                        expandedMode = null;
                        GoatConfigManager.save();
                        return true;
                    }
                    optionY += mode.equals(modeValue.getValue()) ? 0 : 15;
                }
                y += (modeValue.getModes().size() - 1) * 15;
            }
            y += 20;
        }
        return true;
    }

    private void drawNumber(DrawContext context, NumberValue value, int y) {
        int x = windowX + windowWidth - 110;
        context.fill(x, y + 1, x + 80, y + 9, OPTION_BG);
        double pct = (value.getValue() - value.getMin()) / (value.getMax() - value.getMin());
        int fill = (int) Math.round(pct * 70.0);
        context.fill(x, y + 1, x + 10 + fill, y + 9, THEME);
        String label = value.getValue() == Math.rint(value.getValue())
            ? Integer.toString((int) value.getValue())
            : String.format(java.util.Locale.ROOT, "%.1f", value.getValue());
        fontRenderer.drawText(context, label, x + 84 - fontRenderer.getWidth(label), y, TEXT);
    }

    private void drawMode(DrawContext context, ModeValue value, int y) {
        int x = windowX + windowWidth - 110;
        int h = expandedMode == value ? 15 + (value.getModes().size() - 1) * 15 : 15;
        rounded(context, x, y + 1, 80, h, OPTION_BG);
        fontRenderer.drawText(context, value.getValue(), x + 10, y + 5, TEXT);
        if (expandedMode == value) {
            int optionY = y + 20;
            for (String mode : value.getModes()) {
                if (!mode.equals(value.getValue())) {
                    fontRenderer.drawText(context, mode, x + 10, optionY, MUTED);
                    optionY += 15;
                }
            }
        }
    }

    private void drawToggle(DrawContext context, int x, int y, boolean enabled) {
        rounded(context, x, y, 20, 10, OPTION_BG);
        int knobX = enabled ? x + 12 : x + 2;
        context.fill(knobX, y + 2, knobX + 6, y + 8, enabled ? OPTION_ON : OPTION_OFF);
    }

    private void drawCheck(DrawContext context, int x, int y, boolean enabled) {
        context.fill(x, y, x + 8, y + 8, enabled ? OPTION_ON : OPTION_BG);
        if (enabled) {
            fontRenderer.drawText(context, "x", x + 2, y, 0xFFFFFFFF);
        }
    }

    private void updateNumberValue(NumberValue value, int mouseX) {
        double pct = clamp((mouseX - (windowX + windowWidth - 100)) / 70.0, 0.0, 1.0);
        double raw = value.getMin() + (value.getMax() - value.getMin()) * pct;
        value.setValue(Math.round(raw * 10.0) / 10.0);
    }

    private void rounded(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x + 2, y, x + w - 2, y + h, color);
        context.fill(x, y + 2, x + w, y + h - 2, color);
    }

    private int visibleModuleCount() {
        int count = 0;
        for (GoatModule module : modules) {
            if (module.getCategory() == selectedCategory) {
                count++;
            }
        }
        return count;
    }

    private int leftWidth() {
        return 90;
    }

    private void clampWindow() {
        windowX = clamp(windowX, 4 - windowWidth + leftWidth(), width - 40);
        windowY = clamp(windowY, 4, height - 40);
    }

    private boolean isInside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

}
