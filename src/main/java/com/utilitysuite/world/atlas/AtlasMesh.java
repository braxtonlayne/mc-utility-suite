package com.utilitysuite.world.atlas;

/**
 * Engine-free coarse height grid for the 3D hologram: {@code (cells+1)^2} corner samples spaced {@code step} blocks
 * apart around a centre block, with the surface colour of each corner. At the default 48 cells that is 2304 quads.
 * {@link #rebuildIfNeeded} rebuilds only when the store version, centre or step changed; the arrays are allocated once.
 * Missing columns take the nearest known height (or the centre height) and a dim colour, so unexplored ground reads as
 * a flat dark plate rather than holes.
 */
public final class AtlasMesh {

    public final int cells;
    public final int side;
    public final float[] height;
    public final int[] argb;
    public final boolean[] known;
    private int builtVersion = -1;
    private int builtCx = Integer.MIN_VALUE;
    private int builtCz = Integer.MIN_VALUE;
    private int builtStep;
    private int step = 1;
    private int centerX;
    private int centerZ;
    private float baseHeight;
    private int rebuilds;

    public AtlasMesh(int cells) {
        this.cells = cells;
        this.side = cells + 1;
        height = new float[side * side];
        argb = new int[side * side];
        known = new boolean[side * side];
    }

    public int step() {
        return step;
    }

    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    public float baseHeight() {
        return baseHeight;
    }

    public int rebuilds() {
        return rebuilds;
    }

    public int index(int ix, int iz) {
        return iz * side + ix;
    }

    /** World block x of grid column {@code ix}. */
    public int blockX(int ix) {
        return centerX + (ix - cells / 2) * step;
    }

    public int blockZ(int iz) {
        return centerZ + (iz - cells / 2) * step;
    }

    /**
     * @param cx         centre block x (snapped to the step so small moves do not rebuild)
     * @param cz         centre block z
     * @param newStep    blocks per cell (>= 1)
     * @param fallbackY  height used where nothing is known (the player's feet)
     * @param emptyArgb  colour of unknown corners
     * @return true when the grid was rebuilt
     */
    public boolean rebuildIfNeeded(AtlasTileStore store, int cx, int cz, int newStep, int fallbackY, int emptyArgb) {
        int s = Math.max(1, newStep);
        int scx = Math.floorDiv(cx, s) * s;
        int scz = Math.floorDiv(cz, s) * s;
        if (store.version() == builtVersion && scx == builtCx && scz == builtCz && s == builtStep) {
            return false;
        }
        builtVersion = store.version();
        builtCx = scx;
        builtCz = scz;
        builtStep = s;
        step = s;
        centerX = scx;
        centerZ = scz;
        baseHeight = fallbackY;
        for (int iz = 0; iz < side; iz++) {
            int bz = blockZ(iz);
            for (int ix = 0; ix < side; ix++) {
                int bx = blockX(ix);
                int i = index(ix, iz);
                int h = store.heightAt(bx, bz);
                if (h == AtlasTileStore.NO_HEIGHT) {
                    known[i] = false;
                    height[i] = Float.NaN;
                    argb[i] = emptyArgb;
                } else {
                    known[i] = true;
                    height[i] = h;
                    argb[i] = store.colorAt(bx, bz, emptyArgb);
                }
            }
        }
        // Fill unknown corners from a known neighbour (row-wise sweep, then column-wise), else the fallback.
        for (int iz = 0; iz < side; iz++) {
            float last = Float.NaN;
            for (int ix = 0; ix < side; ix++) {
                int i = index(ix, iz);
                if (known[i]) {
                    last = height[i];
                } else if (!Float.isNaN(last)) {
                    height[i] = last;
                }
            }
        }
        for (int i = 0; i < height.length; i++) {
            if (Float.isNaN(height[i])) {
                height[i] = fallbackY;
            }
        }
        rebuilds++;
        return true;
    }
}
