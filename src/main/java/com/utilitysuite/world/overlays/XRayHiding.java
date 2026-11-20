package com.utilitysuite.world.overlays;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.util.Set;

/**
 * State for the optional "hide everything else" X-ray mode, read by {@code BlockFaceHidingMixin} on the chunk-compile
 * worker threads. The visible set is replaced, never mutated, so the workers read it without locking.
 * <p>
 * Only effective with the vanilla chunk renderer: Sodium and Embeddium mesh chunks with their own occlusion code that
 * never calls {@code Block.shouldRenderFace}, so with them installed the setting is greyed out with a tooltip and the
 * outline overlay remains the way to see matches.
 */
public final class XRayHiding {

    private static volatile boolean active;
    private static volatile Set<Block> visible = Set.of();

    private XRayHiding() {
    }

    public static boolean active() {
        return active;
    }

    /** Mixin query: should faces of this block render while hiding is active? */
    public static boolean isVisible(BlockState state) {
        return visible.contains(state.getBlock());
    }

    /** True when a chunk-meshing replacement is installed that bypasses the vanilla face check. */
    public static boolean rendererIncompatible() {
        ModList mods = ModList.get();
        return mods != null && (mods.isLoaded("sodium") || mods.isLoaded("embeddium"));
    }

    /** Applies a new state; rebuilds every chunk mesh when the visible result changes. Client thread. */
    public static void set(boolean on, Set<Block> blocks) {
        boolean want = on && !rendererIncompatible();
        Set<Block> next = new ReferenceOpenHashSet<>(blocks);
        boolean changed = want != active || (want && !next.equals(visible));
        visible = next;
        active = want;
        if (changed) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.levelRenderer != null && mc.level != null) {
                mc.levelRenderer.allChanged();
            }
        }
    }
}
