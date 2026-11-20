package com.utilitysuite.mixin.travel;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.utilitysuite.travel.terrain.TerrainHooks;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SafeWalk: clamps the local player's horizontal movement at block edges, like vanilla sneaking but without the sneak
 * slowdown and with a configurable minimum drop.
 * <p>
 * Why a mixin: no NeoForge event exposes the movement vector between input and collision; vanilla's own edge clamp
 * lives in {@code Player.maybeBackOffFromEdge} and only runs while sneaking. {@code @ModifyReturnValue} (MixinExtras,
 * bundled with NeoForge) replaces the result only when SafeWalk is active and allocates no callback object.
 */
@Mixin(Player.class)
public abstract class PlayerSafeWalkMixin {

    @ModifyReturnValue(method = "maybeBackOffFromEdge", at = @At("RETURN"))
    private Vec3 utilitysuite$safeWalk(Vec3 original, Vec3 movement, MoverType mover) {
        return TerrainHooks.backOffFromEdge((Player) (Object) this, original, movement, mover);
    }
}
