package com.utilitysuite.mixin.travel;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.utilitysuite.travel.terrain.TerrainHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Surface traversal, SOLID_COLLISION mode, second half: vanilla gives an entity that can stand on a fluid only the
 * half-height {@code STABLE_SHAPE} (striders wade half-submerged), which left the player standing inside the water with
 * swimming physics. For the local player that already stands on the fluid through {@link LivingEntityFluidSurfaceMixin}
 * this swaps in a full block while their feet are above the block's top, so they walk on the surface.
 * <p>
 * Why a mixin: the shape comes from the block's collision query; no event exposes it. Constant shapes only, no
 * allocation; every other entity and context gets vanilla's answer.
 */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockSolidSurfaceMixin {

    @ModifyReturnValue(method = "getCollisionShape", at = @At("RETURN"))
    private VoxelShape utilitysuite$fullSurface(VoxelShape original, BlockState state, BlockGetter level, BlockPos pos,
                                                CollisionContext context) {
        return TerrainHooks.fluidSurfaceShape(original, pos, context);
    }
}
