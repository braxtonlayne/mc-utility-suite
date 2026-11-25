package com.utilitysuite.jobs.storage.hall;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.jobs.goal.WorldActions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries out {@link HallSortTrips} with the goal framework's world actions: for each trip, walk to the source chest
 * and take ({@link WorldActions#beginFetch}, which walks, opens and clicks through the container clicker), then walk
 * to each destination and put ({@link WorldActions#beginDeposit}). Contents refresh as it goes because Storage Memory
 * re-records every container the actions open. A short fetch deposits only what was taken; a failed fetch skips the
 * trip; a failed deposit leaves those items in the inventory and is counted. One step per tick; no allocation while
 * waiting on an action.
 */
public final class HallSortRunner {

    private enum Phase { IDLE, FETCH, DEPOSIT }

    private final WorldActions actions;
    private List<HallSortTrips.Trip> trips = List.of();
    private int trip;
    private final List<Map.Entry<Long, Map<String, Integer>>> deposits = new ArrayList<>();
    private int deposit;
    private final Map<String, Integer> carried = new LinkedHashMap<>();
    private Phase phase = Phase.IDLE;
    private boolean waiting;
    private int moved;
    private int failures;
    private String status = "";

    public HallSortRunner(WorldActions actions) {
        this.actions = actions;
    }

    public void start(List<HallSortTrips.Trip> t) {
        trips = List.copyOf(t);
        trip = 0;
        moved = 0;
        failures = 0;
        waiting = false;
        phase = trips.isEmpty() ? Phase.IDLE : Phase.FETCH;
        status = trips.isEmpty() ? "Hall already sorted" : "Sorting: trip 1/" + trips.size();
    }

    public boolean isRunning() {
        return phase != Phase.IDLE;
    }

    public String status() {
        return status;
    }

    public int movedItems() {
        return moved;
    }

    public int failures() {
        return failures;
    }

    public void cancel(String why) {
        if (phase != Phase.IDLE) {
            if (waiting) {
                actions.abort();
            }
            phase = Phase.IDLE;
            waiting = false;
            status = why;
        }
    }

    /** Advances by at most one action start or completion. The caller holds an active lease. */
    public void tick(ControlLease lease) {
        if (phase == Phase.IDLE) {
            return;
        }
        HallSortTrips.Trip t = trips.get(trip);
        if (phase == Phase.FETCH) {
            if (!waiting) {
                actions.beginFetch(t.source(), t.take(), lease);
                waiting = true;
                return;
            }
            WorldActions.Result r = actions.poll();
            if (r == WorldActions.Result.RUNNING) {
                return;
            }
            waiting = false;
            carried.clear();
            if (r == WorldActions.Result.DONE) {
                carried.putAll(actions.transferred());
            }
            deposits.clear();
            for (Map.Entry<Long, Map<String, Integer>> e : t.deposits().entrySet()) {
                Map<String, Integer> want = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> w : e.getValue().entrySet()) {
                    int have = carried.getOrDefault(w.getKey(), 0);
                    int n = Math.min(have, w.getValue());
                    if (n > 0) {
                        want.put(w.getKey(), n);
                        carried.put(w.getKey(), have - n);
                    }
                }
                if (!want.isEmpty()) {
                    deposits.add(Map.entry(e.getKey(), want));
                }
            }
            if (deposits.isEmpty()) {
                failures++;
                nextTrip();
                return;
            }
            deposit = 0;
            phase = Phase.DEPOSIT;
            return;
        }
        // DEPOSIT
        Map.Entry<Long, Map<String, Integer>> d = deposits.get(deposit);
        if (!waiting) {
            actions.beginDeposit(d.getKey(), d.getValue(), lease);
            waiting = true;
            return;
        }
        WorldActions.Result r = actions.poll();
        if (r == WorldActions.Result.RUNNING) {
            return;
        }
        waiting = false;
        if (r == WorldActions.Result.DONE) {
            for (Integer n : actions.transferred().values()) {
                moved += n;
            }
        } else {
            failures++;
        }
        if (++deposit >= deposits.size()) {
            nextTrip();
        }
    }

    private void nextTrip() {
        if (++trip >= trips.size()) {
            phase = Phase.IDLE;
            status = "Hall sorted: " + moved + " items moved" + (failures > 0 ? ", " + failures + " trips short" : "");
        } else {
            phase = Phase.FETCH;
            status = "Sorting: trip " + (trip + 1) + "/" + trips.size() + " (" + moved + " moved)";
        }
    }
}
