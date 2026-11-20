package com.utilitysuite.core.api;

import java.util.List;

/**
 * Keeps confirmed world data and seed predictions strictly apart. Implemented by the world module; consumed by the
 * goal planner and overlays. Reads return immutable snapshots and are safe from any thread.
 */
public interface IWorldKnowledgeService {

    /** Data source for visual overlays. */
    DataSourceMode overlayMode();

    void setOverlayMode(DataSourceMode mode);

    /** Data source for planner / Baritone routing. Independent of the overlay mode. */
    DataSourceMode routingMode();

    void setRoutingMode(DataSourceMode mode);

    /** Candidates matching the query, best first. Never contains VERIFIED_MISS entries. */
    List<WorldObservation> findCandidates(CandidateQuery query);

    /** Records that a predicted position was checked and holds something else; downranks its cluster. */
    void recordVerifiedMiss(String dimension, long packedPos, String actualBlockId);

    /** Records a confirmed sighting (e.g. the miner broke or saw the block). */
    void recordObserved(String dimension, long packedPos, String blockId);

    /** True when the position was recorded as a verified miss. */
    boolean isVerifiedMiss(String dimension, long packedPos);
}
