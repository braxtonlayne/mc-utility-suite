package com.utilitysuite.tools.packetlab;

/**
 * Fixed-capacity primitive ring buffer for 3D acoustic pings and global directional bearings.
 * <p>
 * Pure Java, zero Minecraft imports, zero per-ping allocations, and thread-safe via tiny synchronized blocks.
 */
public final class AcousticRadar {

    public static final int CONTAINER = 0;
    public static final int PORTAL = 1;
    public static final int EXPLOSION = 2;
    public static final int PISTON = 3;
    public static final int PLAYER = 4;
    public static final int OTHER = 5;
    public static final int CATEGORY_COUNT = 6;

    public static final int DEFAULT_CAPACITY = 256;

    private static final String[] CATEGORY_NAMES = {
            "Container", "Portal", "Explosion", "Piston", "Player", "Sound"
    };

    private static final int[] CATEGORY_COLORS = {
            0xFFE0A040, // CONTAINER: amber
            0xFFB050FF, // PORTAL: purple
            0xFFFF4040, // EXPLOSION: red
            0xFF60C0FF, // PISTON: cyan
            0xFFFFFFFF, // PLAYER: white
            0xFFA0E080  // OTHER: soft green
    };

    @FunctionalInterface
    public interface Visitor {
        void visit(boolean global, double x, double y, double z, double dx, double dz, long timestampMs, int categoryId);
    }

    private final int capacity;
    private final boolean[] globalFlag;
    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    private final double[] dxs;
    private final double[] dzs;
    private final long[] timestamps;
    private final int[] categories;

    private int head;
    private int size;

    public AcousticRadar() {
        this(DEFAULT_CAPACITY);
    }

    public AcousticRadar(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.globalFlag = new boolean[capacity];
        this.xs = new double[capacity];
        this.ys = new double[capacity];
        this.zs = new double[capacity];
        this.dxs = new double[capacity];
        this.dzs = new double[capacity];
        this.timestamps = new long[capacity];
        this.categories = new int[capacity];
    }

    public synchronized void recordSound(double x, double y, double z, long timestampMs, int categoryId) {
        int idx = head;
        globalFlag[idx] = false;
        xs[idx] = x;
        ys[idx] = y;
        zs[idx] = z;
        dxs[idx] = 0.0;
        dzs[idx] = 0.0;
        timestamps[idx] = timestampMs;
        categories[idx] = clampCategory(categoryId);
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
    }

    public synchronized void recordGlobalBearing(double dx, double dz, long timestampMs, int categoryId) {
        double len = Math.sqrt(dx * dx + dz * dz);
        double ndx = len > 1.0e-6 ? dx / len : 0.0;
        double ndz = len > 1.0e-6 ? dz / len : 1.0;
        int idx = head;
        globalFlag[idx] = true;
        xs[idx] = 0.0;
        ys[idx] = 0.0;
        zs[idx] = 0.0;
        dxs[idx] = ndx;
        dzs[idx] = ndz;
        timestamps[idx] = timestampMs;
        categories[idx] = clampCategory(categoryId);
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
    }

    /**
     * Visits all entries whose age ({@code nowMs - timestampMs}) is in {@code [0, maxAgeMs]} from oldest to newest.
     * Allocates nothing.
     *
     * @return number of active entries visited
     */
    public synchronized int forEachActive(long nowMs, long maxAgeMs, Visitor visitor) {
        if (visitor == null || size == 0 || maxAgeMs < 0) {
            return 0;
        }
        int visited = 0;
        int start = (head - size + capacity) % capacity;
        for (int i = 0; i < size; i++) {
            int idx = (start + i) % capacity;
            long ts = timestamps[idx];
            long age = nowMs - ts;
            if (age >= 0 && age <= maxAgeMs) {
                visitor.visit(globalFlag[idx], xs[idx], ys[idx], zs[idx], dxs[idx], dzs[idx], ts, categories[idx]);
                visited++;
            }
        }
        return visited;
    }

    public synchronized void clear() {
        head = 0;
        size = 0;
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    public static String categoryName(int categoryId) {
        return CATEGORY_NAMES[clampCategory(categoryId)];
    }

    public static int categoryColor(int categoryId) {
        return CATEGORY_COLORS[clampCategory(categoryId)];
    }

    private static int clampCategory(int categoryId) {
        return (categoryId >= 0 && categoryId < CATEGORY_COUNT) ? categoryId : OTHER;
    }

    /** Maps a Minecraft sound path (e.g. {@code block.chest.open}) to an acoustic category. */
    public static int classifySound(String path) {
        if (path == null || path.isEmpty()) {
            return OTHER;
        }
        if (path.contains("chest") || path.contains("barrel") || path.contains("shulker_box")
                || path.contains("hopper") || path.contains("dispenser") || path.contains("dropper")) {
            return CONTAINER;
        }
        if (path.contains("portal")) {
            return PORTAL;
        }
        if (path.contains("explode") || path.contains("explosion") || path.contains("tnt")
                || path.contains("creeper") || path.contains("wither") || path.contains("wind_charge.burst")
                || path.contains("firework_rocket.blast") || path.contains("firework_rocket.large_blast")) {
            return EXPLOSION;
        }
        if (path.contains("piston")) {
            return PISTON;
        }
        if (path.startsWith("entity.player.") || path.contains(".player.")) {
            return PLAYER;
        }
        return OTHER;
    }

    /** Returns true if a non-global {@code ClientboundLevelEventPacket} type represents a notable acoustic event. */
    public static boolean isRelevantLevelEvent(int type) {
        return (type >= 1003 && type <= 1014) // doors, trapdoors, gates
                || type == 1023 // wither spawn
                || type == 1028 // dragon death
                || type == 1032 // portal travel
                || type == 1038; // end portal spawn
    }

    /** Maps a {@code ClientboundLevelEventPacket} type id to an acoustic category. */
    public static int classifyLevelEvent(int type) {
        if (type == 1032 || type == 1038) {
            return PORTAL;
        }
        if (type == 1023 || type == 1028) {
            return EXPLOSION;
        }
        return OTHER;
    }
}
