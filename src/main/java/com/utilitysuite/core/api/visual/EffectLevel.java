package com.utilitysuite.core.api.visual;

/** How much decoration the Shell draws. OFF is a fully supported flat look, not a degraded one. */
public enum EffectLevel {
    /** Flat plates, plain 1 px lines, no glow, scanlines, sweeps or ribbons. */
    OFF,
    /** Glow and soft ribbons; no scanlines, sweeps or flicker. */
    SUBTLE,
    FULL
}
