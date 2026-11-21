package com.utilitysuite.mixin.ui;

import com.utilitysuite.ui.TabGui;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the Tab-GUI swallow the arrow/Enter keys it navigates with, so they reach neither vanilla key mappings nor other
 * {@code InputEvent.Key} listeners.
 * <p>
 * Why a mixin: NeoForge 21.1's {@code InputEvent.Key} is not cancellable and is posted after vanilla has already handled
 * the key, so no event can stop it. Target checked against the 21.1.265 sources:
 * {@code public void keyPress(long windowPointer, int key, int scanCode, int action, int modifiers)}, descriptor
 * {@code (JIIII)V}. Cancels only when {@link TabGui#consumesKey} says so (Tab-GUI visible, no screen open, a
 * navigation key, or the release of a press it swallowed); everything else runs untouched. This is the user's own
 * keyboard: nothing is simulated and the arbiter is not involved.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerTabGuiMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$tabGuiConsume(long windowPointer, int key, int scanCode, int action, int modifiers,
                                            CallbackInfo ci) {
        if (TabGui.consumesKey(windowPointer, key, action)) {
            ci.cancel();
        }
    }
}
