package com.utilitysuite.ui.holo;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * The render types of the 2D kit, created once (lazily, on the render thread). Both use position-tex-colour quads
 * sampling the {@link HoloAtlas}, translucent blending, no depth test, no depth write and no culling (ribbon quads may
 * wind either way):
 * <ul>
 *     <li>{@link #gui()} ("holo_gui"): linear filtering, for the soft ribbons (lines, arcs, rings) and the glow
 *     sprite;</li>
 *     <li>{@link #glyphs()} ("holo_gui_glyph"): the same state with nearest filtering, so the 7x7 glyph bitmaps stay
 *     crisp at every GUI scale.</li>
 * </ul>
 * Shard and vertex-format names were checked against the mapped NeoForge 21.1.265 sources
 * ({@code RenderStateShard.TRANSLUCENT_TRANSPARENCY}, {@code NO_DEPTH_TEST}, {@code COLOR_WRITE}, {@code NO_CULL},
 * {@code ShaderStateShard(Supplier)}, {@code TextureStateShard(ResourceLocation, blur, mipmap)},
 * {@code GameRenderer.getPositionTexColorShader}, {@code DefaultVertexFormat.POSITION_TEX_COLOR},
 * {@code RenderType.create(String, VertexFormat, Mode, int, CompositeState)}).
 */
public final class HoloRenderTypes {

    private static final int BUFFER_SIZE = 8192;

    private static RenderType gui;
    private static RenderType glyphs;

    private HoloRenderTypes() {
    }

    /** The soft-ribbon type. Render thread only. */
    public static RenderType gui() {
        RenderType t = gui;
        if (t == null) {
            t = create("holo_gui", true);
            gui = t;
        }
        return t;
    }

    /** The crisp glyph type. Render thread only. */
    public static RenderType glyphs() {
        RenderType t = glyphs;
        if (t == null) {
            t = create("holo_gui_glyph", false);
            glyphs = t;
        }
        return t;
    }

    private static RenderType create(String name, boolean blur) {
        HoloAtlas.ensureUploaded(); // the texture shard looks the atlas up by id when it is set up
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader))
                .setTextureState(new RenderStateShard.TextureStateShard(HoloAtlas.ID, blur, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setCullState(RenderStateShard.NO_CULL)
                .createCompositeState(false);
        return RenderType.create("utilitysuite_" + name, DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS, BUFFER_SIZE, state);
    }
}
