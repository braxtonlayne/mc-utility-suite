package com.utilitysuite.mixin.input;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.utilitysuite.core.input.InputSmoother;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Held automated ATTACK keeps breaking a block while the mouse is not grabbed.
 * <p>
 * Why a mixin: in 1.21.1 {@code Minecraft.handleKeybinds} calls
 * {@code continueAttack(screen == null && ... && keyAttack.isDown() && mouseHandler.isMouseGrabbed())}. With the
 * window unfocused (alt-tabbed during a job, or a test client in the background) the mouse is released, so a block
 * break driven by a held attack key under a job's lease (ender chest / shulker box pick-up, field work) starts on the
 * first click and is cancelled the next tick; no event sits between. This treats the mouse as grabbed for that one
 * call only while {@link InputSmoother#automationHoldsAttack()} (automation holds ATTACK with a live lease); the user's
 * own attack key is untouched. Target verified against the 21.1.265 sources: the only {@code isMouseGrabbed()} call in
 * {@code handleKeybinds}. Allocation free.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackHoldMixin {

    @ModifyExpressionValue(method = "handleKeybinds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean utilitysuite$attackHoldWithoutGrab(boolean grabbed) {
        return grabbed || InputSmoother.automationHoldsAttack();
    }
}
