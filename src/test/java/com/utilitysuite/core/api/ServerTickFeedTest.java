package com.utilitysuite.core.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerTickFeedTest {

    @AfterEach
    void reset() {
        ServerTickFeed.reset();
    }

    @Test
    void noPacketYetIsNeverOverdue() {
        assertEquals(0L, ServerTickFeed.overdueMs(50_000L));
    }

    @Test
    void healthyServerIsOnTime() {
        ServerTickFeed.onTimePacket(1_000L, 20L);
        ServerTickFeed.onTimePacket(2_000L, 40L);
        assertEquals(0L, ServerTickFeed.overdueMs(2_900L));
    }

    @Test
    void missingPacketsGoOverdue() {
        ServerTickFeed.onTimePacket(1_000L, 20L);
        assertEquals(500L, ServerTickFeed.overdueMs(2_500L));
    }

    /** /tick freeze: the packets keep coming once a second, but the game time stands still. */
    @Test
    void frozenGameTimeGoesOverdueAlthoughPacketsArrive() {
        ServerTickFeed.onTimePacket(1_000L, 100L);
        ServerTickFeed.onTimePacket(2_000L, 100L);
        ServerTickFeed.onTimePacket(3_000L, 100L);
        assertEquals(1_500L, ServerTickFeed.overdueMs(3_500L));
        ServerTickFeed.onTimePacket(4_000L, 120L); // unfrozen
        assertEquals(0L, ServerTickFeed.overdueMs(4_500L));
    }
}
