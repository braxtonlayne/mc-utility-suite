package com.utilitysuite.mixin.world;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Picture-in-picture full render: a pending full occlusion rebuild is scheduled from whichever camera renders next.
 * The second pass skips its frame while one is pending, so the main view's graph is always built from the main
 * camera. Why a mixin: the flag is private and no event exposes it.
 */
@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphAccessor {

    @Accessor("needsFullUpdate")
    boolean utilitysuite$needsFullUpdate();
}
