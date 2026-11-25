package com.utilitysuite.jobs.storage.hall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link HallSortPlanner.Plan} into walking trips: per source chest, take everything leaving it (by item id,
 * capped at {@link #MAX_STACKS_PER_TRIP} stacks so it fits the inventory), then deposit at each destination. Engine
 * free.
 * <p>
 * Skipped: moves inside one chest (stack merging needs slot clicks in an open chest, not a fetch/deposit trip), and
 * enchanted-book moves out of a chest that holds books with other enchantments (a fetch by item id cannot pick one
 * enchantment). Both are counted in {@link Result#skipped()}.
 */
public final class HallSortTrips {

    public static final int MAX_STACKS_PER_TRIP = 27;

    /** Take {@code take} at {@code source}, then put {@code deposits} (destination pos -> item -> count). */
    public record Trip(long source, Map<String, Integer> take, Map<Long, Map<String, Integer>> deposits) {
    }

    public record Result(List<Trip> trips, int skipped) {
    }

    private HallSortTrips() {
    }

    public static Result build(List<HallSortPlanner.Chest> chests, HallSortPlanner.Plan plan) {
        List<Trip> trips = new ArrayList<>();
        int skipped = 0;
        // source chest index -> moves, in plan order
        Map<Integer, List<HallSortPlanner.Move>> bySource = new LinkedHashMap<>();
        for (HallSortPlanner.Move m : plan.moves()) {
            if (m.fromChest() == m.toChest()) {
                skipped++;
                continue;
            }
            if (StorageCategories.BOOKS.equals(StorageCategories.of(m.itemId()))
                    && mixedBooks(chests.get(m.fromChest()))) {
                skipped++;
                continue;
            }
            bySource.computeIfAbsent(m.fromChest(), k -> new ArrayList<>()).add(m);
        }
        for (Map.Entry<Integer, List<HallSortPlanner.Move>> e : bySource.entrySet()) {
            long src = chests.get(e.getKey()).pos;
            List<HallSortPlanner.Move> ms = e.getValue();
            for (int start = 0; start < ms.size(); start += MAX_STACKS_PER_TRIP) {
                Map<String, Integer> take = new LinkedHashMap<>();
                Map<Long, Map<String, Integer>> dep = new LinkedHashMap<>();
                for (int i = start; i < Math.min(ms.size(), start + MAX_STACKS_PER_TRIP); i++) {
                    HallSortPlanner.Move m = ms.get(i);
                    take.merge(m.itemId(), m.count(), Integer::sum);
                    dep.computeIfAbsent(chests.get(m.toChest()).pos, k -> new LinkedHashMap<>())
                            .merge(m.itemId(), m.count(), Integer::sum);
                }
                trips.add(new Trip(src, take, dep));
            }
        }
        return new Result(trips, skipped);
    }

    private static boolean mixedBooks(HallSortPlanner.Chest c) {
        String seen = null;
        for (int s = 0; s < c.item.length; s++) {
            if (c.item[s] != null && c.count[s] > 0
                    && StorageCategories.BOOKS.equals(StorageCategories.of(c.item[s]))) {
                if (seen == null) {
                    seen = c.variant[s];
                } else if (!seen.equals(c.variant[s])) {
                    return true;
                }
            }
        }
        return false;
    }
}
