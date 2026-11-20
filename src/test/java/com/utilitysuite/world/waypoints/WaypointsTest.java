package com.utilitysuite.world.waypoints;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.world.BlockPosPacking;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointsTest {

    private static final String OW = DimensionScale.OVERWORLD;
    private static final String NE = DimensionScale.NETHER;
    private static final String END = "minecraft:the_end";

    @Test
    void eightToOneConversion() {
        assertEquals(100, DimensionScale.convert(800, OW, NE));
        assertEquals(800, DimensionScale.convert(100, NE, OW));
        assertEquals(-1, DimensionScale.convert(-1, OW, NE), "floors toward negative infinity like the portal logic");
        assertEquals(-2, DimensionScale.convert(-9, OW, NE));
        assertEquals(12, DimensionScale.convert(12, OW, OW));
        assertEquals(555, DimensionScale.convert(555, OW, END), "the End is not converted");
        assertFalse(DimensionScale.convertible(OW, END));
        long p = DimensionScale.convert(80, 64, -808, OW, NE);
        assertEquals(10, BlockPosPacking.x(p));
        assertEquals(64, BlockPosPacking.y(p), "Y is unchanged");
        assertEquals(-101, BlockPosPacking.z(p));

        WaypointStore store = new WaypointStore(() -> null);
        Waypoint w = store.add("Base", WaypointStore.GROUP_BASES, 1600, 70, -320, OW);
        long inNether = store.positionIn(w, NE);
        assertEquals(200, BlockPosPacking.x(inNether));
        assertEquals(-40, BlockPosPacking.z(inNether));
    }

    @Test
    void breadcrumbsRespectSpacingAndChangesOnly() {
        BreadcrumbTrail t = new BreadcrumbTrail(16, 4);
        assertTrue(t.sample(OW, 0, 64, 0, 1));
        assertFalse(t.sample(OW, 0, 64, 0, 2), "same block: no sample");
        assertFalse(t.sample(OW, 2, 64, 0, 3), "closer than the spacing");
        assertFalse(t.sample(OW, 3, 64, 0, 4));
        assertTrue(t.sample(OW, 4, 64, 0, 5), "exactly the spacing");
        assertTrue(t.sample(OW, 4, 64, 4, 6));
        assertEquals(3, t.size());
        assertArrayEquals(new long[] {BlockPosPacking.pack(0, 64, 0), BlockPosPacking.pack(4, 64, 0),
                BlockPosPacking.pack(4, 64, 4)}, t.toArray());
        assertArrayEquals(new long[] {BlockPosPacking.pack(4, 64, 4), BlockPosPacking.pack(4, 64, 0),
                BlockPosPacking.pack(0, 64, 0)}, t.reversed(), "Take Me Back walks newest first");
        assertTrue(t.sample(NE, 0, 64, 0, 7));
        assertEquals(1, t.size(), "changing dimension starts a new trail");
    }

    @Test
    void ringBufferKeepsTheNewestPoints() {
        BreadcrumbTrail t = new BreadcrumbTrail(4, 1);
        for (int i = 0; i < 10; i++) {
            t.sample(OW, i * 2, 64, 0, i);
        }
        assertEquals(4, t.size());
        assertEquals(BlockPosPacking.pack(12, 64, 0), t.get(0), "oldest kept");
        assertEquals(BlockPosPacking.pack(18, 64, 0), t.newest());
        assertEquals(9, t.timeOf(3));
        t.setCapacity(2);
        assertEquals(2, t.size());
        assertEquals(BlockPosPacking.pack(16, 64, 0), t.get(0));
        assertEquals(BlockPosPacking.pack(18, 64, 0), t.get(1));
    }

    @Test
    void samplingDoesNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        BreadcrumbTrail t = new BreadcrumbTrail(256, 2);
        int[] i = {0};
        long bytes = AllocationProbe.measure(100_000, () -> {
            int n = i[0]++;
            t.sample(OW, n % 1000, 64, (n / 1000) % 1000, n);
        });
        assertTrue(bytes < 1024, "allocated " + bytes);
    }

    @Test
    void deathSitesAreRecordedOncePerDeath() {
        WaypointStore store = new WaypointStore(() -> null);
        assertNotNull(store.addDeathSite(10, 20, 30, OW, 1_000L));
        assertNull(store.addDeathSite(11, 20, 30, OW, 1_500L), "same death reported by a second source");
        assertNotNull(store.addDeathSite(10, 20, 30, OW, 60_000L), "a later death at the same place");
        assertEquals(2, store.inGroup(WaypointStore.GROUP_DEATHS).size());
        assertEquals("Death 2", store.inGroup(WaypointStore.GROUP_DEATHS).get(1).name());
    }

    @Test
    void jsonRoundTripKeepsGroupsTagsAndIds() {
        WaypointStore store = new WaypointStore(() -> null);
        Waypoint base = store.add("Home", WaypointStore.GROUP_BASES, 1, 2, 3, OW);
        Waypoint portal = store.add("Hub", WaypointStore.GROUP_PORTALS, -4, 70, 9, NE);
        store.addTag(base.id(), "spawn");
        store.addTag(base.id(), "beds");
        int v = store.version();
        String json = store.toJson();
        WaypointStore copy = new WaypointStore(() -> null);
        copy.fromJson(json);
        assertEquals(store.all(), copy.all());
        assertEquals(store.tags(base.id()), copy.tags(base.id()));
        assertEquals(portal, copy.byName("hub").orElseThrow());
        Waypoint next = copy.add("Mine", WaypointStore.GROUP_MINES, 0, 0, 0, OW);
        assertFalse(next.id().equals(base.id()) || next.id().equals(portal.id()), "ids continue after load");
        assertFalse(copy.isDirty() && copy.all().size() == 2);
        assertTrue(store.version() == v);
        store.remove(portal.id());
        assertTrue(store.version() != v, "changes bump the render version");
    }
}
