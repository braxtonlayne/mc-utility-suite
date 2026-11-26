package com.utilitysuite.ui.kit;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A {@link GuiGraphicsExtractor} that multiplies one alpha into every fill, gradient and text colour: the 26.3
 * replacement for {@code RenderSystem.setShaderColor(1, 1, 1, a)}, which the menu used to fade its content in on
 * open and on a tab switch. It writes into the frame's shared GUI render state (so layering against everything drawn
 * through the real extractor stays correct) with its own pose and scissor stacks: {@link #begin} copies the real
 * extractor's pose. Items cannot fade and are drawn while the alpha is at least one half. Vanilla widgets that draw
 * text through {@code textRenderer()} collectors (edit box text) bypass the multiplier.
 * <p>
 * One instance per screen, rebuilt only when the GUI size changes; nothing is allocated per call. Render thread only.
 */
public final class FadingGraphics extends GuiGraphicsExtractor {

    private final int guiW;
    private final int guiH;
    private float alpha = 1f;

    private FadingGraphics(Minecraft mc, int mouseX, int mouseY) {
        super(mc, mc.gameRenderer.gameRenderState().guiRenderState, mouseX, mouseY);
        guiW = guiWidth();
        guiH = guiHeight();
    }

    /**
     * The instance to draw a faded section with this frame: {@code cached} when still valid, else a new one. The pose
     * is copied from {@code real}.
     */
    public static FadingGraphics begin(@Nullable FadingGraphics cached, GuiGraphicsExtractor real, int mouseX,
                                       int mouseY) {
        FadingGraphics f = cached;
        if (f == null || f.guiW != real.guiWidth() || f.guiH != real.guiHeight()) {
            f = new FadingGraphics(Minecraft.getInstance(), mouseX, mouseY);
        }
        f.pose().set(real.pose());
        f.alpha = 1f;
        return f;
    }

    /** Sets the alpha multiplied into everything drawn next (0..1). */
    public FadingGraphics alpha(float a) {
        alpha = a <= 0f ? 0f : Math.min(1f, a);
        return this;
    }

    private int mul(int argb) {
        if (alpha >= 1f) {
            return argb;
        }
        int a = Math.round((argb >>> 24) * alpha);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    @Override
    public void fill(RenderPipeline pipeline, int x0, int y0, int x1, int y1, int col) {
        super.fill(pipeline, x0, y0, x1, y1, mul(col));
    }

    @Override
    public void fillGradient(int x0, int y0, int x1, int y1, int col1, int col2) {
        super.fillGradient(x0, y0, x1, y1, mul(col1), mul(col2));
    }

    @Override
    public void text(Font font, FormattedCharSequence str, int x, int y, int color, boolean dropShadow) {
        super.text(font, str, x, y, mul(color), dropShadow);
    }

    @Override
    public void item(ItemStack itemStack, int x, int y) {
        if (alpha >= 0.5f) {
            super.item(itemStack, x, y);
        }
    }

    @Override
    public void item(ItemStack itemStack, int x, int y, int seed) {
        if (alpha >= 0.5f) {
            super.item(itemStack, x, y, seed);
        }
    }
}
