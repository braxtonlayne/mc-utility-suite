package com.utilitysuite.mixin.travel;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.utilitysuite.travel.terrain.TerrainHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Surface traversal, SOLID_COLLISION mode: source fluid blocks give the local player a solid top (the same path striders
 * use: {@code LiquidBlock.getCollisionShape} asks the collision context, which asks
 * {@code LivingEntity.canStandOnFluid}).
 * <p>
 * Why a mixin: fluid collision is decided inside the block's collision shape query; no event exposes it. Only the local
 * player is affected (the hook checks identity), only while the mode is toggled on, and sneaking sinks.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFluidSurfaceMixin {

    @ModifyReturnValue(method = "canStandOnFluid", at = @At("RETURN"))
    private boolean utilitysuite$standOnSurface(boolean original, FluidState fluid) {
        return TerrainHooks.standOnFluid((LivingEntity) (Object) this, fluid, original);
    }
}
