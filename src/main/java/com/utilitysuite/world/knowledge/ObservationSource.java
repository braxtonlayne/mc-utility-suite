package com.utilitysuite.world.knowledge;

import java.util.Set;

/** Blocks currently seen in loaded chunks (the overlay scanner's index). Any thread. */
public interface ObservationSource {

    void forEachObserved(String dimension, Set<String> blockIds, long origin, int radius,
                         KnowledgeLedger.ObservedVisitor visitor);

    ObservationSource NONE = (dimension, blockIds, origin, radius, visitor) -> { };
}
