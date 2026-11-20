package com.utilitysuite.ui;

import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.ui.tabs.ToolsTab;
import com.utilitysuite.ui.tabs.TravelTab;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * Wires the Shell: Right Shift opens the five-tab {@link SuiteMenuScreen}; the {@link SuiteHud} module (status pill +
 * badges) is registered as one GUI layer above everything; custom module panels for travel and tools are registered
 * in {@link ModulePanels}. Called last from {@code UtilitySuiteMod}.
 */
public final class UiBootstrap {

    private static SuiteHud hud;

    private UiBootstrap() {
    }

    public static void init(IEventBus modBus) {
        hud = new SuiteHud();
        Services.modules().register(hud);
        hud.subscribe();
        KeybindManager.setOpenMenuHandler(UiBootstrap::openMenu);
        modBus.addListener(UiBootstrap::onRegisterLayers);
        TravelTab.registerPanels();
        ToolsTab.registerPanels();
        if (UiPreview.enabled()) {
            UiPreview.install(); // dev only: -Dutilitysuite.uiPreview=true (gradlew runUiPreview)
        }
    }

    private static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "status_line"), hud);
        // drawn after the status line so it can sit below the pill when both share a corner
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "info_strip"),
                hud.infoStrip());
    }

    /** Opens the menu (key handler; also usable from other code on the client thread). */
    public static void openMenu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            return; // idempotent: never replaces (or toggles) an open screen
        }
        long sinceClose = Util.getMillis() - SuiteMenuScreen.lastClosedAt();
        if (SuiteMenuScreen.lastClosedAt() != 0L && sinceClose < REOPEN_DEBOUNCE_MS) {
            // A queued click or key-repeat from the press that just closed the menu: ignore it.
            UtilitySuiteMod.LOGGER.info("Suite menu reopen ignored ({} ms after close)", sinceClose);
            SuiteMenuScreen.drainOpenKeyClicks();
            return;
        }
        mc.setScreen(new SuiteMenuScreen(null)); // the constructor logs who opened it
        SuiteMenuScreen.drainOpenKeyClicks();
    }

    private static final long REOPEN_DEBOUNCE_MS = 300L;

    public static SuiteHud hud() {
        return hud;
    }
}
