package com.utilitysuite.devtest;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.gui.GuiLayer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A GuiGraphicsExtractor that records fill/text calls instead of drawing, to prove what a HUD layer would draw.
 * 26.3: it writes into its own throwaway {@link GuiRenderState}, never the frame's; custom GUI elements submitted
 * through {@code submitGuiElementRenderState} (the holo quad batches) count as fills.
 */
final class CountingGraphics extends GuiGraphicsExtractor {

    int fills;
    final List<String> texts = new ArrayList<>();

    CountingGraphics() {
        super(Minecraft.getInstance(), new GuiRenderState(), 0, 0);
    }

    static CountingGraphics render(GuiLayer layer) {
        CountingGraphics g = new CountingGraphics();
        layer.render(g, DeltaTracker.ZERO);
        return g;
    }

    int calls() {
        return fills + texts.size();
    }

    @Override
    public void fill(RenderPipeline pipeline, int x0, int y0, int x1, int y1, int col) {
        fills++;
    }

    @Override
    public void fillGradient(int x0, int y0, int x1, int y1, int col1, int col2) {
        fills++;
    }

    @Override
    public void fill(RenderPipeline renderPipeline, TextureSetup textureSetup, int x0, int y0, int x1, int y1) {
        fills++;
    }

    @Override
    public void submitGuiElementRenderState(GuiElementRenderState renderState) {
        fills++;
    }

    @Override
    public void text(Font font, @Nullable String text, int x, int y, int color, boolean dropShadow) {
        if (text != null) {
            texts.add(text);
        }
    }

    @Override
    public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean dropShadow) {
        texts.add("<seq>");
    }

    @Override
    public String toString() {
        return fills + " fills, texts " + texts;
    }
}
