package com.utilitysuite.tools.villager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerLogicTest {

    // ---- offer matching -----------------------------------------------------------------------------------------

    @Test
    void normalizeAddsNamespace() {
        assertEquals("minecraft:mending", BookOfferMatch.normalize(" Mending "));
        assertEquals("mod:thing", BookOfferMatch.normalize("mod:thing"));
        assertEquals("", BookOfferMatch.normalize("  "));
        assertEquals("", BookOfferMatch.normalize(null));
    }

    @Test
    void matchChecksIdLevelAndPrice() {
        String t = BookOfferMatch.normalize("efficiency");
        assertTrue(BookOfferMatch.matches("minecraft:efficiency", 4, 20, t, 3, 30));
        assertFalse(BookOfferMatch.matches("minecraft:unbreaking", 4, 20, t, 3, 30));
        assertFalse(BookOfferMatch.matches("minecraft:efficiency", 2, 20, t, 3, 30));
        assertFalse(BookOfferMatch.matches("minecraft:efficiency", 4, 31, t, 3, 30));
        assertTrue(BookOfferMatch.matches("minecraft:efficiency", 4, 64, t, 3, 0), "0 = any price");
        assertFalse(BookOfferMatch.matches("minecraft:efficiency", 4, -1, t, 3, 30), "non-emerald price with a cap");
        assertFalse(BookOfferMatch.matches("minecraft:efficiency", 4, 5, "", 1, 0), "no target never matches");
    }

    // ---- bulk trade ---------------------------------------------------------------------------------------------

    @Test
    void bulkStopConditions() {
        assertEquals(BulkTradeRun.Stop.SCREEN_CLOSED, BulkTradeRun.check(false, 0, 12, true, true));
        assertEquals(BulkTradeRun.Stop.LOCKED, BulkTradeRun.check(true, 12, 12, true, true));
        assertEquals(BulkTradeRun.Stop.OUT_OF_INPUTS, BulkTradeRun.check(true, 3, 12, false, true));
        assertEquals(BulkTradeRun.Stop.INVENTORY_FULL, BulkTradeRun.check(true, 3, 12, true, false));
        assertEquals(BulkTradeRun.Stop.NONE, BulkTradeRun.check(true, 3, 12, true, true));
    }

    @Test
    void bulkIsPacedAndStopsWhenLocked() {
        BulkTradeRun r = new BulkTradeRun();
        r.start(2);
        int trades = 0;
        int uses = 0;
        for (int t = 0; t < 100 && r.running(); t++) {
            if (r.tick(true, uses, 4, true, true, 5, 0) == BulkTradeRun.Action.TRADE) {
                trades++;
                uses++;
            }
        }
        assertEquals(4, trades);
        assertEquals(BulkTradeRun.Stop.LOCKED, r.stopReason());
        assertEquals(2, r.offerIndex());

        r.start(0);
        assertEquals(BulkTradeRun.Action.TRADE, r.tick(true, 0, 99, true, true, 3, 0));
        assertEquals(BulkTradeRun.Action.NONE, r.tick(true, 0, 99, true, true, 3, 0));
        assertEquals(BulkTradeRun.Action.NONE, r.tick(true, 0, 99, true, true, 3, 0));
        assertEquals(BulkTradeRun.Action.NONE, r.tick(true, 0, 99, true, true, 3, 0));
        assertEquals(BulkTradeRun.Action.TRADE, r.tick(true, 0, 99, true, true, 3, 0), "3 ticks between trades");
    }

    @Test
    void bulkStopsOnInputsRoomCapAndManual() {
        BulkTradeRun r = new BulkTradeRun();
        r.start(0);
        r.tick(true, 0, 99, false, true, 1, 0);
        assertEquals(BulkTradeRun.Stop.OUT_OF_INPUTS, r.stopReason());
        r.start(0);
        r.tick(true, 0, 99, true, false, 1, 0);
        assertEquals(BulkTradeRun.Stop.INVENTORY_FULL, r.stopReason());
        r.start(0);
        r.tick(true, 0, 99, true, true, 1, 1);
        r.tick(true, 0, 99, true, true, 1, 1);
        r.tick(true, 0, 99, true, true, 1, 1);
        assertEquals(BulkTradeRun.Stop.MAX_TRADES, r.stopReason());
        assertEquals(1, r.trades());
        r.start(0);
        r.stop(BulkTradeRun.Stop.MANUAL);
        assertFalse(r.running());
        assertEquals(BulkTradeRun.Action.NONE, r.tick(true, 0, 99, true, true, 1, 0));
        r.start(-1);
        assertFalse(r.running(), "no selected offer never runs");
    }

    // ---- roller -------------------------------------------------------------------------------------------------

    private static LibrarianRoller.Facts librarianAtLectern() {
        LibrarianRoller.Facts f = new LibrarianRoller.Facts();
        f.villagerPresent = true;
        f.librarian = true;
        f.lecternAtPos = true;
        f.lecternInHotbar = true;
        f.traderLevel = 1;
        return f;
    }

    @Test
    void rollerFullCycleThenSuccess() {
        LibrarianRoller r = new LibrarianRoller();
        LibrarianRoller.Facts f = librarianAtLectern();
        r.start(10);
        assertEquals(LibrarianRoller.Action.INTERACT_VILLAGER, r.tick(f));
        f.offersShown = true;
        assertEquals(LibrarianRoller.Action.CLOSE_SCREEN, r.tick(f), "wrong book: close, then break");
        assertEquals(LibrarianRoller.Phase.BREAK, r.phase());
        assertEquals(1, r.cycles());
        f.offersShown = false;
        assertEquals(LibrarianRoller.Action.BREAK_LECTERN, r.tick(f));
        f.lecternAtPos = false;
        assertEquals(LibrarianRoller.Action.NONE, r.tick(f));
        assertEquals(LibrarianRoller.Phase.WAIT_UNEMPLOYED, r.phase());
        f.librarian = false;
        f.unemployed = true;
        r.tick(f);
        assertEquals(LibrarianRoller.Phase.PLACE, r.phase());
        assertEquals(LibrarianRoller.Action.PLACE_LECTERN, r.tick(f));
        f.lecternAtPos = true;
        r.tick(f);
        assertEquals(LibrarianRoller.Phase.WAIT_EMPLOYED, r.phase());
        f.unemployed = false;
        f.librarian = true;
        r.tick(f);
        assertEquals(LibrarianRoller.Phase.OPEN, r.phase());
        f.offersShown = true;
        f.wantedOffered = true;
        r.tick(f);
        assertFalse(r.running());
        assertEquals(LibrarianRoller.Stop.SUCCESS, r.stopReason());
        assertEquals(1, r.cycles());
    }

    @Test
    void rollerStopsWhenVillagerWasTradedWith() {
        LibrarianRoller r = new LibrarianRoller();
        LibrarianRoller.Facts f = librarianAtLectern();
        f.offersShown = true;
        f.traderXp = 5;
        r.start(10);
        assertEquals(LibrarianRoller.Action.NONE, r.tick(f), "never breaks the lectern of a traded villager");
        assertEquals(LibrarianRoller.Stop.TRADED_WITH, r.stopReason());

        f.traderXp = 0;
        f.traderLevel = 2;
        r.start(10);
        r.tick(f);
        assertEquals(LibrarianRoller.Stop.TRADED_WITH, r.stopReason());
    }

    @Test
    void rollerStopsAtCycleCapAndOnMissingLectern() {
        LibrarianRoller r = new LibrarianRoller();
        LibrarianRoller.Facts f = librarianAtLectern();
        f.offersShown = true;
        r.start(1);
        assertEquals(LibrarianRoller.Action.CLOSE_SCREEN, r.tick(f));
        assertEquals(LibrarianRoller.Stop.MAX_CYCLES, r.stopReason());

        LibrarianRoller.Facts g = librarianAtLectern();
        r.start(5);
        g.offersShown = true;
        r.tick(g); // -> BREAK
        g.offersShown = false;
        g.lecternAtPos = false;
        r.tick(g); // -> WAIT_UNEMPLOYED
        g.unemployed = true;
        g.librarian = false;
        r.tick(g); // -> PLACE
        g.lecternInHotbar = false;
        r.tick(g);
        assertEquals(LibrarianRoller.Stop.NO_LECTERN_ITEM, r.stopReason());
    }

    @Test
    void rollerTimeoutsAndVillagerLoss() {
        LibrarianRoller r = new LibrarianRoller();
        LibrarianRoller.Facts f = librarianAtLectern();
        r.start(5);
        for (int i = 0; i <= LibrarianRoller.OPEN_TIMEOUT + 1 && r.running(); i++) {
            r.tick(f);
        }
        assertEquals(LibrarianRoller.Stop.OPEN_TIMEOUT, r.stopReason());

        r.start(5);
        f.villagerPresent = false;
        r.tick(f);
        assertEquals(LibrarianRoller.Stop.VILLAGER_LOST, r.stopReason());

        f.villagerPresent = true;
        f.offersShown = true;
        r.start(5);
        r.tick(f); // BREAK
        f.offersShown = false;
        for (int i = 0; i <= LibrarianRoller.BREAK_TIMEOUT + 1 && r.running(); i++) {
            r.tick(f);
        }
        assertEquals(LibrarianRoller.Stop.BREAK_TIMEOUT, r.stopReason());

        r.start(5);
        r.stop(LibrarianRoller.Stop.MANUAL);
        assertEquals(LibrarianRoller.Action.NONE, r.tick(f));
        assertEquals(LibrarianRoller.Stop.MANUAL, r.stopReason());
    }
}
