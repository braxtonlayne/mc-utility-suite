package com.utilitysuite.travel.autopilot;

import com.utilitysuite.travel.autopilot.PortalHubPlanner.Config;
import com.utilitysuite.travel.autopilot.PortalHubPlanner.Plan;
import com.utilitysuite.travel.autopilot.PortalHubPlanner.Portal;
import com.utilitysuite.travel.autopilot.PortalHubRoute.Phase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.utilitysuite.travel.autopilot.PortalHubPlanner.NETHER;
import static com.utilitysuite.travel.autopilot.PortalHubPlanner.OVERWORLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalHubTest {

    private static final Config CFG = Config.defaults();

    // Home portal pair at the origin, a far portal pair near (8000, 0).
    private static final Portal HOME_OW = new Portal(OVERWORLD, 10, 70, 5);
    private static final Portal HOME_N = new Portal(NETHER, 1, 64, 0);
    private static final Portal FAR_N = new Portal(NETHER, 1000, 40, 2);
    private static final Portal FAR_OW = new Portal(OVERWORLD, 8003, 66, 17);

    @Test
    void nearDestinationWalksDirectly() {
        assertNull(PortalHubPlanner.plan(0, 0, 900, 64, 0, List.of(HOME_OW, HOME_N, FAR_N, FAR_OW), CFG));
    }

    @Test
    void noRecordedOverworldPortalWalksDirectly() {
        assertNull(PortalHubPlanner.plan(0, 0, 8000, 64, 0, List.of(HOME_N, FAR_N), CFG));
        assertNull(PortalHubPlanner.plan(0, 0, 8000, 64, 0, List.of(), CFG));
    }

    @Test
    void farDestinationUsesRecordedPortalPair() {
        Plan p = PortalHubPlanner.plan(0, 0, 8050, 64, 30, List.of(HOME_OW, HOME_N, FAR_N, FAR_OW), CFG);
        assertNotNull(p);
        assertEquals(HOME_OW, p.entry());
        assertTrue(p.arrivalKnown());
        assertEquals(HOME_N, p.arrivalNether());
        assertTrue(p.exitPortalKnown());
        assertEquals(FAR_N, p.exitNether());
        assertEquals(FAR_OW, p.exitOverworld());
        assertTrue(p.hubCost() < p.directCost());
        // 1000 nether blocks * 1.5 + overheads is far below 8000 overworld blocks
        assertTrue(p.hubCost() < 2000, "hub cost " + p.hubCost());
    }

    @Test
    void withoutExitPortalFallsBackToTargetOverEight() {
        Plan p = PortalHubPlanner.plan(0, 0, 8000, 64, -4000, List.of(HOME_OW, HOME_N), CFG);
        assertNotNull(p);
        assertFalse(p.exitPortalKnown());
        assertEquals(1000, p.exitNether().x());
        assertEquals(-500, p.exitNether().z());
        assertEquals(NETHER, p.exitNether().dimension());
    }

    @Test
    void arrivalWithoutRecordedNetherPortalIsEntryOverEight() {
        Portal ow = new Portal(OVERWORLD, -17, 70, 100);
        Plan p = PortalHubPlanner.plan(0, 0, 0, 64, 9000, List.of(ow), CFG);
        assertNotNull(p);
        assertFalse(p.arrivalKnown());
        assertEquals(-3, p.arrivalNether().x()); // floorDiv(-17, 8)
        assertEquals(12, p.arrivalNether().z());
    }

    @Test
    void distantEntryPortalIsNotWorthIt() {
        // The only portal is 3000 blocks the wrong way: walking 4000 directly is cheaper.
        Portal ow = new Portal(OVERWORLD, -3000, 70, 0);
        assertNull(PortalHubPlanner.plan(0, 0, 4000, 64, 0, List.of(ow, new Portal(NETHER, 500, 64, 0)), CFG));
    }

    @Test
    void picksTheCheaperOfTwoEntries() {
        Portal near = new Portal(OVERWORLD, 100, 70, 0);
        Portal far = new Portal(OVERWORLD, -900, 70, 0);
        Plan p = PortalHubPlanner.plan(0, 0, 8000, 64, 0, List.of(far, near, FAR_N, FAR_OW), CFG);
        assertNotNull(p);
        assertEquals(near, p.entry());
    }

    @Test
    void higherNetherCostCanMakeDirectCheaper() {
        Config pricey = new Config(1000, 8.0, 64, 256);
        assertNull(PortalHubPlanner.plan(0, 0, 3000, 64, 0, List.of(HOME_OW, HOME_N,
                new Portal(NETHER, 375, 64, 0)), pricey));
    }

    @Test
    void minDistanceSettingIsRespected() {
        Config strict = new Config(20000, 1.5, 64, 256);
        assertNull(PortalHubPlanner.plan(0, 0, 8050, 64, 30, List.of(HOME_OW, HOME_N, FAR_N, FAR_OW), strict));
    }

    // ---- state machine -----------------------------------------------------------------------------------------

    private static PortalHubRoute route(boolean exitKnown) {
        Plan p = exitKnown
                ? PortalHubPlanner.plan(0, 0, 8050, 64, 30, List.of(HOME_OW, HOME_N, FAR_N, FAR_OW), CFG)
                : PortalHubPlanner.plan(0, 0, 8050, 64, 30, List.of(HOME_OW, HOME_N), CFG);
        return new PortalHubRoute(p, new RouteParser.Stop(8050, 64, 30, true, "Target"));
    }

    @Test
    void fullRouteSequencesThroughBothDimensions() {
        PortalHubRoute r = route(true);
        assertEquals(Phase.TO_ENTRY, r.phase());
        assertTrue(r.isWalking());
        assertTrue(r.legEndsInPortal());
        assertEquals(HOME_OW.x(), r.currentStop().x());
        assertFalse(r.onDimension(OVERWORLD));

        r.onLegArrived();
        assertEquals(Phase.IN_ENTRY_PORTAL, r.phase());
        assertNull(r.currentStop());
        for (int i = 0; i < 80; i++) {
            r.tickWait(true);
        }
        assertEquals(Phase.IN_ENTRY_PORTAL, r.phase());

        assertTrue(r.onDimension(NETHER));
        assertEquals(Phase.NETHER_LEG, r.phase());
        assertEquals(NETHER, r.expectedDimension());
        assertEquals(FAR_N.x(), r.currentStop().x());
        assertTrue(r.currentStop().hasY());
        assertTrue(r.legEndsInPortal());

        r.onLegArrived();
        assertEquals(Phase.IN_EXIT_PORTAL, r.phase());
        assertTrue(r.onDimension(OVERWORLD));
        assertEquals(Phase.FINAL_LEG, r.phase());
        assertFalse(r.legEndsInPortal());
        assertEquals(8050, r.currentStop().x());

        r.onLegArrived();
        assertEquals(Phase.DONE, r.phase());
        assertTrue(r.isFinished());
    }

    @Test
    void unknownExitWaitsForThePlayerWithoutTimeout() {
        PortalHubRoute r = route(false);
        r.onLegArrived();
        r.onDimension(NETHER);
        assertFalse(r.currentStop().hasY());
        assertFalse(r.legEndsInPortal());
        r.onLegArrived();
        assertEquals(Phase.AWAIT_PORTAL, r.phase());
        for (int i = 0; i < 5000; i++) {
            r.tickWait(false);
        }
        assertEquals(Phase.AWAIT_PORTAL, r.phase());
        assertTrue(r.onDimension(OVERWORLD));
        assertEquals(Phase.FINAL_LEG, r.phase());
    }

    @Test
    void playerEnteringPortalEarlySkipsAhead() {
        PortalHubRoute r = route(true);
        assertTrue(r.onDimension(NETHER));
        assertEquals(Phase.NETHER_LEG, r.phase());
    }

    @Test
    void portalWaitFailsWhenNotInPortalOrTooSlow() {
        PortalHubRoute r = route(true);
        r.onLegArrived();
        for (int i = 0; i <= PortalHubRoute.NOT_IN_PORTAL_TICKS; i++) {
            r.tickWait(false);
        }
        assertEquals(Phase.FAILED, r.phase());
        assertEquals("not_in_portal", r.failure());

        PortalHubRoute slow = route(true);
        slow.onLegArrived();
        for (int i = 0; i <= PortalHubRoute.PORTAL_TIMEOUT_TICKS; i++) {
            slow.tickWait(true);
        }
        assertEquals("portal_timeout", slow.failure());
    }

    @Test
    void unexpectedDimensionFails() {
        PortalHubRoute r = route(true);
        assertTrue(r.onDimension("minecraft:the_end"));
        assertEquals(Phase.FAILED, r.phase());
        assertEquals("wrong_dimension", r.failure());
        assertFalse(r.onDimension(NETHER)); // finished routes ignore further changes
    }

    @Test
    void cancelEndsTheRoute() {
        PortalHubRoute r = route(true);
        r.cancel();
        assertTrue(r.isFinished());
        assertEquals("cancelled", r.failure());
    }
}
