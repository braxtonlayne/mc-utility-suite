package com.utilitysuite.mixin.input;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to the movement input's move vector ({@code protected Vec2 moveVector}, checked against the 26.3
 * sources), used only by {@code core.input.MoveInputs} (shared control and the input backend). Why a mixin: 26.3's
 * {@code ClientInput} exposes the vector read-only ({@code getMoveVector()}) and {@code MovementInputUpdateEvent} hands
 * out the input object itself, so no event or public method can change the impulses. Nothing is injected.
 */
@Mixin(ClientInput.class)
public interface ClientInputAccessor {

    @Accessor("moveVector")
    void utilitysuite$setMoveVector(Vec2 moveVector);
}
