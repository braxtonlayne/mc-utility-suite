package com.utilitysuite.combat.tactics;

/**
 * Finds wind-charge burst spots around the player (engine free, allocation free after construction): every exposed
 * floor face (+Y) and wall face (the four sides) of a solid block whose burst point lies inside the burst radius of the
 * feet, with the launch {@link KineticChainPlanner} predicts for it. Ceiling faces are skipped (they push down).
 * Results are kept in primitive arrays, highest apex first is not guaranteed; {@link #best} is the highest.
 */
public final class KineticSpotScanner {

    /** Solid-block view of the world. Must not allocate. */
    @FunctionalInterface
    public interface Solid {
        boolean solid(int x, int y, int z);
    }

    /** Face normals: floor, then east, west, south, north. */
    static final int[] NX = {0, 1, -1, 0, 0};
    static final int[] NY = {1, 0, 0, 0, 0};
    static final int[] NZ = {0, 0, 0, 1, -1};
    static final int SCAN = 3;

    public final int capacity;
    public final double[] bx, by, bz, apex;
    public final int[] face;
    public int count;
    /** Index of the highest-apex spot, -1 when none. */
    public int best = -1;
    private final KineticChainPlanner.Launch launch = new KineticChainPlanner.Launch();

    public KineticSpotScanner(int capacity) {
        this.capacity = capacity;
        bx = new double[capacity];
        by = new double[capacity];
        bz = new double[capacity];
        apex = new double[capacity];
        face = new int[capacity];
    }

    public int scan(double px, double py, double pz, double vx, double vy, double vz, Solid world) {
        count = 0;
        best = -1;
        int fx = (int) Math.floor(px), fy = (int) Math.floor(py), fz = (int) Math.floor(pz);
        for (int y = fy - SCAN; y <= fy + SCAN; y++) {
            for (int x = fx - SCAN; x <= fx + SCAN; x++) {
                for (int z = fz - SCAN; z <= fz + SCAN; z++) {
                    if (!world.solid(x, y, z)) {
                        continue;
                    }
                    for (int f = 0; f < NX.length && count < capacity; f++) {
                        if (world.solid(x + NX[f], y + NY[f], z + NZ[f])) {
                            continue; // covered face
                        }
                        double sx = KineticChainPlanner.faceCoord(x, NX[f]);
                        double sy = KineticChainPlanner.faceCoord(y, NY[f]);
                        double sz = KineticChainPlanner.faceCoord(z, NZ[f]);
                        KineticChainPlanner.evaluate(launch, px, py, pz, vx, vy, vz, sx, sy, sz);
                        if (!launch.inRange || launch.ky <= 0) {
                            continue;
                        }
                        bx[count] = sx;
                        by[count] = sy;
                        bz[count] = sz;
                        apex[count] = launch.apex;
                        face[count] = f;
                        if (best < 0 || launch.apex > apex[best]) {
                            best = count;
                        }
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Index of the spot on block (x, y, z) face {@code f}, or -1. */
    public int find(int x, int y, int z, int f) {
        double sx = KineticChainPlanner.faceCoord(x, NX[f]);
        double sy = KineticChainPlanner.faceCoord(y, NY[f]);
        double sz = KineticChainPlanner.faceCoord(z, NZ[f]);
        for (int i = 0; i < count; i++) {
            if (face[i] == f && bx[i] == sx && by[i] == sy && bz[i] == sz) {
                return i;
            }
        }
        return -1;
    }

    /** Face index for a block-hit direction (dx, dy, dz unit normal), -1 for a ceiling or unknown. */
    public static int faceOf(int dx, int dy, int dz) {
        for (int f = 0; f < NX.length; f++) {
            if (NX[f] == dx && NY[f] == dy && NZ[f] == dz) {
                return f;
            }
        }
        return -1;
    }

    public static int normalX(int f) {
        return NX[f];
    }

    public static int normalY(int f) {
        return NY[f];
    }

    public static int normalZ(int f) {
        return NZ[f];
    }
}
