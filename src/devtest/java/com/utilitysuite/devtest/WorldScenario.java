package com.utilitysuite.devtest;

import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldBootstrap;
import com.utilitysuite.world.camera.ScoutCameraModule;
import com.utilitysuite.world.knowledge.PredictionSource;
import com.utilitysuite.world.oresim.OreSimModule;
import com.utilitysuite.world.overlays.HighlightIndex;
import com.utilitysuite.world.waypoints.WaypointsModule;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/** Scenario 7: OreSim accuracy, X-Ray/radar/cave/hazard overlays, freecam, waypoints, breadcrumbs. */
final class WorldScenario {

    static final Set<String> ORES = Set.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore");

    private WorldScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("7-world", "World: OreSim, overlays, freecam, waypoints, breadcrumbs");
        double[][] saved = new double[2][];
        s.run("back to spawn", () -> Harness.command("spreadplayers " + (int) BootScenario.spawn[0] + " "
                + (int) BootScenario.spawn[2] + " 0 1 false @s"));
        s.waitTicks(60);
        // --- OreSim ---
        s.run("enable OreSim (diamond, iron)", () -> {
            H.setting(OreSimModule.ID, "resources", "diamond,iron");
            H.setting(OreSimModule.ID, "radius_chunks", 5);
            H.enable(OreSimModule.ID, true);
        });
        int[] stable = {0, -1};
        s.step("OreSim predictions settle", t -> {
            if (t % 20 != 0) {
                return false;
            }
            int n = countPredictions();
            stable[0] = n == stable[1] && n > 0 ? stable[0] + 1 : 0;
            stable[1] = n;
            if (stable[0] >= 5 || t >= 20 * 120) {
                s.note("OreSim predictions after " + t / 20 + " s: " + n + (stable[0] >= 5 ? " (stable)"
                        : " (still growing at the 120 s cap)"));
                return true;
            }
            return false;
        });
        s.run("compare predictions with real blocks", () -> compareOreSim(s));
        // --- overlays ---
        s.run("enable overlays", () -> {
            H.setting("world_overlays", "caves", true);
            H.setting("world_overlays", "fluids", true);
            H.enable("world_overlays", true);
            H.enable("world_radar", true);
            H.setting("terrain_assist", "hazards", true);
            H.enable("terrain_assist", true);
        });
        s.waitTicks(100);
        s.run("overlay content", () -> {
            HighlightIndex idx = WorldBootstrap.runtime().scanner().observed;
            idx.rebuildRenderListIfDirty();
            int n = idx.renderCount();
            int checked = 0;
            int ok = 0;
            String firstBad = "";
            for (int i = 0; i < n && checked < 200; i += Math.max(1, n / 200)) {
                long p = idx.renderPos(i);
                String id = idx.idAt(HighlightIndex.idIndexOf(idx.renderValue(i)));
                String real = H.blockId(BlockPosPacking.x(p), BlockPosPacking.y(p), BlockPosPacking.z(p));
                checked++;
                if (real.equals(id)) {
                    ok++;
                } else if (firstBad.isEmpty()) {
                    firstBad = "first mismatch: index says " + id + ", world has " + real;
                }
            }
            s.check("X-Ray index holds ores and matches the world", n > 0 && ok == checked,
                    n + " highlighted blocks; sampled " + checked + ", " + ok + " match " + firstBad);
            s.check("cave/fluid near view populated", WorldBootstrap.runtime().scanner().fluids.size() > 0
                            || WorldBootstrap.runtime().scanner().caves != null,
                    "fluid pockets " + WorldBootstrap.runtime().scanner().fluids.size());
        });
        s.run("look down for overlay shot", () -> H.p().setXRot(40f));
        Shots.add(s, "world-overlays");
        // Minimap (military default) + Info strip, overlays off so the HUD reads cleanly.
        s.run("minimap + info strip on", () -> {
            H.enable("world_overlays", false);
            H.enable("minimap", true);
            H.setting("suite_hud", "info_strip", true);
            H.p().setXRot(10f);
        });
        s.waitTicks(80);
        Shots.add(s, "hud-minimap-infostrip");
        s.run("minimap + info strip off", () -> {
            H.enable("minimap", false);
            H.setting("suite_hud", "info_strip", false);
            H.enable("world_overlays", true);
        });
        // --- freecam ---
        s.run("freecam on", () -> {
            saved[0] = H.pos();
            ScoutCameraModule cam = H.module(ScoutCameraModule.ID);
            cam.toggleFreecam();
            s.check("freecam activates", cam.isFreecamActive() && H.mc().getCameraEntity() != H.p(),
                    "active=" + cam.isFreecamActive() + " camera=" + H.mc().getCameraEntity());
            H.mc().options.keyUp.setDown(true); // the user holding W (test scaffolding)
        });
        s.waitTicks(30);
        s.run("freecam moved", () -> {
            H.mc().options.keyUp.setDown(false);
            Vec3 c = H.mc().gameRenderer.getMainCamera().getPosition();
            double moved = Math.sqrt(c.distanceToSqr(saved[0][0], saved[0][1] + H.p().getEyeHeight(), saved[0][2]));
            double player = H.dist(saved[0], H.pos());
            s.check("camera flies, player stays", moved > 2 && player < 0.3,
                    Harness.fmt("camera moved %.2f, player moved %.2f", moved, player) + "; " + H.arbiterState());
            ((ScoutCameraModule) H.module(ScoutCameraModule.ID)).toggleFreecam();
        });
        s.waitTicks(3);
        Shots.add(s, "world-freecam");
        s.run("freecam off", () -> {
            ScoutCameraModule cam = H.module(ScoutCameraModule.ID);
            Vec3 c = H.mc().gameRenderer.getMainCamera().getPosition();
            Vec3 eye = H.p().getEyePosition();
            s.check("freecam off: camera back on the player", !cam.isFreecamActive()
                            && H.mc().getCameraEntity() == H.p() && c.distanceTo(eye) < 0.5,
                    Harness.fmt("active=%s, camera-eye distance %.2f", cam.isFreecamActive(), c.distanceTo(eye)));
        });
        // --- waypoints ---
        s.run("waypoints", () -> {
            IWaypointService wp = Services.waypoints();
            Waypoint w = wp.add("Playtest base", "Bases", 800, 70, -1600, "minecraft:overworld");
            long n = wp.positionIn(w, "minecraft:the_nether");
            boolean ok = BlockPos.getX(n) == 100 && BlockPos.getZ(n) == -200;
            s.check("waypoint add + 8:1 nether conversion", ok && wp.byName("Playtest base").isPresent(),
                    "overworld 800,-1600 -> nether " + BlockPos.getX(n) + "," + BlockPos.getY(n) + ","
                            + BlockPos.getZ(n));
        });
        // --- breadcrumbs while walking (on the arena) ---
        Arena.ensure(s);
        s.run("walk", () -> {
            Harness.command(Arena.tp(0.5, -10.5, 0f));
            WaypointsModule m = H.module(WaypointsModule.ID);
            saved[1] = new double[] {m.trail().size()};
        });
        s.waitTicks(5);
        s.run("hold W", () -> H.mc().options.keyUp.setDown(true));
        s.waitTicks(80);
        s.run("stop", () -> H.mc().options.keyUp.setDown(false));
        s.run("breadcrumbs", () -> {
            WaypointsModule m = H.module(WaypointsModule.ID);
            int before = (int) saved[1][0];
            s.check("breadcrumbs recorded while walking", m.trail().size() > before + 2,
                    "trail " + before + " -> " + m.trail().size() + " points, now at " + H.posStr() + "; " + H.arbiterState());
        });
        s.always("release W", () -> H.mc().options.keyUp.setDown(false));
        s.always("freecam off", () -> {
            ScoutCameraModule cam = H.module(ScoutCameraModule.ID);
            if (cam.isFreecamActive()) {
                cam.toggleFreecam();
            }
        });
        return s;
    }

    static int countPredictions() {
        OreSimModule ore = H.module(OreSimModule.ID);
        int[] n = {0};
        ore.predictions().forEachPrediction("minecraft:overworld", BlockPosPacking.pack(H.floorX(), 0, H.floorZ()),
                48, (pos, id, alt, conf, cluster) -> n[0]++);
        return n[0];
    }

    /** Predicted vs real diamond/iron ore inside the radius around the player, in loaded chunks. */
    static void compareOreSim(Scenario s) {
        OreSimModule ore = H.module(OreSimModule.ID);
        PredictionSource src = ore.predictions();
        int px = H.floorX();
        int pz = H.floorZ();
        int radius = 48;
        long origin = BlockPosPacking.pack(px, 0, pz);
        Long2ObjectOpenHashMap<String[]> predicted = new Long2ObjectOpenHashMap<>();
        src.forEachPrediction("minecraft:overworld", origin, 4096, (pos, id, alt, conf, cluster) -> {
            int dx = BlockPosPacking.x(pos) - px;
            int dz = BlockPosPacking.z(pos) - pz;
            if (dx * dx + dz * dz <= radius * radius) {
                predicted.put(pos, new String[] {id, alt});
            }
        });
        // Real ores in the same cylinder.
        var level = H.mc().level;
        int real = 0;
        int realPredicted = 0;
        int realDiamond = 0;
        int realDiamondPredicted = 0;
        java.util.Map<String, Integer> realById = new java.util.HashMap<>();
        java.util.Map<String, Integer> realHitById = new java.util.HashMap<>();
        java.util.Map<String, Integer> predById = new java.util.HashMap<>();
        java.util.Map<String, Integer> hitById = new java.util.HashMap<>();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet covered = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (long p : predicted.keySet()) {
            covered.add(BlockPosPacking.chunk(BlockPosPacking.x(p) >> 4, BlockPosPacking.z(p) >> 4));
        }
        int realCovered = 0;
        int realCoveredHit = 0;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int cx = (px - radius) >> 4; cx <= (px + radius) >> 4; cx++) {
            for (int cz = (pz - radius) >> 4; cz <= (pz + radius) >> 4; cz++) {
                LevelChunk ch = level.getChunkSource().getChunk(cx, cz, false);
                if (ch == null) {
                    continue;
                }
                LevelChunkSection[] secs = ch.getSections();
                for (int si = 0; si < secs.length; si++) {
                    LevelChunkSection sec = secs[si];
                    if (sec == null || sec.hasOnlyAir()) {
                        continue;
                    }
                    int baseY = level.getSectionYFromSectionIndex(si) << 4;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState st = sec.getBlockState(x, y, z);
                                String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                                if (!ORES.contains(id)) {
                                    continue;
                                }
                                int wx = (cx << 4) + x;
                                int wz = (cz << 4) + z;
                                int dx = wx - px;
                                int dz = wz - pz;
                                if (dx * dx + dz * dz > radius * radius) {
                                    continue;
                                }
                                real++;
                                realById.merge(id, 1, Integer::sum);
                                if (covered.contains(BlockPosPacking.chunk(cx, cz))) {
                                    realCovered++;
                                }
                                boolean dia = id.contains("diamond");
                                if (dia) {
                                    realDiamond++;
                                }
                                if (predicted.containsKey(BlockPosPacking.pack(wx, baseY + y, wz))) {
                                    realPredicted++;
                                    realHitById.merge(id, 1, Integer::sum);
                                    if (covered.contains(BlockPosPacking.chunk(cx, cz))) {
                                        realCoveredHit++;
                                    }
                                    if (dia) {
                                        realDiamondPredicted++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        int pred = predicted.size();
        int hit = 0;
        int predDiamond = 0;
        int hitDiamond = 0;
        for (var e : predicted.long2ObjectEntrySet()) {
            long p = e.getLongKey();
            mp.set(BlockPosPacking.x(p), BlockPosPacking.y(p), BlockPosPacking.z(p));
            String actual = BuiltInRegistries.BLOCK.getKey(level.getBlockState(mp).getBlock()).toString();
            String[] ids = e.getValue();
            boolean dia = ids[0].contains("diamond");
            if (dia) {
                predDiamond++;
            }
            predById.merge(ids[0], 1, Integer::sum);
            if (actual.equals(ids[0]) || actual.equals(ids[1])) {
                hit++;
                hitById.merge(ids[0], 1, Integer::sum);
                if (dia) {
                    hitDiamond++;
                }
            }
        }
        double precision = pred == 0 ? 0 : (double) hit / pred;
        double recall = real == 0 ? 0 : (double) realPredicted / real;
        String ev = Harness.fmt("radius %d around (%d,%d): predicted %d (diamond %d), real %d (diamond %d); "
                        + "precision %.3f (diamond %d/%d), recall %.3f (diamond %d/%d)", radius, px, pz, pred,
                predDiamond, real, realDiamond, precision, hitDiamond, predDiamond, recall, realDiamondPredicted,
                realDiamond);
        StringBuilder by = new StringBuilder();
        for (String k : new java.util.TreeSet<>(realById.keySet())) {
            by.append(k.replace("minecraft:", "")).append(" real ").append(realById.get(k)).append(" found ")
                    .append(realHitById.getOrDefault(k, 0)).append("; ");
        }
        for (String k : new java.util.TreeSet<>(predById.keySet())) {
            by.append(k.replace("minecraft:", "")).append(" predicted ").append(predById.get(k)).append(" hit ")
                    .append(hitById.getOrDefault(k, 0)).append("; ");
        }
        s.note("OreSim breakdown: " + by + "chunks in radius with snapshot predictions: " + covered.size()
                + "; real in covered chunks " + realCovered + ", found " + realCoveredHit);
        s.check("OreSim predicts diamond/iron in loaded chunks (precision and recall >= 0.5)",
                pred > 0 && precision >= 0.5 && recall >= 0.5, ev);
    }
}
