package com.utilitysuite.jobs.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Furnace-array batch manager (engine free). Splits a smelt request (e.g. 192 raw iron) evenly across N remembered
 * furnace-type stations, picks one fuel per station with the least wasted burn time, orders the visits as a
 * nearest-neighbour route, and later decides which stations to collect from. Menu slot layout of every furnace-type
 * station: {@link #INPUT_SLOT}, {@link #FUEL_SLOT}, {@link #OUTPUT_SLOT}.
 * <p>
 * Blast furnaces and smokers smelt twice as fast and burn fuel twice as fast, so one fuel item covers half as many
 * items there ({@link #itemsPerFuel}). Each station's input and fuel slot hold one stack (64, lava bucket 1); input
 * that does not fit in this round is returned as {@link Batch#unassigned()} for a second round.
 */
public final class FurnaceBatchPlanner {

    public static final int INPUT_SLOT = 0;
    public static final int FUEL_SLOT = 1;
    public static final int OUTPUT_SLOT = 2;
    public static final int STACK = 64;
    /** Ticks per item in a plain furnace (200); blast furnace and smoker take half. */
    public static final int FURNACE_TICKS = 200;

    public static final String FURNACE = "minecraft:furnace";
    public static final String BLAST_FURNACE = "minecraft:blast_furnace";
    public static final String SMOKER = "minecraft:smoker";

    /**
     * What is known about one station.
     *
     * @param inputItem  item already in the input slot (null when empty); a different item blocks the station
     * @param inputCount count already in the input slot
     * @param fuelItem   fuel already in the fuel slot (null when empty)
     * @param fuelCount  count already in the fuel slot
     */
    public record Station(long pos, String type, String inputItem, int inputCount, String fuelItem, int fuelCount,
                          String outputItem, int outputCount) {
        public static Station empty(long pos, String type) {
            return new Station(pos, type, null, 0, null, 0, null, 0);
        }
    }

    /** One station's share: put {@code input} items and {@code fuelCount} of {@code fuelItem} in, expect output. */
    public record Load(long pos, String type, int input, String fuelItem, int fuelCount, double wastedSmelts,
                       int readyTicks) {
    }

    /**
     * @param loads       per-station loads in visit order
     * @param unassigned  input left for a later round (all stations full or blocked)
     * @param fuelShort   true when the available fuel could not cover every load (some loads have fewer items)
     */
    public record Batch(List<Load> loads, int unassigned, Map<String, Integer> fuelUsed, boolean fuelShort) {
        public int totalInput() {
            int s = 0;
            for (Load l : loads) {
                s += l.input();
            }
            return s;
        }
    }

    private final FuelTable fuels;

    public FurnaceBatchPlanner(FuelTable fuels) {
        this.fuels = fuels;
    }

    /** Smelt operations one fuel item gives at this station type. */
    public double itemsPerFuel(String fuelItem, String stationType) {
        double base = fuels.smeltsPer(fuelItem);
        return FURNACE.equals(stationType) ? base : base / 2.0;
    }

    public static int ticksPerItem(String stationType) {
        return FURNACE.equals(stationType) ? FURNACE_TICKS : FURNACE_TICKS / 2;
    }

    private static int fuelStack(String fuelItem) {
        return fuelItem.endsWith("_bucket") ? 1 : STACK;
    }

    /**
     * Plans one round.
     *
     * @param item      item to smelt
     * @param count     how many
     * @param stations  usable stations (the caller passes only types that accept the item)
     * @param available fuel item -> count carried/fetchable; only {@link FuelTable#consumableFuels()} are used
     * @param fromPos   player position, start of the visit route
     */
    public Batch plan(String item, int count, List<Station> stations, Map<String, Integer> available, long fromPos) {
        List<Station> usable = new ArrayList<>();
        for (Station s : stations) {
            if (s.inputItem() == null || s.inputItem().equals(item)) {
                usable.add(s);
            }
        }
        List<Station> route = route(usable, fromPos);
        int n = route.size();
        int[] share = new int[n];
        // Even split capped by free input space; spill the remainder to stations with room.
        int left = Math.max(0, count);
        boolean progress = true;
        while (left > 0 && progress) {
            progress = false;
            int open = 0;
            for (int i = 0; i < n; i++) {
                if (room(route.get(i)) - share[i] > 0) {
                    open++;
                }
            }
            if (open == 0) {
                break;
            }
            int each = Math.max(1, left / open);
            int extra = left >= open ? left % open : 0;
            for (int i = 0; i < n && left > 0; i++) {
                int free = room(route.get(i)) - share[i];
                if (free <= 0) {
                    continue;
                }
                int want = each + (extra > 0 ? 1 : 0);
                if (extra > 0) {
                    extra--;
                }
                int give = Math.min(Math.min(want, free), left);
                if (give > 0) {
                    share[i] += give;
                    left -= give;
                    progress = true;
                }
            }
        }

        Map<String, Integer> stock = new TreeMap<>(available);
        Map<String, Integer> used = new TreeMap<>();
        List<Load> loads = new ArrayList<>();
        boolean fuelShort = false;
        for (int i = 0; i < n; i++) {
            if (share[i] <= 0) {
                continue;
            }
            Station s = route.get(i);
            int items = share[i];
            // smelts already paid for by fuel sitting in the slot (only counts toward this load)
            double covered = s.fuelItem() == null ? 0 : s.fuelCount() * itemsPerFuel(s.fuelItem(), s.type());
            double needed = Math.max(0, items + s.inputCount() - covered);
            Choice c = needed <= 0 ? null : chooseFuel(needed, s, stock);
            if (needed > 0 && c == null) {
                // not enough of any single fuel: use the best partial and shrink the load to what it covers
                Choice p = partialFuel(s, stock);
                fuelShort = true;
                if (p == null) {
                    left += items;
                    continue;
                }
                int coverable = (int) Math.floor(covered + p.count * itemsPerFuel(p.item, s.type())) - s.inputCount();
                coverable = Math.max(0, Math.min(items, coverable));
                left += items - coverable;
                items = coverable;
                c = new Choice(p.item, p.count, 0);
                if (items == 0) {
                    continue;
                }
            }
            String fuelItem = c == null ? null : c.item;
            int fuelCount = c == null ? 0 : c.count;
            if (c != null) {
                stock.merge(c.item, -c.count, Integer::sum);
                used.merge(c.item, c.count, Integer::sum);
            }
            double burn = covered + (c == null ? 0 : c.count * itemsPerFuel(c.item, s.type()));
            double waste = Math.max(0, burn - (items + s.inputCount()));
            loads.add(new Load(s.pos(), s.type(), items, fuelItem, fuelCount, waste,
                    (items + s.inputCount()) * ticksPerItem(s.type())));
        }
        return new Batch(List.copyOf(loads), left, Map.copyOf(used), fuelShort);
    }

    private record Choice(String item, int count, double waste) {
    }

    /** Free input space for the item (also capped by the output stack left free). */
    private static int room(Station s) {
        int in = STACK - s.inputCount();
        int out = s.outputItem() == null ? STACK : STACK - s.outputCount();
        return Math.max(0, Math.min(in, out - s.inputCount()));
    }

    /** Single fuel type covering {@code needed} smelts with the least waste; ties keep the table's preference order. */
    private Choice chooseFuel(double needed, Station s, Map<String, Integer> stock) {
        Choice best = null;
        for (String f : fuels.consumableFuels()) {
            if (s.fuelItem() != null && !s.fuelItem().equals(f)) {
                continue; // the slot already holds another fuel
            }
            double per = itemsPerFuel(f, s.type());
            if (per <= 0) {
                continue;
            }
            int cnt = (int) Math.ceil(needed / per - 1e-9);
            int cap = fuelStack(f) - (s.fuelItem() == null ? 0 : s.fuelCount());
            if (cnt > stock.getOrDefault(f, 0) || cnt > cap) {
                continue;
            }
            double waste = cnt * per - needed;
            if (best == null || waste < best.waste - 1e-9) {
                best = new Choice(f, cnt, waste);
            }
        }
        return best;
    }

    /** Largest burn any single available fuel gives (all of it), or null when none fits the slot. */
    private Choice partialFuel(Station s, Map<String, Integer> stock) {
        Choice best = null;
        double bestBurn = 0;
        for (String f : fuels.consumableFuels()) {
            if (s.fuelItem() != null && !s.fuelItem().equals(f)) {
                continue;
            }
            int cnt = Math.min(stock.getOrDefault(f, 0), fuelStack(f) - (s.fuelItem() == null ? 0 : s.fuelCount()));
            double burn = cnt * itemsPerFuel(f, s.type());
            if (cnt > 0 && burn > bestBurn) {
                bestBurn = burn;
                best = new Choice(f, cnt, 0);
            }
        }
        return best;
    }

    /** Nearest-neighbour visit order from {@code from}. */
    public static List<Station> route(List<Station> stations, long from) {
        List<Station> left = new ArrayList<>(stations);
        List<Station> out = new ArrayList<>(left.size());
        long at = from;
        while (!left.isEmpty()) {
            int bi = 0;
            long bd = Long.MAX_VALUE;
            for (int i = 0; i < left.size(); i++) {
                long d = PackedPos.distSq(left.get(i).pos(), at);
                if (d < bd) {
                    bd = d;
                    bi = i;
                }
            }
            Station s = left.remove(bi);
            out.add(s);
            at = s.pos();
        }
        return out;
    }

    /**
     * Stations worth visiting to collect {@code outputItem}: those holding at least {@code minOutput} of it, or done
     * (input empty) with any output. Nearest-neighbour ordered.
     */
    public static List<Station> collectRoute(List<Station> observed, String outputItem, int minOutput, long from) {
        List<Station> pick = new ArrayList<>();
        for (Station s : observed) {
            if (!outputItem.equals(s.outputItem()) || s.outputCount() <= 0) {
                continue;
            }
            if (s.outputCount() >= minOutput || s.inputCount() == 0) {
                pick.add(s);
            }
        }
        return route(pick, from);
    }
}
