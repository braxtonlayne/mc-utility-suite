package com.utilitysuite.ui.holo;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.utilitysuite.UtilitySuiteMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/**
 * The render pipelines, texture setups and render type of the holo kits (26.3 render-state pipeline API). All draw
 * position-tex-colour quads sampling the {@link HoloAtlas} with translucent blending and no culling (ribbon quads may
 * wind either way):
 * <ul>
 *     <li>{@link #GUI_PIPELINE} ("holo_gui"): the vanilla GUI textured snippet with culling off, used by the HUD kit
 *     through {@link HoloQuadBatch}; {@link #guiTextures(boolean)} picks linear filtering (soft ribbons, arcs, the
 *     glow sprite) or nearest filtering (the 7x7 glyph bitmaps stay crisp at every GUI scale);</li>
 *     <li>{@link #WORLD_PIPELINE} ("holo_world"): the world ribbons, depth test on (reverse-Z
 *     {@code GREATER_THAN_OR_EQUAL}, as vanilla) without depth write, linear filtering, through {@link #world()}.</li>
 * </ul>
 * Both pipelines are registered with {@link RegisterRenderPipelinesEvent} (mod bus, {@link #onRegisterPipelines}) so
 * they are compiled up front.
 */
public final class HoloRenderTypes {

    public static final RenderPipeline GUI_PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "pipeline/holo_gui"))
            .withCull(false)
            .build();

    public static final RenderPipeline WORLD_PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withLocation(Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "pipeline/holo_world"))
            .withVertexShader("core/position_tex_color")
            .withFragmentShader("core/position_tex_color")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();

    private static TextureSetup guiLinear;
    private static TextureSetup guiNearest;
    private static RenderType world;

    private HoloRenderTypes() {
    }

    /** Mod-bus listener: registers both pipelines for up-front compilation. */
    public static void onRegisterPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(GUI_PIPELINE);
        event.registerPipeline(WORLD_PIPELINE);
    }

    /** The atlas bound for GUI drawing: linear ({@code true}, ribbons) or nearest (glyphs). Render thread only. */
    public static TextureSetup guiTextures(boolean linear) {
        TextureSetup t = linear ? guiLinear : guiNearest;
        if (t == null) {
            HoloAtlas.ensureUploaded();
            GpuTextureView view = Minecraft.getInstance().getTextureManager().getTexture(HoloAtlas.ID)
                    .getTextureView();
            GpuSampler sampler = RenderSystem.getSamplerCache()
                    .getClampToEdge(linear ? FilterMode.LINEAR : FilterMode.NEAREST);
            t = TextureSetup.singleTexture(view, sampler);
            if (linear) {
                guiLinear = t;
            } else {
                guiNearest = t;
            }
        }
        return t;
    }

    /** The soft-ribbon GUI pipeline (kept for callers of the old accessor). */
    public static RenderPipeline gui() {
        return GUI_PIPELINE;
    }

    /**
     * The world ribbon type ("utilitysuite_holo_world"): {@link #WORLD_PIPELINE} sampling the atlas with linear
     * filtering. Render thread only.
     */
    public static RenderType world() {
        RenderType t = world;
        if (t == null) {
            HoloAtlas.ensureUploaded();
            t = RenderType.create("utilitysuite_holo_world", RenderSetup.builder(WORLD_PIPELINE)
                    .withTexture("Sampler0", HoloAtlas.ID,
                            () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                    .createRenderSetup());
            world = t;
        }
        return t;
    }
}
