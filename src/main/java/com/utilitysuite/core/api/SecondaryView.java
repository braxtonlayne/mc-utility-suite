package com.utilitysuite.core.api;

/**
 * Set while the suite renders the level a second time from another camera (the picture-in-picture full render).
 * World-render hooks read it to stay out of that pass: render-stage events are not dispatched during it, and
 * per-frame camera listeners (freelook) leave the second camera alone. Plain static field: written and read only on
 * the render thread.
 */
public final class SecondaryView {

    private static boolean active;

    private SecondaryView() {
    }

    public static boolean active() {
        return active;
    }

    public static void begin() {
        active = true;
    }

    public static void end() {
        active = false;
    }
}
