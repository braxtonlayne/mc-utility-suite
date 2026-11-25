package com.utilitysuite.ui;

import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.ui.holo.HudContextModule;
import com.utilitysuite.ui.holo.NoticeBoard;
import com.utilitysuite.ui.holo.NoticeLayer;
import com.utilitysuite.ui.holo.Visuals;
import com.utilitysuite.ui.holo.VisualSettings;
import com.utilitysuite.ui.tabs.ToolsTab;
import com.utilitysuite.ui.tabs.TravelTab;
import com.utilitysuite.ui.tabs.WorldTab;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Wires the Shell: Right Shift opens the five-tab {@link SuiteMenuScreen}; the {@link SuiteHud} module (the HUD host:
 * status line, badges, info strip, module list and every registered HudElement) is one GUI layer above everything
 * and backs {@code Services.visuals()}; the Appearance settings live in {@link VisualSettings}; custom module panels
 * for travel and tools are registered in {@link ModulePanels}. Called last from {@code UtilitySuiteMod}.
 */
public final class UiBootstrap {

    private static SuiteHud hud;
    private static TabGui tabGui;

    private UiBootstrap() {
    }

    public static void init(IEventBus modBus) {
        hud = new SuiteHud();
        Services.registerVisuals(hud.visuals()); // other modules register HUD elements with its host
        // toasts: the board backs Services.visuals().notices(); its layer is one more HUD element (self-placed)
        NoticeBoard board = new NoticeBoard(System::currentTimeMillis, () -> Services.modState().isCleanScreen());
        Visuals.useNotices(board);
        NoticeLayer notices = new NoticeLayer(board);
        hud.host().register(notices);
        hud.host().setInstant(notices, true); // the layer runs its own enter and leave motion
        Services.modules().register(new VisualSettings()); // writes HoloTheme on profile load and change
        Services.modules().register(hud);
        hud.subscribe();
        // V6 context-morphing HUD (off by default): the host fades with it, tactical layers read its factors
        HudContextModule context = new HudContextModule();
        Services.modules().register(context);
        Visuals.useContext(context.machine());
        hud.host().context = context.machine();
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, context::onRightClickBlock);
        tabGui = new TabGui(); // off by default; reads arrow keys only while enabled
        Services.modules().register(tabGui);
        KeybindManager.setOpenMenuHandler(UiBootstrap::openMenu);
        modBus.addListener(UiBootstrap::onRegisterLayers);
        TravelTab.registerPanels();
        ToolsTab.registerPanels();
        WorldTab.registerPanels();
        if (UiPreview.enabled()) {
            UiPreview.install(); // dev only: -Dutilitysuite.uiPreview=true (gradlew runUiPreview)
        }
    }

    private static void onRegisterLayers(RegisterGuiLayersEvent event) {
        // the one HUD host layer: status line, badges, info strip, module list and every registered HudElement
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "status_line"), hud);
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "tab_gui"), tabGui);
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
