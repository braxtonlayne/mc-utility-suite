package com.utilitysuite.devtest;

import com.utilitysuite.tools.ToolsBootstrap;
import com.utilitysuite.tools.commands.DotCommands;
import com.utilitysuite.tools.packetlab.PacketLab;
import com.utilitysuite.travel.TravelBootstrap;
import com.utilitysuite.travel.autopilot.TravelAutopilot;
import com.utilitysuite.world.minimap.MinimapModule;
import com.utilitysuite.world.oresim.OreSimModule;
import com.utilitysuite.world.overlays.WorldRadarModule;
import com.utilitysuite.world.seed.SeedDiffModule;
import com.utilitysuite.world.seed.SeedScannerModule;

/**
 * Scenario 14: in-game playtest coverage for W3/W7 (Seed Scanner, .seed commands, BiomeManager hash &amp; bedrock pattern
 * verification), W5 (Seed-Diff base/tunnel scan), W4/W9 (Chunk Forensics, oxidized copper/palette signals, binary
 * persistence, TrailVector &amp; AutopilotMode.TRAIL), U1 (AcousticRadar &amp; ClientSoundPacketMixin), and U2
 * (ServerPhysicsMonitor tick tracking &amp; setback step-down). Select alone with {@code -PplaytestOnly=Recon}.
 */
final class ReconScenario {

    private ReconScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("16-recon",
                "Recon & calibration: seed scanner, seed diff, chunk forensics, acoustic radar, server physics");
        Arena.ensure(s);
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd("clear @s");
        s.cmd("effect clear @s");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));

        ReconSeedTests.add(s);
        ReconForensicsTests.add(s);
        ReconPacketLabTests.add(s);

        s.always("disarm", () -> {
            if (TravelBootstrap.autopilot() != null) {
                TravelBootstrap.autopilot().stop();
            }
            H.enable(TravelAutopilot.ID, false);
            H.enable(SeedDiffModule.ID, false);
            H.enable(OreSimModule.ID, false);
            H.enable(WorldRadarModule.ID, false);
            H.enable(MinimapModule.ID, false);
            H.enable(PacketLab.ID, false);
            H.enable(SeedScannerModule.ID, true);
            H.enable(DotCommands.ID, true);
            Harness.command(Arena.fill(-12, 1, -12, 12, 9, 12, "minecraft:air"));
            if (ToolsBootstrap.packetLab() != null) {
                ToolsBootstrap.packetLab().physicsMonitor().clear();
            }
        });
        return s;
    }
}
