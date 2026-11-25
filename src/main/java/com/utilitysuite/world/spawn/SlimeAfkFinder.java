package com.utilitysuite.world.spawn;

import java.util.ArrayList;
import java.util.List;

/**
 * Engine-free search for the best slime-farm AFK spot: the chunk whose centre has the most slime chunks around it
 * within the mob spawn sphere. Mobs only spawn within 128 blocks of the player and despawn instantly beyond that, so a
 * slime chunk counts when its centre lies within {@value #COUNT_RADIUS} blocks horizontally of the AFK chunk's centre
 * (128 minus half a chunk, so most of the chunk is inside the sphere), and not within {@value #NO_SPAWN_RADIUS} blocks
 * (no spawns that close to the player). Runs on the world executor; the slime map is computed once per search.
 */
public final class SlimeAfkFinder {

    public static final int COUNT_RADIUS = 120;
    public static final int NO_SPAWN_RADIUS = 24;
    /** Reach of the counting disk in chunks. */
    public static final int DISK_CHUNKS = COUNT_RADIUS / 16 + 1;

    /** One AFK spot: chunk, its block centre and how many slime chunks it covers. */
    public record Spot(int chunkX, int chunkZ, int slimeChunks) {
        public int blockX() {
            return (chunkX << 4) + 8;
        }

        public int blockZ() {
            return (chunkZ << 4) + 8;
        }
    }

    private static final int[] DISK = buildDisk();

    private SlimeAfkFinder() {
    }

    /** Chunk offsets {dx0, dz0, ...} counted around an AFK chunk. */
    static int[] disk() {
        return DISK.clone();
    }

    private static int[] buildDisk() {
        List<int[]> cells = new ArrayList<>();
        long max = (long) COUNT_RADIUS * COUNT_RADIUS;
        long min = (long) NO_SPAWN_RADIUS * NO_SPAWN_RADIUS;
        for (int dx = -DISK_CHUNKS; dx <= DISK_CHUNKS; dx++) {
            for (int dz = -DISK_CHUNKS; dz <= DISK_CHUNKS; dz++) {
                long d2 = (long) (dx * 16) * (dx * 16) + (long) (dz * 16) * (dz * 16);
                if (d2 <= max && d2 > min) {
                    cells.add(new int[]{dx, dz});
                }
            }
        }
        int[] out = new int[cells.size() * 2];
        for (int i = 0; i < cells.size(); i++) {
            out[2 * i] = cells.get(i)[0];
            out[2 * i + 1] = cells.get(i)[1];
        }
        return out;
    }

    /** Slime chunks counted around the AFK chunk (direct, for tests and single checks). */
    public static int countAround(long seed, int chunkX, int chunkZ) {
        int n = 0;
        for (int i = 0; i < DISK.length; i += 2) {
            if (SlimeChunks.isSlimeChunk(seed, chunkX + DISK[i], chunkZ + DISK[i + 1])) {
                n++;
            }
        }
        return n;
    }

    /**
     * Best AFK chunks within {@code searchChunks} of {@code (centerX, centerZ)}: most slime chunks first, ties to the
     * nearer spot; spots at least {@code minApart} chunks apart (Chebyshev), at most {@code count}.
     */
    public static List<Spot> best(long seed, int centerX, int centerZ, int searchChunks, int count, int minApart) {
        int reach = searchChunks + DISK_CHUNKS;
        int side = 2 * reach + 1;
        boolean[] slime = new boolean[side * side];
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                slime[(dx + reach) * side + dz + reach] = SlimeChunks.isSlimeChunk(seed, centerX + dx, centerZ + dz);
            }
        }
        int cs = 2 * searchChunks + 1;
        int[] counts = new int[cs * cs];
        for (int ax = -searchChunks; ax <= searchChunks; ax++) {
            for (int az = -searchChunks; az <= searchChunks; az++) {
                int n = 0;
                for (int i = 0; i < DISK.length; i += 2) {
                    if (slime[(ax + DISK[i] + reach) * side + az + DISK[i + 1] + reach]) {
                        n++;
                    }
                }
                counts[(ax + searchChunks) * cs + az + searchChunks] = n;
            }
        }
        List<Spot> out = new ArrayList<>();
        boolean[] taken = new boolean[counts.length];
        while (out.size() < count) {
            int bestIdx = -1;
            long bestDist = Long.MAX_VALUE;
            for (int i = 0; i < counts.length; i++) {
                if (taken[i]) {
                    continue;
                }
                int ax = i / cs - searchChunks;
                int az = i % cs - searchChunks;
                long d = (long) ax * ax + (long) az * az;
                if (bestIdx < 0 || counts[i] > counts[bestIdx] || (counts[i] == counts[bestIdx] && d < bestDist)) {
                    bestIdx = i;
                    bestDist = d;
                }
            }
            if (bestIdx < 0) {
                break;
            }
            int bx = bestIdx / cs - searchChunks;
            int bz = bestIdx % cs - searchChunks;
            out.add(new Spot(centerX + bx, centerZ + bz, counts[bestIdx]));
            for (int i = 0; i < counts.length; i++) {
                int ax = i / cs - searchChunks;
                int az = i % cs - searchChunks;
                if (Math.max(Math.abs(ax - bx), Math.abs(az - bz)) < minApart) {
                    taken[i] = true;
                }
            }
        }
        return List.copyOf(out);
    }
}
