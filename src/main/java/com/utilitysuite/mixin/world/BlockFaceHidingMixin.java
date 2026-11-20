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

    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void utilitysuite$hideFaces(BlockState state, BlockGetter level, BlockPos offset, Direction face,
                                               BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (XRayHiding.active()) {
            cir.setReturnValue(XRayHiding.isVisible(state));
        }
    }
}
