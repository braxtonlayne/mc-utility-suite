package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.movement.ClutchChain.Fallback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClutchChainTest {

    static final double FAST_TURN = 22.5; // 450 deg/s, the core's cap

    /** Vanilla free fall from rest at {@code height} above the landing: heights per tick, returns ticks to impact. */
    static int fall(double height, double[] out) {
        double y = height;
        double vy = 0.0;
        int t = 0;
        out[0] = y;
        while (y > 0.0) {
            vy = (vy - 0.08) * 0.98;
            y = Math.max(0.0, y + vy);
            out[++t] = y;
        }
        return t;
    }

    private final double[] h = new double[200];
    private final ClutchChain chain = new ClutchChain();

    @Test
    void netherExcludesWater() {
        int n = fall(20, h);
        int items = Fallback.WATER_BUCKET.bit() | Fallback.COBWEB.bit();
        assertEquals(Fallback.WATER_BUCKET, chain.choose(items, false, h, n, 0f, FAST_TURN, true));
        assertEquals(Fallback.COBWEB, chain.choose(items, true, h, n, 0f, FAST_TURN, true));
        assertNull(chain.choose(Fallback.WATER_BUCKET.bit(), true, h, n, 0f, FAST_TURN, true));
    }

    @Test
    void itemAvailabilityFollowsChainOrder() {
        int n = fall(20, h);
        int all = 0;
        for (Fallback f : ClutchChain.CHAIN) {
            all |= f.bit();
        }
        assertEquals(Fallback.WATER_BUCKET, chain.choose(all, false, h, n, 0f, FAST_TURN, true));
        assertEquals(Fallback.POWDER_SNOW, chain.choose(all, true, h, n, 0f, FAST_TURN, true));
        int noBlocks = Fallback.WIND_CHARGE.bit() | Fallback.BOAT.bit() | Fallback.TWISTING_VINES.bit();
        assertEquals(Fallback.BOAT, chain.choose(noBlocks, false, h, n, 0f, FAST_TURN, true));
        assertEquals(ClutchChain.entersVines(h, n) ? Fallback.TWISTING_VINES : Fallback.WIND_CHARGE,
                chain.choose(noBlocks & ~Fallback.BOAT.bit(), false, h, n, 0f, FAST_TURN, true));
        assertEquals(Fallback.WIND_CHARGE, chain.choose(Fallback.WIND_CHARGE.bit(), false, h, n, 0f, FAST_TURN, true));
        assertNull(chain.choose(0, false, h, n, 0f, FAST_TURN, true));
    }

    @Test
    void unplaceableLandingFallsThroughToTheWindCharge() {
        int n = fall(20, h);
        int items = Fallback.SLIME_OR_HAY.bit() | Fallback.BOAT.bit() | Fallback.WIND_CHARGE.bit();
        assertEquals(Fallback.WIND_CHARGE, chain.choose(items, false, h, n, 0f, FAST_TURN, false));
    }

    @Test
    void lookDownMustFitBeforeTheUseTick() {
        int n = fall(8, h); // a short fall: about 15 ticks
        int items = Fallback.BOAT.bit();
        Fallback fast = chain.choose(items, false, h, n, 0f, FAST_TURN, true);
        assertEquals(Fallback.BOAT, fast);
        int turn = FallClutchPlanner.turnTicks(ClutchChain.PLACE_PITCH, FAST_TURN);
        assertTrue(chain.useTick() >= turn, "use after the smoothed turn");
        assertTrue(chain.useTick() <= n - Fallback.BOAT.lead, "boat spawned before the feet arrive");
        assertTrue(h[chain.useTick()] + FallClutchPlanner.EYE <= FallClutchPlanner.REACH, "landing within reach");
        // a slow head cannot look down in time
        assertNull(chain.choose(items, false, h, n, -40f, 4.0, true));
        // already looking down: nothing to turn
        assertEquals(Fallback.BOAT, chain.choose(items, false, h, n, 85f, 4.0, true));
    }

    @Test
    void windChargeBurstsBeforeImpactWithThePlayerInReach() {
        int n = fall(40, h);
        assertEquals(Fallback.WIND_CHARGE, chain.choose(Fallback.WIND_CHARGE.bit(), false, h, n, 0f, FAST_TURN,
                true));
        int u = chain.useTick();
        int burst = ClutchChain.burstTick(h, n, u);
        assertTrue(burst > u && burst < n, "bursts on the ground before the feet land");
        assertTrue(h[burst] <= ClutchChain.WIND_REACH, "player inside the burst");
        assertEquals(ClutchChain.THROW_PITCH, chain.aimPitch());
        // too short a fall for the head to come down: nothing
        int m = fall(3, h);
        assertNull(chain.choose(Fallback.WIND_CHARGE.bit(), false, h, m, -60f, 3.0, true));
    }

    /** Vines only reset a fall when some tick starts with the feet inside their cell. */
    @Test
    void vinesOnlyWhenATickStartsInsideTheVineCell() {
        // a drop whose last airborne tick starts a block or more up: it passes the vine cell within one tick
        double skip = 12.0;
        int n = fall(skip, h);
        while (h[n - 1] < ClutchChain.VINE_CELL && skip < 40.0) {
            skip += 0.25;
            n = fall(skip, h);
        }
        assertTrue(h[n - 1] >= ClutchChain.VINE_CELL, "found a drop that passes the vine cell within one tick");
        assertNull(chain.choose(Fallback.TWISTING_VINES.bit(), false, h, n, 0f, FAST_TURN, true));
        assertEquals(Fallback.WIND_CHARGE, chain.choose(Fallback.TWISTING_VINES.bit() | Fallback.WIND_CHARGE.bit(),
                false, h, n, 0f, FAST_TURN, true));
        // some drop heights do end a tick inside the cell: there the vines are chosen
        int found = 0;
        for (double height = 4.0; height <= 30.0; height += 0.25) {
            int m = fall(height, h);
            if (ClutchChain.entersVines(h, m)
                    && chain.choose(Fallback.TWISTING_VINES.bit(), false, h, m, 0f, FAST_TURN, true) != null) {
                assertEquals(Fallback.TWISTING_VINES, chain.chosen());
                // placed a full tick before the tick that starts in the cell (the feet state is cached per tick)
                assertTrue(chain.useTick() <= m - 2, "vines used at " + chain.useTick() + " of " + m);
                found++;
            }
        }
        assertTrue(found > 0, "vines stay usable where the fall enters the cell");
    }

    @Test
    void terminalSpeedPlacementNeedsATickInReach() {
        // a very long fall: at about 3.9 blocks per tick there may be no start-of-tick eye in reach of the landing;
        // whatever is chosen must still satisfy the reach rule
        int n = fall(120, h);
        Fallback f = chain.choose(Fallback.COBWEB.bit(), false, h, n, 0f, FAST_TURN, true);
        if (f != null) {
            assertTrue(h[chain.useTick()] + FallClutchPlanner.EYE <= FallClutchPlanner.REACH);
        }
    }
}
