package com.utilitysuite.tools.packetlab;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** World Radar's acoustic pings get a sound feed without Packet Lab being on (audit 2026-11-25). */
class AcousticDemandTest {

    @AfterEach
    void reset() {
        PacketLab.setAcousticDemand(false);
    }

    @Test
    void noFeedWithoutPacketLabOrDemand() {
        PacketLab.setAcousticDemand(false);
        assertNull(PacketLab.acousticRadar());
    }

    @Test
    void demandAloneFeedsTheSharedRadar() {
        PacketLab.setAcousticDemand(true);
        AcousticRadar first = PacketLab.acousticRadar();
        assertNotNull(first);
        assertSame(first, PacketLab.acousticRadar());
        PacketLab.setAcousticDemand(false);
        assertNull(PacketLab.acousticRadar());
    }
}
