package com.utilitysuite.core.api.visual;

/**
 * The context-morphing HUD (V6): the tactical layers (reticles, the authority bar, the Read) multiply their alpha
 * and size by these values, so they animate in when combat or a fall starts and the HUD fades while the player stands
 * still. {@link #NONE} (the module off) changes nothing: every factor is 1. Render or client thread; allocation free.
 */
public interface IHudContext {

    IHudContext NONE = new IHudContext() {
    };

    default HudContextState state() {
        return HudContextState.EXPLORING;
    }

    /** Multiplier for the whole HUD (fades toward the idle opacity while standing still). */
    default float hudAlpha() {
        return 1f;
    }

    /** Extra multiplier for the tactical layers (quiet outside combat and falls, full inside). */
    default float tacticalAlpha() {
        return 1f;
    }

    /** Size multiplier for the tactical layers (a short pop from above 1 when combat or a fall starts). */
    default float tacticalScale() {
        return 1f;
    }
}
