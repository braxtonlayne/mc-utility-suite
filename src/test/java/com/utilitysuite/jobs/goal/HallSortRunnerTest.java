package com.utilitysuite.jobs.goal;

import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.storage.hall.HallSortRunner;
import com.utilitysuite.jobs.storage.hall.HallSortTrips;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hall sort runner drives fetch-then-deposit trips through the goal framework's world actions. */
class HallSortRunnerTest {

    private static final long SRC = PackedPos.pack(0, 64, 0);
    private static final long D1 = PackedPos.pack(2, 64, 0);
    private static final long D2 = PackedPos.pack(4, 64, 0);

    private static Map<Long, Map<String, Integer>> deps(Object... kv) {
        Map<Long, Map<String, Integer>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 3) {
            m.computeIfAbsent((Long) kv[i], k -> new LinkedHashMap<>()).put((String) kv[i + 1], (Integer) kv[i + 2]);
        }
        return m;
    }

    @Test
    void fetchesThenDepositsAtEachDestination() {
        Fakes.Actions a = new Fakes.Actions(new Fakes.Inventory());
        a.realChests.put(SRC, new HashMap<>(Map.of("minecraft:cobblestone", 20, "minecraft:raw_iron", 10)));
        HallSortRunner r = new HallSortRunner(a);
        r.start(List.of(new HallSortTrips.Trip(SRC, Map.of("minecraft:cobblestone", 20, "minecraft:raw_iron", 10),
                deps(D1, "minecraft:cobblestone", 20, D2, "minecraft:raw_iron", 10))));
        for (int i = 0; i < 20 && r.isRunning(); i++) {
            r.tick(null);
        }
        assertFalse(r.isRunning());
        assertEquals(3, a.log.size());
        assertTrue(a.log.get(0).startsWith("fetch"));
        assertEquals("deposit {minecraft:cobblestone=20}", a.log.get(1));
        assertEquals("deposit {minecraft:raw_iron=10}", a.log.get(2));
        assertTrue(r.status().startsWith("Hall sorted"));
    }

    @Test
    void shortFetchDepositsOnlyWhatWasTakenAndEmptyFetchSkips() {
        Fakes.Actions a = new Fakes.Actions(new Fakes.Inventory());
        a.realChests.put(SRC, new HashMap<>(Map.of("minecraft:cobblestone", 5)));
        HallSortRunner r = new HallSortRunner(a);
        r.start(List.of(
                new HallSortTrips.Trip(SRC, Map.of("minecraft:cobblestone", 20, "minecraft:raw_iron", 10),
                        deps(D1, "minecraft:cobblestone", 20, D2, "minecraft:raw_iron", 10)),
                new HallSortTrips.Trip(D2, Map.of("minecraft:dirt", 3), deps(D1, "minecraft:dirt", 3))));
        for (int i = 0; i < 20 && r.isRunning(); i++) {
            r.tick(null);
        }
        assertFalse(r.isRunning());
        assertEquals("deposit {minecraft:cobblestone=5}", a.log.get(1));
        assertEquals(3, a.log.size(), "no deposit for iron (not taken) nor for the empty second trip");
        assertEquals(1, r.failures());
    }

    @Test
    void cancelAbortsAndStops() {
        Fakes.Actions a = new Fakes.Actions(new Fakes.Inventory());
        a.realChests.put(SRC, new HashMap<>(Map.of("minecraft:cobblestone", 5)));
        HallSortRunner r = new HallSortRunner(a);
        r.start(List.of(new HallSortTrips.Trip(SRC, Map.of("minecraft:cobblestone", 5),
                deps(D1, "minecraft:cobblestone", 5))));
        r.tick(null);
        r.cancel("stopped");
        assertFalse(r.isRunning());
        assertEquals("stopped", r.status());
    }
}
