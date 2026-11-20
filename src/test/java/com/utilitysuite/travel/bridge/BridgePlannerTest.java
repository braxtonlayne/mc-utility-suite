package com.utilitysuite.travel.bridge;

import com.utilitysuite.travel.Packed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgePlannerTest {

    private static void assertFaceConnected(int sx, int sz, long[] ps) {
        int px = sx;
        int pz = sz;
        for (long p : ps) {
            int x = Packed.x(p);
            int z = Packed.z(p);
            assertEquals(1, Math.abs(x - px) + Math.abs(z - pz), "each block shares a face with the previous");
            px = x;
            pz = z;
        }
    }

    @Test
    void straightLineToMarker() {
        BridgePlanner.Plan plan = BridgePlanner.between(0, 64, 0, 5, 0);
        assertTrue(plan.toMarker());
        assertEquals(5, plan.length());
        for (int i = 0; i < 5; i++) {
            long p = plan.positions()[i];
            assertEquals(i + 1, Packed.x(p));
            assertEquals(64, Packed.y(p));
            assertEquals(0, Packed.z(p));
        }
    }

    @Test
    void slantedLineIsFaceConnectedAndEndsAtTheMarker() {
        BridgePlanner.Plan plan = BridgePlanner.between(10, 70, -3, 17, 1);
        assertEquals(7 + 4, plan.length());
        assertFaceConnected(10, -3, plan.positions());
        long last = plan.positions()[plan.length() - 1];
        assertEquals(17, Packed.x(last));
        assertEquals(1, Packed.z(last));
        // Stays close to the ideal line.
        for (long p : plan.positions()) {
            double t = (Packed.x(p) - 10) / 7.0;
            double idealZ = -3 + 4 * t;
            assertTrue(Math.abs(Packed.z(p) - idealZ) <= 1.0, "within a block of the line");
        }
    }

    @Test
    void facingPlansSnapToEightDirections() {
        BridgePlanner.Plan south = BridgePlanner.facing(0, 64, 0, 3.0, 4); // yaw ~0 = +z
        assertFalse(south.toMarker());
        assertEquals(4, south.length());
        assertEquals(Packed.pack(0, 64, 4), south.positions()[3]);

        BridgePlanner.Plan west = BridgePlanner.facing(0, 64, 0, 88.0, 3); // yaw 90 = -x
        assertEquals(Packed.pack(-3, 64, 0), west.positions()[2]);

        BridgePlanner.Plan diagonal = BridgePlanner.facing(0, 64, 0, -45.0, 6); // +x +z
        assertEquals(6, diagonal.length());
        assertFaceConnected(0, 0, diagonal.positions());
        long last = diagonal.positions()[5];
        assertTrue(Packed.x(last) > 0 && Packed.z(last) > 0);
    }

    @Test
    void stopsAtMarkerBudgetAndHotbarReserve() {
        assertEquals(BridgeStop.NONE, BridgeStop.check(2, 10, true, 2, 64, 30, 4));
        assertEquals(BridgeStop.AT_MARKER, BridgeStop.check(10, 10, true, 9, 64, 30, 4));
        assertEquals(BridgeStop.PLAN_DONE, BridgeStop.check(10, 10, false, 10, 64, 30, 4));
        assertEquals(BridgeStop.BUDGET, BridgeStop.check(5, 10, true, 5, 5, 30, 4));
        assertEquals(BridgeStop.NONE, BridgeStop.check(5, 10, true, 500, 0, 30, 4), "budget 0 = unlimited");
        assertEquals(BridgeStop.HOTBAR_LOW, BridgeStop.check(5, 10, true, 1, 64, 4, 4));
        assertEquals(BridgeStop.AT_MARKER, BridgeStop.check(10, 10, true, 64, 64, 0, 4),
                "reaching the marker wins over budget and hotbar on the last block");
    }

    @Test
    void simulatedRunPlacesExactlyTheBudget() {
        BridgePlanner.Plan plan = BridgePlanner.between(0, 64, 0, 40, 0);
        int placed = 0;
        int index = 0;
        int hotbar = 64;
        BridgeStop stop;
        while ((stop = BridgeStop.check(index, plan.length(), plan.toMarker(), placed, 25, hotbar, 4))
                == BridgeStop.NONE) {
            placed++;
            index++;
            hotbar--;
        }
        assertEquals(BridgeStop.BUDGET, stop);
        assertEquals(25, placed);
    }

    @Test
    void moveKeysAreCameraRelative() {
        assertEquals(MoveKeys.FORWARD, MoveKeys.forDirection(0, 0));
        assertEquals(MoveKeys.BACK, MoveKeys.forDirection(0, 180));
        assertEquals(MoveKeys.BACK, MoveKeys.forDirection(180, 0), "backward bridging: face back, walk back");
        assertEquals(MoveKeys.RIGHT, MoveKeys.forDirection(0, 90));
        assertEquals(MoveKeys.LEFT, MoveKeys.forDirection(0, -90));
        assertEquals(MoveKeys.FORWARD | MoveKeys.RIGHT, MoveKeys.forDirection(10, 50));
        assertEquals(MoveKeys.BACK | MoveKeys.LEFT, MoveKeys.forDirection(350, 215));
    }
}
