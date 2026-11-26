package com.utilitysuite.mixin.world;

import com.utilitysuite.world.overlays.XRayHiding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional "hide other blocks" X-ray mode. While (and only while) the user has selected it, culled faces of blocks
 * outside the visible set are dropped and every face of a visible block is kept, so full cubes other than the matches
 * disappear from newly compiled chunk meshes. Toggling rebuilds the meshes ({@code LevelRenderer.allChanged}).
 * <p>
 * Why a mixin: chunk meshing has no event hook; {@code Block.shouldRenderFace} is the single vanilla decision point for
 * face culling. It runs on chunk-compile worker threads, so it reads only a volatile flag and an immutable set. When
 * the mode is off this is one volatile read. Sodium/Embeddium do not call this method (see {@link XRayHiding}).
 */
@Mixin(Block.class)
public abstract class BlockFaceHidingMixin {

    // 26.3 (checked against the sources): vanilla's shouldRenderFace(BlockState, BlockState, Direction) delegates to
    // NeoForge's level-aware overload, which the block model renderer also calls directly; hooking the overload
    // covers both.
    @Inject(method = "shouldRenderFace(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/core/Direction;)Z", at = @At("HEAD"), cancellable = true)
    private static void utilitysuite$hideFaces(BlockGetter level, BlockPos pos, BlockState state, BlockState neighbor,
                                               Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (XRayHiding.active()) {
            cir.setReturnValue(XRayHiding.isVisible(state));
        }
    }
}
