package com.utilitysuite.combat.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrafeBiasTest {

    @Test
    void noBiasKeepsTheInterval() {
        assertEquals(30, StrafePlanner.shaped(30, StrafePlanner.RIGHT, 0.0));
        assertEquals(30, StrafePlanner.shaped(30, StrafePlanner.LEFT, 0.3));
    }

    @Test
    void strongBiasLengthensTheMirroredDirection() {
        assertEquals(45, StrafePlanner.shaped(30, StrafePlanner.RIGHT, 1.0));
        assertEquals(15, StrafePlanner.shaped(30, StrafePlanner.LEFT, 1.0));
        assertEquals(45, StrafePlanner.shaped(30, StrafePlanner.LEFT, -1.0));
    }

    @Test
    void biasedPlannerSpendsMoreTicksOnThePreferredSide() {
        StrafePlanner p = new StrafePlanner();
        int right = 0;
        int left = 0;
        for (int t = 0; t < 600; t++) {
            int d = p.update(false, false, 30, 1.0);
            if (d == StrafePlanner.RIGHT) {
                right++;
            } else if (d == StrafePlanner.LEFT) {
                left++;
            }
        }
        assertTrue(right > left * 2, "right " + right + " left " + left);
    }
}
