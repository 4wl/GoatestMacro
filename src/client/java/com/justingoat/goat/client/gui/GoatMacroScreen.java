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
    private static final int TEXT_PRIMARY = 0xFFF7F7FA;
    private static final int TEXT_SECONDARY = 0xFFB4B4C6;
    private static final int TEXT_MUTED = 0xFF6E6E80;
    private static final int ACCENT = 0xFF91E8FF;
    private static final int ACCENT_PURPLE = 0xFF9B7CFF;
    private static final int DANGER = 0xFFFF5A76;
    private static final int DARK_PANEL = 0xD5101014;
    private static final int DARK_BODY = 0x7A08080B;
    private static final int HOVER = 0x18FFFFFF;
    private static final int SELECTED = 0x26FFFFFF;
    private static final int LINE = 0x26FFFFFF;

    private static final int LENS_W = 500;
    private static final int LENS_H = 330;
    private static final int LENS_R = 58;
    private static final int SIDE_W = 230;
    private static final int SIDE_HEADER_H = 24;
    private static final int ROW_H = 23;
    private static final int PANEL_GAP = 10;
    private static final int SLIDER_W = 126;

    private static ModuleCategory selectedCategory = ModuleCategory.RENDER;
    private static GoatModule selectedModule;
    private static int moduleScroll;
    private static int valueScroll;
    private static boolean categoryExpanded = true;

    private final List<GoatModule> modules = new ArrayList<>();
    private CustomFontRenderer fontRenderer;
    private GoatGuiRenderer r;
    private int cursorX;
    private int cursorY;
    private NumberValue draggingSlider;
    private Double sliderValueBeforeDrag;
    private ModeValue expandedMode;
    private KeybindValue listeningKeybind;
    private boolean listeningModuleKeybind;

    public GoatMacroScreen() {
        super(Text.literal("Goat"));
        modules.addAll(ModuleManager.getModules());
    }

    @Override
    protected void init() {
        if (fontRenderer == null) {
            fontRenderer = new CustomFontRenderer();
        }
        ensureSelectedModule();
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x76000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        try {
            if (draggingSlider != null) {
                updateSliderDrag(mouseX);
            }
            cursorX = mouseX;
            cursorY = mouseY;
            r = new GoatGuiRenderer(context, fontRenderer);
            ensureSelectedModule();

            drawLens();
            drawCategoryPanels();

            super.render(context, mouseX, mouseY, delta);
        } catch (Throwable e) {
            System.err.println("[Goat] GUI render error: " + e);
            e.printStackTrace();
        }
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        int keyCode = keyInput.key();
        if (listeningKeybind != null) {
            listeningKeybind.setKeyCode(keyCode == GLFW.GLFW_KEY_ESCAPE ? -1 : keyCode);
            listeningKeybind = null;
            GoatConfigManager.save();
            return true;
        }
        if (listeningModuleKeybind && selectedModule != null) {
            selectedModule.setKeyBind(keyCode == GLFW.GLFW_KEY_ESCAPE ? -1 : keyCode);
            listeningModuleKeybind = false;
            GoatConfigManager.save();
            return true;
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int mx = (int) click.x();
        int my = (int) click.y();
        int btn = click.button();

        if (listeningKeybind != null || listeningModuleKeybind) {
            listeningKeybind = null;
            listeningModuleKeybind = false;
            return true;
        }

        if (clickCategoryPanels(mx, my, btn)) {
            return true;
        }
        if (clickLens(mx, my, btn)) {
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (draggingSlider != null && sliderValueBeforeDrag != null
                && draggingSlider.getValue() != sliderValueBeforeDrag) {
            GoatConfigManager.save();
        }
        draggingSlider = null;
        sliderValueBeforeDrag = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        int delta = vAmount > 0 ? 24 : -24;
        if (categoryExpanded && isInside(mouseX, mouseY, sideX(), selectedPanelBodyY(), SIDE_W, selectedPanelBodyH())) {
            moduleScroll = clampI(moduleScroll + delta, moduleScrollMin(), 0);
        } else {
            valueScroll = clampI(valueScroll + delta, valueScrollMin(), 0);
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

    private void drawLens() {
        int x = lensX();
        int y = lensY();
        int w = lensW();
        int h = lensH();

        r.glassPanel(x, y, w, h, LENS_R, 0xB8121218);

        r.roundedRect(x + 18, y + 18, w - 36, 42, 18, 0x12000000);
        r.fill(x + 46, y + 60, x + w - 46, y + 61, LINE);

        String category = selectedModule == null ? selectedCategory.getLabel() : selectedModule.getCategory().getLabel();
        String title = selectedModule == null ? "Goat" : selectedModule.getName();
        r.text(category.toUpperCase(), x + 34, y + 29, ACCENT);
        r.boldText(title, x + 34, y + 43, TEXT_PRIMARY);

        if (selectedModule != null) {
            drawModuleToggle(x + w - 72, y + 34, selectedModule.isEnabled());
            int kbX = x + w - 172;
            r.roundedRect(kbX, y + 31, 76, 20, 8,
                    listeningModuleKeybind ? 0x704DDBFF : 0x14000000);
            r.roundedOutline(kbX, y + 31, 76, 20, 8, 0x30FFFFFF);
            r.text(listeningModuleKeybind ? "Press..." : getModuleKeyName(selectedModule),
                    kbX + 9, y + 37, listeningModuleKeybind ? TEXT_PRIMARY : TEXT_SECONDARY);
        }

        drawLensValues(x, y, w, h);
    }

    private void drawLensValues(int x, int y, int w, int h) {
        if (selectedModule == null) {
            r.text("Select a module from the category panels.", x + 34, y + 92, TEXT_SECONDARY);
            return;
        }

        int listX = x + 28;
        int listY = y + 76;
        int listW = w - 56;
        int listH = h - 104;

        r.enableScissor(listX, listY, listW, listH);
        int rowY = listY + valueScroll;

        if (selectedModule.getValues().isEmpty()) {
            drawValueShell(listX, rowY, listW, "No settings", false);
            r.text("This module only has an on/off state.", listX + 14, rowY + 28, TEXT_SECONDARY);
        } else {
            for (ModuleValue value : selectedModule.getValues()) {
                if (rowY > listY - 40 && rowY < listY + listH + 40) {
                    drawValueRow(value, listX, rowY, listW);
                }
                rowY += valueRowHeight(value);
            }
        }

        r.disableScissor();

        int hintY = y + h - 26;
        r.fill(x + 52, hintY - 9, x + w - 52, hintY - 8, 0x18FFFFFF);
        r.text("Left click toggles/selects. Scroll adjusts long lists.", x + 56, hintY, 0xAAC8C8D8);
    }

    private void drawValueRow(ModuleValue value, int x, int y, int w) {
        boolean hover = isInside(cursorX, cursorY, x, y, w, 24);
        drawValueShell(x, y, w, value.getName(), hover);

        if (value instanceof BooleanValue bv) {
            drawModuleToggle(x + w - 48, y + 6, bv.getValue());
        } else if (value instanceof NumberValue nv) {
            drawSlider(nv, x + w - 174, y + 7);
        } else if (value instanceof ModeValue mv) {
            drawModeSelector(mv, x + w - 130, y + 4);
        } else if (value instanceof KeybindValue kv) {
            drawKeybindButton(kv, x + w - 112, y + 4);
        }
    }

    private void drawValueShell(int x, int y, int w, String label, boolean hover) {
        r.roundedRect(x, y, w, 24, 9, hover ? 0x18000000 : 0x0C000000);
        r.roundedOutline(x, y, w, 24, 9, hover ? 0x34FFFFFF : 0x16FFFFFF);
        r.text(label, x + 13, y + 8, TEXT_PRIMARY);
    }

    private void drawSlider(NumberValue value, int x, int y) {
        double pct = (value.getValue() - value.getMin()) / (value.getMax() - value.getMin());
        int fill = (int) Math.round(clampD(pct, 0.0, 1.0) * SLIDER_W);
        r.roundedRect(x, y + 5, SLIDER_W, 6, 3, 0x44000000);
        r.roundedRect(x, y + 5, Math.max(6, fill), 6, 3, 0xCCFFFFFF);
        r.roundedRect(x + Math.max(0, fill - 4), y + 2, 8, 12, 4, ACCENT);

        String label = value.getValue() == Math.rint(value.getValue())
                ? Integer.toString((int) value.getValue())
                : String.format(java.util.Locale.ROOT, "%.1f", value.getValue());
        int tx = x - r.textWidth(label) - 8;
        r.text(label, tx, y + 3, ACCENT_PURPLE);
    }

    private void drawModeSelector(ModeValue value, int x, int y) {
        boolean expanded = expandedMode == value;
        int options = expanded ? value.getModes().size() - 1 : 0;
        int h = 18 + options * 18;
        r.roundedRect(x, y, 112, h, 7, 0x30000000);
        r.roundedOutline(x, y, 112, h, 7, 0x22FFFFFF);
        r.text(value.getValue(), x + 8, y + 5, TEXT_PRIMARY);
        r.text(expanded ? "-" : "+", x + 96, y + 5, TEXT_SECONDARY);
        if (expanded) {
            r.fill(x + 6, y + 18, x + 106, y + 19, LINE);
            int oy = y + 21;
            for (String mode : value.getModes()) {
                if (!mode.equals(value.getValue())) {
                    boolean hover = isInside(cursorX, cursorY, x + 2, oy - 2, 108, 18);
                    if (hover) {
                        r.roundedRect(x + 3, oy - 2, 106, 17, 5, HOVER);
                    }
                    r.text(mode, x + 8, oy + 2, hover ? TEXT_PRIMARY : TEXT_SECONDARY);
                    oy += 18;
                }
            }
        }
    }

    private void drawKeybindButton(KeybindValue value, int x, int y) {
        boolean listening = listeningKeybind == value;
        r.roundedRect(x, y, 96, 18, 7, listening ? 0x704DDBFF : 0x30000000);
        r.roundedOutline(x, y, 96, 18, 7, 0x22FFFFFF);
        r.text(listening ? "Press..." : value.getKeyName(), x + 8, y + 5,
                listening ? TEXT_PRIMARY : TEXT_SECONDARY);
    }

    private void drawCategoryPanels() {
        int x = sideX();
        int y = sideStartY();
        for (ModuleCategory category : ModuleCategory.values()) {
            int h = category == selectedCategory && categoryExpanded ? selectedPanelH() : SIDE_HEADER_H;
            drawCategoryPanel(category, x, y, h);
            y += h + PANEL_GAP;
        }
    }

    private void drawCategoryPanel(ModuleCategory category, int x, int y, int h) {
        boolean selected = category == selectedCategory;
        boolean headerHover = isInside(cursorX, cursorY, x, y, SIDE_W, SIDE_HEADER_H);
        boolean expanded = selected && categoryExpanded;

        r.roundedRect(x, y, SIDE_W, SIDE_HEADER_H, 2, headerHover ? 0xEA17171D : DARK_PANEL);
        r.fill(x, y + SIDE_HEADER_H - 1, x + SIDE_W, y + SIDE_HEADER_H, expanded ? 0x70FFFFFF : LINE);
        r.boldText(category.getLabel(), x + 10, y + 7, TEXT_PRIMARY);
        r.boldText(expanded ? "-" : "+", x + SIDE_W - 20, y + 7, TEXT_PRIMARY);

        if (!expanded) {
            return;
        }

        int bodyY = y + SIDE_HEADER_H;
        int bodyH = h - SIDE_HEADER_H;
        r.roundedRect(x, bodyY, SIDE_W, bodyH, 2, DARK_BODY);
        r.enableScissor(x, bodyY + 4, SIDE_W, bodyH - 8);

        int rowY = bodyY + 5 + moduleScroll;
        for (GoatModule module : modulesFor(category)) {
            if (rowY > bodyY - ROW_H && rowY < bodyY + bodyH) {
                drawModuleRow(module, x + 7, rowY, SIDE_W - 14);
            }
            rowY += ROW_H;
        }

        r.disableScissor();
    }

    private void drawModuleRow(GoatModule module, int x, int y, int w) {
        boolean selected = module == selectedModule;
        boolean hover = isInside(cursorX, cursorY, x, y, w, ROW_H - 2);
        int bg = selected ? SELECTED : hover ? HOVER : 0x00000000;
        if (bg != 0) {
            r.roundedRect(x, y, w, ROW_H - 2, 4, bg);
        }
        if (module.isEnabled()) {
            r.fill(x + 2, y + 5, x + 4, y + ROW_H - 7, ACCENT);
        }

        int color = module.isEnabled() ? TEXT_PRIMARY : hover ? TEXT_SECONDARY : TEXT_MUTED;
        r.text(module.getName(), x + 10, y + 7, color);

        if (module.canToggle()) {
            int tx = x + w - 31;
            r.roundedRect(tx, y + 6, 22, 10, 5, module.isEnabled() ? 0xDDF7F7FA : 0x45FFFFFF);
            r.roundedRect(tx + (module.isEnabled() ? 12 : 2), y + 8, 6, 6, 3,
                    module.isEnabled() ? ACCENT_PURPLE : TEXT_MUTED);
        }
    }

    private boolean clickCategoryPanels(int mx, int my, int btn) {
        int x = sideX();
        int y = sideStartY();
        for (ModuleCategory category : ModuleCategory.values()) {
            int h = category == selectedCategory && categoryExpanded ? selectedPanelH() : SIDE_HEADER_H;
            if (isInside(mx, my, x, y, SIDE_W, SIDE_HEADER_H)) {
                if (btn == 1 && category == selectedCategory) {
                    categoryExpanded = !categoryExpanded;
                    moduleScroll = 0;
                } else {
                    selectedCategory = category;
                    selectedModule = firstModule(category);
                    categoryExpanded = true;
                    moduleScroll = 0;
                    valueScroll = 0;
                    expandedMode = null;
                }
                return true;
            }

            if (category == selectedCategory && categoryExpanded
                    && isInside(mx, my, x, y + SIDE_HEADER_H, SIDE_W, h - SIDE_HEADER_H)) {
                int rowY = y + SIDE_HEADER_H + 5 + moduleScroll;
                for (GoatModule module : modulesFor(category)) {
                    if (isInside(mx, my, x + 7, rowY, SIDE_W - 14, ROW_H - 2)) {
                        if (btn == 0 && module.canToggle() && mx >= x + SIDE_W - 45) {
                            module.toggle();
                            GoatConfigManager.save();
                        } else {
                            selectedModule = module;
                            valueScroll = 0;
                            expandedMode = null;
                        }
                        return true;
                    }
                    rowY += ROW_H;
                }
                return true;
            }
            y += h + PANEL_GAP;
        }
        return false;
    }

    private boolean clickLens(int mx, int my, int btn) {
        if (selectedModule == null) {
            return false;
        }

        int x = lensX();
        int y = lensY();
        int w = lensW();
        int h = lensH();
        if (!isInside(mx, my, x, y, w, h)) {
            return false;
        }

        if (btn == 0 && selectedModule.canToggle() && isInside(mx, my, x + w - 72, y + 34, 34, 16)) {
            selectedModule.toggle();
            GoatConfigManager.save();
            return true;
        }
        if (btn == 0 && isInside(mx, my, x + w - 172, y + 31, 76, 20)) {
            listeningModuleKeybind = true;
            return true;
        }

        int listX = x + 28;
        int listY = y + 76;
        int listW = w - 56;
        int rowY = listY + valueScroll;

        for (ModuleValue value : selectedModule.getValues()) {
            int rowH = valueRowHeight(value);
            if (isInside(mx, my, listX, rowY, listW, Math.min(rowH, 24))) {
                return clickValue(value, mx, my, listX, rowY, listW);
            }
            if (value instanceof ModeValue mv && expandedMode == mv) {
                int modeX = listX + listW - 130;
                int oy = rowY + 4 + 21;
                for (String mode : mv.getModes()) {
                    if (!mode.equals(mv.getValue())) {
                        if (isInside(mx, my, modeX + 2, oy - 2, 108, 18)) {
                            mv.setValue(mode);
                            expandedMode = null;
                            GoatConfigManager.save();
                            return true;
                        }
                        oy += 18;
                    }
                }
            }
            rowY += rowH;
        }
        return true;
    }

    private boolean clickValue(ModuleValue value, int mx, int my, int x, int y, int w) {
        if (value instanceof BooleanValue bv) {
            if (isInside(mx, my, x + w - 48, y + 6, 34, 16)) {
                bv.toggle();
                GoatConfigManager.save();
            }
            return true;
        }
        if (value instanceof NumberValue nv) {
            int sliderX = x + w - 174;
            if (isInside(mx, my, sliderX - 38, y + 2, SLIDER_W + 44, 18)) {
                draggingSlider = nv;
                sliderValueBeforeDrag = nv.getValue();
                updateSliderDrag(mx);
            }
            return true;
        }
        if (value instanceof ModeValue mv) {
            int modeX = x + w - 130;
            if (isInside(mx, my, modeX, y + 4, 112, 18)) {
                expandedMode = expandedMode == mv ? null : mv;
            }
            return true;
        }
        if (value instanceof KeybindValue kv) {
            if (isInside(mx, my, x + w - 112, y + 4, 96, 18)) {
                listeningKeybind = kv;
            }
            return true;
        }
        return true;
    }

    private void drawModuleToggle(int x, int y, boolean enabled) {
        r.roundedRect(x, y, 34, 16, 8, enabled ? 0xEAF7F7FA : 0x45FFFFFF);
        r.roundedRect(x + (enabled ? 18 : 3), y + 3, 10, 10, 5,
                enabled ? ACCENT_PURPLE : TEXT_MUTED);
    }

    private void updateSliderDrag(int mouseX) {
        if (selectedModule == null || draggingSlider == null) {
            return;
        }
        int x = lensX() + 28;
        int w = lensW() - 56;
        int sliderX = x + w - 174;
        double pct = clampD((mouseX - sliderX) / (double) SLIDER_W, 0.0, 1.0);
        double raw = draggingSlider.getMin()
                + (draggingSlider.getMax() - draggingSlider.getMin()) * pct;
        draggingSlider.setValue(Math.round(raw * 100.0) / 100.0);
    }

    private int valueRowHeight(ModuleValue value) {
        if (value instanceof ModeValue mv && expandedMode == mv) {
            return 30 + Math.max(0, mv.getModes().size() - 1) * 18;
        }
        return 30;
    }

    private int selectedPanelH() {
        int collapsed = (ModuleCategory.values().length - 1) * (SIDE_HEADER_H + PANEL_GAP);
        int max = Math.max(120, height - sideStartY() - 28 - collapsed);
        int wanted = SIDE_HEADER_H + 12 + modulesFor(selectedCategory).size() * ROW_H;
        return clampI(wanted, 118, max);
    }

    private int selectedPanelBodyY() {
        int y = sideStartY();
        for (ModuleCategory category : ModuleCategory.values()) {
            if (category == selectedCategory) {
                return y + SIDE_HEADER_H;
            }
            y += SIDE_HEADER_H + PANEL_GAP;
        }
        return sideStartY() + SIDE_HEADER_H;
    }

    private int selectedPanelBodyH() {
        return selectedPanelH() - SIDE_HEADER_H;
    }

    private int moduleScrollMin() {
        int content = modulesFor(selectedCategory).size() * ROW_H + 10;
        return -Math.max(0, content - selectedPanelBodyH());
    }

    private int valueScrollMin() {
        if (selectedModule == null) {
            return 0;
        }
        int content = 0;
        for (ModuleValue value : selectedModule.getValues()) {
            content += valueRowHeight(value);
        }
        int visible = lensH() - 104;
        return -Math.max(0, content - visible);
    }

    private void ensureSelectedModule() {
        if (selectedModule != null && selectedModule.getCategory() == selectedCategory) {
            return;
        }
        selectedModule = firstModule(selectedCategory);
    }

    private GoatModule firstModule(ModuleCategory category) {
        for (GoatModule module : modules) {
            if (module.getCategory() == category) {
                return module;
            }
        }
        return null;
    }

    private List<GoatModule> modulesFor(ModuleCategory category) {
        List<GoatModule> result = new ArrayList<>();
        for (GoatModule module : modules) {
            if (module.getCategory() == category) {
                result.add(module);
            }
        }
        return result;
    }

    private String getModuleKeyName(GoatModule module) {
        int key = module.getKeyBind();
        if (key <= 0) return "NONE";
        String name = GLFW.glfwGetKeyName(key, 0);
        return name != null ? name.toUpperCase() : "KEY " + key;
    }

    private int sideX() {
        return Math.max(12, Math.min(42, width - SIDE_W - 18));
    }

    private int sideStartY() {
        return Math.max(22, height / 2 - 220);
    }

    private int lensW() {
        int left = sideX() + SIDE_W + 34;
        int available = width - left - 18;
        return clampI(Math.min(LENS_W, available), 310, LENS_W);
    }

    private int lensH() {
        return Math.min(LENS_H, Math.max(260, height - 70));
    }

    private int lensX() {
        int left = sideX() + SIDE_W + 34;
        int available = width - left - 18;
        int centered = left + Math.max(0, (available - lensW()) / 2);
        return clampI(centered, left, Math.max(left, width - lensW() - 18));
    }

    private int lensY() {
        return Math.max(26, (height - lensH()) / 2);
    }

    private boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private int clampI(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampD(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
