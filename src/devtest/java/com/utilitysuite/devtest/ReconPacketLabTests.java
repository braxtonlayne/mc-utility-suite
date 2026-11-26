package com.utilitysuite.devtest;

import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.tools.ToolsBootstrap;
import com.utilitysuite.tools.packetlab.AcousticRadar;
import com.utilitysuite.tools.packetlab.PacketLab;
import com.utilitysuite.tools.packetlab.ServerPhysicsMonitor;
import com.utilitysuite.world.overlays.WorldRadarModule;
import net.minecraft.client.player.LocalPlayer;

/**
 * Live devtest coverage for PacketLab's {@link AcousticRadar} (via {@code ClientSoundPacketMixin}) and
 * {@link ServerPhysicsMonitor}.
 */
final class ReconPacketLabTests {

    private static final String K_ACOUSTIC_PINGS = "acoustic_pings";
    private static final long PING_MAX_AGE_MS = 10_000L;
    private static final double MAX_PING_DIST_SQ = 16.0;

    private ReconPacketLabTests() {
    }

    static void add(Scenario s) {
        acousticRadar(s);
        serverPhysicsMonitor(s);
        s.always("packet lab tests cleanup", () -> {
            H.setting(WorldRadarModule.ID, K_ACOUSTIC_PINGS, false);
            H.enable(WorldRadarModule.ID, false);
            H.enable(PacketLab.ID, false);
        });
    }

    private static void acousticRadar(Scenario s) {
        long[] baseline = new long[1];
        s.run("U1 enable packet_lab + world_radar acoustic_pings and play chest sound", () -> {
            H.enable(PacketLab.ID, true);
            H.setting(WorldRadarModule.ID, K_ACOUSTIC_PINGS, true);
            H.enable(WorldRadarModule.ID, true);
            AcousticRadar radar = ToolsBootstrap.packetLab().radar();
            baseline[0] = radar.written();
            Harness.command("playsound minecraft:block.chest.open block @s ~ ~ ~ 1 1");
        });
        s.waitUpTo("U1 acoustic radar captures chest sound", 40, () -> {
            AcousticRadar radar = PacketLab.acousticRadar();
            return radar != null && radar.written() > baseline[0] && inspectActiveContainer(radar).matched();
        });
        s.run("U1 acoustic radar ping check", () -> {
            AcousticRadar radar = PacketLab.acousticRadar();
            AcousticRadar labRadar = ToolsBootstrap.packetLab().radar();
            long written = radar != null ? radar.written() : labRadar.written();
            PingProbe probe = radar != null ? inspectActiveContainer(radar) : PingProbe.EMPTY;
            s.check("AcousticRadar records live CONTAINER ping near player",
                    radar == labRadar && written > baseline[0] && probe.matched(),
                    "activeMatch=" + (radar == labRadar) + ", baseline=" + baseline[0] + ", written=" + written
                            + ", activeVisited=" + probe.visited() + ", containerNearPlayer=" + probe.matched()
                            + (probe.hasContainer()
                                    ? Harness.fmt(", nearestContainerDist=%.2f", Math.sqrt(probe.minDistSq()))
                                    : ", noContainerPing"));
        });
        s.waitTicks(10);
        s.run("U1 world_radar and packet_lab stay enabled with active ping", () -> {
            IModule radarMod = H.module(WorldRadarModule.ID);
            IModule labMod = H.module(PacketLab.ID);
            s.check("world_radar and packet_lab stay enabled after acoustic ping ticks",
                    radarMod.isEnabled() && labMod.isEnabled(),
                    "world_radar=" + radarMod.isEnabled() + ", packet_lab=" + labMod.isEnabled());
        });
    }

    private static void serverPhysicsMonitor(Scenario s) {
        long[] firstCorrectionMs = new long[1];
        s.run("U2 enable packet_lab and reset monitor", () -> {
            H.enable(PacketLab.ID, true);
            ToolsBootstrap.packetLab().physicsMonitor().clear();
        });
        s.waitTicks(25);
        s.run("U2 ServerPhysicsMonitor live tick tracking", () -> {
            ServerPhysicsMonitor current = ServerPhysicsMonitor.current();
            ServerPhysicsMonitor labMon = ToolsBootstrap.packetLab().physicsMonitor();
            double jitterMs = current.tickJitterMs();
            s.check("ServerPhysicsMonitor.current() matches PacketLab monitor and tickJitterMs is finite >= 0",
                    current == labMon && Double.isFinite(jitterMs) && jitterMs >= 0.0,
                    Harness.fmt("sameInstance=%s, tickJitterMs=%.3f", current == labMon, jitterMs));
        });
        s.run("U2 post first PositionCorrection", () -> {
            firstCorrectionMs[0] = System.currentTimeMillis();
            Services.events().post(new SuiteEvent.PositionCorrection(firstCorrectionMs[0], 0.25, 0.0, -0.25));
        });
        s.waitTicks(3);
        s.run("U2 post second PositionCorrection and verify step-down + bounds", () -> {
            long secondMs = Math.max(System.currentTimeMillis(), firstCorrectionMs[0] + 100L);
            Services.events().post(new SuiteEvent.PositionCorrection(secondMs, -0.25, 0.0, 0.25));
            ServerPhysicsMonitor mon = ServerPhysicsMonitor.current();
            boolean firstStepDown = mon.shouldStepDownPreset();
            boolean secondStepDown = mon.shouldStepDownPreset();
            double extraLead = mon.extraLeadTicks();
            double safetyMargin = mon.movementSafetyMargin();
            s.check("ServerPhysicsMonitor setback step-down fires once then clears",
                    firstStepDown && !secondStepDown,
                    "first=" + firstStepDown + ", second=" + secondStepDown
                            + ", dtMs=" + (secondMs - firstCorrectionMs[0]));
            s.check("ServerPhysicsMonitor extraLeadTicks and movementSafetyMargin within valid bounds",
                    Double.isFinite(extraLead) && extraLead >= 0.0 && extraLead <= 2.0
                            && Double.isFinite(safetyMargin) && safetyMargin >= 0.0 && safetyMargin <= 0.15,
                    Harness.fmt("extraLeadTicks=%.4f, movementSafetyMargin=%.4f", extraLead, safetyMargin));
        });
    }

    private static PingProbe inspectActiveContainer(AcousticRadar radar) {
        LocalPlayer player = H.p();
        if (player == null) {
            return PingProbe.EMPTY;
        }
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        int[] counts = new int[2]; // [0]=visited, [1]=matched(0/1)
        boolean[] hasContainer = new boolean[1];
        double[] minDistSq = {Double.POSITIVE_INFINITY};
        radar.forEachActive(System.currentTimeMillis(), PING_MAX_AGE_MS,
                (global, x, y, z, dx, dz, ts, categoryId) -> {
                    counts[0]++;
                    if (!global && categoryId == AcousticRadar.CONTAINER) {
                        hasContainer[0] = true;
                        double ddx = x - px;
                        double ddy = y - py;
                        double ddz = z - pz;
                        double d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                        if (d2 < minDistSq[0]) {
                            minDistSq[0] = d2;
                        }
                        if (d2 <= MAX_PING_DIST_SQ) {
                            counts[1] = 1;
                        }
                    }
                });
        return new PingProbe(counts[0], counts[1] == 1, hasContainer[0], minDistSq[0]);
    }

    private record PingProbe(int visited, boolean matched, boolean hasContainer, double minDistSq) {
        static final PingProbe EMPTY = new PingProbe(0, false, false, Double.POSITIVE_INFINITY);
    }
}
