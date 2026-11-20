package com.utilitysuite.tools.inventory;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.util.function.Supplier;

/**
 * Adds "Deposit" (matching items) and "Dump" (everything except pinned slots) buttons to storage container screens
 * ({@code ScreenEvent.Init.Post}) and handles the Quick Deposit key inside those screens (Shift + key = Dump).
 */
public final class ContainerButtons {

    public static final String KEY_QUICK_DEPOSIT = "key.utilitysuite.quick_deposit";

    private final Supplier<SmartInventory> module;
    private KeyMapping quickDeposit;

    public ContainerButtons(Supplier<SmartInventory> module) {
        this.module = module;
    }

    /** Mod bus. Default R, active only inside GUIs, so it never conflicts with in-game bindings. */
    public void onRegisterKeys(RegisterKeyMappingsEvent event) {
        quickDeposit = new KeyMapping(KEY_QUICK_DEPOSIT, KeyConflictContext.GUI, InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R, com.utilitysuite.core.input.KeybindManager.CATEGORY);
        event.register(quickDeposit);
    }

    public KeyMapping quickDepositKey() {
        return quickDeposit;
    }

    /** Game bus. */
    public void onScreenInit(ScreenEvent.Init.Post event) {
        SmartInventory m = module.get();
        if (m == null || !m.depositButtonsEnabled()) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)
                || !SmartInventory.isStorageMenu(screen.getMenu())) {
            return;
        }
        int w = 46;
        int h = 16;
        int x = screen.getGuiLeft() + screen.getXSize() + 3;
        int y = screen.getGuiTop() + 4;
        boolean below = x + w > screen.width;
        if (below) {
            x = screen.getGuiLeft() + screen.getXSize() - 2 * w - 2;
            y = screen.getGuiTop() - h - 2;
        }
        Button deposit = Button.builder(Component.translatable("button.utilitysuite.deposit"), b -> run(false))
                .bounds(x, y, w, h)
                .tooltip(Tooltip.create(Component.translatable("button.utilitysuite.deposit.tooltip")))
                .build();
        Button dump = Button.builder(Component.translatable("button.utilitysuite.dump"), b -> run(true))
                .bounds(below ? x + w + 2 : x, below ? y : y + h + 2, w, h)
                .tooltip(Tooltip.create(Component.translatable("button.utilitysuite.dump.tooltip")))
                .build();
        event.addListener(deposit);
        event.addListener(dump);
    }

    /** Game bus: the Quick Deposit key inside a storage screen. */
    public void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        KeyMapping key = quickDeposit;
        SmartInventory m = module.get();
        if (key == null || m == null || !m.isEnabled() || key.isUnbound()
                || !key.matches(event.getKeyCode(), event.getScanCode())) { // modifiers ignored: Shift = Dump
            return;
        }
        Screen s = event.getScreen();
        if (!(s instanceof AbstractContainerScreen<?> screen) || !SmartInventory.isStorageMenu(screen.getMenu())
                || s.getFocused() instanceof EditBox) {
            return;
        }
        run(Screen.hasShiftDown());
        event.setCanceled(true);
    }

    private void run(boolean all) {
        SmartInventory m = module.get();
        if (m == null) {
            return;
        }
        String result = m.depositToOpenContainer(all);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !com.utilitysuite.core.api.Services.modState().isCleanScreen()) {
            mc.player.displayClientMessage(Component.literal(result), true);
        }
    }
}
