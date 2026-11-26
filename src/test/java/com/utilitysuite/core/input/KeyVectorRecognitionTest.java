package com.utilitysuite.core.input;

import net.minecraft.world.phys.Vec2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.3 normalizes the key vector before the movement-input event (W+A = 0.707 each); the overlay recognises that
 * vector so grants that override one axis work on raw +-1 keys again, as in 1.21.1.
 */
class KeyVectorRecognitionTest {

    @Test
    void recognisesEveryKeyCombinationVanillaBuilds() {
        for (int f = -1; f <= 1; f++) {
            for (int l = -1; l <= 1; l++) {
                Vec2 v = new Vec2(l, f).normalized(); // 26.3 KeyboardInput
                assertTrue(SharedInputOverlay.isNormalizedKeyVector(v.x, v.y, l, f), "keys f=" + f + " l=" + l);
            }
        }
    }

    @Test
    void rejectsVectorsThatDidNotComeFromTheKeys() {
        assertFalse(SharedInputOverlay.isNormalizedKeyVector(0.5f, 0.3f, 1f, 1f)); // analog stick
        assertFalse(SharedInputOverlay.isNormalizedKeyVector(0f, 1f, 1f, 1f)); // W+A held, another mod zeroed strafe
        assertFalse(SharedInputOverlay.isNormalizedKeyVector(0f, 0.3f, 0f, 1f)); // sneak-scaled elsewhere
        assertFalse(SharedInputOverlay.isNormalizedKeyVector(0f, 1f, 0f, 0f)); // no keys, yet moving
    }
}
