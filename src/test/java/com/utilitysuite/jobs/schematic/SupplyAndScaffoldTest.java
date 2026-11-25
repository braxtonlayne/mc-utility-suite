package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.IInventoryView;
import com.utilitysuite.core.api.StorageEntry;
import com.utilitysuite.jobs.schematic.bom.SupplyTrip;
import com.utilitysuite.jobs.schematic.plan.ScaffoldLedger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Roadmap J3: auto-supply between layers (shortfall, trip decision) and temporary scaffolding removal order. */
class SupplyAndScaffoldTest {

    private static final String OW = "minecraft:overworld";
    private static final long PLAYER = BlockPos.asLong(0, 64, 0);

    private static IInventoryView inv(Map<String, Integer> counts) {
        return new IInventoryView() {
            @Override
            public Map<String, Integer> counts() {
                return counts;
            }

            @Override
            public int count(String itemId) {
                return counts.getOrDefault(itemId, 0);
            }

            @Override
            public int bestPickaxeTier() {
                return 0;
            }
        };
    }

    private static Map<String, Integer> ordered(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    private static StorageEntry chest(int x, int z, Map<String, Integer> contents, boolean stale) {
        return new StorageEntry(BlockPos.asLong(x, 64, z), OW, "minecraft:chest", contents, 0L, stale);
    }

    // ---- shortfall ----------------------------------------------------------------------------------------------

    @Test
    void shortfallIsWhatTheLayerNeedsBeyondTheInventoryInPlanOrder() {
        Map<String, Integer> need = ordered("minecraft:stone_bricks", 40, "minecraft:oak_planks", 10,
                "minecraft:glass", 5);
        Map<String, Integer> s = SupplyTrip.shortfall(need, inv(Map.of("minecraft:stone_bricks", 12,
                "minecraft:glass", 64)));
        assertEquals(List.of("minecraft:stone_bricks", "minecraft:oak_planks"), List.copyOf(s.keySet()));
        assertEquals(28, s.get("minecraft:stone_bricks"));
        assertEquals(10, s.get("minecraft:oak_planks"));
        assertTrue(SupplyTrip.shortfall(Map.of("minecraft:glass", 5), inv(Map.of("minecraft:glass", 5))).isEmpty());
    }

    // ---- trip decision ------------------------------------------------------------------------------------------

    @Test
    void noTripWhenTheInventoryCoversTheLayer() {
        var d = SupplyTrip.decide(Map.of(), new AcceptanceScenario5Test.FakeStorage(List.of()), OW, PLAYER, 96,
                id -> 64, 4);
        assertEquals(SupplyTrip.Kind.NONE_NEEDED, d.kind());
        assertFalse(d.trip());
    }

    @Test
    void tripFetchesWholeStacksCappedToWhatNearbyChestsHold() {
        var storage = new AcceptanceScenario5Test.FakeStorage(List.of(
                chest(10, 0, Map.of("minecraft:stone_bricks", 500), false),
                chest(20, 0, Map.of("minecraft:oak_planks", 7), false)));
        var d = SupplyTrip.decide(ordered("minecraft:stone_bricks", 28, "minecraft:oak_planks", 10), storage, OW,
                PLAYER, 96, id -> 64, 4);
        assertEquals(SupplyTrip.Kind.TRIP, d.kind());
        assertEquals(64, d.fetch().get("minecraft:stone_bricks"), "28 missing rounds up to one stack");
        assertEquals(7, d.fetch().get("minecraft:oak_planks"), "capped to what is stored");
        assertEquals(BlockPos.asLong(10, 64, 0), d.nearestChest());
    }

    @Test
    void stackRoundingIsCappedToTheStackLimit() {
        var storage = new AcceptanceScenario5Test.FakeStorage(List.of(
                chest(5, 5, Map.of("minecraft:cobblestone", 5000), false)));
        var d = SupplyTrip.decide(Map.of("minecraft:cobblestone", 1000), storage, OW, PLAYER, 96, id -> 64, 4);
        assertEquals(256, d.fetch().get("minecraft:cobblestone"));
        var pearls = SupplyTrip.decide(Map.of("minecraft:cobblestone", 3), storage, OW, PLAYER, 96, id -> 16, 4);
        assertEquals(16, pearls.fetch().get("minecraft:cobblestone"));
    }

    @Test
    void chestsBeyondTheMaxTripDistanceAreTooFar() {
        var storage = new AcceptanceScenario5Test.FakeStorage(List.of(
                chest(300, 0, Map.of("minecraft:glass", 64), false)));
        var d = SupplyTrip.decide(Map.of("minecraft:glass", 10), storage, OW, PLAYER, 96, id -> 64, 4);
        assertEquals(SupplyTrip.Kind.TOO_FAR, d.kind());
        assertTrue(d.fetch().isEmpty());
        assertEquals(SupplyTrip.Kind.TRIP,
                SupplyTrip.decide(Map.of("minecraft:glass", 10), storage, OW, PLAYER, 400, id -> 64, 4).kind());
    }

    @Test
    void staleOrOtherDimensionContainersDoNotCount() {
        var storage = new AcceptanceScenario5Test.FakeStorage(List.of(
                chest(4, 0, Map.of("minecraft:glass", 64), true),
                new StorageEntry(BlockPos.asLong(2, 64, 0), "minecraft:the_nether", "minecraft:chest",
                        Map.of("minecraft:glass", 64), 0L, false)));
        assertEquals(SupplyTrip.Kind.NOT_STORED,
                SupplyTrip.decide(Map.of("minecraft:glass", 10), storage, OW, PLAYER, 96, id -> 64, 4).kind());
        assertEquals(SupplyTrip.Kind.NOT_STORED,
                SupplyTrip.decide(Map.of("minecraft:glass", 10), null, OW, PLAYER, 96, id -> 64, 4).kind());
    }

    // ---- scaffold removal order ---------------------------------------------------------------------------------

    @Test
    void scaffoldComesDownTopDownWithThePlayersColumnLast() {
        ScaffoldLedger ledger = new ScaffoldLedger();
        long feet = BlockPos.asLong(0, 70, 0);
        long under1 = BlockPos.asLong(0, 69, 0); // stood on
        long under2 = BlockPos.asLong(0, 68, 0); // holds it up
        long pillarTop = BlockPos.asLong(3, 72, 0);
        long pillarMid = BlockPos.asLong(3, 71, 0);
        long bridgeNear = BlockPos.asLong(1, 66, 0);
        long bridgeFar = BlockPos.asLong(4, 66, 0);
        for (long p : new long[]{under2, bridgeFar, pillarMid, under1, bridgeNear, pillarTop}) {
            ledger.record(p);
        }
        long[] order = ledger.removalOrder(feet, null);
        long[] expected = {pillarTop, pillarMid, bridgeNear, bridgeFar, under1, under2};
        long[] got = new long[ledger.size()];
        System.arraycopy(order, 0, got, 0, got.length);
        assertArrayEquals(expected, got);
        assertTrue(ScaffoldLedger.underPlayer(under1, feet));
        assertFalse(ScaffoldLedger.underPlayer(BlockPos.asLong(0, 71, 0), feet), "above the head is not support");
    }

    @Test
    void ledgerDeduplicatesAndForgets() {
        ScaffoldLedger ledger = new ScaffoldLedger();
        long p = BlockPos.asLong(1, 2, 3);
        ledger.record(p);
        ledger.record(p);
        assertEquals(1, ledger.size());
        assertTrue(ledger.contains(p));
        ledger.forget(p);
        assertTrue(ledger.isEmpty());
    }

    @Test
    void removalOrderReusesItsBufferWithoutAllocating() {
        assumeTrue(AllocationProbe.supported());
        ScaffoldLedger ledger = new ScaffoldLedger();
        for (int i = 0; i < 24; i++) {
            ledger.record(BlockPos.asLong(i % 5, 64 + i, i / 5));
        }
        long feet = BlockPos.asLong(0, 90, 0);
        long[] buf = ledger.removalOrder(feet, new long[32]);
        long bytes = AllocationProbe.measure(2_000, () -> ledger.removalOrder(feet, buf));
        assertEquals(0, bytes);
    }
}
