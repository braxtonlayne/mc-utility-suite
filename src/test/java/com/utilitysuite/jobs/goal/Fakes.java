package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.CandidateQuery;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.IInventoryView;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.IWorldKnowledgeService;
import com.utilitysuite.core.api.ObservationKind;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.api.WorldObservation;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.planner.PlanStep;
import com.utilitysuite.jobs.planner.ToolTiers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Test doubles for the executor: inventory, pathing driver, world actions, world knowledge and event bus. */
final class Fakes {

    private Fakes() {
    }

    static final class Inventory implements IInventoryView {
        final Map<String, Integer> items = new HashMap<>();
        /** Carried shulker boxes: box item id + mutable contents. */
        final List<Map.Entry<String, Map<String, Integer>>> boxes = new ArrayList<>();

        void addBox(String boxId, Map<String, Integer> contents) {
            boxes.add(Map.entry(boxId, new HashMap<>(contents)));
            add(boxId, 1);
        }

        @Override
        public List<com.utilitysuite.core.api.NestedContainer> carriedContainers() {
            List<com.utilitysuite.core.api.NestedContainer> out = new ArrayList<>();
            for (Map.Entry<String, Map<String, Integer>> b : boxes) {
                out.add(new com.utilitysuite.core.api.NestedContainer(b.getKey(), b.getValue()));
            }
            return out;
        }

        void add(String id, int n) {
            items.merge(id, n, Integer::sum);
        }

        void removeAll(String id) {
            items.remove(id);
        }

        @Override
        public Map<String, Integer> counts() {
            return Map.copyOf(items);
        }

        @Override
        public int count(String itemId) {
            return items.getOrDefault(itemId, 0);
        }

        @Override
        public int bestPickaxeTier() {
            int t = 0;
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                if (e.getValue() > 0) {
                    t = Math.max(t, ToolTiers.tierOf(e.getKey()));
                }
            }
            return t;
        }
    }

    static final class Driver implements MiningDriver {
        final List<String> log = new ArrayList<>();
        Status status = Status.IDLE;
        /** When set, every new command immediately fails with this reason. */
        String alwaysFail;
        int mineCalls;
        int lastDesired;
        boolean paused;

        @Override
        public boolean isAvailable() {
            return true;
        }

        private void issued(String what) {
            log.add(what);
            paused = false;
            status = alwaysFail != null ? Status.FAILED : Status.RUNNING;
        }

        @Override
        public void mineByYield(String itemId, int desiredItemCount, List<String> candidateBlocks) {
            mineCalls++;
            lastDesired = desiredItemCount;
            issued("mine " + itemId + " x" + desiredItemCount);
        }

        @Override
        public void gotoCoord(long packedPos, int range) {
            issued("goto " + PackedPos.format(packedPos));
        }

        @Override
        public void clearArea(long a, long b) {
            issued("clear " + PackedPos.format(a) + " to " + PackedPos.format(b));
        }

        @Override
        public void followPath(long[] packedPositions) {
            issued("follow");
        }

        @Override
        public void interactBlock(long packedPos, ControlLease lease) {
            issued("interact " + PackedPos.format(packedPos));
        }

        @Override
        public void pause() {
            paused = true;
            log.add("pause");
        }

        @Override
        public void resume() {
            paused = false;
            log.add("resume");
            status = Status.RUNNING;
        }

        @Override
        public void cancel() {
            log.add("cancel");
            status = Status.IDLE;
        }

        @Override
        public Status status() {
            return status;
        }

        @Override
        public String failureReason() {
            return alwaysFail == null ? "" : alwaysFail;
        }
    }

    /** Crafting/smelting succeed instantly; fetches read the "real" contents of chests, which may differ from the index. */
    static final class Actions implements WorldActions {
        final Inventory inv;
        final Map<Long, Map<String, Integer>> realChests = new HashMap<>();
        final Map<Long, String> blocks = new HashMap<>();
        final List<String> log = new ArrayList<>();
        Result pending = Result.DONE;
        String message = "";
        Map<String, Integer> transferred = Map.of();
        long playerPos = PackedPos.pack(0, 64, 0);
        /** Hazard mask reported around any position, and the queries made for it. */
        int hazards;
        int hazardQueries;
        long lastHazardPos = PlanStep.NO_POS;

        @Override
        public int hazardsNear(long packedPos, int kinds) {
            hazardQueries++;
            lastHazardPos = packedPos;
            return hazards & kinds;
        }

        Actions(Inventory inv) {
            this.inv = inv;
        }

        /** When set, every craft fails with this message and produces nothing. */
        String craftFails;
        int craftCalls;

        @Override
        public void beginCraft(PlanStep step, ControlLease lease) {
            log.add("craft " + step.itemId());
            craftCalls++;
            if (craftFails != null) {
                message = craftFails;
                pending = Result.FAILED;
                return;
            }
            inv.add(step.itemId(), step.count());
            pending = Result.DONE;
        }

        @Override
        public void beginSmelt(PlanStep step, ControlLease lease) {
            log.add("smelt " + step.itemId());
            inv.add(step.itemId(), step.count());
            pending = Result.DONE;
        }

        @Override
        public void beginFetch(long packedPos, Map<String, Integer> items, ControlLease lease) {
            log.add("fetch " + PackedPos.format(packedPos) + " " + items);
            Map<String, Integer> chest = realChests.getOrDefault(packedPos, new HashMap<>());
            Map<String, Integer> moved = new LinkedHashMap<>();
            items.forEach((k, want) -> {
                int have = chest.getOrDefault(k, 0);
                int got = Math.min(have, want);
                if (got > 0) {
                    chest.put(k, have - got);
                    inv.add(k, got);
                    moved.put(k, got);
                }
            });
            transferred = moved;
            pending = Result.DONE;
        }

        /** When set, every shulker unpack fails with this message (e.g. no placement spot). */
        String restockFails;

        @Override
        public void beginShulkerRestock(String boxItemId, String itemId, int count, ControlLease lease) {
            log.add("unpack " + boxItemId + " " + count + " " + itemId);
            if (restockFails != null) {
                message = restockFails;
                transferred = Map.of();
                pending = Result.FAILED;
                return;
            }
            Map<String, Integer> moved = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Integer>> b : inv.boxes) {
                int have = b.getValue().getOrDefault(itemId, 0);
                if (have <= 0) {
                    continue;
                }
                int got = Math.min(have, count);
                b.getValue().put(itemId, have - got);
                inv.add(itemId, got);
                moved.put(itemId, got);
                break;
            }
            transferred = moved;
            pending = Result.DONE;
        }

        @Override
        public void beginDeposit(long packedPos, Map<String, Integer> items, ControlLease lease) {
            log.add("deposit " + items);
            pending = Result.DONE;
        }

        @Override
        public Result poll() {
            return pending;
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public Map<String, Integer> transferred() {
            return transferred;
        }

        @Override
        public void abort() {
            log.add("abort");
        }

        @Override
        public String blockAtIfLoaded(String dimension, long packedPos) {
            return blocks.get(packedPos);
        }

        @Override
        public long playerPos() {
            return playerPos;
        }

        @Override
        public String dimension() {
            return "minecraft:overworld";
        }

        @Override
        public int fortuneLevel() {
            return 0;
        }

        @Override
        public Set<String> nearbyStations() {
            return Set.of();
        }
    }

    static final class Knowledge implements IWorldKnowledgeService {
        final List<WorldObservation> predictions = new ArrayList<>();
        final List<Long> misses = new ArrayList<>();
        final List<Long> observed = new ArrayList<>();
        DataSourceMode routing = DataSourceMode.HYBRID;

        void predict(long pos, String block, double confidence) {
            predictions.add(new WorldObservation(pos, "minecraft:overworld", block, ObservationKind.PREDICTED,
                    confidence, 0));
        }

        @Override
        public DataSourceMode overlayMode() {
            return routing;
        }

        @Override
        public void setOverlayMode(DataSourceMode mode) {
        }

        @Override
        public DataSourceMode routingMode() {
            return routing;
        }

        @Override
        public void setRoutingMode(DataSourceMode mode) {
            routing = mode;
        }

        @Override
        public List<WorldObservation> findCandidates(CandidateQuery query) {
            List<WorldObservation> out = new ArrayList<>();
            for (WorldObservation o : predictions) {
                if (query.blockIds().contains(o.blockId()) && !misses.contains(o.packedPos())) {
                    out.add(o);
                }
            }
            return out;
        }

        @Override
        public void recordVerifiedMiss(String dimension, long packedPos, String actualBlockId) {
            misses.add(packedPos);
        }

        @Override
        public void recordObserved(String dimension, long packedPos, String blockId) {
            observed.add(packedPos);
        }

        @Override
        public boolean isVerifiedMiss(String dimension, long packedPos) {
            return misses.contains(packedPos);
        }
    }

    static final class Bus implements ISuiteEventBus {
        final List<SuiteEvent> events = new ArrayList<>();

        @Override
        public <E extends SuiteEvent> void subscribe(Class<E> type, Consumer<? super E> listener) {
        }

        @Override
        public void post(SuiteEvent event) {
            events.add(event);
        }

        <E extends SuiteEvent> List<E> of(Class<E> type) {
            List<E> out = new ArrayList<>();
            for (SuiteEvent e : events) {
                if (type.isInstance(e)) {
                    out.add(type.cast(e));
                }
            }
            return out;
        }
    }
}
