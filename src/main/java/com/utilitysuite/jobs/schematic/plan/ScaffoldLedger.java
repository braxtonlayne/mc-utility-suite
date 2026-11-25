package com.utilitysuite.jobs.schematic.plan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

/**
 * Temporary blocks the builder put down that are not part of the schematic (pillars and bridges Baritone placed while
 * walking to a reach spot), keyed by {@code BlockPos.asLong()}, plus the safe order to take them down.
 * <p>
 * Removal order ({@link #removalOrder}): top-down (highest Y first), nearest to the player first within a Y level.
 * Blocks in the player's own column at or below their feet (the block they stand on and the pillar under it) go last,
 * top-down, so the player is never left standing on a block whose support was removed first; the executor re-checks
 * before breaking each of those that the player has stepped off.
 * <p>
 * Engine-agnostic (only {@code BlockPos} packing); allocation-free after construction except when the set grows.
 * Client thread only.
 */
public final class ScaffoldLedger {

    private final LongOpenHashSet positions = new LongOpenHashSet();
    /** Dense copy of {@link #positions} for allocation-free iteration. */
    private long[] dense = new long[16];

    public void record(long packedPos) {
        if (positions.add(packedPos)) {
            int n = positions.size();
            if (dense.length < n) {
                dense = java.util.Arrays.copyOf(dense, n * 2);
            }
            dense[n - 1] = packedPos;
        }
    }

    public void forget(long packedPos) {
        if (positions.remove(packedPos)) {
            int last = positions.size(); // index of the old last element
            for (int i = 0; i <= last; i++) {
                if (dense[i] == packedPos) {
                    dense[i] = dense[last];
                    break;
                }
            }
        }
    }

    public boolean contains(long packedPos) {
        return positions.contains(packedPos);
    }

    public int size() {
        return positions.size();
    }

    public boolean isEmpty() {
        return positions.isEmpty();
    }

    public void clear() {
        positions.clear();
    }

    /**
     * Writes the removal order into {@code out} (grown when too small) and returns the buffer; the first {@link #size()}
     * entries are valid.
     *
     * @param playerFeet packed block position of the player's feet ({@code player.blockPosition()})
     */
    public long[] removalOrder(long playerFeet, long[] out) {
        int n = positions.size();
        if (out == null || out.length < n) {
            out = new long[Math.max(n, 16)];
        }
        System.arraycopy(dense, 0, out, 0, n);
        sort(out, n, playerFeet);
        return out;
    }

    /** True when {@code pos} is in the player's column at or below their feet (stood on, or holding that up). */
    public static boolean underPlayer(long pos, long playerFeet) {
        return BlockPos.getX(pos) == BlockPos.getX(playerFeet) && BlockPos.getZ(pos) == BlockPos.getZ(playerFeet)
                && BlockPos.getY(pos) < BlockPos.getY(playerFeet);
    }

    /** In-place insertion sort by {@link #before} (ledgers are small: tens of blocks). */
    static void sort(long[] a, int n, long playerFeet) {
        for (int i = 1; i < n; i++) {
            long v = a[i];
            int j = i - 1;
            while (j >= 0 && before(v, a[j], playerFeet)) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = v;
        }
    }

    /** Whether {@code a} is removed before {@code b}. */
    static boolean before(long a, long b, long playerFeet) {
        boolean ua = underPlayer(a, playerFeet), ub = underPlayer(b, playerFeet);
        if (ua != ub) {
            return !ua; // the player's own column goes last
        }
        int ya = BlockPos.getY(a), yb = BlockPos.getY(b);
        if (ya != yb) {
            return ya > yb; // top-down
        }
        return dist2(a, playerFeet) < dist2(b, playerFeet);
    }

    private static long dist2(long a, long b) {
        long dx = BlockPos.getX(a) - BlockPos.getX(b);
        long dy = BlockPos.getY(a) - BlockPos.getY(b);
        long dz = BlockPos.getZ(a) - BlockPos.getZ(b);
        return dx * dx + dy * dy + dz * dz;
    }
}
