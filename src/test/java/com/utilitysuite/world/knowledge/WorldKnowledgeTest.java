package com.utilitysuite.world.knowledge;

import com.utilitysuite.core.api.CandidateQuery;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.ObservationKind;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.api.WorldObservation;
import com.utilitysuite.world.BlockPosPacking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldKnowledgeTest {

    private static final String OW = "minecraft:overworld";
    private static final String DIAMOND = "minecraft:diamond_ore";
    private static final String DEEP = "minecraft:deepslate_diamond_ore";

    /** Fake prediction source: a few predicted veins (cluster = vein). */
    private static final class FakePredictions implements PredictionSource {
        final List<long[]> entries = new ArrayList<>(); // pos, cluster

        void add(int x, int y, int z, long cluster) {
            entries.add(new long[] {BlockPosPacking.pack(x, y, z), cluster});
        }

        @Override
        public void forEachPrediction(String dimension, long origin, int radius, Visitor visitor) {
            if (!OW.equals(dimension)) {
                return;
            }
            for (long[] e : entries) {
                if (BlockPosPacking.distSq(e[0], origin) <= (long) radius * radius) {
                    visitor.visit(e[0], DEEP, DIAMOND, 0.9f, e[1]);
                }
            }
        }

        @Override
        public long clusterAt(String dimension, long pos) {
            for (long[] e : entries) {
                if (e[0] == pos) {
                    return e[1];
                }
            }
            return NO_CLUSTER;
        }

        @Override
        public String predictedIdAt(String dimension, long pos) {
            return clusterAt(dimension, pos) == NO_CLUSTER ? null : DEEP;
        }
    }

    private KnowledgeLedger ledger;
    private WorldKnowledgeService service;
    private FakePredictions predictions;
    private final List<SuiteEvent> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ledger = new KnowledgeLedger();
        service = new WorldKnowledgeService(ledger, events::add, () -> 100L);
        predictions = new FakePredictions();
        // vein 1: three blocks, vein 2: two blocks
        predictions.add(10, -50, 10, 1L);
        predictions.add(11, -50, 10, 1L);
        predictions.add(12, -50, 10, 1L);
        predictions.add(30, -40, 30, 2L);
        predictions.add(31, -40, 30, 2L);
        service.setPredictionSource(predictions);
    }

    private List<WorldObservation> query(DataSourceMode mode) {
        return service.findCandidates(new CandidateQuery(Set.of(DIAMOND, DEEP), OW, BlockPosPacking.pack(0, -50, 0),
                100, 0, mode));
    }

    @Test
    void verifiedMissesNeverAppearInCandidates() {
        long miss = BlockPosPacking.pack(10, -50, 10);
        service.recordVerifiedMiss(OW, miss, "minecraft:deepslate");
        assertTrue(service.isVerifiedMiss(OW, miss));
        for (DataSourceMode mode : DataSourceMode.values()) {
            for (WorldObservation o : query(mode)) {
                assertFalse(o.packedPos() == miss, mode + " returned a verified miss");
                assertFalse(o.kind() == ObservationKind.VERIFIED_MISS);
            }
        }
        assertEquals(1, events.size());
        SuiteEvent.VerifiedMiss e = (SuiteEvent.VerifiedMiss) events.get(0);
        assertEquals(miss, e.packedPos());
        assertEquals(DEEP, e.expectedBlockId());
        // the same miss twice is one event
        service.recordVerifiedMiss(OW, miss, "minecraft:deepslate");
        assertEquals(1, events.size());
    }

    @Test
    void missesDownrankThenSuppressTheirCluster() {
        ledger.setPolicy(2, 0.35f);
        double before = confidenceOf(BlockPosPacking.pack(11, -50, 10));
        service.recordVerifiedMiss(OW, BlockPosPacking.pack(10, -50, 10), "minecraft:deepslate");
        double after = confidenceOf(BlockPosPacking.pack(11, -50, 10));
        assertEquals(before * 0.35, after, 1e-6, "one miss downranks the rest of the vein");
        assertEquals(before, confidenceOf(BlockPosPacking.pack(30, -40, 30)), 1e-9, "other veins untouched");

        service.recordVerifiedMiss(OW, BlockPosPacking.pack(11, -50, 10), "minecraft:tuff");
        assertEquals(-1, confidenceOf(BlockPosPacking.pack(12, -50, 10)), "second miss suppresses the vein");
        assertEquals(0f, service.overlayFactor(OW, BlockPosPacking.pack(12, -50, 10), 1L));
        assertEquals(2, query(DataSourceMode.SIMULATED_ONLY).size(), "only the untouched vein remains");
    }

    private double confidenceOf(long pos) {
        for (WorldObservation o : query(DataSourceMode.SIMULATED_ONLY)) {
            if (o.packedPos() == pos) {
                return o.confidence();
            }
        }
        return -1;
    }

    @Test
    void observedAndPredictedStaySeparatePerMode() {
        long seen = BlockPosPacking.pack(5, -55, 5);
        service.recordObserved(OW, seen, DIAMOND);

        List<WorldObservation> observed = query(DataSourceMode.OBSERVED_ONLY);
        assertEquals(1, observed.size());
        assertEquals(ObservationKind.VERIFIED, observed.get(0).kind());
        assertEquals(1.0, observed.get(0).confidence());

        List<WorldObservation> simulated = query(DataSourceMode.SIMULATED_ONLY);
        assertEquals(5, simulated.size());
        for (WorldObservation o : simulated) {
            assertEquals(ObservationKind.PREDICTED, o.kind());
            assertTrue(o.confidence() < 1.0, "predictions never claim certainty");
        }

        List<WorldObservation> hybrid = query(DataSourceMode.HYBRID);
        assertEquals(6, hybrid.size());
        assertEquals(seen, hybrid.get(0).packedPos(), "a confirmed block ranks first");

        // A predicted block that was confirmed is reported once, as VERIFIED.
        long confirmed = BlockPosPacking.pack(30, -40, 30);
        service.recordObserved(OW, confirmed, DEEP);
        long both = query(DataSourceMode.HYBRID).stream().filter(o -> o.packedPos() == confirmed).count();
        assertEquals(1, both);
        assertTrue(query(DataSourceMode.SIMULATED_ONLY).stream().noneMatch(o -> o.packedPos() == confirmed));
    }

    @Test
    void overlayAndRoutingModesAreIndependent() {
        int[] changes = {0};
        service.setModeListener(() -> changes[0]++);
        service.setOverlayMode(DataSourceMode.SIMULATED_ONLY);
        service.setRoutingMode(DataSourceMode.OBSERVED_ONLY);
        assertEquals(DataSourceMode.SIMULATED_ONLY, service.overlayMode());
        assertEquals(DataSourceMode.OBSERVED_ONLY, service.routingMode());
        service.setRoutingMode(DataSourceMode.HYBRID);
        assertEquals(DataSourceMode.SIMULATED_ONLY, service.overlayMode());
        assertEquals(3, changes[0], "every set notifies (the module persists both)");
        // A query without an explicit mode uses the routing mode.
        List<WorldObservation> r = service.findCandidates(new CandidateQuery(Set.of(DIAMOND, DEEP), OW,
                BlockPosPacking.pack(0, -50, 0), 100, 0, null));
        assertEquals(5, r.size());
    }

    @Test
    void ledgerRoundTripsThroughItsBinaryForm() throws Exception {
        ledger.setPolicy(3, 0.5f);
        service.recordVerifiedMiss(OW, BlockPosPacking.pack(10, -50, 10), "minecraft:stone");
        service.recordVerifiedMiss("minecraft:the_nether", BlockPosPacking.pack(-5, 20, 7), "minecraft:netherrack");
        service.recordObserved(OW, BlockPosPacking.pack(1, 2, 3), DIAMOND);
        service.recordObserved(OW, BlockPosPacking.pack(-1, -2, -3), "minecraft:emerald_ore");
        assertTrue(ledger.isDirty());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ledger.write(new DataOutputStream(bytes));
        KnowledgeLedger copy = new KnowledgeLedger();
        copy.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertTrue(copy.isMiss(OW, BlockPosPacking.pack(10, -50, 10)));
        assertTrue(copy.isMiss("minecraft:the_nether", BlockPosPacking.pack(-5, 20, 7)));
        assertEquals(1, copy.clusterMisses(OW, 1L));
        assertEquals(DIAMOND, copy.observedAt(OW, BlockPosPacking.pack(1, 2, 3)));
        assertEquals("minecraft:emerald_ore", copy.observedAt(OW, BlockPosPacking.pack(-1, -2, -3)));
        assertNull(copy.observedAt(OW, BlockPosPacking.pack(9, 9, 9)));
        assertEquals(ledger.missCount(OW), copy.missCount(OW));
        assertEquals(ledger.observedCount(OW), copy.observedCount(OW));
        // compact: two misses + two observations fit in well under a kilobyte
        assertTrue(bytes.size() < 512, "ledger is " + bytes.size() + " bytes");
    }

    @Test
    void verificationTrackerRecordsHitsAndMisses() {
        VerificationTracker tracker = new VerificationTracker(service);
        tracker.refill(predictions, OW, BlockPosPacking.pack(0, -50, 0), 100, 100);
        assertEquals(5, tracker.pending());
        int misses = tracker.step((x, y, z) -> {
            if (x == 30 && z == 30) {
                return null; // not loaded: nothing recorded
            }
            return x == 10 ? "minecraft:deepslate" : DEEP;
        }, 100);
        assertEquals(1, misses);
        assertTrue(ledger.isMiss(OW, BlockPosPacking.pack(10, -50, 10)));
        assertEquals(DEEP, ledger.observedAt(OW, BlockPosPacking.pack(11, -50, 10)));
        assertNull(ledger.observedAt(OW, BlockPosPacking.pack(30, -40, 30)));
        assertFalse(ledger.isMiss(OW, BlockPosPacking.pack(30, -40, 30)));
    }
}
