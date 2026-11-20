package com.utilitysuite.devtest;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** A GuiGraphics that records fill/text calls instead of drawing, to prove what a HUD layer would draw. */
final class CountingGraphics extends GuiGraphics {

    int fills;
    final List<String> texts = new ArrayList<>();

    CountingGraphics() {
        super(Minecraft.getInstance(), Minecraft.getInstance().renderBuffers().bufferSource());
    }

    static CountingGraphics render(LayeredDraw.Layer layer) {
        CountingGraphics g = new CountingGraphics();
        layer.render(g, DeltaTracker.ZERO);
        return g;
    }

    int calls() {
        return fills + texts.size();
    }

    @Override
    public void fill(RenderType renderType, int minX, int minY, int maxX, int maxY, int z, int color) {
        fills++;
    }

    @Override
    public int drawString(Font font, String text, float x, float y, int color, boolean dropShadow) {
        texts.add(text);
        return 0;
    }

    @Override
    public int drawString(Font font, FormattedCharSequence text, float x, float y, int color, boolean dropShadow) {
        texts.add("<seq>");
        return 0;
    }

    @Override
    public String toString() {
        return fills + " fills, texts " + texts;
    }
}
