package com.utilitysuite.world.replay;

import java.util.function.Predicate;

/**
 * Engine-free decisions of the picture-in-picture panel: which render mode actually runs (the full second world
 * render falls back to the wireframe when another renderer owns the level render, with Fabulous graphics, or after
 * the pass failed once) and how big the offscreen target is. Allocation free.
 */
public final class PipRenderPolicy {

    /** What the panel shows. OFF hides it while the module stays on (the park key still works). */
    public enum RenderMode { OFF, WIREFRAME, FULL }

    /** Why a requested full render is showing the wireframe instead. */
    public enum Fallback { NONE, RENDERER_MOD, FABULOUS, FAILED }

    /**
     * Mod ids whose renderers replace vanilla's level renderer internals (section graph, render lists, shader
     * pipeline); a second {@code LevelRenderer.renderLevel} with them loaded is unsupported, so full render is off.
     */
    static final String[] INCOMPATIBLE_MODS = {"sodium", "embeddium", "iris", "oculus"};

    public static final int MIN_TARGET_W = 64;
    public static final int MAX_TARGET_W = 1280;

    private PipRenderPolicy() {
    }

    /** True when any known incompatible renderer mod is loaded. */
    public static boolean anyIncompatible(Predicate<String> isLoaded) {
        for (String id : INCOMPATIBLE_MODS) {
            if (isLoaded.test(id)) {
                return true;
            }
        }
        return false;
    }

    public static Fallback fallback(RenderMode requested, boolean rendererMod, boolean fabulous, boolean failed) {
        if (requested != RenderMode.FULL) {
            return Fallback.NONE;
        }
        if (rendererMod) {
            return Fallback.RENDERER_MOD;
        }
        if (failed) {
            return Fallback.FAILED;
        }
        return fabulous ? Fallback.FABULOUS : Fallback.NONE;
    }

    /** The mode that actually draws. */
    public static RenderMode effective(RenderMode requested, boolean rendererMod, boolean fabulous, boolean failed) {
        if (requested == RenderMode.FULL && fallback(requested, rendererMod, fabulous, failed) != Fallback.NONE) {
            return RenderMode.WIREFRAME;
        }
        return requested;
    }

    /**
     * Offscreen target width in pixels: the panel's on-screen pixel width times the resolution percentage, clamped.
     * @param panelGuiWidth panel width in GUI pixels
     * @param guiScale      window GUI scale (screen pixels per GUI pixel)
     * @param percent       resolution setting, 10..100
     */
    public static int targetWidth(int panelGuiWidth, double guiScale, int percent) {
        double px = panelGuiWidth * Math.max(1.0, guiScale) * Math.max(10, Math.min(100, percent)) / 100.0;
        return (int) Math.max(MIN_TARGET_W, Math.min(MAX_TARGET_W, Math.round(px)));
    }

    /** 16:9 height for a target width. */
    public static int targetHeight(int width) {
        return Math.max(36, width * 9 / 16);
    }
}
