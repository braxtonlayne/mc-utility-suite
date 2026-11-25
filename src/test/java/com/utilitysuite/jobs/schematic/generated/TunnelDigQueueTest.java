package com.utilitysuite.jobs.schematic.generated;

import com.utilitysuite.jobs.planner.PackedPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelDigQueueTest {

    /** Solid until dug. */
    private static final class Rock implements TunnelDigQueue.Solid {
        final LongOpenHashSet dug = new LongOpenHashSet();

        @Override
        public boolean solidAt(int x, int y, int z) {
            return !dug.contains(PackedPos.pack(x, y, z));
        }
    }

    /** 3-wide (z -1..1), 3-high (y 65..67) tunnel cells for x 0..len-1. */
    private static long[] tunnel(int len) {
        long[] out = new long[len * 9];
        int i = 0;
        for (int x = 0; x < len; x++) {
            for (int y = 65; y <= 67; y++) {
                for (int z = -1; z <= 1; z++) {
                    out[i++] = PackedPos.pack(x, y, z);
                }
            }
        }
        return out;
    }

    private static long pick(TunnelDigQueue q, double px, double py, double pz, Rock rock) {
        int fx = (int) Math.floor(px);
        int fz = (int) Math.floor(pz);
        return q.pick(px, py + 1.62, pz, 4.5, fx, fx, fz, fz, (int) Math.floor(py) - 1, rock);
    }

    @Test
    void digsNearestColumnFirstAndTopDownWithinIt() {
        TunnelDigQueue q = new TunnelDigQueue();
        q.addAll(tunnel(8));
        Rock rock = new Rock();
        // Player stands at the tunnel mouth, x -1, feet y 65 (outside the tunnel, on its floor line).
        long first = pick(q, -0.5, 65, 0.5, rock);
        assertEquals(PackedPos.pack(0, 67, 0), first, "nearest column (x 0, z 0), top row first");
        rock.dug.add(first);
        assertEquals(PackedPos.pack(0, 66, 0), pick(q, -0.5, 65, 0.5, rock));
        rock.dug.add(PackedPos.pack(0, 66, 0));
        assertEquals(PackedPos.pack(0, 65, 0), pick(q, -0.5, 65, 0.5, rock));
        rock.dug.add(PackedPos.pack(0, 65, 0));
        // Then a side column at x 0 (z -1 and z 1 are equally near; either, top first).
        long side = pick(q, -0.5, 65, 0.5, rock);
        assertEquals(0, PackedPos.x(side));
        assertEquals(67, PackedPos.y(side));
        assertTrue(Math.abs(PackedPos.z(side)) == 1);
        assertEquals(8 * 9 - 3, q.size(), "dug cells are dropped as they are met");
    }

    @Test
    void neverPicksTheBlockUnderTheFeetAndRespectsReach() {
        TunnelDigQueue q = new TunnelDigQueue();
        q.addAll(tunnel(20));
        Rock rock = new Rock();
        // Standing on an undug tunnel cell (x 5, y 65, z 0): feet at y 66.
        for (int i = 0; i < 400; i++) {
            long c = pick(q, 5.5, 66, 0.5, rock);
            if (c == TunnelDigQueue.NONE) {
                break;
            }
            assertTrue(!(PackedPos.x(c) == 5 && PackedPos.y(c) == 65 && PackedPos.z(c) == 0), "never the support");
            double dx = PackedPos.x(c) + 0.5 - 5.5;
            double dy = PackedPos.y(c) + 0.5 - (66 + 1.62);
            double dz = PackedPos.z(c) + 0.5 - 0.5;
            assertTrue(dx * dx + dy * dy + dz * dz <= 4.5 * 4.5 + 1e-9, "within reach");
            rock.dug.add(c);
        }
        assertTrue(rock.solidAt(5, 65, 0));
        assertTrue(rock.solidAt(15, 66, 0), "out of reach cells stay queued");
        assertEquals(PackedPos.pack(5, 65, 0), q.nearest(5.5, 0.5), "the support is still the nearest pending cell");
    }

    @Test
    void ignoresSeamDuplicatesAndRemovesFailedCells() {
        TunnelDigQueue q = new TunnelDigQueue();
        long[] t = tunnel(2);
        q.addAll(t);
        q.addAll(t);
        assertEquals(18, q.size());
        q.remove(t[0]);
        q.remove(t[0]);
        assertEquals(17, q.size());
        q.clear();
        assertTrue(q.isEmpty());
        assertEquals(TunnelDigQueue.NONE, q.nearest(0, 0));
    }
}
