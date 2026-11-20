package com.utilitysuite.world.camera;

/**
 * Smooth scroll zoom: a target factor changed by scroll steps while the zoom key is held, and a displayed factor that
 * eases toward it every frame (exponential smoothing, frame-rate independent). Pure; allocation-free.
 */
public final class ZoomState {

    private double minFactor = 1.0;
    private double maxFactor = 50.0;
    private double defaultFactor = 4.0;
    private double stepRatio = 1.25;
    /** Time constant of the easing in seconds. */
    private double smoothing = 0.08;

    private boolean active;
    private double target = 1.0;
    private double current = 1.0;

    public void configure(double defaultFactor, double maxFactor, double stepRatio, double smoothingSeconds) {
        this.maxFactor = Math.max(1.0, maxFactor);
        this.defaultFactor = clamp(defaultFactor);
        this.stepRatio = Math.max(1.01, stepRatio);
        this.smoothing = Math.max(0.0, smoothingSeconds);
    }

    public void setActive(boolean on) {
        if (on && !active) {
            target = defaultFactor;
        } else if (!on && active) {
            target = 1.0;
        }
        active = on;
    }

    public boolean active() {
        return active;
    }

    /** Scroll while zooming: positive zooms in. */
    public void scroll(double delta) {
        if (!active || delta == 0) {
            return;
        }
        target = clamp(delta > 0 ? target * stepRatio : target / stepRatio);
    }

    /** Advances the easing by {@code dtSeconds}. */
    public void update(double dtSeconds) {
        if (smoothing <= 0.0) {
            current = target;
            return;
        }
        double a = 1.0 - Math.exp(-Math.max(0.0, dtSeconds) / smoothing);
        current += (target - current) * a;
        if (Math.abs(current - target) < 1e-3) {
            current = target;
        }
    }

    /** Current factor (1 = no zoom). */
    public double factor() {
        return current;
    }

    public double target() {
        return target;
    }

    /** True while zooming or still easing back out. */
    public boolean affectsView() {
        return active || current > 1.0 + 1e-3;
    }

    public double apply(double fov) {
        return fov / current;
    }

    public void reset() {
        active = false;
        target = 1.0;
        current = 1.0;
    }

    private double clamp(double f) {
        return Math.max(minFactor, Math.min(maxFactor, f));
    }
}
