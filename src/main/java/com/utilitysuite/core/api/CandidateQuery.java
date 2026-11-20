package com.utilitysuite.core.api;

import java.util.Set;

/**
 * A request for candidate block positions, nearest/most confident first.
 *
 * @param blockIds block registry ids to look for
 * @param origin   packed {@code BlockPos} to measure distance from
 */
public record CandidateQuery(Set<String> blockIds, String dimension, long origin, int radius, int maxResults,
                             DataSourceMode mode) {

    public CandidateQuery {
        blockIds = Set.copyOf(blockIds);
    }
}
