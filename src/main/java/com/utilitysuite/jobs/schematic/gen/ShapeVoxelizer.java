package com.utilitysuite.jobs.schematic.gen;

import com.utilitysuite.jobs.schematic.model.BlockStateString;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;

/**
 * Engine-free parametric shape generator: voxelises a {@link Params} into a {@link SchematicVolume} whose cells outside
 * the shape are void (the build does not care about them). The volume's offset places the shape relative to the
 * anchor: sphere and torus are centred on it, dome, cylinder and arch stand on it, a bridge starts at it.
 * <p>
 * Hollow shells ({@code thickness > 0}) are cut by 6-connected distance to the outside, so a shell of thickness 1 is
 * watertight (every solid cell next to empty space is kept). A dome has no floor and a cylinder no caps when hollow.
 * Runs on the schematic thread; the cost is one pass over the bounding box.
 */
public final class ShapeVoxelizer {

    public enum Kind { SPHERE, DOME, CYLINDER, ARCH, BRIDGE, TORUS }

    /** Largest radius / height / span accepted (keeps the box well under {@link SchematicVolume#MAX_CELLS}). */
    public static final int MAX_DIM = 96;

    /**
     * @param radius    sphere/dome/cylinder radius, arch half-span, torus major radius
     * @param height    cylinder height, arch leg height, torus tube radius (ignored by sphere/dome/bridge)
     * @param thickness 0 = solid; otherwise shell thickness in blocks (arch ring thickness, bridge deck layers)
     * @param endX      bridge end relative to the start (also endY, endZ)
     * @param width     bridge deck width, arch depth
     */
    public record Params(Kind kind, int radius, int height, int thickness, String material, int endX, int endY,
                         int endZ, int width) {
        public Params {
            radius = clamp(radius, 1, MAX_DIM);
            height = clamp(height, 1, MAX_DIM);
            thickness = clamp(thickness, 0, MAX_DIM);
            width = clamp(width, 1, 16);
            endX = clamp(endX, -MAX_DIM * 2, MAX_DIM * 2);
            endY = clamp(endY, -MAX_DIM, MAX_DIM);
            endZ = clamp(endZ, -MAX_DIM * 2, MAX_DIM * 2);
            material = material == null || material.isBlank() ? "minecraft:stone" : BlockStateString.canonical(material);
        }

        public static Params of(Kind kind, int radius, int height, int thickness, String material) {
            return new Params(kind, radius, height, thickness, material, 0, 0, 0, 3);
        }

        /** Short display name, e.g. {@code sphere_r8_hollow1}. */
        public String label() {
            String base = kind.name().toLowerCase(java.util.Locale.ROOT);
            String dims = switch (kind) {
                case BRIDGE -> "_" + endX + "_" + endY + "_" + endZ + "_w" + width;
                case CYLINDER, ARCH, TORUS -> "_r" + radius + "_h" + height;
                default -> "_r" + radius;
            };
            return base + dims + (thickness > 0 ? "_t" + thickness : "_solid");
        }
    }

    private ShapeVoxelizer() {
    }

    public static SchematicVolume voxelize(Params p) {
        return switch (p.kind()) {
            case SPHERE -> radial(p, false);
            case DOME -> radial(p, true);
            case CYLINDER -> cylinder(p);
            case ARCH -> arch(p);
            case BRIDGE -> bridge(p);
            case TORUS -> torus(p);
        };
    }

    // ---- shapes -----------------------------------------------------------------------------------------------------

    private static SchematicVolume radial(Params p, boolean dome) {
        int r = p.radius();
        int sx = 2 * r + 1, sy = dome ? r + 1 : 2 * r + 1, sz = 2 * r + 1;
        boolean[] solid = new boolean[sx * sy * sz];
        double lim = (r + 0.5) * (r + 0.5);
        int y0 = dome ? 0 : -r;
        for (int y = 0; y < sy; y++) {
            int dy = y + y0;
            for (int z = 0; z < sz; z++) {
                int dz = z - r;
                for (int x = 0; x < sx; x++) {
                    int dx = x - r;
                    solid[(y * sz + z) * sx + x] = dx * dx + dy * dy + dz * dz <= lim;
                }
            }
        }
        if (p.thickness() > 0) {
            shell(solid, sx, sy, sz, p.thickness(), dome, false);
        }
        return toVolume(p, solid, sx, sy, sz, -r, dome ? 0 : -r, -r);
    }

    private static SchematicVolume cylinder(Params p) {
        int r = p.radius(), h = p.height();
        int sx = 2 * r + 1, sz = 2 * r + 1;
        boolean[] solid = new boolean[sx * h * sz];
        double lim = (r + 0.5) * (r + 0.5);
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int dx = x - r, dz = z - r;
                    solid[(y * sz + z) * sx + x] = dx * dx + dz * dz <= lim;
                }
            }
        }
        if (p.thickness() > 0) {
            shell(solid, sx, h, sz, p.thickness(), true, true);
        }
        return toVolume(p, solid, sx, h, sz, -r, 0, -r);
    }

    /** Semicircular ring of the given thickness on two legs, spanning X, {@code width} deep in Z. */
    private static SchematicVolume arch(Params p) {
        int r = p.radius(), legs = p.height(), t = p.thickness() <= 0 ? 2 : Math.min(p.thickness(), r);
        int depth = p.width();
        int sx = 2 * r + 1, sy = legs + r + 1, sz = depth;
        boolean[] solid = new boolean[sx * sy * sz];
        double outer = (r + 0.5) * (r + 0.5), inner = (r + 0.5 - t) * (r + 0.5 - t);
        for (int y = 0; y < sy; y++) {
            for (int x = 0; x < sx; x++) {
                int dx = x - r;
                boolean in;
                if (y < legs) {
                    in = Math.abs(dx) > r - t;
                } else {
                    int dy = y - legs;
                    double d = dx * dx + dy * dy;
                    in = d <= outer && d > inner;
                }
                if (in) {
                    for (int z = 0; z < sz; z++) {
                        solid[(y * sz + z) * sx + x] = true;
                    }
                }
            }
        }
        return toVolume(p, solid, sx, sy, sz, -r, 0, -(depth / 2));
    }

    private static SchematicVolume torus(Params p) {
        int big = p.radius(), tube = Math.min(p.height(), big);
        int ext = big + tube;
        int sx = 2 * ext + 1, sy = 2 * tube + 1, sz = 2 * ext + 1;
        boolean[] solid = new boolean[sx * sy * sz];
        double lim = (tube + 0.5) * (tube + 0.5);
        for (int y = 0; y < sy; y++) {
            int dy = y - tube;
            for (int z = 0; z < sz; z++) {
                int dz = z - ext;
                for (int x = 0; x < sx; x++) {
                    int dx = x - ext;
                    double ring = Math.sqrt((double) dx * dx + (double) dz * dz) - big;
                    solid[(y * sz + z) * sx + x] = ring * ring + dy * dy <= lim;
                }
            }
        }
        if (p.thickness() > 0) {
            shell(solid, sx, sy, sz, p.thickness(), false, false);
        }
        return toVolume(p, solid, sx, sy, sz, -ext, -tube, -ext);
    }

    /** Straight sloped deck from (0,0,0) to (endX,endY,endZ), {@code width} wide, {@code max(1,thickness)} thick. */
    private static SchematicVolume bridge(Params p) {
        int ex = p.endX(), ey = p.endY(), ez = p.endZ(), w = p.width(), layers = Math.max(1, p.thickness());
        int len = Math.max(Math.abs(ex), Math.abs(ez));
        int half = (w - 1) / 2;
        boolean alongX = Math.abs(ex) >= Math.abs(ez);
        int minX = Math.min(0, ex) - (alongX ? 0 : half), maxX = Math.max(0, ex) + (alongX ? 0 : w - 1 - half);
        int minZ = Math.min(0, ez) - (alongX ? half : 0), maxZ = Math.max(0, ez) + (alongX ? w - 1 - half : 0);
        int minY = Math.min(0, ey) - (layers - 1), maxY = Math.max(0, ey);
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        boolean[] solid = new boolean[sx * sy * sz];
        int prevY = 0;
        for (int i = 0; i <= len; i++) {
            double f = len == 0 ? 0 : (double) i / len;
            int cx = (int) Math.round(ex * f), cz = (int) Math.round(ez * f), cy = (int) Math.round(ey * f);
            // top surface at cy; a step down from the previous column also fills the riser, so the deck has no gaps
            int bottom = (i == 0 ? cy : Math.min(prevY, cy)) - (layers - 1);
            prevY = cy;
            for (int k = -half; k < w - half; k++) {
                int x = cx + (alongX ? 0 : k), z = cz + (alongX ? k : 0);
                for (int y = bottom; y <= cy; y++) {
                    solid[((y - minY) * sz + (z - minZ)) * sx + (x - minX)] = true;
                }
            }
        }
        return toVolume(p, solid, sx, sy, sz, minX, minY, minZ);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /**
     * Keeps only solid cells within {@code t} 6-connected steps of an empty cell (or of the box edge). Box faces with
     * {@code openBottom}/{@code openTop} count as solid continuation (no floor / no caps).
     */
    static void shell(boolean[] solid, int sx, int sy, int sz, int t, boolean openBottom, boolean openTop) {
        int n = solid.length;
        int[] dist = new int[n];
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            if (!solid[i]) {
                dist[i] = 0;
                continue;
            }
            dist[i] = Integer.MAX_VALUE;
            int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
            boolean edge = x == 0 || x == sx - 1 || z == 0 || z == sz - 1 || (y == 0 && !openBottom)
                    || (y == sy - 1 && !openTop) || emptyNeighbour(solid, x, y, z, sx, sy, sz);
            if (edge) {
                dist[i] = 1;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int d = dist[i];
            if (d >= t) {
                continue;
            }
            int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
            for (int k = 0; k < 6; k++) {
                int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0);
                int ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
                int nz = z + (k == 4 ? 1 : k == 5 ? -1 : 0);
                if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) {
                    continue;
                }
                int j = (ny * sz + nz) * sx + nx;
                if (solid[j] && dist[j] > d + 1) {
                    dist[j] = d + 1;
                    queue[tail++] = j;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (solid[i] && dist[i] > t) {
                solid[i] = false;
            }
        }
    }

    private static boolean emptyNeighbour(boolean[] s, int x, int y, int z, int sx, int sy, int sz) {
        return (x + 1 < sx && !s[(y * sz + z) * sx + x + 1]) || (x > 0 && !s[(y * sz + z) * sx + x - 1])
                || (y + 1 < sy && !s[((y + 1) * sz + z) * sx + x]) || (y > 0 && !s[((y - 1) * sz + z) * sx + x])
                || (z + 1 < sz && !s[(y * sz + z + 1) * sx + x]) || (z > 0 && !s[(y * sz + z - 1) * sx + x]);
    }

    private static SchematicVolume toVolume(Params p, boolean[] solid, int sx, int sy, int sz, int ox, int oy,
                                            int oz) {
        SchematicVolume.Builder b = SchematicVolume.builder(p.label(), "generated", sx, sy, sz).offset(ox, oy, oz);
        int id = b.paletteId(p.material());
        for (int i = 0; i < solid.length; i++) {
            if (solid[i]) {
                b.setId(i % sx, i / (sx * sz), (i / sx) % sz, id);
            }
        }
        return b.build();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
