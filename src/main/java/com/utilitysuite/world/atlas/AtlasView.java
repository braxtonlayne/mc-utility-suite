package com.utilitysuite.world.atlas;

/**
 * Engine-free 2D camera of the World Atlas: a world point (x east, z south) at the centre of a {@code width x height}
 * pixel viewport, drawn at {@code scale} pixels per block. North is up. Pan by dragging, zoom about the cursor (the
 * world point under the cursor stays put). Mutable, allocation free.
 */
public final class AtlasView {

    public static final double MIN_SCALE = 1.0 / 16.0;
    public static final double MAX_SCALE = 8.0;

    private double centerX;
    private double centerZ;
    private double scale = 1.0;
    private int width = 1;
    private int height = 1;

    public void setViewport(int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
    }

    public void center(double x, double z) {
        centerX = x;
        centerZ = z;
    }

    public void setScale(double s) {
        scale = clampScale(s);
    }

    public static double clampScale(double s) {
        return s < MIN_SCALE ? MIN_SCALE : Math.min(MAX_SCALE, s);
    }

    public double centerX() {
        return centerX;
    }

    public double centerZ() {
        return centerZ;
    }

    public double scale() {
        return scale;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public double toScreenX(double worldX) {
        return (worldX - centerX) * scale + width * 0.5;
    }

    public double toScreenY(double worldZ) {
        return (worldZ - centerZ) * scale + height * 0.5;
    }

    public double toWorldX(double screenX) {
        return (screenX - width * 0.5) / scale + centerX;
    }

    public double toWorldZ(double screenY) {
        return (screenY - height * 0.5) / scale + centerZ;
    }

    /** Drag by a pixel delta: the map follows the mouse. */
    public void pan(double dxPixels, double dyPixels) {
        centerX -= dxPixels / scale;
        centerZ -= dyPixels / scale;
    }

    /** Multiplies the scale by {@code factor} (clamped), keeping the world point under (px, py) fixed. */
    public void zoomAt(double px, double py, double factor) {
        double wx = toWorldX(px);
        double wz = toWorldZ(py);
        scale = clampScale(scale * factor);
        centerX = wx - (px - width * 0.5) / scale;
        centerZ = wz - (py - height * 0.5) / scale;
    }

    /** Visible world bounds (inclusive min, exclusive max), for culling. */
    public double minWorldX() {
        return toWorldX(0);
    }

    public double maxWorldX() {
        return toWorldX(width);
    }

    public double minWorldZ() {
        return toWorldZ(0);
    }

    public double maxWorldZ() {
        return toWorldZ(height);
    }
}
