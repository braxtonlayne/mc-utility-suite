package com.utilitysuite.mixin.world;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Picture-in-picture full render: the culling bookkeeping {@code LevelRenderer.setupRender} compares against the last
 * camera. The second pass primes it so its own camera neither invalidates the main section-occlusion graph (an
 * async full rebuild every swap) nor reuses the main view's frustum-culled section list, then restores it so the main
 * pass re-culls for its own frustum. Why a mixin: the fields are private and no event exposes them.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererCullStateAccessor {

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

    @Accessor("prevCamRotX")
    void utilitysuite$setPrevCamRotX(double v);

    @Accessor("prevCamRotY")
    void utilitysuite$setPrevCamRotY(double v);

    @Accessor("sectionOcclusionGraph")
    SectionOcclusionGraph utilitysuite$occlusionGraph();
}
