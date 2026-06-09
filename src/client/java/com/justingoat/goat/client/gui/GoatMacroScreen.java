package com.justingoat.goat.client.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    private static Category selectedCategory = Category.COMBAT;
    private static Module selectedModule;
    private static int moduleScroll;
    private static int valueScroll;

    private final List<Module> modules = new ArrayList<>();
    private boolean dragging;
    private int dragX;
    private int dragY;
    private boolean resizing;
    private int resizeX;
    private int resizeY;
    private Value draggingSlider;
    private ModeValue expandedMode;

    public GoatMacroScreen() {
        super(Text.literal("Goat"));
        seedModules();
    }

    @Override
    protected void init() {
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
        for (Category category : Category.values()) {
            if (isInside(mouseX, mouseY, windowX, categoryY - 5, leftWidth() - 10, 22)) {
                selectedCategory = category;
                selectedModule = category == Category.SETTINGS ? findModule("ClientSettings") : null;
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
        draggingSlider = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int delta = verticalAmount > 0 ? 16 : -16;
        if (selectedModule == null) {
            moduleScroll = clamp(moduleScroll + delta, -Math.max(0, visibleModuleCount() * 35 - (windowHeight - 40)), 0);
        } else {
            valueScroll = clamp(valueScroll + delta, -Math.max(0, selectedModule.values.size() * 28 - (windowHeight - 64)), 0);
        }
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void seedModules() {
        add("Sprint", Category.MOVEMENT, true, key("Mode", "Legit", "Legit", "Omni"), num("Boost", 1.0, 0.1, 2.0));
        add("ClickGui", Category.RENDER, true, bool("PauseGame", false), key("Theme", "Light", "Light", "Dark"));
//        add("OldAnimation", Category.RENDER, false, bool("BlockHit", true), num("Scale", 1.0, 0.5, 1.5));
//        add("MoreParticles", Category.RENDER, true, num("Amount", 2.0, 1.0, 8.0));
//        add("CustomFov", Category.RENDER, false, num("FOV", 90.0, 60.0, 120.0));
//        add("PotionDisplay", Category.RENDER, true);
//        add("AutoGG", Category.MISC, false, key("Message", "Default", "Default", "Short", "Toxic"));
//        add("KeyStrokes", Category.RENDER, true, bool("ShowCPS", true), bool("ShowSpacebar", true));
//        add("FullBright", Category.RENDER, false, num("Gamma", 10.0, 1.0, 15.0));
//        add("NameTags", Category.RENDER, true, bool("Health", true), bool("Distance", true));
//        add("Blink", Category.WORLD, false, num("Pulse", 500.0, 100.0, 2000.0));
//        add("Scoreboard", Category.RENDER, true);
//        add("TimeChanger", Category.RENDER, false, num("Time", 6000.0, 0.0, 24000.0));
//        add("DragonWings", Category.RENDER, false, key("Style", "Classic", "Classic", "Crystal"));
//        add("BlockOverlay", Category.RENDER, true, bool("Fill", true), bool("Outlined", true));
        add("Notification", Category.RENDER, true, key("Mode", "Goat", "Goat", "Simple"));
//        add("ItemPhysics", Category.WORLD, false);
        add("Crosshair", Category.RENDER, true, num("Size", 4.0, 1.0, 12.0));
//        add("TNTTimer", Category.RENDER, true);
//        add("MotionBlur", Category.RENDER, false, num("Strength", 0.6, 0.1, 1.0));
//        add("Coordinates", Category.RENDER, true);
//        add("SnapLook", Category.MOVEMENT, false);
//        add("ArmorStatus", Category.RENDER, true);
        add("ClientSettings", Category.SETTINGS, true, bool("BetterButton", true), bool("ScreenAnimation", true), key("Theme", "Light", "Light", "Dark"));
        add("KillAura", Category.COMBAT, false, num("Range", 3.2, 3.0, 6.0), key("Priority", "Health", "Health", "Distance"));
//        add("AimAssistant", Category.COMBAT, false, num("Speed", 30.0, 1.0, 100.0));
//        add("Reach", Category.COMBAT, false, num("Range", 3.1, 3.0, 4.5));
        add("Velocity", Category.COMBAT, false, num("Horizontal", 80.0, 0.0, 100.0), num("Vertical", 100.0, 0.0, 100.0));
//        add("Fly", Category.MOVEMENT, false, key("Mode", "Vanilla", "Vanilla", "Motion"), num("Speed", 1.0, 0.1, 5.0));
        add("KeepSprint", Category.MOVEMENT, true);
        add("NoSlow", Category.MOVEMENT, false);
        add("Speed", Category.MOVEMENT, false, key("Mode", "Hypixel", "Hypixel", "Vanilla"), num("Speed", 1.2, 0.1, 3.0));
//        add("ArrowESP", Category.RENDER, true);
        add("AntiBot", Category.COMBAT, true);
//        add("Chams", Category.RENDER, false);
//        add("ESP", Category.RENDER, false, key("Mode", "Box", "Box", "Outline", "Glow"));
//        add("Xray", Category.RENDER, false);
//        add("Scaffold", Category.MOVEMENT, false);
//        add("SpeedMine", Category.WORLD, false, num("BreakSpeed", 1.4, 1.0, 3.0));
//        add("FastPlace", Category.WORLD, false, num("Delay", 1.0, 0.0, 4.0));
//        add("Eagle", Category.MOVEMENT, false);
//        add("Tracers", Category.RENDER, false);
//        add("Nuker", Category.WORLD, false);
//        add("InventoryManager", Category.WORLD, false);
//        add("ChestStealer", Category.WORLD, false);
//        add("AutoArmor", Category.WORLD, false);
//        add("AntiFall", Category.WORLD, true);
//        add("Timer", Category.WORLD, false, num("Speed", 1.0, 0.1, 5.0));
//        add("Disabler", Category.WORLD, false, key("Mode", "WatchDog", "WatchDog", "Basic"));
//        add("LagBackChecker", Category.MISC, true);
//        add("HUD", Category.RENDER, true, bool("ArrayList", true), bool("Watermark", true));
//        add("Teams", Category.MISC, true);
//        add("InvMove", Category.MOVEMENT, false);
//        add("TargetStrafe", Category.MOVEMENT, false, num("Radius", 2.0, 0.5, 5.0));
    }

    private void drawShell(DrawContext context) {
        rounded(context, windowX - 1, windowY - 1, windowWidth + 2, windowHeight + 2, OUTLINE);
        rounded(context, windowX, windowY, windowWidth, windowHeight, BG);
        rounded(context, windowX, windowY, leftWidth(), windowHeight, LEFT);
        context.fill(windowX + leftWidth(), windowY, windowX + leftWidth() + 1, windowY + windowHeight, LINE);

        context.drawText(textRenderer, "Goat", windowX + 14, windowY + 27, THEME, false);
        context.drawText(textRenderer, "1.0", windowX + 55, windowY + 27, MUTED, false);
    }

    private void drawCategories(DrawContext context, int mouseX, int mouseY) {
        int y = windowY + 80;
        for (Category category : Category.values()) {
            boolean selected = category == selectedCategory;
            if (selected) {
                rounded(context, windowX, y - 4, leftWidth(), 20, THEME);
            }
            int color = selected ? 0xFFFFFFFF : MUTED;
            context.drawText(textRenderer, category.icon, windowX + 10, y, color, false);
            context.drawText(textRenderer, category.label, windowX + 28, y, color, false);
            y += 30;
        }
    }

    private void drawModuleList(DrawContext context, int mouseX, int mouseY) {
        int y = windowY + 10 + moduleScroll;
        for (Module module : modules) {
            if (module.category != selectedCategory) {
                continue;
            }
            if (y > windowY - 35 && y < windowY + windowHeight - 20) {
                boolean hover = isInside(mouseX, mouseY, windowX + leftWidth() + 8, y, windowWidth - leftWidth() - 16, 30);
                rounded(context, windowX + leftWidth() + 8, y, windowWidth - leftWidth() - 16, 30, hover ? 0xFFF9F9F9 : MODULE_BOX);
                context.drawText(textRenderer, module.name, windowX + leftWidth() + 14, y + 11, module.enabled ? TEXT : DISABLED, false);
                if (!module.values.isEmpty()) {
                    context.drawText(textRenderer, "...", windowX + windowWidth - 68, y + 11, MUTED, false);
                }
                if (module.canToggle) {
                    drawToggle(context, windowX + windowWidth - 50, y + 10, module.enabled);
                }
            }
            y += 35;
        }
    }

    private void drawValueList(DrawContext context, int mouseX, int mouseY) {
        context.drawText(textRenderer, selectedModule.name, windowX + leftWidth() + 25, windowY + 14, TEXT, false);
        context.fill(windowX + leftWidth() + 10, windowY + 26, windowX + windowWidth, windowY + 27, LINE);

        int y = windowY + 40 + valueScroll;
        for (Value value : selectedModule.values) {
            if (y > windowY - 35 && y < windowY + windowHeight - 20) {
                context.drawText(textRenderer, value.name, windowX + leftWidth() + 26, y, TEXT, false);
                if (value.kind == ValueKind.BOOLEAN) {
                    drawCheck(context, windowX + leftWidth() + 15, y, value.boolValue);
                } else if (value.kind == ValueKind.NUMBER) {
                    drawNumber(context, value, y);
                } else if (value instanceof ModeValue modeValue) {
                    drawMode(context, modeValue, y);
                    if (expandedMode == modeValue) {
                        y += (modeValue.modes.size() - 1) * 15;
                    }
                }
                context.fill(windowX + leftWidth() + 10, y + 14, windowX + windowWidth - 10, y + 15, LINE);
            }
            y += value instanceof ModeValue modeValue && expandedMode == modeValue ? 40 : 20;
        }
        context.drawText(textRenderer, "Right click to go back", windowX + leftWidth() + 25, windowY + windowHeight - 22, MUTED, false);
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
        for (Module module : modules) {
            if (module.category == selectedCategory) {
                if (isInside(mouseX, mouseY, windowX + leftWidth() + 8, y, windowWidth - leftWidth() - 16, 30)) {
                    if (button == 0 && module.canToggle) {
                        module.enabled = !module.enabled;
                    } else if (button == 1 && !module.values.isEmpty()) {
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
            selectedModule = selectedCategory == Category.SETTINGS ? selectedModule : null;
            expandedMode = null;
            return true;
        }

        int y = windowY + 40 + valueScroll;
        for (Value value : selectedModule.values) {
            if (value.kind == ValueKind.BOOLEAN && isInside(mouseX, mouseY, windowX + leftWidth() + 10, y - 2, windowWidth - leftWidth() - 40, 16)) {
                value.boolValue = !value.boolValue;
                return true;
            }
            if (value.kind == ValueKind.NUMBER && isInside(mouseX, mouseY, windowX + windowWidth - 110, y, 80, 12)) {
                draggingSlider = value;
                updateNumberValue(value, mouseX);
                return true;
            }
            if (value instanceof ModeValue modeValue && isInside(mouseX, mouseY, windowX + windowWidth - 110, y, 80, 15)) {
                expandedMode = expandedMode == modeValue ? null : modeValue;
                return true;
            }
            if (value instanceof ModeValue modeValue && expandedMode == modeValue) {
                int optionY = y + 15;
                for (String mode : modeValue.modes) {
                    if (!mode.equals(modeValue.mode) && isInside(mouseX, mouseY, windowX + windowWidth - 110, optionY, 80, 15)) {
                        modeValue.mode = mode;
                        expandedMode = null;
                        return true;
                    }
                    optionY += mode.equals(modeValue.mode) ? 0 : 15;
                }
                y += (modeValue.modes.size() - 1) * 15;
            }
            y += 20;
        }
        return true;
    }

    private void drawNumber(DrawContext context, Value value, int y) {
        int x = windowX + windowWidth - 110;
        context.fill(x, y + 1, x + 80, y + 9, OPTION_BG);
        double pct = (value.numberValue - value.min) / (value.max - value.min);
        int fill = (int) Math.round(pct * 70.0);
        context.fill(x, y + 1, x + 10 + fill, y + 9, THEME);
        String label = value.numberValue == Math.rint(value.numberValue)
            ? Integer.toString((int) value.numberValue)
            : String.format(java.util.Locale.ROOT, "%.1f", value.numberValue);
        context.drawText(textRenderer, label, x + 84 - textRenderer.getWidth(label), y, TEXT, false);
    }

    private void drawMode(DrawContext context, ModeValue value, int y) {
        int x = windowX + windowWidth - 110;
        int h = expandedMode == value ? 15 + (value.modes.size() - 1) * 15 : 15;
        rounded(context, x, y + 1, 80, h, OPTION_BG);
        context.drawText(textRenderer, value.mode, x + 10, y + 5, TEXT, false);
        if (expandedMode == value) {
            int optionY = y + 20;
            for (String mode : value.modes) {
                if (!mode.equals(value.mode)) {
                    context.drawText(textRenderer, mode, x + 10, optionY, MUTED, false);
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
            context.drawText(textRenderer, "x", x + 2, y, 0xFFFFFFFF, false);
        }
    }

    private void updateNumberValue(Value value, int mouseX) {
        double pct = clamp((mouseX - (windowX + windowWidth - 100)) / 70.0, 0.0, 1.0);
        double raw = value.min + (value.max - value.min) * pct;
        value.numberValue = Math.round(raw * 10.0) / 10.0;
    }

    private void rounded(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x + 2, y, x + w - 2, y + h, color);
        context.fill(x, y + 2, x + w, y + h - 2, color);
    }

    private void add(String name, Category category, boolean enabled, Value... values) {
        modules.add(new Module(name, category, enabled, true, Arrays.asList(values)));
    }

    private Value bool(String name, boolean value) {
        Value v = new Value(name, ValueKind.BOOLEAN);
        v.boolValue = value;
        return v;
    }

    private Value num(String name, double value, double min, double max) {
        Value v = new Value(name, ValueKind.NUMBER);
        v.numberValue = value;
        v.min = min;
        v.max = max;
        return v;
    }

    private ModeValue key(String name, String value, String... modes) {
        return new ModeValue(name, value, Arrays.asList(modes));
    }

    private Module findModule(String name) {
        for (Module module : modules) {
            if (module.name.equals(name)) {
                return module;
            }
        }
        return null;
    }

    private int visibleModuleCount() {
        int count = 0;
        for (Module module : modules) {
            if (module.category == selectedCategory) {
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

    private enum Category {
        COMBAT("Combat", "C"),
        MOVEMENT("Movement", "M"),
        RENDER("Render", "R"),
        WORLD("World", "W"),
        MISC("Misc", "*"),
        SETTINGS("Settings", "S");

        private final String label;
        private final String icon;

        Category(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private enum ValueKind {
        BOOLEAN,
        NUMBER,
        MODE
    }

    private static final class Module {
        private final String name;
        private final Category category;
        private final boolean canToggle;
        private final List<Value> values;
        private boolean enabled;

        private Module(String name, Category category, boolean enabled, boolean canToggle, List<Value> values) {
            this.name = name;
            this.category = category;
            this.enabled = enabled;
            this.canToggle = canToggle;
            this.values = values;
        }
    }

    private static class Value {
        private final String name;
        private final ValueKind kind;
        private boolean boolValue;
        private double numberValue;
        private double min;
        private double max;

        private Value(String name, ValueKind kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    private static final class ModeValue extends Value {
        private final List<String> modes;
        private String mode;

        private ModeValue(String name, String mode, List<String> modes) {
            super(name, ValueKind.MODE);
            this.mode = mode;
            this.modes = modes;
        }
    }
}
