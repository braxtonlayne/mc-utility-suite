package com.utilitysuite.mixin.world;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Picture-in-picture second pass: the level renderer's occlusion graph. 26.3 (checked against the sources): the
 * camera bookkeeping that 1.21.1 kept here moved out; {@code prevCamX/Y/Z} (now the real camera position, not the
 * 8-block cell) live in {@code SectionOcclusionGraph} ({@link SectionOcclusionGraphAccessor}) and
 * {@code prevCamRotX/Y} in {@code LevelExtractor} ({@link LevelExtractorCullStateAccessor}).
 * {@code private final SectionOcclusionGraph sectionOcclusionGraph} also has a public getter now
 * ({@code LevelRenderer.sectionOcclusionGraph()}); the accessor is kept for existing callers.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererCullStateAccessor {

    @Accessor("sectionOcclusionGraph")
    SectionOcclusionGraph utilitysuite$occlusionGraph();
}
