package com.utilitysuite.devtest;

import com.utilitysuite.travel.TravelBootstrap;
import com.utilitysuite.travel.autopilot.AutopilotMode;
import com.utilitysuite.travel.autopilot.TravelAutopilot;
import com.utilitysuite.world.WorldBootstrap;
import com.utilitysuite.world.minimap.MinimapModule;
import com.utilitysuite.world.overlays.ChunkAgeTracker;
import com.utilitysuite.world.overlays.TrailVector;
import com.utilitysuite.world.overlays.WorldRadarModule;
import com.utilitysuite.world.overlays.WorldScanner;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Devtest checks for W4 (chunk forensics, {@link ChunkAgeTracker} persistence, radar/minimap integration) and W9
 * ({@link TrailVector} corridor heading and {@link AutopilotMode#TRAIL}).
 */
final class ReconForensicsTests {

    private ReconForensicsTests() {
    }

    static void add(Scenario s) {
        Arena.ensure(s);

        // ---- W4: Chunk forensics & tracking live ----------------------------------------------------------------
        s.run("recon: enable world_radar (trails) + minimap", () -> {
            H.setting(WorldRadarModule.ID, "trails", true);
            H.setting(WorldRadarModule.ID, "trail_classes", true);
            H.enable(WorldRadarModule.ID, true);
            H.setting(MinimapModule.ID, "trail_heatmap", true);
            H.enable(MinimapModule.ID, true);
        });
        s.waitTicks(20);
        s.run("recon: chunkAge active and modules stay enabled", () -> {
            WorldScanner scanner = WorldBootstrap.runtime().scanner();
            ChunkAgeTracker live = scanner.chunkAge;
            int pcx = H.floorX() >> 4;
            int pcz = H.floorZ() >> 4;
            byte state = live.stateOf(pcx, pcz);
            byte flags = live.flagsOf(pcx, pcz);
            boolean radarOn = H.module(WorldRadarModule.ID).isEnabled();
            boolean minimapOn = H.module(MinimapModule.ID).isEnabled();
            s.check("chunkAge non-null and active with world_radar + minimap enabled",
                    live != null && WorldScanner.activeChunkAge() == live && radarOn && minimapOn,
                    "chunkAge=" + (live != null) + " activeMatch=" + (WorldScanner.activeChunkAge() == live)
                            + " radarOn=" + radarOn + " minimapOn=" + minimapOn
                            + " playerChunk=(" + pcx + "," + pcz + ") state=" + state
                            + " flags=0x" + Integer.toHexString(flags & 0xFF));
        });

        s.run("recon: place oxidized_copper near player", () -> Harness.command(
                "setblock " + (Arena.cx + 2) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 2)
                        + " minecraft:oxidized_copper"));
        s.waitFor("recon: oxidized_copper placed on deck", 60, () -> H.blockId(
                Arena.cx + 2, Arena.FLOOR + 1, Arena.cz + 2).equals("minecraft:oxidized_copper"));
        s.waitTicks(5);
        s.run("recon: scanner inspects oxidized_copper + binary round-trip", () -> {
            int bx = Arena.cx + 2;
            int bz = Arena.cz + 2;
            int cx = bx >> 4;
            int cz = bz >> 4;
            var level = H.mc().level;
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
            WorldScanner scanner = WorldBootstrap.runtime().scanner();
            if (chunk != null) {
                scanner.onChunkLoad(level, chunk);
            }
            ChunkAgeTracker live = scanner.chunkAge;
            byte state = live.stateOf(cx, cz);
            byte flags = live.flagsOf(cx, cz);
            boolean hasCopper = (flags & ChunkAgeTracker.OXIDIZED_COPPER) != 0;
            boolean hasOldPalette = (flags & ChunkAgeTracker.PALETTE_OLD) != 0;
            s.check("ChunkAgeTracker detects oxidized_copper forensic flags",
                    chunk != null && state == ChunkAgeTracker.OLD && hasCopper && hasOldPalette,
                    "chunk=(" + cx + "," + cz + ") loaded=" + (chunk != null) + " state=" + state
                            + " flags=0x" + Integer.toHexString(flags & 0xFF));

            Path tmp = Files.createTempFile("utilitysuite-trails-roundtrip-", ".bin");
            try {
                scanner.saveTrails(tmp, false);
                long bytes = Files.size(tmp);
                ChunkAgeTracker restored = new ChunkAgeTracker();
                try (DataInputStream in = new DataInputStream(Files.newInputStream(tmp))) {
                    restored.read(in);
                }
                boolean reloaded = scanner.loadTrails(tmp);
                byte rState = restored.stateOf(cx, cz);
                byte rFlags = restored.flagsOf(cx, cz);
                boolean roundTripOk = bytes > 0
                        && restored.size() == live.size()
                        && rState == ChunkAgeTracker.OLD
                        && (rFlags & ChunkAgeTracker.OXIDIZED_COPPER) != 0
                        && (rFlags & ChunkAgeTracker.PALETTE_OLD) != 0
                        && reloaded;
                s.check("ChunkAgeTracker binary save/load round-trip preserves flags",
                        roundTripOk,
                        "bytes=" + bytes + " size=" + restored.size() + "/" + live.size()
                                + " state=" + rState + " flags=0x" + Integer.toHexString(rFlags & 0xFF)
                                + " reloaded=" + reloaded);
            } finally {
                Files.deleteIfExists(tmp);
            }
        });
        s.cmd(() -> "setblock " + (Arena.cx + 2) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 2) + " minecraft:air");

        // ---- W9: TrailVector & AutopilotMode.TRAIL --------------------------------------------------------------
        s.run("recon: TrailVector heading + live ChunkAgeTracker health", () -> {
            int pcx = H.floorX() >> 4;
            int pcz = H.floorZ() >> 4;

            // Standalone ChunkAgeTracker: exercise NEW fluid updates and settled OLD corridor for TrailVector.
            ChunkAgeTracker synthetic = new ChunkAgeTracker();
            synthetic.configure(60, 2);
            synthetic.onChunkLoad(pcx - 1, pcz + 2, 100L, true);
            synthetic.onFluidUpdate(pcx - 1, pcz + 2, 105L);
            synthetic.onFluidUpdate(pcx - 1, pcz + 2, 110L);
            boolean newMarked = synthetic.stateOf(pcx - 1, pcz + 2) == ChunkAgeTracker.NEW
                    && synthetic.seenTickOf(pcx - 1, pcz + 2) > 0L
                    && synthetic.seenWallOf(pcx - 1, pcz + 2) > 0L;

            for (int i = 0; i <= 6; i++) {
                synthetic.onChunkLoad(pcx + i, pcz, 0L, true);
            }
            synthetic.settle(100L);

            TrailVector tv = new TrailVector();
            boolean ok = tv.estimate(synthetic, pcx, pcz, 12, 4, 1.0, 0.0);
            double norm = Math.hypot(tv.dx(), tv.dz());
            boolean headingOk = ok && tv.valid() && Math.abs(norm - 1.0) < 1e-6
                    && tv.confidence() > 0.5 && tv.targetChunkX() > pcx;

            // Live ChunkAgeTracker instance health.
            ChunkAgeTracker live = WorldBootstrap.runtime().scanner().chunkAge;
            TrailVector liveTv = new TrailVector();
            boolean liveHasTrail = liveTv.estimate(live, pcx, pcz, 16, 4);
            double liveNorm = Math.hypot(liveTv.dx(), liveTv.dz());
            boolean liveHealthy = live != null && live.size() >= 1
                    && (!liveTv.valid() || Math.abs(liveNorm - 1.0) < 1e-6);

            s.check("TrailVector computes normalized heading and live ChunkAgeTracker is healthy",
                    newMarked && headingOk && liveHealthy,
                    Harness.fmt("newMarked=%s dx=%.3f dz=%.3f norm=%.6f conf=%.3f target=(%d,%d) liveSize=%d liveValid=%s",
                            newMarked, tv.dx(), tv.dz(), norm, tv.confidence(), tv.targetChunkX(),
                            tv.targetChunkZ(), live.size(), liveHasTrail && liveTv.valid()));
        });

        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        s.waitTicks(3);
        s.run("recon: start TravelAutopilot in TRAIL mode", () -> {
            int pcx = H.floorX() >> 4;
            int pcz = H.floorZ() >> 4;
            ChunkAgeTracker live = WorldScanner.activeChunkAge();
            TrailVector probe = new TrailVector();
            if (!probe.estimate(live, pcx, pcz, 16, 4) || !probe.valid()) {
                for (int i = 1; i <= 5; i++) {
                    live.onChunkLoad(pcx + i, pcz, 0L, false);
                    live.onPaletteCheck(pcx + i, pcz, true, true);
                }
            }
            H.setting(TravelAutopilot.ID, "mode", AutopilotMode.TRAIL.name());
            H.enable(TravelAutopilot.ID, true);
            TravelAutopilot ap = TravelBootstrap.autopilot();
            boolean started = ap.start();
            s.check("TravelAutopilot starts in AutopilotMode.TRAIL",
                    started && ap.isEnabled() && ap.isRunning() && "TRAIL".equals(ap.runName()),
                    "started=" + started + " enabled=" + ap.isEnabled() + " running=" + ap.isRunning()
                            + " run=" + ap.runName() + " phase=" + ap.phase());
        });
        s.waitTicks(15);
        s.run("recon: stop TravelAutopilot and verify clean disarm", () -> {
            TravelAutopilot ap = TravelBootstrap.autopilot();
            boolean stayedEnabled = ap.isEnabled();
            ap.stop();
            H.enable(TravelAutopilot.ID, false);
            H.setting(TravelAutopilot.ID, "mode", AutopilotMode.AUTO.name());
            boolean disarmed = !ap.isRunning() && !ap.isEnabled() && ap.phase() == null;
            double dx = Math.abs(H.p().getX() - Arena.cx);
            double dz = Math.abs(H.p().getZ() - Arena.cz);
            boolean onDeck = H.p().getY() >= Arena.FLOOR + 0.99 && dx <= 12.5 && dz <= 12.5;
            s.check("TravelAutopilot TRAIL ticks cleanly and disarms on arena deck",
                    stayedEnabled && disarmed && onDeck,
                    Harness.fmt("stayedEnabled=%s disarmed=%s run=%s phase=%s pos=%s",
                            stayedEnabled, disarmed, ap.runName(), ap.phase(), H.posStr()));
        });

        s.always("recon: cleanup", () -> {
            TravelAutopilot ap = TravelBootstrap.autopilot();
            if (ap != null) {
                ap.stop();
                H.enable(TravelAutopilot.ID, false);
                H.setting(TravelAutopilot.ID, "mode", AutopilotMode.AUTO.name());
            }
            if (H.mc().level != null && H.mc().player != null) {
                Harness.command("setblock " + (Arena.cx + 2) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 2)
                        + " minecraft:air");
            }
            H.setting(WorldRadarModule.ID, "trails", false);
            H.setting(WorldRadarModule.ID, "trail_classes", false);
            H.enable(WorldRadarModule.ID, false);
            H.setting(MinimapModule.ID, "trail_heatmap", false);
            H.enable(MinimapModule.ID, false);
        });
    }
}
