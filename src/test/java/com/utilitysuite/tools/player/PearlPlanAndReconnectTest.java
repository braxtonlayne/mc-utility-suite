package com.utilitysuite.tools.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PearlPlanAndReconnectTest {

    // ---- quick pearl plan ----------------------------------------------------------------------------------------

    @Test
    void pearlInSelectedSlotNeedsNothing() {
        PearlPlan p = new PearlPlan();
        assertTrue(p.plan(3, i -> i == 3));
        assertEquals(3, p.useSlot);
        assertFalse(p.needsSelect());
        assertFalse(p.needsSwap());
    }

    @Test
    void hotbarPearlIsSelectedThenRestored() {
        PearlPlan p = new PearlPlan();
        assertTrue(p.plan(0, i -> i == 6 || i == 20));
        assertEquals(6, p.useSlot, "hotbar first");
        assertEquals(0, p.restoreSlot);
        assertFalse(p.needsSwap());
    }

    @Test
    void inventoryPearlIsSwappedIntoSelectedSlot() {
        PearlPlan p = new PearlPlan();
        assertTrue(p.plan(4, i -> i == 27));
        assertEquals(4, p.useSlot, "no slot change: swapped into the selected slot");
        assertEquals(27, p.swapFromInv);
        assertFalse(p.needsSelect());
        assertEquals(27, OffhandManager.menuSlotOf(27));
        assertEquals(40, OffhandManager.menuSlotOf(4));
    }

    @Test
    void noPearl() {
        PearlPlan p = new PearlPlan();
        assertFalse(p.plan(2, i -> false));
        assertEquals(-1, p.useSlot);
    }

    // ---- reconnect policy ----------------------------------------------------------------------------------------

    @Test
    void backoffDoublesAndCaps() {
        ReconnectPolicy p = new ReconnectPolicy().configure(5, 5);
        assertEquals(100, p.delayTicks(0));
        assertEquals(200, p.delayTicks(1));
        assertEquals(400, p.delayTicks(2));
        assertEquals(ReconnectPolicy.MAX_DELAY_SECONDS * 20, p.delayTicks(30));
    }

    @Test
    void attemptCap() {
        ReconnectPolicy p = new ReconnectPolicy().configure(3, 5);
        assertTrue(p.shouldReconnect("Connection Lost Timed out", false, 2));
        assertFalse(p.shouldReconnect("Connection Lost Timed out", false, 3));
    }

    @Test
    void kickBanAndWhitelistRefused() {
        ReconnectPolicy p = new ReconnectPolicy();
        assertFalse(p.shouldReconnect("Connection Lost Kicked by an operator", false, 0));
        assertFalse(p.shouldReconnect("You are banned from this server", false, 0));
        assertFalse(p.shouldReconnect("You are not white-listed on this server!", false, 0));
        assertFalse(p.shouldReconnect("You logged in from another location", false, 0));
        assertFalse(p.shouldReconnect("Outdated client! Please use 1.21.1", false, 0));
        assertTrue(p.shouldReconnect("Connection Lost Server closed", false, 0));
        assertTrue(p.shouldReconnect("Failed to connect to the server Connection refused", false, 0));
    }

    @Test
    void manualDisconnectRefused() {
        assertFalse(new ReconnectPolicy().shouldReconnect("Connection Lost", true, 0));
    }
}
