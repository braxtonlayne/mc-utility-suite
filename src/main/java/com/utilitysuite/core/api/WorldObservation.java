package com.utilitysuite.core.api;

/**
 * One block-level fact or prediction.
 *
 * @param packedPos  {@code BlockPos.asLong()} encoding (keeps collections primitive-friendly)
 * @param dimension  dimension id, e.g. {@code minecraft:overworld}
 * @param blockId    block registry id, e.g. {@code minecraft:diamond_ore}
 * @param confidence 1.0 for VERIFIED, 0..1 for PREDICTED, 0 for VERIFIED_MISS
 * @param gameTime   level game time when recorded
 */
public record WorldObservation(long packedPos, String dimension, String blockId, ObservationKind kind,
                               double confidence, long gameTime) {
}
