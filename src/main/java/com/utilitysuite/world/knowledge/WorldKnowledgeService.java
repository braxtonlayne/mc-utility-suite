package com.utilitysuite.world.knowledge;

import com.utilitysuite.core.api.CandidateQuery;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.IWorldKnowledgeService;
import com.utilitysuite.core.api.ObservationKind;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.api.WorldObservation;
import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Keeps confirmed world data (observed blocks) and seed predictions strictly apart and answers candidate queries
 * according to an independent {@link DataSourceMode} for overlays and for routing.
 * <ul>
 *   <li>{@link DataSourceMode#OBSERVED_ONLY}: blocks seen in loaded chunks plus confirmations stored in the ledger.</li>
 *   <li>{@link DataSourceMode#SIMULATED_ONLY}: predictions only, minus verified misses, downranked per cluster.</li>
 *   <li>{@link DataSourceMode#HYBRID}: both; observations first (confidence 1), predictions where nothing was seen.</li>
 * </ul>
 * Results never contain {@link ObservationKind#VERIFIED_MISS} entries. Thread-safe; reads build fresh lists.
 */
public final class WorldKnowledgeService implements IWorldKnowledgeService {

    /** Default reach of a query when the caller passes a non-positive radius. */
    public static final int DEFAULT_RADIUS = 128;

    private final KnowledgeLedger ledger;
    private volatile PredictionSource predictions = PredictionSource.NONE;
    private volatile ObservationSource observations = ObservationSource.NONE;
    private final Consumer<SuiteEvent> eventSink;
    private final LongSupplier gameTime;
    private volatile DataSourceMode overlayMode = DataSourceMode.HYBRID;
    private volatile DataSourceMode routingMode = DataSourceMode.OBSERVED_ONLY;
    private volatile Runnable modeListener = () -> { };

    public WorldKnowledgeService(KnowledgeLedger ledger, Consumer<SuiteEvent> eventSink, LongSupplier gameTime) {
        this.ledger = Objects.requireNonNull(ledger);
        this.eventSink = eventSink == null ? e -> { } : eventSink;
        this.gameTime = gameTime == null ? () -> 0L : gameTime;
    }

    public KnowledgeLedger ledger() {
        return ledger;
    }

    public void setPredictionSource(PredictionSource source) {
        predictions = source == null ? PredictionSource.NONE : source;
    }

    public void setObservationSource(ObservationSource source) {
        observations = source == null ? ObservationSource.NONE : source;
    }

    /** Called after either mode changes (the module persists them). */
    public void setModeListener(Runnable listener) {
        modeListener = listener == null ? () -> { } : listener;
    }

    @Override
    public DataSourceMode overlayMode() {
        return overlayMode;
    }

    @Override
    public void setOverlayMode(DataSourceMode mode) {
        overlayMode = Objects.requireNonNull(mode);
        modeListener.run();
    }

    @Override
    public DataSourceMode routingMode() {
        return routingMode;
    }

    @Override
    public void setRoutingMode(DataSourceMode mode) {
        routingMode = Objects.requireNonNull(mode);
        modeListener.run();
    }

    /** Sets both modes without notifying (profile load). */
    public void loadModes(DataSourceMode overlay, DataSourceMode routing) {
        overlayMode = overlay;
        routingMode = routing;
    }

    @Override
    public List<WorldObservation> findCandidates(CandidateQuery query) {
        String dim = query.dimension();
        int radius = query.radius() > 0 ? query.radius() : DEFAULT_RADIUS;
        long origin = query.origin();
        DataSourceMode mode = query.mode() == null ? routingMode : query.mode();
        long now = gameTime.getAsLong();
        List<WorldObservation> out = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet();

        if (mode != DataSourceMode.SIMULATED_ONLY) {
            KnowledgeLedger.ObservedVisitor add = (pos, id) -> {
                if (seen.add(pos)) {
                    out.add(new WorldObservation(pos, dim, id, ObservationKind.VERIFIED, 1.0, now));
                }
            };
            observations.forEachObserved(dim, query.blockIds(), origin, radius, add);
            ledger.forEachObserved(dim, query.blockIds(), origin, radius, add);
        }
        if (mode != DataSourceMode.OBSERVED_ONLY) {
            predictions.forEachPrediction(dim, origin, radius, (pos, id, alt, conf, cluster) -> {
                if (!query.blockIds().contains(id) && (alt == null || !query.blockIds().contains(alt))) {
                    return;
                }
                if (seen.contains(pos) || ledger.isMiss(dim, pos) || ledger.observedAt(dim, pos) != null) {
                    return;
                }
                float factor = ledger.clusterFactor(dim, cluster);
                if (factor <= 0f) {
                    return; // cluster suppressed after repeated verified misses
                }
                seen.add(pos);
                String reported = query.blockIds().contains(id) ? id : alt;
                out.add(new WorldObservation(pos, dim, reported, ObservationKind.PREDICTED,
                        Math.min(0.99, conf * factor), now));
            });
        }
        out.sort(Comparator.comparingDouble(o -> score(o, origin)));
        int max = query.maxResults() > 0 ? query.maxResults() : out.size();
        return out.size() > max ? List.copyOf(out.subList(0, max)) : List.copyOf(out);
    }

    /** Lower is better: distance stretched by uncertainty, so a near guess does not beat a confirmed block nearby. */
    static double score(WorldObservation o, long origin) {
        double dist = Math.sqrt((double) BlockPosPacking.distSq(o.packedPos(), origin));
        return (dist + 4.0) / Math.max(0.05, o.confidence());
    }

    @Override
    public void recordVerifiedMiss(String dimension, long packedPos, String actualBlockId) {
        long cluster = predictions.clusterAt(dimension, packedPos);
        String expected = predictions.predictedIdAt(dimension, packedPos);
        if (cluster == PredictionSource.NO_CLUSTER) {
            cluster = spatialCluster(packedPos);
        }
        if (ledger.recordMiss(dimension, packedPos, cluster)) {
            eventSink.accept(new SuiteEvent.VerifiedMiss(System.currentTimeMillis(), packedPos, dimension,
                    expected == null ? "unknown" : expected));
        }
    }

    /** Fallback cluster for a miss with no live prediction: the 8x8x8 cell around it. */
    static long spatialCluster(long pos) {
        return Long.MIN_VALUE + 1 + BlockPosPacking.pack(BlockPosPacking.x(pos) >> 3, BlockPosPacking.y(pos) >> 3,
                BlockPosPacking.z(pos) >> 3);
    }

    @Override
    public void recordObserved(String dimension, long packedPos, String blockId) {
        ledger.recordObserved(dimension, packedPos, blockId);
    }

    @Override
    public boolean isVerifiedMiss(String dimension, long packedPos) {
        return ledger.isMiss(dimension, packedPos);
    }

    /** Overlay helper: should this predicted cluster be drawn, and how strongly (0 = hidden)? */
    public float overlayFactor(String dimension, long pos, long cluster) {
        if (ledger.isMiss(dimension, pos)) {
            return 0f;
        }
        return ledger.clusterFactor(dimension, cluster);
    }
}
