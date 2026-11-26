package com.utilitysuite.mixin.world;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Picture-in-picture full render: a pending full occlusion rebuild is scheduled from whichever camera renders next.
 * The second pass skips its frame while one is pending, so the main view's graph is always built from the main
 * camera. Why a mixin: the flag is private and no event exposes it.
 * <p>
 * 26.3 (checked against the sources): the graph also owns the last camera position it was updated for
 * ({@code private double prevCamX/Y/Z}, the real position; 1.21.1 kept the 8-block cell on {@code LevelRenderer}). A
 * change of position, FOV or smart-cull flag schedules a section update.
 */
@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphAccessor {

    @Accessor("needsFullUpdate")
    boolean utilitysuite$needsFullUpdate();

    @Accessor("prevCamX")
    double utilitysuite$prevCamX();

    @Accessor("prevCamX")
    void utilitysuite$setPrevCamX(double v);

    @Accessor("prevCamY")
    double utilitysuite$prevCamY();

    @Accessor("prevCamY")
    void utilitysuite$setPrevCamY(double v);

    @Accessor("prevCamZ")
    double utilitysuite$prevCamZ();

    @Accessor("prevCamZ")
    void utilitysuite$setPrevCamZ(double v);
}
