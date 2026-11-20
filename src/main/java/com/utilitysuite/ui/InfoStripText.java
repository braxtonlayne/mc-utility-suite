package com.utilitysuite.ui;

/**
 * Engine-agnostic text cache for the {@link InfoStrip}: keeps the last integer inputs of every segment and rebuilds a
 * segment's string (through one reused {@link StringBuilder}) only when those integers change. Steady-state updates
 * with unchanged values allocate nothing. {@link #version()} increments on every rebuild so the renderer can
 * re-measure widths only then.
 */
public final class InfoStripText {

    /** Dimension codes for {@link #setConversion}. */
    public static final int DIM_OVERWORLD = 0;
    public static final int DIM_NETHER = 1;
    public static final int DIM_OTHER = 2;

    /** Durability colour endpoints (green, yellow, red). */
    static final int GREEN = 0xFF22C55E;
    static final int YELLOW = 0xFFEAB308;
    static final int RED = 0xFFEF4444;

    private final StringBuilder sb = new StringBuilder(48);
    private int version;

    private int cx = Integer.MIN_VALUE, cy = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
    private String coords = "";

    private int convDim = -1, convX = Integer.MIN_VALUE, convZ = Integer.MIN_VALUE;
    private String conversion = "";

    private Object biomeKey;
    private String biome = "";

    private int tpsTenths = -1;
    private String tps = "";

    private int pingMs = Integer.MIN_VALUE;
    private String ping = "";

    /** Monotonic counter, bumped whenever any segment string was rebuilt. */
    public int version() {
        return version;
    }

    public String coords() {
        return coords;
    }

    public String conversion() {
        return conversion;
    }

    public String biome() {
        return biome;
    }

    public String tps() {
        return tps;
    }

    public String ping() {
        return ping;
    }

    /** {@code X 12 Y 64 Z -40}. */
    public void setCoords(int x, int y, int z) {
        if (x == cx && y == cy && z == cz) {
            return;
        }
        cx = x;
        cy = y;
        cz = z;
        sb.setLength(0);
        sb.append("X ").append(x).append("  Y ").append(y).append("  Z ").append(z);
        coords = sb.toString();
        version++;
    }

    /**
     * Overworld shows the Nether equivalent ({@code x/8, z/8}), the Nether shows the Overworld equivalent
     * ({@code x*8, z*8}), anything else shows nothing. Inputs are the player's block coordinates.
     */
    public void setConversion(int dim, int x, int z) {
        int ox;
        int oz;
        if (dim == DIM_OVERWORLD) {
            ox = Math.floorDiv(x, 8);
            oz = Math.floorDiv(z, 8);
        } else if (dim == DIM_NETHER) {
            ox = x * 8;
            oz = z * 8;
        } else {
            ox = 0;
            oz = 0;
        }
        if (dim == convDim && ox == convX && oz == convZ) {
            return;
        }
        convDim = dim;
        convX = ox;
        convZ = oz;
        if (dim == DIM_OVERWORLD || dim == DIM_NETHER) {
            sb.setLength(0);
            sb.append(dim == DIM_OVERWORLD ? "Nether " : "Overworld ").append(ox).append(", ").append(oz);
            conversion = sb.toString();
        } else {
            conversion = "";
        }
        version++;
    }

    /** True when {@code key} is the biome currently cached (identity). */
    public boolean isBiome(Object key) {
        return key == biomeKey;
    }

    /**
     * Biome by identity: {@code key} is any stable object for the biome (the registry holder); {@code id} (e.g.
     * {@code minecraft:dark_forest}) is only read when the key changed. Pass {@code null}/{@code null} for none.
     */
    public void setBiome(Object key, String id) {
        if (key == biomeKey) {
            return;
        }
        biomeKey = key;
        biome = id == null ? "" : prettyBiome(id);
        version++;
    }

    /** {@code minecraft:dark_forest} -> {@code Dark Forest}. */
    public static String prettyBiome(String id) {
        int colon = id.indexOf(':');
        StringBuilder b = new StringBuilder(id.length());
        boolean upper = true;
        for (int i = colon + 1; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '_' || c == '/') {
                b.append(' ');
                upper = true;
            } else {
                b.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return b.toString();
    }

    /** TPS in tenths (e.g. 198 = 19.8), clamped to 0..200. */
    public void setTpsTenths(int tenths) {
        int t = Math.max(0, Math.min(200, tenths));
        if (t == tpsTenths) {
            return;
        }
        tpsTenths = t;
        sb.setLength(0);
        sb.append(t / 10).append('.').append(t % 10).append(" TPS");
        tps = sb.toString();
        version++;
    }

    /** Ping in milliseconds; negative clears it. */
    public void setPing(int ms) {
        int m = Math.max(-1, ms);
        if (m == pingMs) {
            return;
        }
        pingMs = m;
        if (m < 0) {
            ping = "";
        } else {
            sb.setLength(0);
            sb.append(m).append(" ms");
            ping = sb.toString();
        }
        version++;
    }

    /** Forgets every cached value (world change). */
    public void reset() {
        cx = cy = cz = Integer.MIN_VALUE;
        convDim = -1;
        convX = convZ = Integer.MIN_VALUE;
        biomeKey = null;
        tpsTenths = -1;
        pingMs = Integer.MIN_VALUE;
        coords = conversion = biome = tps = ping = "";
        version++;
    }

    // ---- durability -------------------------------------------------------------------------------------------------

    /** Filled pixels of a bar {@code width} wide for {@code damage} out of {@code maxDamage} (at least 1 if not broken). */
    public static int barFill(int damage, int maxDamage, int width) {
        if (maxDamage <= 0) {
            return 0;
        }
        int left = Math.max(0, maxDamage - damage);
        int px = (int) ((long) left * width / maxDamage);
        return left > 0 ? Math.max(1, px) : 0;
    }

    /** Green (full) through yellow to red (broken) for the remaining fraction {@code left/max}. */
    public static int barColor(int damage, int maxDamage) {
        if (maxDamage <= 0) {
            return GREEN;
        }
        float f = Math.max(0f, Math.min(1f, (maxDamage - damage) / (float) maxDamage));
        return f >= 0.5f ? lerp(YELLOW, GREEN, (f - 0.5f) * 2f) : lerp(RED, YELLOW, f * 2f);
    }

    static int lerp(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ---- TPS estimate -----------------------------------------------------------------------------------------------

    /**
     * Smoothed TPS from level game-time deltas against wall-clock time. Feed {@link #sample} once per client tick; it
     * evaluates a window every {@code windowNanos} and blends it into an exponential moving average.
     */
    public static final class TpsEstimator {
        private final long windowNanos;
        private long startNanos = Long.MIN_VALUE;
        private long startGameTime;
        private double smoothed = 20.0;
        private boolean primed;

        public TpsEstimator(long windowNanos) {
            this.windowNanos = windowNanos;
        }

        public void sample(long gameTime, long nowNanos) {
            if (startNanos == Long.MIN_VALUE || gameTime < startGameTime) {
                startNanos = nowNanos;
                startGameTime = gameTime;
                return;
            }
            long dt = nowNanos - startNanos;
            if (dt < windowNanos) {
                return;
            }
            double tps = Math.min(20.0, (gameTime - startGameTime) * 1e9 / dt);
            smoothed = primed ? smoothed * 0.6 + tps * 0.4 : tps;
            primed = true;
            startNanos = nowNanos;
            startGameTime = gameTime;
        }

        public int tenths() {
            return (int) Math.round(smoothed * 10.0);
        }

        public void reset() {
            startNanos = Long.MIN_VALUE;
            smoothed = 20.0;
            primed = false;
        }
    }
}
