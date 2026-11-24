package com.utilitysuite.world.waypoints;

import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.world.BlockPosPacking;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalWaypointsTest {

    private static final String OW = DimensionScale.OVERWORLD;
    private static final String NE = DimensionScale.NETHER;
    private static final String END = "minecraft:the_end";

    @Test
    void onlyOverworldNetherChangesCount() {
        assertTrue(PortalWaypoints.isPortalTransition(OW, NE));
        assertTrue(PortalWaypoints.isPortalTransition(NE, OW));
        assertFalse(PortalWaypoints.isPortalTransition(OW, OW));
        assertFalse(PortalWaypoints.isPortalTransition(OW, END));
        assertFalse(PortalWaypoints.isPortalTransition(END, OW));
        assertFalse(PortalWaypoints.isPortalTransition(null, NE), "first tick after joining is not a trip");
    }

    @Test
    void namesAreNumberedPerDimension() {
        WaypointStore store = new WaypointStore(() -> null);
        assertEquals("Portal (Nether)", store.addPortal(0, 70, 0, NE).name());
        assertEquals("Portal (Overworld)", store.addPortal(0, 70, 0, OW).name());
        assertEquals("Portal (Nether) 2", store.addPortal(100, 70, 0, NE).name());
        assertEquals("Portal (Nether) 3", store.addPortal(200, 70, 0, NE).name());
        store.add("portal (overworld) 2", WaypointStore.GROUP_BASES, 5000, 64, 5000, OW);
        assertEquals("Portal (Overworld) 3", store.addPortal(400, 70, 0, OW).name(), "skips names already used");
        assertEquals("the_end", PortalWaypoints.dimensionLabel(END));
    }

    @Test
    void dedupesWithinSixteenBlocksInTheSameDimensionOnly() {
        WaypointStore store = new WaypointStore(() -> null);
        Waypoint first = store.addPortal(10, 64, 10, OW);
        assertNotNull(first);
        assertEquals(WaypointStore.GROUP_PORTALS, first.group());
        assertNull(store.addPortal(26, 64, 10, OW), "exactly 16 blocks away is a duplicate");
        assertNull(store.addPortal(20, 70, 18, OW));
        assertNotNull(store.addPortal(27, 64, 10, OW), "17 blocks away is a new portal");
        assertNotNull(store.addPortal(10, 64, 10, NE), "same numbers in the other dimension are a different portal");
        store.add("Home", WaypointStore.GROUP_BASES, 500, 64, 500, OW);
        assertNotNull(store.addPortal(505, 64, 500, OW), "other groups never suppress a portal");
        assertEquals(4, store.inGroup(WaypointStore.GROUP_PORTALS).size());
    }

    @Test
    void portalWaypointsConvertToThePairedDimension() {
        WaypointStore store = new WaypointStore(() -> null);
        Waypoint ow = store.addPortal(800, 70, -1601, OW);
        long inNether = store.positionIn(ow, NE);
        assertEquals(100, BlockPosPacking.x(inNether));
        assertEquals(70, BlockPosPacking.y(inNether));
        assertEquals(-201, BlockPosPacking.z(inNether));
        Waypoint ne = store.addPortal(-12, 40, 5, NE);
        long inOverworld = store.positionIn(ne, OW);
        assertEquals(-96, BlockPosPacking.x(inOverworld));
        assertEquals(40, BlockPosPacking.z(inOverworld));
        assertTrue(DimensionScale.convertible(ne.dimension(), OW));
    }
}
