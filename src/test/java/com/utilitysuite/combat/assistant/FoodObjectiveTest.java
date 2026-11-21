package com.utilitysuite.combat.assistant;

import com.utilitysuite.core.api.StorageEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodObjectiveTest {

    private static final java.util.function.Predicate<String> FOOD =
            id -> id.equals("minecraft:bread") || id.equals("minecraft:cooked_beef");

    private static StorageEntry chest(int x, int y, int z, String dim, boolean stale, Map<String, Integer> items) {
        return new StorageEntry(FoodObjective.pack(x, y, z), dim, "minecraft:chest", items, 0L, stale);
    }

    @Test
    void neededOnlyWithNoFoodAnywhereNearby() {
        assertTrue(FoodObjective.needed(0, 0));
        assertFalse(FoodObjective.needed(1, 0));
        assertFalse(FoodObjective.needed(0, 3));
    }

    @Test
    void packingMatchesBlockPosLayoutIncludingNegatives() {
        long p = FoodObjective.pack(-1234, -60, 98765);
        assertEquals(-1234, FoodObjective.unpackX(p));
        assertEquals(-60, FoodObjective.unpackY(p));
        assertEquals(98765, FoodObjective.unpackZ(p));
    }

    @Test
    void countsOnlyFoodInNearbyFreshChestsOfThisDimension() {
        String ow = "minecraft:overworld";
        List<StorageEntry> entries = List.of(
                chest(10, 64, 0, ow, false, Map.of("minecraft:bread", 3, "minecraft:cobblestone", 64)),
                chest(5, 64, 5, ow, true, Map.of("minecraft:cooked_beef", 8)), // stale
                chest(200, 64, 0, ow, false, Map.of("minecraft:bread", 9)), // far
                chest(1, 64, 1, "minecraft:the_nether", false, Map.of("minecraft:bread", 9)),
                chest(-4, 63, -4, ow, false, Map.of("minecraft:rotten_flesh", 30, "minecraft:cooked_beef", 2)));
        assertEquals(5, FoodObjective.storedNearby(entries, ow, 0.5, 64, 0.5, 32, FOOD));
    }
}
