package com.utilitysuite.jobs.planner;

/** Primitive (dimension, packed position) test, so hot loops do not box positions. */
@FunctionalInterface
public interface PositionPredicate {
    boolean test(String dimension, long packedPos);

    PositionPredicate NONE = (d, p) -> false;
}
