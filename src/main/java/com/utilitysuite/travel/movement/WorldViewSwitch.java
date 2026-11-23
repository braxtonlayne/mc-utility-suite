package com.utilitysuite.travel.movement;

/**
 * A {@link MovementPhysics.Voxels} that answers from one of two views: the full-cube view (the default, today's combat
 * and pursuit prediction) or the exact-shape view Movement Assist plans on. The "Exact world shapes for combat
 * prediction" setting flips it. Engine-free, so tests can put any two grids behind it. Allocation free.
 */
public final class WorldViewSwitch implements MovementPhysics.Voxels {

    private final MovementPhysics.Voxels cubes;
    private final MovementPhysics.Voxels exact;
    private boolean useExact;

    public WorldViewSwitch(MovementPhysics.Voxels cubes, MovementPhysics.Voxels exact) {
        this.cubes = cubes;
        this.exact = exact;
    }

    /** True = answer from the exact view (step-up, 1.5-high fence tops, slipperiness); false = full cubes. */
    public void useExact(boolean on) {
        useExact = on;
    }

    public boolean usingExact() {
        return useExact;
    }

    private MovementPhysics.Voxels view() {
        return useExact ? exact : cubes;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return view().solid(x, y, z);
    }

    @Override
    public boolean fullBlock(int x, int y, int z) {
        return view().fullBlock(x, y, z);
    }

    @Override
    public boolean shaped() {
        return view().shaped();
    }

    @Override
    public int boxCount(int x, int y, int z) {
        return view().boxCount(x, y, z);
    }

    @Override
    public void box(int x, int y, int z, int i, float[] out, int off) {
        view().box(x, y, z, i, out, off);
    }

    @Override
    public byte surface(int x, int y, int z) {
        return view().surface(x, y, z);
    }

    @Override
    public float slipperiness(int x, int y, int z) {
        return view().slipperiness(x, y, z);
    }
}
