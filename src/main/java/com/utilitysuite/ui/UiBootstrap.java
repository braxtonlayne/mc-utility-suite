package com.utilitysuite.ui;

import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.ui.tabs.ToolsTab;
import com.utilitysuite.ui.tabs.TravelTab;
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
    }

    /** Opens the menu (key handler; also usable from other code on the client thread). */
    public static void openMenu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            mc.setScreen(new SuiteMenuScreen(null));
        }
    }

    public static SuiteHud hud() {
        return hud;
    }
}
