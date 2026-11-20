package com.utilitysuite.ui;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.ui.tabs.CombatTab;
import com.utilitysuite.ui.tabs.JobsTab;
import com.utilitysuite.ui.tabs.ToolsTab;
import com.utilitysuite.ui.tabs.TravelTab;
import com.utilitysuite.ui.tabs.WorldTab;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The suite menu (Right Shift): five tabs — Jobs, World, Travel, Combat, Tools — built from vanilla widgets on a
 * scrollable {@link Panel}. Tab content is rebuilt when its structure changes (a job starts or ends, a preview
 * arrives, a list grows); text that changes in place (progress, "Now", rates) refreshes every few ticks.
 * Typed text, scroll positions and open drawers survive rebuilds and reopening the menu during the session.
 */
public final class SuiteMenuScreen extends Screen {

    public enum Tab {
        JOBS("Jobs"), WORLD("World"), TRAVEL("Travel"), COMBAT("Combat"), TOOLS("Tools");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final int MAX_WIDTH = 400;
    private static final int TOP = 54;
    private static final int REFRESH_TICKS = 10;

    private static Tab lastTab = Tab.JOBS;
    private static final int[] SCROLL = new int[Tab.values().length];
    private static final Map<String, String> STATE = new HashMap<>();

    private final Screen parent;
    private final Map<AbstractWidget, String> textFieldKeys = new IdentityHashMap<>();
    private Tab tab = lastTab;
    private Panel panel;
    private int panelLeft;
    private int panelWidth;
    private int viewHeight;
    private boolean rebuildRequested;
    private String signature = "";
    private int ticks;
    private String status = "";
    private int statusTicks;

    public SuiteMenuScreen(Screen parent) {
        super(Component.literal("Utility Suite"));
        this.parent = parent;
    }

    // ---- state used by Panel ----------------------------------------------------------------------------------------

    String state(String key, String def) {
        return STATE.getOrDefault(key, def);
    }

    void setState(String key, String value) {
        if (value == null || value.isEmpty()) {
            STATE.remove(key);
        } else {
            STATE.put(key, value);
        }
    }

    void registerTextField(EditBox box, String key) {
        textFieldKeys.put(box, key);
    }

    void requestRebuild() {
        rebuildRequested = true;
    }

    void setStatus(String message) {
        status = message == null ? "" : message;
        statusTicks = 100;
    }

    public Tab tab() {
        return tab;
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        textFieldKeys.clear();
        panelWidth = Math.min(MAX_WIDTH, width - 24);
        panelLeft = (width - panelWidth) / 2;
        int bottomBar = 42;
        viewHeight = Math.max(40, height - TOP - bottomBar);

        Tab[] tabs = Tab.values();
        int tabW = Math.min(76, (panelWidth - (tabs.length - 1) * 2) / tabs.length);
        int tabsWidth = tabs.length * tabW + (tabs.length - 1) * 2;
        int tx = (width - tabsWidth) / 2;
        for (Tab t : tabs) {
            Button b = Button.builder(Component.literal(t.label), btn -> switchTab(t)).bounds(tx, 26, tabW, 20).build();
            b.active = t != tab;
            addRenderableWidget(b);
            tx += tabW + 2;
        }

        if (Services.arbiter().isAutomationStopped()) {
            addRenderableWidget(Button.builder(Component.literal("Resume automation"), b -> {
                Services.arbiter().resumeAutomation();
                setStatus("Automation resumed");
                requestRebuild();
            }).bounds(width - 8 - 120, 4, 120, 18).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 60, height - 25, 120, 20).build());

        panel = new Panel(this, font, panelLeft, TOP, panelWidth, viewHeight);
        try {
            switch (tab) {
                case JOBS -> JobsTab.build(panel);
                case WORLD -> WorldTab.build(panel);
                case TRAVEL -> TravelTab.build(panel);
                case COMBAT -> CombatTab.build(panel);
                case TOOLS -> ToolsTab.build(panel);
            }
        } catch (RuntimeException e) {
            panel.text("This tab failed to build: " + e, Panel.RED);
            com.utilitysuite.UtilitySuiteMod.LOGGER.error("Menu tab {} failed", tab, e);
        }
        for (AbstractWidget w : panel.widgets()) {
            addRenderableWidget(w);
        }
        panel.setScroll(SCROLL[tab.ordinal()]);
        signature = signature();
    }

    private void switchTab(Tab t) {
        SCROLL[tab.ordinal()] = panel == null ? 0 : panel.scroll();
        tab = t;
        lastTab = t;
        rebuildKeepingFocus();
    }

    private String signature() {
        Supplier<String> s = switch (tab) {
            case JOBS -> JobsTab::signature;
            case WORLD -> WorldTab::signature;
            case TRAVEL -> TravelTab::signature;
            case COMBAT -> CombatTab::signature;
            case TOOLS -> ToolsTab::signature;
        };
        try {
            return s.get() + "|" + Services.arbiter().isAutomationStopped();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (statusTicks > 0 && --statusTicks == 0) {
            status = "";
        }
        if (rebuildRequested) {
            rebuildRequested = false;
            rebuildKeepingFocus();
            return;
        }
        if (ticks % REFRESH_TICKS == 0 && panel != null) {
            panel.refreshDynamic();
            String sig = signature();
            if (!sig.equals(signature)) {
                rebuildKeepingFocus();
            }
        }
    }

    private void rebuildKeepingFocus() {
        String focusedKey = null;
        GuiEventListener f = getFocused();
        if (f instanceof EditBox box) {
            focusedKey = textFieldKeys.get(box);
        }
        if (panel != null) {
            SCROLL[tab.ordinal()] = panel.scroll();
        }
        rebuildWidgets();
        if (focusedKey != null) {
            for (Map.Entry<AbstractWidget, String> e : textFieldKeys.entrySet()) {
                if (focusedKey.equals(e.getValue())) {
                    setFocused(e.getKey());
                    break;
                }
            }
        }
    }

    // ---- input ------------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (panel != null && mouseY >= TOP && mouseY <= TOP + viewHeight) {
            panel.setScroll(panel.scroll() - (int) Math.round(scrollY * Panel.ROW));
            SCROLL[tab.ordinal()] = panel.scroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        // Right Shift closes the menu again unless a text box is being typed in.
        var open = com.utilitysuite.core.input.KeybindManager.mapping(
                com.utilitysuite.core.input.KeybindManager.KeyId.OPEN_MENU);
        if (open != null && open.matches(keyCode, scanCode) && !(getFocused() instanceof EditBox)) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        if (panel != null) {
            SCROLL[tab.ordinal()] = panel.scroll();
        }
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, title, panelLeft, 8, Panel.WHITE);
        String profile = "Profile: " + Services.modState().profile().profileId();
        g.drawString(font, profile, panelLeft, 16, Panel.GRAY);
        if (Services.arbiter().isAutomationStopped()) {
            g.drawString(font, "Automation stopped", width - 8 - 120 - 4 - font.width("Automation stopped"), 9,
                    Panel.RED);
        }
        if (panel != null) {
            g.enableScissor(panelLeft - 2, TOP, panelLeft + panelWidth + 8, TOP + viewHeight);
            panel.renderText(g);
            g.disableScissor();
            int max = panel.maxScroll();
            if (max > 0) {
                int barX = panelLeft + panelWidth + 4;
                int barH = Math.max(12, viewHeight * viewHeight / (viewHeight + max));
                int barY = TOP + (viewHeight - barH) * panel.scroll() / max;
                g.fill(barX, TOP, barX + 3, TOP + viewHeight, 0x40FFFFFF);
                g.fill(barX, barY, barX + 3, barY + barH, 0xC0FFFFFF);
            }
        }
        if (!status.isEmpty()) {
            g.drawString(font, status, panelLeft, height - 38, Panel.YELLOW);
        }
    }
}
