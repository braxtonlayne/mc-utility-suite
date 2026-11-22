package com.utilitysuite.combat.tactics;

/**
 * Budgeted read of the fight's surroundings: a {@value #SIZE} x {@value #SIZE} grid of columns (radius
 * {@value #RADIUS} blocks) around a centre, each with its floor height, headroom and floor kind near the fight's
 * height. Columns are scanned a few per tick ({@link #scan}); queries answer from whatever is scanned so far.
 * Feeds knockback vectoring (which way a hit should send the target), HIGH_GROUND and CEILING_DENY.
 * Engine free; all storage is primitive arrays allocated once; no method allocates. Client thread only.
 * <p>
 * Layout: column data lives in slots indexed toroidally by world position ({@code floorMod(x, SIZE) * SIZE +
 * floorMod(z, SIZE)}) and each slot remembers the world x/z and scan epoch it holds, so a recentre keeps every column
 * still inside the grid without moving any data. A column scan reads at most {@value #WINDOW} voxels, from
 * {@code cy + 5} down to {@code cy - 8}. Value table and budgets: {@code integration/arena-field.notes.md}.
 */
public final class ArenaField implements ArenaQuery {

    public static final int RADIUS = 16;
    public static final int SIZE = RADIUS * 2 + 1;
    /** {@link #floorY} for a column that is not scanned yet or has no floor within the vertical window. */
    public static final int NO_FLOOR = Integer.MIN_VALUE;
    /** Headroom is capped here (open blocks above the floor block; 2 = a body fits, a jump does not). */
    public static final int MAX_HEADROOM = 4;

    public static final int KNOCK_NONE = 0;
    /** A drop of at least 3 blocks. */
    public static final int KNOCK_DROP = 1;
    public static final int KNOCK_LAVA = 2;
    public static final int KNOCK_COBWEB = 3;
    /** A wall right behind the target: it cannot be knocked away, so the next hit comes sooner. */
    public static final int KNOCK_WALL = 4;

    // ---- scan window and tuning ------------------------------------------------------------------------------------

    private static final int N = SIZE * SIZE;
    private static final int WIN_UP = 5;
    private static final int WIN_DOWN = 8;
    /** Voxel lookups per column at most. */
    static final int WINDOW = WIN_UP + WIN_DOWN + 1;
    /** A vertical centre move of this much restarts the scan. */
    private static final int RESTART_DY = 3;
    /** A horizontal centre jump of more than this restarts the scan order. */
    private static final int RESTART_JUMP = 8;

    private static final int DIRS = 16;
    private static final int KNOCK_REACH = 3;
    private static final double[] DIR_X = new double[DIRS];
    private static final double[] DIR_Z = new double[DIRS];
    /** Weight of a feature 1, 2, 3 blocks behind the target. */
    private static final double[] NEAR = {0.0, 1.0, 0.75, 0.5};

    static final double V_LETHAL = 1.0;   // lava, drop of 6+ / void
    static final double V_DROP = 0.6;     // drop of 3..5
    static final double V_COBWEB = 0.4;
    static final double V_HARM = 0.35;    // fire, magma, cactus... reported as KNOCK_LAVA
    static final double V_WALL = 0.3;
    static final double V_SLOW = 0.15;    // soul sand, honey, slime floor: reported as KNOCK_COBWEB

    /** Spiral scan order: offsets sorted nearest-first, computed once. */
    private static final int[] ORDER_X = new int[N];
    private static final int[] ORDER_Z = new int[N];

    static {
        for (int k = 0; k < DIRS; k++) {
            double a = Math.PI * 2.0 * k / DIRS;
            DIR_X[k] = Math.cos(a);
            DIR_Z[k] = Math.sin(a);
        }
        long[] keys = new long[N];
        int n = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int lin = (dx + RADIUS) * SIZE + (dz + RADIUS);
                keys[n++] = ((long) (dx * dx + dz * dz) << 32) | lin;
            }
        }
        java.util.Arrays.sort(keys);
        for (int i = 0; i < N; i++) {
            int lin = (int) keys[i];
            ORDER_X[i] = lin / SIZE - RADIUS;
            ORDER_Z[i] = lin % SIZE - RADIUS;
        }
    }

    // ---- column storage (slot = toroidal index) --------------------------------------------------------------------

    private final int[] colX = new int[N];
    private final int[] colZ = new int[N];
    /** Centre y the column was scanned at (the window is colY-8..colY+5). */
    private final int[] colY = new int[N];
    private final int[] floor = new int[N];
    private final byte[] head = new byte[N];
    private final byte[] kind = new byte[N];
    /** Standable cells of the window above the floor, bit i = y colY+5-i. */
    private final short[] mask = new short[N];
    /** Scan epoch the slot belongs to; 0 = never scanned. */
    private final int[] stamp = new int[N];

    private int epoch = 1;
    private boolean centred;
    private int cx, cy, cz, baseY;
    private int scanned;
    private int cursor;
    private int refreshCursor;

    // ---- flood fill scratch (local index = (x - cx + R) * SIZE + (z - cz + R)) ------------------------------------

    private final int[] queue = new int[N];
    private final int[] visit = new int[N];
    private int visitGen;
    private final int[] queue2 = new int[N];
    private final int[] comp = new int[N];
    private final int[] compStamp = new int[N];
    private final int[] compSize = new int[N];
    private int compGen;

    private final double[] dirValue = new double[DIRS];
    private final int[] dirKind = new int[DIRS];

    private double knockX, knockZ, knockValue;
    private int knockKind;
    private double spotX, spotY, spotZ, spotGain;

    /**
     * Moves the grid centre. Columns still inside the grid keep their data; a move of more than a few blocks or a
     * vertical move of 3 or more restarts the scan from the centre outward. Cheap when the centre did not change.
     */
    public void recenter(int cx, int cy, int cz) {
        if (centred && cx == this.cx && cy == this.cy && cz == this.cz) {
            return;
        }
        boolean restart = !centred
                || Math.abs(cy - baseY) >= RESTART_DY
                || Math.abs(cx - this.cx) > RESTART_JUMP
                || Math.abs(cz - this.cz) > RESTART_JUMP;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        centred = true;
        cursor = 0;
        refreshCursor = 0;
        if (restart) {
            epoch++;
            baseY = cy;
            scanned = 0;
            return;
        }
        int count = 0;
        for (int s = 0; s < N; s++) {
            if (stamp[s] == epoch && Math.abs(colX[s] - cx) <= RADIUS && Math.abs(colZ[s] - cz) <= RADIUS) {
                count++;
            }
        }
        scanned = count;
    }

    /**
     * Scans up to {@code budgetColumns} not-yet-scanned columns, nearest the centre first, and re-scans old columns
     * round-robin once everything is covered.
     *
     * @return columns scanned by this call
     */
    public int scan(CombatVoxels w, int budgetColumns) {
        if (!centred || budgetColumns <= 0) {
            return 0;
        }
        int done = 0;
        while (cursor < N && done < budgetColumns) {
            int x = cx + ORDER_X[cursor];
            int z = cz + ORDER_Z[cursor];
            cursor++;
            int s = slot(x, z);
            if (stamp[s] != epoch || colX[s] != x || colZ[s] != z) {
                scanColumn(w, x, z, s);
                scanned++;
                done++;
            }
        }
        while (done < budgetColumns && cursor >= N) {
            if (refreshCursor >= N) {
                refreshCursor = 0;
            }
            int x = cx + ORDER_X[refreshCursor];
            int z = cz + ORDER_Z[refreshCursor];
            refreshCursor++;
            // phase 1 finished, so every column in range is already scanned: this only refreshes
            scanColumn(w, x, z, slot(x, z));
            done++;
        }
        return done;
    }

    /** Reads one column: floor, headroom, floor kind and the standable mask, at most {@link #WINDOW} lookups. */
    private void scanColumn(CombatVoxels w, int x, int z, int s) {
        int top = cy + WIN_UP;
        int maxFloor = cy + 1;
        int openRun = 0;
        int f = NO_FLOOR;
        int k = CombatVoxels.UNKNOWN;
        int hr = 0;
        int m = 0;
        boolean resolved = false;
        boolean lastOpen = false;
        for (int i = 0; i < WINDOW; i++) {
            int y = top - i;
            int v = w.kind(x, y, z);
            if (v == CombatVoxels.UNKNOWN) {
                k = CombatVoxels.UNKNOWN;
                resolved = true;
                break;
            }
            if (CombatVoxels.standable(v)) {
                if (y <= maxFloor && openRun >= 2) {
                    f = y;
                    k = v;
                    hr = openRun;
                    resolved = true;
                    break;
                }
                m |= 1 << i;
                openRun = 0;
                lastOpen = false;
            } else if (v == CombatVoxels.AIR) {
                openRun++;
                lastOpen = true;
            } else if (v == CombatVoxels.WATER) {
                if (y <= maxFloor && openRun >= 1) {
                    f = y;
                    k = v;
                    hr = openRun;
                    resolved = true;
                    break;
                }
                openRun++;
                lastOpen = true;
            } else { // LAVA, HARM, COBWEB
                if (y <= maxFloor && openRun >= 1) {
                    f = y;
                    k = v;
                    hr = openRun;
                    resolved = true;
                    break;
                }
                openRun = 0;
                lastOpen = false;
            }
        }
        if (!resolved) {
            k = lastOpen ? CombatVoxels.AIR : CombatVoxels.SOLID; // open to the bottom = a drop; else buried
        }
        colX[s] = x;
        colZ[s] = z;
        colY[s] = cy;
        floor[s] = f;
        head[s] = (byte) Math.min(hr, MAX_HEADROOM);
        kind[s] = (byte) k;
        mask[s] = (short) m;
        stamp[s] = epoch;
    }

    private static int slot(int x, int z) {
        return Math.floorMod(x, SIZE) * SIZE + Math.floorMod(z, SIZE);
    }

    /** Slot of a scanned column inside the grid, or -1. */
    private int valid(int x, int z) {
        if (!centred || Math.abs(x - cx) > RADIUS || Math.abs(z - cz) > RADIUS) {
            return -1;
        }
        int s = slot(x, z);
        return stamp[s] == epoch && colX[s] == x && colZ[s] == z ? s : -1;
    }

    /** Share of the grid's columns scanned at least once, 0..1. */
    @Override
    public double coverage() {
        return centred ? (double) scanned / N : 0.0;
    }

    /** Drops everything (world unload, disengage). */
    public void clear() {
        epoch++;
        centred = false;
        scanned = 0;
        cursor = 0;
        refreshCursor = 0;
        knockKind = KNOCK_NONE;
        knockValue = 0.0;
        knockX = 0.0;
        knockZ = 0.0;
        spotGain = 0.0;
    }

    /** Y of the floor block (the block stood ON) of the column, or {@link #NO_FLOOR}. */
    @Override
    public int floorY(int x, int z) {
        int s = valid(x, z);
        return s < 0 ? NO_FLOOR : floor[s];
    }

    /** Open blocks above the column's floor, 0..{@link #MAX_HEADROOM}; {@link #MAX_HEADROOM} when unknown. */
    @Override
    public int headroom(int x, int z) {
        int s = valid(x, z);
        return s < 0 || floor[s] == NO_FLOOR ? MAX_HEADROOM : head[s];
    }

    /** {@link CombatVoxels} kind of the column's floor block ({@code UNKNOWN} when not scanned). */
    @Override
    public int floorKind(int x, int z) {
        int s = valid(x, z);
        return s < 0 ? CombatVoxels.UNKNOWN : kind[s];
    }

    /** True when the column's block at y collides (cells at or below a standable floor count as solid). */
    private boolean solidAt(int s, int y) {
        int k = kind[s];
        int f = floor[s];
        if (f != NO_FLOOR && CombatVoxels.standable(k) && y <= f) {
            return true;
        }
        int i = colY[s] + WIN_UP - y;
        if (i < 0) {
            return false;
        }
        if (i >= WINDOW) {
            return f == NO_FLOOR && k == CombatVoxels.SOLID;
        }
        return (mask[s] & (1 << i)) != 0;
    }

    // ---- knockback vectoring ---------------------------------------------------------------------------------------

    /**
     * Finds the horizontal direction a hit should push a target standing at the position: toward the most valuable
     * thing within about 3 blocks of it (lava, a deep drop, a cobweb, or a wall to pin it against), weighted by how
     * near it is. Results in {@link #knockX()}.. {@link #knockKind()}.
     *
     * @return true when something worth steering toward exists ({@link #knockValue()} above 0)
     */
    @Override
    public boolean evalKnock(double tx, double ty, double tz) {
        knockKind = KNOCK_NONE;
        knockValue = 0.0;
        if (!centred) {
            return false;
        }
        int bx0 = floorToInt(tx);
        int bz0 = floorToInt(tz);
        int fy = floorToInt(ty + 1e-6);
        int tFloor = fy - 1;
        for (int k = 0; k < DIRS; k++) {
            double best = 0.0;
            int bestKind = KNOCK_NONE;
            for (int d = 1; d <= KNOCK_REACH; d++) {
                int bx = floorToInt(tx + DIR_X[k] * d);
                int bz = floorToInt(tz + DIR_Z[k] * d);
                if (bx == bx0 && bz == bz0) {
                    continue;
                }
                int s = valid(bx, bz);
                if (s < 0 || kind[s] == CombatVoxels.UNKNOWN) {
                    break; // cannot see past an unscanned column
                }
                if (solidAt(s, fy) && solidAt(s, fy + 1)) {
                    if (d == 1 && V_WALL > best) {
                        best = V_WALL;
                        bestKind = KNOCK_WALL;
                    }
                    break; // nothing beyond a wall is reachable by knockback
                }
                double v = 0.0;
                int vk = KNOCK_NONE;
                int ck = kind[s];
                int f = floor[s];
                if (ck == CombatVoxels.LAVA) {
                    v = V_LETHAL;
                    vk = KNOCK_LAVA;
                } else if (ck == CombatVoxels.HARM) {
                    v = V_HARM;
                    vk = KNOCK_LAVA;
                } else if (ck == CombatVoxels.COBWEB) {
                    v = V_COBWEB;
                    vk = KNOCK_COBWEB;
                } else if (ck == CombatVoxels.AIR) {
                    int minDrop = tFloor - (colY[s] - WIN_DOWN - 1);
                    if (minDrop >= 6) {
                        v = V_LETHAL;
                        vk = KNOCK_DROP;
                    } else if (minDrop >= 3) {
                        v = V_DROP;
                        vk = KNOCK_DROP;
                    }
                } else if (f != NO_FLOOR && ck != CombatVoxels.WATER) {
                    int drop = tFloor - f;
                    if (drop >= 6) {
                        v = V_LETHAL;
                        vk = KNOCK_DROP;
                    } else if (drop >= 3) {
                        v = V_DROP;
                        vk = KNOCK_DROP;
                    } else if (ck == CombatVoxels.SLOW) {
                        v = V_SLOW;
                        vk = KNOCK_COBWEB;
                    }
                }
                v *= NEAR[d];
                if (v > best) {
                    best = v;
                    bestKind = vk;
                }
            }
            dirValue[k] = best;
            dirKind[k] = bestKind;
        }
        int bestK = -1;
        double bestS = 0.0;
        for (int k = 0; k < DIRS; k++) {
            double s = 0.6 * dirValue[k]
                    + 0.2 * (dirValue[(k + DIRS - 1) % DIRS] + dirValue[(k + 1) % DIRS]);
            if (s > bestS + 1e-9) {
                bestS = s;
                bestK = k;
            }
        }
        if (bestK < 0) {
            return false;
        }
        int kk = dirKind[bestK];
        double kv = dirValue[bestK];
        int l = (bestK + DIRS - 1) % DIRS;
        int r = (bestK + 1) % DIRS;
        if (dirValue[l] > kv) {
            kk = dirKind[l];
            kv = dirValue[l];
        }
        if (dirValue[r] > kv) {
            kk = dirKind[r];
        }
        knockX = DIR_X[bestK];
        knockZ = DIR_Z[bestK];
        knockValue = Math.min(1.0, bestS);
        knockKind = kk;
        return true;
    }

    private static int floorToInt(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** Unit direction (x part) from the target toward the feature; the attacker should stand on the opposite side. */
    @Override
    public double knockX() {
        return knockX;
    }

    @Override
    public double knockZ() {
        return knockZ;
    }

    /** 0..1: 1 = lava or a lethal drop one block behind the target. */
    @Override
    public double knockValue() {
        return knockValue;
    }

    @Override
    public int knockKind() {
        return knockKind;
    }

    // ---- reachability ----------------------------------------------------------------------------------------------

    /** Walkable column: scanned, a standable floor and room for a body. */
    private boolean walkable(int s) {
        return s >= 0 && CombatVoxels.standable(kind[s]) && floor[s] != NO_FLOOR && head[s] >= 2;
    }

    /**
     * Flood fill from the player's column over walkable columns (steps of at most +1 up / -3 down). Fills
     * {@link #queue} with local indices; returns how many, 0 when the player's column is not walkable.
     */
    private int flood(double px, double pz) {
        int x0 = floorToInt(px);
        int z0 = floorToInt(pz);
        int s0 = valid(x0, z0);
        if (!walkable(s0)) {
            return 0;
        }
        int gen = ++visitGen;
        int ox = cx - RADIUS;
        int oz = cz - RADIUS;
        int head0 = 0;
        int tail = 0;
        int li0 = (x0 - ox) * SIZE + (z0 - oz);
        visit[li0] = gen;
        queue[tail++] = li0;
        while (head0 < tail) {
            int li = queue[head0++];
            int lx = li / SIZE;
            int lz = li - lx * SIZE;
            int fa = floor[slot(lx + ox, lz + oz)];
            for (int n = 0; n < 4; n++) {
                int nx = lx + (n == 0 ? 1 : n == 1 ? -1 : 0);
                int nz = lz + (n == 2 ? 1 : n == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) {
                    continue;
                }
                int nli = nx * SIZE + nz;
                if (visit[nli] == gen) {
                    continue;
                }
                int s = valid(nx + ox, nz + oz);
                if (!walkable(s)) {
                    continue;
                }
                int dy = floor[s] - fa;
                if (dy > 1 || dy < -3) {
                    continue;
                }
                visit[nli] = gen;
                queue[tail++] = nli;
            }
        }
        return tail;
    }

    /** True when no neighbour (8 around) is a hazard, a drop of 3+, or unknown. */
    private boolean safeSurroundings(int x, int z, int f) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int s = valid(x + dx, z + dz);
                if (s < 0) {
                    return false;
                }
                int k = kind[s];
                if (k == CombatVoxels.UNKNOWN || k == CombatVoxels.LAVA || k == CombatVoxels.HARM
                        || k == CombatVoxels.AIR) {
                    return false;
                }
                int nf = floor[s];
                if (nf != NO_FLOOR && f - nf >= 3) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- positions -------------------------------------------------------------------------------------------------

    /**
     * Finds a standing spot at least one block above the target's feet, within {@code maxDist} blocks of the player
     * and within about 4 blocks of the target, not next to a hazard, reachable by walking and 1-block steps over
     * scanned columns. Result in {@link #spotX()}.. {@link #spotGain()} (height above the target in blocks).
     */
    @Override
    public boolean findHighGround(double px, double py, double pz, double tx, double ty, double tz, double maxDist) {
        if (!centred) {
            return false;
        }
        int n = flood(px, pz);
        if (n == 0) {
            return false;
        }
        int tfy = floorToInt(ty + 1e-6);
        int ox = cx - RADIUS;
        int oz = cz - RADIUS;
        double maxD2 = maxDist * maxDist;
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestX = 0, bestZ = 0, bestF = 0;
        for (int i = 0; i < n; i++) {
            int li = queue[i];
            int x = li / SIZE + ox;
            int z = li % SIZE + oz;
            int s = slot(x, z);
            int f = floor[s];
            int gain = f + 1 - tfy;
            if (gain < 1 || kind[s] != CombatVoxels.SOLID || head[s] < 3) {
                continue;
            }
            double sx = x + 0.5;
            double sz = z + 0.5;
            double dpx = sx - px;
            double dpz = sz - pz;
            double dp2 = dpx * dpx + dpz * dpz;
            if (dp2 > maxD2) {
                continue;
            }
            double dtx = sx - tx;
            double dtz = sz - tz;
            double dt = Math.sqrt(dtx * dtx + dtz * dtz);
            if (dt > 4.0) {
                continue;
            }
            if (!safeSurroundings(x, z, f)) {
                continue;
            }
            double score = (gain <= 2 ? 10.0 : 6.0) + Math.min(gain, 2) - Math.sqrt(dp2) * 0.3
                    - Math.abs(dt - 2.5) * 0.2;
            if (score > bestScore) {
                bestScore = score;
                bestX = x;
                bestZ = z;
                bestF = f;
            }
        }
        if (bestScore == Double.NEGATIVE_INFINITY) {
            return false;
        }
        spotX = bestX + 0.5;
        spotY = bestF + 1;
        spotZ = bestZ + 0.5;
        spotGain = bestF + 1 - tfy;
        return true;
    }

    /**
     * Finds a standing spot under a low ceiling (headroom exactly 2: a body fits, a jump does not), at about the
     * player's height and within {@code maxDist} blocks, where a crit-hopping opponent has to follow under the roof.
     * Result in {@link #spotX()}..; {@link #spotGain()} is the number of connected low-ceiling columns (room to fight).
     */
    @Override
    public boolean findLowCeiling(double px, double py, double pz, double tx, double ty, double tz, double maxDist) {
        if (!centred) {
            return false;
        }
        int n = flood(px, pz);
        if (n == 0) {
            return false;
        }
        int pfy = floorToInt(py + 1e-6);
        int ox = cx - RADIUS;
        int oz = cz - RADIUS;
        double maxD2 = maxDist * maxDist;
        double awayX = px - tx;
        double awayZ = pz - tz;
        double awayLen = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (awayLen > 1e-6) {
            awayX /= awayLen;
            awayZ /= awayLen;
        } else {
            awayX = 0.0;
            awayZ = 0.0;
        }
        int cgen = ++compGen;
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestX = 0, bestZ = 0, bestF = 0, bestSize = 0;
        for (int i = 0; i < n; i++) {
            int li = queue[i];
            int x = li / SIZE + ox;
            int z = li % SIZE + oz;
            int s = slot(x, z);
            if (head[s] != 2 || kind[s] != CombatVoxels.SOLID) {
                continue;
            }
            int f = floor[s];
            if (Math.abs(f + 1 - pfy) > 1) {
                continue;
            }
            double sx = x + 0.5;
            double sz = z + 0.5;
            double dpx = sx - px;
            double dpz = sz - pz;
            double dp2 = dpx * dpx + dpz * dpz;
            if (dp2 > maxD2) {
                continue;
            }
            int size = componentSize(li, cgen);
            if (size < 3) {
                continue;
            }
            double dp = Math.sqrt(dp2);
            double far = dp > 1e-6 ? (dpx * awayX + dpz * awayZ) / dp : 0.0;
            double score = far * 2.0 + Math.min(size, 8) * 0.15 - dp * 0.15;
            if (score > bestScore) {
                bestScore = score;
                bestX = x;
                bestZ = z;
                bestF = f;
                bestSize = size;
            }
        }
        if (bestScore == Double.NEGATIVE_INFINITY) {
            return false;
        }
        spotX = bestX + 0.5;
        spotY = bestF + 1;
        spotZ = bestZ + 0.5;
        spotGain = bestSize;
        return true;
    }

    /** Size of the connected low-ceiling (headroom 2, walkable steps) region holding local index li; memoised. */
    private int componentSize(int li0, int cgen) {
        if (compStamp[li0] == cgen) {
            return compSize[comp[li0]];
        }
        int ox = cx - RADIUS;
        int oz = cz - RADIUS;
        int h = 0;
        int t = 0;
        compStamp[li0] = cgen;
        comp[li0] = li0;
        queue2[t++] = li0;
        while (h < t) {
            int li = queue2[h++];
            int lx = li / SIZE;
            int lz = li - lx * SIZE;
            int fa = floor[slot(lx + ox, lz + oz)];
            for (int n = 0; n < 4; n++) {
                int nx = lx + (n == 0 ? 1 : n == 1 ? -1 : 0);
                int nz = lz + (n == 2 ? 1 : n == 3 ? -1 : 0);
                if (nx < 0 || nz < 0 || nx >= SIZE || nz >= SIZE) {
                    continue;
                }
                int nli = nx * SIZE + nz;
                if (compStamp[nli] == cgen) {
                    continue;
                }
                int s = valid(nx + ox, nz + oz);
                if (!walkable(s) || head[s] != 2) {
                    continue;
                }
                int dy = floor[s] - fa;
                if (dy > 1 || dy < -1) {
                    continue;
                }
                compStamp[nli] = cgen;
                comp[nli] = li0;
                queue2[t++] = nli;
            }
        }
        compSize[li0] = t;
        return t;
    }

    /** Block-centre x of the spot the last successful find returned. */
    @Override
    public double spotX() {
        return spotX;
    }

    /** Feet y of that spot. */
    @Override
    public double spotY() {
        return spotY;
    }

    @Override
    public double spotZ() {
        return spotZ;
    }

    @Override
    public double spotGain() {
        return spotGain;
    }
}
