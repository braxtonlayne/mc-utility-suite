package com.utilitysuite.mixin.world;

import com.utilitysuite.world.WorldHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reports every client-side block change to the world scanner so X-ray matches, exposure flags and the chunk-age
 * heuristic stay current without rescanning whole sections.
 * <p>
 * Why a mixin: NeoForge has no client-side block change event ({@code BlockEvent} is server-side only), and the client
 * applies single-block packets, section-update packets and its own predicted edits through {@code Level.setBlock},
 * which ends in {@code ClientLevel.sendBlockUpdated} with both the old and the new state. Read-only; nothing changes.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelBlockUpdateMixin {

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void utilitysuite$reportBlockChange(BlockPos pos, BlockState oldState, BlockState newState, int flags,
                                                CallbackInfo ci) {
        WorldHooks.blockChanged(pos, oldState, newState);
    }
}
