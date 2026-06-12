package com.justingoat.goat.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.justingoat.goat.client.config.GoatConfigManager;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.KeybindValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.ModuleValue;
import com.justingoat.goat.client.module.value.NumberValue;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class GoatMacroScreen extends Screen {
    private static final int BG = 0xFF15111D;
    private static final int SIDEBAR_BG = 0xFF15111D;
    private static final int BORDER = 0xFF2A2239;
    private static final int HEADER_BG = 0xFF1C1628;
    private static final int HEADER_BORDER = 0xFF392561;
    private static final int TEXT_PRIMARY = 0xFFEBE8E5;
    private static final int TEXT_SECONDARY = 0xFF5E5470;
    private static final int ACCENT = 0xFF865DD4;
    private static final int MODULE_BG = 0xFF15111D;
    private static final int MODULE_BORDER = 0xFF2A2239;
    private static final int CATEGORY_BG_START = 0xFF2B2140;
    private static final int CATEGORY_OUTLINE = 0xFF372952;
    private static final int SWITCH_ON_TRACK = 0xFF312054;
    private static final int SWITCH_OFF_TRACK = 0xFF261D38;
    private static final int SWITCH_ON_THUMB = 0xFFEBE8E5;
    private static final int SWITCH_OFF_THUMB = 0xFF5E5470;
    private static final int SLIDER_TRACK = 0xFF3A2A5C;
    private static final int SLIDER_KNOB = 0xFFEBE8E5;
    private static final int KEYBIND_BG = 0xFF261D38;
    private static final int KEYBIND_LISTENING = 0xFF865DD4;
    private static final int SEPARATOR = 0xFF2A2239;
    private static final int MODE_BG = 0xFF261D38;

    private static final int BASE_WIDTH = 460;
    private static final int BASE_HEIGHT = 320;
    private static final int HEADER_HEIGHT = 36;
    private static final int SIDEBAR_WIDTH = 120;

    private static int windowX = -1;
    private static int windowY = -1;
    private static ModuleCategory selectedCategory = ModuleCategory.MACRO;
    private static GoatModule selectedModule;
    private static int moduleScroll;
    private static int valueScroll;

    private final List<GoatModule> modules = new ArrayList<>();
    private NumberValue draggingSlider;
    private Double sliderValueBeforeDrag;
    private ModeValue expandedMode;
    private KeybindValue listeningKeybind;
    private boolean listeningModuleKeybind;
    private GoatModule listeningModule;
    private CustomFontRenderer fontRenderer;
    private GoatGuiRenderer renderer;

    public GoatMacroScreen() {
        super(Text.literal("Goat"));
        modules.addAll(ModuleManager.getModules());
    }

    @Override
    protected void init() {
        if (fontRenderer == null) {
            fontRenderer = new CustomFontRenderer();
        }
        if (windowX < 0 || windowY < 0) {
            windowX = (width - BASE_WIDTH) / 2;
            windowY = (height - BASE_HEIGHT) / 2;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (draggingSlider != null) {
            updateNumberValue(draggingSlider, mouseX);
        }

        renderer = new GoatGuiRenderer(context, fontRenderer);
        drawBackground(context);
        drawHeader(context);
        drawSidebar(context, mouseX, mouseY);
        drawSeparators(context);

        if (selectedModule == null) {
            drawModuleList(context, mouseX, mouseY);
        } else {
            drawValueList(context, mouseX, mouseY);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        int keyCode = keyInput.key();
        if (listeningKeybind != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                listeningKeybind.setKeyCode(-1);
            } else {
                listeningKeybind.setKeyCode(keyCode);
            }
            listeningKeybind = null;
            GoatConfigManager.save();
            return true;
        }
        if (listeningModuleKeybind && listeningModule != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                listeningModule.setKeyBind(-1);
            } else {
                listeningModule.setKeyBind(keyCode);
            }
            listeningModuleKeybind = false;
            listeningModule = null;
            GoatConfigManager.save();
            return true;
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mouseX = (int) click.x();
        int mouseY = (int) click.y();
        int button = click.button();

        if (listeningKeybind != null || listeningModuleKeybind) {
            listeningKeybind = null;
            listeningModuleKeybind = false;
            listeningModule = null;
            return true;
        }

        int sx = windowX;
        int sy = windowY + HEADER_HEIGHT;
        int categoryY = sy + 12;
        for (ModuleCategory category : ModuleCategory.values()) {
            if (isInside(mouseX, mouseY, sx + 8, categoryY, SIDEBAR_WIDTH - 16, 24)) {
                selectedCategory = category;
                selectedModule = null;
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
        if (draggingSlider != null && sliderValueBeforeDrag != null && draggingSlider.getValue() != sliderValueBeforeDrag) {
            GoatConfigManager.save();
        }
        draggingSlider = null;
        sliderValueBeforeDrag = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int delta = verticalAmount > 0 ? 20 : -20;
        if (selectedModule == null) {
            moduleScroll = clamp(moduleScroll + delta, -Math.max(0, visibleModuleCount() * 46 - contentHeight()), 0);
        } else {
            int totalHeight = computeValueListHeight();
            valueScroll = clamp(valueScroll + delta, -Math.max(0, totalHeight - contentHeight()), 0);
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

    private void drawBackground(DrawContext context) {
        rounded(context, windowX - 1, windowY - 1, BASE_WIDTH + 2, BASE_HEIGHT + 2, BORDER);
        rounded(context, windowX, windowY, BASE_WIDTH, BASE_HEIGHT, BG);
    }

    private void drawHeader(DrawContext context) {
        rounded(context, windowX, windowY, BASE_WIDTH, HEADER_HEIGHT, HEADER_BG);
        fill(context, windowX, windowY + HEADER_HEIGHT - 1, windowX + BASE_WIDTH, windowY + HEADER_HEIGHT, HEADER_BORDER);

        fontRenderer.drawMediumText(context, "Goat", windowX + 14, windowY + 14, ACCENT);
        fontRenderer.drawText(context, "v1.0", windowX + 46, windowY + 14, TEXT_SECONDARY);
    }

    private void drawSidebar(DrawContext context, int mouseX, int mouseY) {
        int sx = windowX;
        int sy = windowY + HEADER_HEIGHT;

        int categoryY = sy + 12;
        for (ModuleCategory category : ModuleCategory.values()) {
            boolean selected = category == selectedCategory;
            boolean hover = isInside(mouseX, mouseY, sx + 8, categoryY, SIDEBAR_WIDTH - 16, 24);

            if (selected) {
                rounded(context, sx + 8, categoryY, SIDEBAR_WIDTH - 16, 24, CATEGORY_BG_START);
                fill(context, sx + 8, categoryY, sx + 10, categoryY + 24, ACCENT);
            } else if (hover) {
                rounded(context, sx + 8, categoryY, SIDEBAR_WIDTH - 16, 24, 0xFF1E1830);
            }

            int textColor = selected ? ACCENT : (hover ? TEXT_PRIMARY : TEXT_SECONDARY);
            fontRenderer.drawText(context, category.getIcon(), sx + 16, categoryY + 9, textColor);
            if (selected) {
                fontRenderer.drawMediumText(context, category.getLabel(), sx + 28, categoryY + 9, textColor);
            } else {
                fontRenderer.drawText(context, category.getLabel(), sx + 28, categoryY + 9, textColor);
            }
            categoryY += 30;
        }
    }

    private void drawSeparators(DrawContext context) {
        int sx = windowX + SIDEBAR_WIDTH;
        fill(context, sx, windowY + HEADER_HEIGHT, sx + 1, windowY + BASE_HEIGHT, SEPARATOR);
    }

    private void drawModuleList(DrawContext context, int mouseX, int mouseY) {
        int contentX = windowX + SIDEBAR_WIDTH + 1;
        int contentW = BASE_WIDTH - SIDEBAR_WIDTH - 1;
        int contentY = windowY + HEADER_HEIGHT;
        int contentH = BASE_HEIGHT - HEADER_HEIGHT;

        enableScissor(context, contentX, contentY, contentW, contentH);

        int y = contentY + 8 + moduleScroll;
        for (GoatModule module : modules) {
            if (module.getCategory() != selectedCategory) continue;

            if (y > contentY - 46 && y < contentY + contentH) {
                int mx = contentX + 8;
                int mw = contentW - 16;
                boolean hover = isInside(mouseX, mouseY, mx, y, mw, 40);

                rounded(context, mx, y, mw, 40, hover ? 0xFF1A1526 : MODULE_BG);
                drawBorder(context, mx, y, mw, 40, MODULE_BORDER);

                if (module.isEnabled()) {
                    fontRenderer.drawMediumText(context, module.getName(), mx + 10, y + 12, TEXT_PRIMARY);
                } else {
                    fontRenderer.drawText(context, module.getName(), mx + 10, y + 12, TEXT_SECONDARY);
                }

                String keyName = getModuleKeyName(module);
                int knX = mx + mw - 52 - fontRenderer.getWidth(keyName);
                rounded(context, knX - 4, y + 8, fontRenderer.getWidth(keyName) + 8, 14, KEYBIND_BG);
                boolean isListening = listeningModuleKeybind && listeningModule == module;
                fontRenderer.drawText(context, isListening ? "..." : keyName, knX, y + 12, isListening ? KEYBIND_LISTENING : TEXT_SECONDARY);

                drawSwitch(context, mx + mw - 38, y + 14, module.isEnabled());

                if (!module.getValues().isEmpty()) {
                    fontRenderer.drawText(context, ">", mx + mw - 14, y + 12, TEXT_SECONDARY);
                }
            }
            y += 46;
        }

        disableScissor(context);
    }

    private void drawValueList(DrawContext context, int mouseX, int mouseY) {
        int contentX = windowX + SIDEBAR_WIDTH + 1;
        int contentW = BASE_WIDTH - SIDEBAR_WIDTH - 1;
        int contentY = windowY + HEADER_HEIGHT;
        int contentH = BASE_HEIGHT - HEADER_HEIGHT;

        fontRenderer.drawMediumText(context, "< " + selectedModule.getName(), contentX + 12, contentY + 12, TEXT_PRIMARY);
        fill(context, contentX + 8, contentY + 24, contentX + contentW - 8, contentY + 25, SEPARATOR);

        enableScissor(context, contentX, contentY + 26, contentW, contentH - 26);

        int y = contentY + 32 + valueScroll;

        String keyName = getModuleKeyName(selectedModule);
        boolean isListeningMod = listeningModuleKeybind && listeningModule == selectedModule;
        fontRenderer.drawText(context, "Keybind", contentX + 20, y + 4, TEXT_PRIMARY);
        int kbX = contentX + contentW - 90;
        rounded(context, kbX, y, 70, 16, isListeningMod ? KEYBIND_LISTENING : KEYBIND_BG);
        fontRenderer.drawText(context, isListeningMod ? "Press a key..." : keyName, kbX + 6, y + 4, isListeningMod ? 0xFFFFFFFF : TEXT_SECONDARY);
        y += 24;
        fill(context, contentX + 14, y - 4, contentX + contentW - 14, y - 3, SEPARATOR);

        for (ModuleValue value : selectedModule.getValues()) {
            if (y > contentY - 30 && y < contentY + contentH + 30) {
                fontRenderer.drawText(context, value.getName(), contentX + 20, y + 4, TEXT_PRIMARY);

                if (value instanceof BooleanValue booleanValue) {
                    drawSwitch(context, contentX + contentW - 48, y + 2, booleanValue.getValue());
                } else if (value instanceof NumberValue numberValue) {
                    drawSlider(context, numberValue, contentX + contentW - 110, y);
                } else if (value instanceof ModeValue modeValue) {
                    drawModeSelector(context, modeValue, contentX + contentW - 100, y);
                    if (expandedMode == modeValue) {
                        y += (modeValue.getModes().size() - 1) * 16;
                    }
                } else if (value instanceof KeybindValue keybindValue) {
                    drawKeybindButton(context, keybindValue, contentX + contentW - 90, y);
                }

                fill(context, contentX + 14, y + 20, contentX + contentW - 14, y + 21, SEPARATOR);
            }
            y += 22;
        }

        disableScissor(context);
    }

    private void drawSwitch(DrawContext context, int x, int y, boolean enabled) {
        rounded(context, x, y, 26, 12, enabled ? SWITCH_ON_TRACK : SWITCH_OFF_TRACK);
        int knobX = enabled ? x + 16 : x + 2;
        fill(context, knobX, y + 2, knobX + 8, y + 10, enabled ? SWITCH_ON_THUMB : SWITCH_OFF_THUMB);
    }

    private void drawSlider(DrawContext context, NumberValue value, int x, int y) {
        fill(context, x, y + 6, x + 80, y + 10, SLIDER_TRACK);
        double pct = (value.getValue() - value.getMin()) / (value.getMax() - value.getMin());
        int fill = (int) Math.round(pct * 74.0);
        fill(context, x, y + 6, x + 6 + fill, y + 10, ACCENT);
        fill(context, x + fill, y + 3, x + fill + 6, y + 13, SLIDER_KNOB);

        String label = value.getValue() == Math.rint(value.getValue())
            ? Integer.toString((int) value.getValue())
            : String.format(java.util.Locale.ROOT, "%.1f", value.getValue());
        fontRenderer.drawText(context, label, x + 84, y + 4, TEXT_SECONDARY);
    }

    private void drawModeSelector(DrawContext context, ModeValue value, int x, int y) {
        int h = expandedMode == value ? 16 + (value.getModes().size() - 1) * 16 : 16;
        rounded(context, x, y, 80, h, MODE_BG);
        drawBorder(context, x, y, 80, h, CATEGORY_OUTLINE);
        fontRenderer.drawText(context, value.getValue(), x + 8, y + 4, TEXT_PRIMARY);
        fontRenderer.drawText(context, expandedMode == value ? "^" : "v", x + 68, y + 4, TEXT_SECONDARY);

        if (expandedMode == value) {
            int optionY = y + 16;
            for (String mode : value.getModes()) {
                if (!mode.equals(value.getValue())) {
                    fontRenderer.drawText(context, mode, x + 8, optionY + 4, TEXT_SECONDARY);
                    optionY += 16;
                }
            }
        }
    }

    private void drawKeybindButton(DrawContext context, KeybindValue value, int x, int y) {
        boolean isListening = listeningKeybind == value;
        rounded(context, x, y, 70, 16, isListening ? KEYBIND_LISTENING : KEYBIND_BG);
        drawBorder(context, x, y, 70, 16, isListening ? ACCENT : CATEGORY_OUTLINE);
        String display = isListening ? "Press a key..." : value.getKeyName();
        fontRenderer.drawText(context, display, x + 6, y + 4, isListening ? 0xFFFFFFFF : TEXT_PRIMARY);
    }

    private boolean clickModule(int mouseX, int mouseY, int button) {
        int contentX = windowX + SIDEBAR_WIDTH + 1;
        int contentW = BASE_WIDTH - SIDEBAR_WIDTH - 1;
        int contentY = windowY + HEADER_HEIGHT;

        int y = contentY + 8 + moduleScroll;
        for (GoatModule module : modules) {
            if (module.getCategory() != selectedCategory) continue;

            int mx = contentX + 8;
            int mw = contentW - 16;

            if (isInside(mouseX, mouseY, mx, y, mw, 40)) {
                String keyName = getModuleKeyName(module);
                int knX = mx + mw - 52 - fontRenderer.getWidth(keyName);
                if (isInside(mouseX, mouseY, knX - 4, y + 8, fontRenderer.getWidth(keyName) + 8, 14)) {
                    listeningModuleKeybind = true;
                    listeningModule = module;
                    return true;
                }

                if (button == 0 && isInside(mouseX, mouseY, mx + mw - 38, y + 14, 26, 12) && module.canToggle()) {
                    module.toggle();
                    GoatConfigManager.save();
                    return true;
                }

                if (button == 0 && !module.getValues().isEmpty()) {
                    selectedModule = module;
                    valueScroll = 0;
                    expandedMode = null;
                    return true;
                }

                if (button == 0 && module.canToggle()) {
                    module.toggle();
                    GoatConfigManager.save();
                    return true;
                }
                return true;
            }
            y += 46;
        }
        return false;
    }

    private boolean clickValue(int mouseX, int mouseY, int button) {
        int contentX = windowX + SIDEBAR_WIDTH + 1;
        int contentW = BASE_WIDTH - SIDEBAR_WIDTH - 1;
        int contentY = windowY + HEADER_HEIGHT;

        if (button == 0 && isInside(mouseX, mouseY, contentX + 8, contentY + 6, 60, 16)) {
            if (selectedCategory != ModuleCategory.SETTINGS) {
                selectedModule = null;
                expandedMode = null;
            }
            return true;
        }

        if (button == 1) {
            if (selectedCategory != ModuleCategory.SETTINGS) {
                selectedModule = null;
                expandedMode = null;
            }
            return true;
        }

        int y = contentY + 32 + valueScroll;

        int kbX = contentX + contentW - 90;
        if (isInside(mouseX, mouseY, kbX, y, 70, 16)) {
            listeningModuleKeybind = true;
            listeningModule = selectedModule;
            return true;
        }
        y += 24;

        for (ModuleValue value : selectedModule.getValues()) {
            if (value instanceof BooleanValue booleanValue && isInside(mouseX, mouseY, contentX + contentW - 48, y + 2, 26, 12)) {
                booleanValue.toggle();
                GoatConfigManager.save();
                return true;
            }
            if (value instanceof NumberValue numberValue && isInside(mouseX, mouseY, contentX + contentW - 110, y, 90, 16)) {
                draggingSlider = numberValue;
                sliderValueBeforeDrag = numberValue.getValue();
                updateNumberValue(numberValue, mouseX);
                return true;
            }
            if (value instanceof ModeValue modeValue) {
                int modeX = contentX + contentW - 100;
                if (isInside(mouseX, mouseY, modeX, y, 80, 16)) {
                    expandedMode = expandedMode == modeValue ? null : modeValue;
                    return true;
                }
                if (expandedMode == modeValue) {
                    int optionY = y + 16;
                    for (String mode : modeValue.getModes()) {
                        if (!mode.equals(modeValue.getValue()) && isInside(mouseX, mouseY, modeX, optionY, 80, 16)) {
                            modeValue.setValue(mode);
                            expandedMode = null;
                            GoatConfigManager.save();
                            return true;
                        }
                        optionY += mode.equals(modeValue.getValue()) ? 0 : 16;
                    }
                    y += (modeValue.getModes().size() - 1) * 16;
                }
            }
            if (value instanceof KeybindValue keybindValue && isInside(mouseX, mouseY, contentX + contentW - 90, y, 70, 16)) {
                listeningKeybind = keybindValue;
                return true;
            }
            y += 22;
        }
        return true;
    }

    private void updateNumberValue(NumberValue value, int mouseX) {
        int sliderX = windowX + BASE_WIDTH - 110;
        double pct = clamp((mouseX - sliderX) / 80.0, 0.0, 1.0);
        double raw = value.getMin() + (value.getMax() - value.getMin()) * pct;
        value.setValue(Math.round(raw * 10.0) / 10.0);
    }

    private String getModuleKeyName(GoatModule module) {
        int key = module.getKeyBind();
        if (key <= 0) return "None";
        String name = GLFW.glfwGetKeyName(key, 0);
        if (name != null) return name.toUpperCase();
        return "KEY " + key;
    }

    private int visibleModuleCount() {
        int count = 0;
        for (GoatModule module : modules) {
            if (module.getCategory() == selectedCategory) count++;
        }
        return count;
    }

    private int contentHeight() {
        return BASE_HEIGHT - HEADER_HEIGHT;
    }

    private int computeValueListHeight() {
        int h = 56;
        for (ModuleValue value : selectedModule.getValues()) {
            h += value instanceof ModeValue modeValue && expandedMode == modeValue
                ? 22 + (modeValue.getModes().size() - 1) * 16
                : 22;
        }
        return h;
    }

    private void rounded(DrawContext context, int x, int y, int w, int h, int color) {
        renderer.rounded(x, y, w, h, color);
    }

    private void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        renderer.border(x, y, w, h, color);
    }

    private void fill(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        renderer.fill(x1, y1, x2, y2, color);
    }

    private void enableScissor(DrawContext context, int x, int y, int w, int h) {
        renderer.enableScissor(x, y, w, h);
    }

    private void disableScissor(DrawContext context) {
        renderer.disableScissor();
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
