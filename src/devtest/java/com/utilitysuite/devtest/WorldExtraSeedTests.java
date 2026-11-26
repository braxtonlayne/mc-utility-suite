package com.utilitysuite.devtest;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.world.fortress.FortressSpawnModule;
import com.utilitysuite.world.oresim.OreSimModule;
import com.utilitysuite.world.seed.GeneratorProfile;
import com.utilitysuite.world.seed.SeedLocatorModule;
import com.utilitysuite.world.seed.StructureLocator;
import com.utilitysuite.world.seed.StructureSpread;
import com.utilitysuite.world.spawn.SlimeAfkFinder;
import com.utilitysuite.world.stronghold.StrongholdFinderModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Seed-driven world modules in game: {@link SeedLocatorModule} (candidates and slime AFK spots against vanilla's own
 * placement and slime-chunk maths, OreSim off), {@link StrongholdFinderModule} (two real eye-of-ender throws) and
 * {@link FortressSpawnModule} (the world's real nearest Nether fortress, OreSim off).
 */
final class WorldExtraSeedTests {

    private WorldExtraSeedTests() {
    }

    static void add(Scenario s) {
        seedLocator(s);
        strongholdFinder(s);
        fortressSpawns(s);
    }

    private static long worldSeed() {
        return H.mc().getSingleplayerServer().getWorldGenSettings().options().seed();
    }

    // ---- Seed Locator -----------------------------------------------------------------------------------------------

    private static void seedLocator(Scenario s) {
        boolean[] oreWasOn = new boolean[1];
        s.run("seed-locator: OreSim off, locator on", () -> {
            oreWasOn[0] = H.module(OreSimModule.ID).isEnabled();
            H.enable(OreSimModule.ID, false);
            H.enable(SeedLocatorModule.ID, true);
        });
        s.waitUpTo("seed-locator: first search published", 200, () -> {
            SeedLocatorModule m = H.module(SeedLocatorModule.ID);
            SeedLocatorModule.Snapshot sn = m.snapshot();
            return sn.version() > 0 && sn.seedKnown() && !m.isSearching() && !sn.structures().isEmpty();
        });
        s.run("seed-locator: results against vanilla", () -> {
            SeedLocatorModule m = H.module(SeedLocatorModule.ID);
            OreSimModule ore = H.module(OreSimModule.ID);
            SeedLocatorModule.Snapshot sn = m.snapshot();
            long seed = worldSeed();
            GeneratorProfile gp = ore.profile();
            s.check("seed_locator: seed known in single player with seed_oresim off",
                    !ore.isEnabled() && sn.seedKnown() && m.isEnabled() && !sn.structures().isEmpty(),
                    "oresim on=" + ore.isEnabled() + ", snapshot v" + sn.version() + " seedKnown=" + sn.seedKnown()
                            + ", " + sn.structures().size() + " candidates, " + sn.slimeSpots().size()
                            + " slime spots, dim " + sn.dim());
            s.check("seed_oresim profile() resolves the seed while OreSim is off (ff52aba)",
                    !ore.isEnabled() && gp.seedKnown() && gp.seed() == seed,
                    "profile=" + gp + ", world seed " + seed);

            // The placement table (spacing / separation / salt / spread) matches the server's structure sets.
            Registry<StructureSet> sets = H.mc().getSingleplayerServer().registryAccess()
                    .lookupOrThrow(Registries.STRUCTURE_SET);
            // 26.3: salt() moved from StructurePlacement (now an interface) to AbstractSpreadingStructurePlacement
            Method salt = net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement.class
                    .getDeclaredMethod("salt");
            salt.setAccessible(true);
            List<String> tableBad = new ArrayList<>();
            List<String> all = new ArrayList<>(StructureLocator.OVERWORLD_TYPES);
            all.addAll(StructureLocator.NETHER_TYPES);
            all.addAll(StructureLocator.END_TYPES);
            for (String type : all) {
                StructureSpread.Placement mine = StructureSpread.PLACEMENTS.get(type);
                StructureSet set = sets.getValue(Identifier.withDefaultNamespace(setId(type)));
                if (mine == null || set == null
                        || !(set.placement() instanceof RandomSpreadStructurePlacement rsp)) {
                    tableBad.add(type + "(missing: mine=" + mine + ", set=" + (set != null) + ")");
                    continue;
                }
                int vSalt = (Integer) salt.invoke(rsp);
                boolean tri = rsp.spreadType() == RandomSpreadType.TRIANGULAR;
                if (rsp.spacing() != mine.spacing() || rsp.separation() != mine.separation() || vSalt != mine.salt()
                        || tri != mine.triangular()) {
                    tableBad.add(type + " vanilla " + rsp.spacing() + "/" + rsp.separation() + "/" + vSalt + "/"
                            + tri + " vs " + mine);
                }
            }
            s.check("seed_locator: placement table matches the server's structure sets", tableBad.isEmpty(),
                    tableBad.isEmpty() ? all.size() + " structure sets match" : String.join("; ", tableBad));

            // Every listed candidate is the start chunk vanilla's placement picks for its region.
            List<String> candBad = new ArrayList<>();
            StringBuilder sample = new StringBuilder();
            for (StructureLocator.Candidate c : sn.structures()) {
                StructureSet set = sets.getValue(Identifier.withDefaultNamespace(setId(c.type())));
                ChunkPos v = set != null && set.placement() instanceof RandomSpreadStructurePlacement rsp
                        ? rsp.getPotentialStructureChunk(seed, c.chunkX(), c.chunkZ()) : null;
                if (v == null || v.x() != c.chunkX() || v.z() != c.chunkZ()) {
                    candBad.add(c.type() + "@" + c.chunkX() + "," + c.chunkZ() + " vanilla " + v);
                }
                if (sample.length() < 300) {
                    sample.append(c.type()).append('@').append(c.chunkX()).append(',').append(c.chunkZ())
                            .append(' ').append(c.distance()).append("m; ");
                }
            }
            s.check("seed_locator: every candidate is vanilla's potential start chunk",
                    candBad.isEmpty() && !sn.structures().isEmpty(),
                    candBad.isEmpty() ? sample.toString() : String.join("; ", candBad));

            // Slime AFK spots: recount with vanilla's slime-chunk rule (WorldgenRandom.seedSlimeChunk).
            List<String> slimeBad = new ArrayList<>();
            int r = SlimeAfkFinder.DISK_CHUNKS;
            long max = (long) SlimeAfkFinder.COUNT_RADIUS * SlimeAfkFinder.COUNT_RADIUS;
            long min = (long) SlimeAfkFinder.NO_SPAWN_RADIUS * SlimeAfkFinder.NO_SPAWN_RADIUS;
            for (SlimeAfkFinder.Spot spot : sn.slimeSpots()) {
                int n = 0;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        long d2 = (long) (dx * 16) * (dx * 16) + (long) (dz * 16) * (dz * 16);
                        if (d2 <= max && d2 > min && WorldgenRandom.seedSlimeChunk(spot.chunkX() + dx,
                                spot.chunkZ() + dz, seed, 987234911L).nextInt(10) == 0) {
                            n++;
                        }
                    }
                }
                if (n != spot.slimeChunks()) {
                    slimeBad.add(spot + " vanilla count " + n);
                }
            }
            s.check("seed_locator: slime AFK spot counts match vanilla slime chunks",
                    slimeBad.isEmpty() && !sn.slimeSpots().isEmpty(),
                    slimeBad.isEmpty() ? "spots " + sn.slimeSpots() : String.join("; ", slimeBad));
        });
        s.always("seed-locator: off, OreSim restored", () -> {
            H.enable(SeedLocatorModule.ID, false);
            H.enable(OreSimModule.ID, oreWasOn[0]);
        });
    }

    /** Vanilla structure-set id for a {@link StructureLocator} type ("village" -> "villages"). */
    static String setId(String type) {
        if (type.endsWith("s")) {
            return type; // trial_chambers, trail_ruins
        }
        if (type.endsWith("city")) {
            return type.substring(0, type.length() - 4) + "cities";
        }
        if (type.endsWith("complex")) {
            return type + "es";
        }
        return type + "s";
    }

    // ---- Stronghold Finder ----------------------------------------------------------------------------------------

    private static void strongholdFinder(Scenario s) {
        @SuppressWarnings("unchecked")
        CompletableFuture<BlockPos>[] fut = new CompletableFuture[2];
        int[] plan = new int[6]; // ax, az, bx, bz, sx, sz
        boolean[] ok = {true};
        s.run("stronghold: ask the server for the nearest stronghold", () -> {
            plan[0] = Arena.cx;
            plan[1] = Arena.cz;
            BlockPos from = new BlockPos(plan[0], 64, plan[1]);
            fut[0] = WorldExtraScenario.onServer(() -> H.mc().getSingleplayerServer().overworld()
                    .findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, from, 100, false));
        });
        s.waitUpTo("stronghold: server locate A", 600, () -> fut[0].isDone());
        s.run("stronghold: plan the second throw spot", () -> {
            BlockPos sa = WorldExtraScenario.done(fut[0]);
            if (sa == null) {
                ok[0] = false;
                s.notTested("stronghold_finder", "the server found no stronghold within 100 chunks");
                return;
            }
            plan[4] = sa.getX();
            plan[5] = sa.getZ();
            double dx = plan[4] - plan[0];
            double dz = plan[5] - plan[1];
            double len = Math.hypot(dx, dz);
            plan[2] = (int) Math.round(plan[0] - dz / len * 250); // 250 blocks sideways to the eye's path
            plan[3] = (int) Math.round(plan[1] + dx / len * 250);
            BlockPos from = new BlockPos(plan[2], 64, plan[3]);
            fut[1] = WorldExtraScenario.onServer(() -> H.mc().getSingleplayerServer().overworld()
                    .findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, from, 100, false));
            s.note("stronghold at " + sa.toShortString() + " (" + Math.round(len) + " blocks); second throw from "
                    + plan[2] + "," + plan[3]);
        });
        s.waitUpTo("stronghold: server locate B", 600, () -> !ok[0] || fut[1].isDone());
        s.run("stronghold: same target from both spots, eyes in hand", () -> {
            if (!ok[0]) {
                return;
            }
            BlockPos sb = WorldExtraScenario.done(fut[1]);
            if (sb == null || sb.getX() != plan[4] || sb.getZ() != plan[5]) {
                ok[0] = false;
                s.notTested("stronghold_finder", "the second spot's nearest stronghold differs: " + sb);
                return;
            }
            Harness.command("clear @s");
            Harness.command("give @s minecraft:ender_eye 2");
            H.p().getInventory().setSelectedSlot(0);
            H.enable(StrongholdFinderModule.ID, true);
        });
        s.waitTicks(10);
        s.run("stronghold: first throw", () -> {
            if (!ok[0]) {
                return;
            }
            WorldExtraScenario.lookAtAbs(plan[4], H.p().getEyeY(), plan[5]);
            H.mc().gameMode.useItem(H.p(), InteractionHand.MAIN_HAND);
        });
        s.waitUpTo("stronghold: first throw stored", 60, () -> !ok[0]
                || "WAITING_FOR_SECOND_THROW".equals(H.module(StrongholdFinderModule.ID).phase()));
        s.run("stronghold: first throw check", () -> {
            if (!ok[0]) {
                return;
            }
            StrongholdFinderModule m = H.module(StrongholdFinderModule.ID);
            boolean waiting = "WAITING_FOR_SECOND_THROW".equals(m.phase());
            s.check("stronghold_finder: first eye throw is followed and stored", waiting,
                    "phase=" + m.phase() + ", eyes left " + H.count("minecraft:ender_eye"));
            if (!waiting) {
                ok[0] = false;
                return;
            }
            Harness.command("forceload add " + (plan[2] - 2) + " " + (plan[3] - 2) + " " + (plan[2] + 2) + " "
                    + (plan[3] + 2));
        });
        s.waitTicks(40);
        s.run("stronghold: pad at the second spot", () -> {
            if (ok[0]) {
                Harness.command(pad(plan, "minecraft:stone"));
            }
        });
        s.waitTicks(5);
        s.cmd(() -> ok[0] ? "tp @s " + plan[2] + ".5 " + (Arena.FLOOR + 1) + " " + plan[3] + ".5" : "tp @s ~ ~ ~");
        s.waitUpTo("stronghold: standing on the far pad", 300, () -> !ok[0]
                || H.p().onGround() && Math.abs(H.p().getX() - plan[2] - 0.5) < 1.0
                && Math.abs(H.p().getZ() - plan[3] - 0.5) < 1.0);
        s.waitTicks(10);
        s.run("stronghold: second throw", () -> {
            if (!ok[0]) {
                return;
            }
            WorldExtraScenario.lookAtAbs(plan[4], H.p().getEyeY(), plan[5]);
            H.mc().gameMode.useItem(H.p(), InteractionHand.MAIN_HAND);
        });
        s.waitUpTo("stronghold: estimate saved", 80, () -> !ok[0] || !strongholdWaypoints().isEmpty());
        s.run("stronghold: estimate check", () -> {
            if (!ok[0]) {
                return;
            }
            StrongholdFinderModule m = H.module(StrongholdFinderModule.ID);
            List<Waypoint> wps = strongholdWaypoints();
            Waypoint w = wps.isEmpty() ? null : wps.get(0);
            double err = w == null ? -1 : Math.hypot(w.x() - plan[4], w.z() - plan[5]);
            s.check("stronghold_finder: two throws 250 blocks apart locate the real stronghold",
                    w != null && err <= 120 && m.phase() == null,
                    w == null ? "no waypoint; phase=" + m.phase() + ", eyes left " + H.count("minecraft:ender_eye")
                            : String.format(Locale.ROOT, "waypoint '%s' at %d,%d vs server %d,%d: %.0f blocks off; "
                            + "phase after=%s", w.name(), w.x(), w.z(), plan[4], plan[5], err, m.phase()));
        });
        s.always("stronghold: cleanup", () -> {
            H.enable(StrongholdFinderModule.ID, false);
            for (Waypoint w : strongholdWaypoints()) {
                Services.waypoints().remove(w.id());
            }
            Harness.command(Arena.tp(0.5, 0.5, 0f));
            if (plan[2] != 0 || plan[3] != 0) {
                Harness.command(pad(plan, "minecraft:air"));
                Harness.command("forceload remove " + (plan[2] - 2) + " " + (plan[3] - 2) + " " + (plan[2] + 2)
                        + " " + (plan[3] + 2));
            }
            Harness.command("kill @e[type=minecraft:item]");
            Harness.command("kill @e[type=minecraft:eye_of_ender]");
            Harness.command("clear @s");
        });
        s.waitTicks(20);
    }

    private static String pad(int[] plan, String block) {
        return "fill " + (plan[2] - 2) + " " + Arena.FLOOR + " " + (plan[3] - 2) + " " + (plan[2] + 2) + " "
                + Arena.FLOOR + " " + (plan[3] + 2) + " " + block;
    }

    private static List<Waypoint> strongholdWaypoints() {
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : Services.waypoints().all()) {
            if ("Strongholds".equals(w.group())) {
                out.add(w);
            }
        }
        return out;
    }

    // ---- Fortress Spawns -------------------------------------------------------------------------------------------

    private static void fortressSpawns(Scenario s) {
        boolean[] oreWasOn = new boolean[1];
        boolean[] ok = {true};
        boolean[] wentNether = new boolean[1];
        @SuppressWarnings("unchecked")
        CompletableFuture<int[]>[] fut = new CompletableFuture[1];
        int[] info = new int[11];
        s.run("fortress: OreSim off, ask the server for the nearest fortress", () -> {
            oreWasOn[0] = H.module(OreSimModule.ID).isEnabled();
            H.enable(OreSimModule.ID, false);
            BlockPos from = new BlockPos(Arena.cx >> 3, 64, Arena.cz >> 3);
            fut[0] = WorldExtraScenario.onServer(() -> locateFortress(from));
        });
        s.waitUpTo("fortress: server locate", 1200, () -> fut[0].isDone());
        s.run("fortress: go above it (spectator)", () -> {
            int[] r = WorldExtraScenario.done(fut[0]);
            if (r == null) {
                ok[0] = false;
                s.notTested("fortress_spawns", "the server found no fortress within 100 chunks: "
                        + (fut[0].isCompletedExceptionally() ? "failed" : "none"));
                return;
            }
            System.arraycopy(r, 0, info, 0, info.length);
            s.note("fortress start chunk at " + info[0] + "," + info[1] + ", bounds " + info[2] + "," + info[3] + ","
                    + info[4] + " .. " + info[5] + "," + info[6] + "," + info[7] + "; start piece top y " + info[9]);
            wentNether[0] = true;
            Harness.command("gamemode spectator");
            Harness.command("execute in minecraft:the_nether run tp @s " + info[8] + ".5 " + (info[9] + 2) + " "
                    + info[10] + ".5 0 60");
        });
        s.waitUpTo("fortress: in the Nether with chunks loaded", 400, () -> !ok[0]
                || H.mc().level.dimension() == Level.NETHER
                && H.mc().level.getChunkSource().getChunk(H.floorX() >> 4, H.floorZ() >> 4, false) != null);
        s.waitTicks(40);
        s.run("fortress: module on", () -> {
            if (ok[0]) {
                H.enable(FortressSpawnModule.ID, true);
            }
        });
        s.waitUpTo("fortress: boxes, floors and seed candidates", 1200, () -> {
            if (!ok[0]) {
                return true;
            }
            FortressSpawnModule m = H.module(FortressSpawnModule.ID);
            return WorldExtraScenario.intField(m, "boxCount") > 0 && WorldExtraScenario.intField(m, "markCount") > 0
                    && WorldExtraScenario.field(m, "candidates") instanceof long[] c && c.length > 0;
        });
        s.run("fortress: checks", () -> {
            if (!ok[0]) {
                return;
            }
            FortressSpawnModule m = H.module(FortressSpawnModule.ID);
            int boxCount = WorldExtraScenario.intField(m, "boxCount");
            int labelCount = WorldExtraScenario.intField(m, "labelCount");
            int markCount = WorldExtraScenario.intField(m, "markCount");
            int[] boxes = (int[]) WorldExtraScenario.field(m, "boxes");
            long[] cand = (long[]) WorldExtraScenario.field(m, "candidates");
            byte[] kinds = (byte[]) WorldExtraScenario.field(m, "markKind");
            int inside = 0;
            int outside = 0;
            StringBuilder outBoxes = new StringBuilder();
            for (int i = 0; i < boxCount; i++) {
                int b = i * 6;
                // Box (max exclusive) against the structure's bounds (inclusive), 2 blocks of slack.
                boolean in = boxes[b] >= info[2] - 2 && boxes[b + 3] <= info[5] + 3 && boxes[b + 1] >= info[3] - 2
                        && boxes[b + 4] <= info[6] + 3 && boxes[b + 2] >= info[4] - 2 && boxes[b + 5] <= info[7] + 3;
                if (in) {
                    inside++;
                } else {
                    if (outside < 6) {
                        outBoxes.append(" [").append(boxes[b]).append(',').append(boxes[b + 1]).append(',')
                                .append(boxes[b + 2]).append(" .. ").append(boxes[b + 3]).append(',')
                                .append(boxes[b + 4]).append(',').append(boxes[b + 5]).append(']');
                    }
                    outside++;
                }
            }
            s.check("fortress_spawns: brick boxes found inside the real fortress (OreSim off)",
                    !H.module(OreSimModule.ID).isEnabled() && boxCount > 0 && outside == 0 && labelCount > 0,
                    boxCount + " boxes (" + inside + " inside the server's fortress bounds, " + outside
                            + " outside" + outBoxes + "), " + labelCount + " fortress labels");
            int wither = 0;
            for (int i = 0; i < markCount; i++) {
                if (kinds[i] == com.utilitysuite.world.fortress.FortressSections.SPAWN_WITHER) {
                    wither++;
                }
            }
            s.check("fortress_spawns: spawn floors marked near you", markCount > 0,
                    markCount + " floor marks (" + wither + " wither-skeleton, " + (markCount - wither)
                            + " blaze-only) around " + H.posStr());
            int fx = info[0] >> 4;
            int fz = info[1] >> 4;
            boolean found = false;
            StringBuilder cs = new StringBuilder();
            for (long k : cand) {
                int cx = (int) (k >> 32);
                int cz = (int) k;
                cs.append(cx).append(',').append(cz).append(' ');
                found |= cx == fx && cz == fz;
            }
            s.check("fortress_spawns: seed candidates include the real fortress start chunk (OreSim off)", found,
                    "candidates " + cs.toString().trim() + "; server start chunk " + fx + "," + fz);
            Shots.take(s, "worldextra-fortress");
        });
        s.always("fortress: back to the overworld", () -> {
            H.enable(FortressSpawnModule.ID, false);
            H.enable(OreSimModule.ID, oreWasOn[0]);
            if (wentNether[0]) {
                Harness.command("execute in minecraft:overworld run " + Arena.tp(0.5, 0.5, 0f));
                Harness.command("gamemode survival");
            }
        });
        s.waitUpTo("fortress: in the overworld again", 400, () -> H.mc().level.dimension() == Level.OVERWORLD
                && H.mc().level.getChunkSource().getChunk(H.floorX() >> 4, H.floorZ() >> 4, false) != null);
        s.waitTicks(20);
    }

    /**
     * Server thread: nearest fortress start, {locX, locZ, bounds minX..maxZ (6), start-piece centre x, top y, centre
     * z}, or null.
     */
    private static int[] locateFortress(BlockPos from) {
        var server = H.mc().getSingleplayerServer();
        ServerLevel nether = server.getLevel(Level.NETHER);
        Registry<Structure> reg = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Holder<Structure> h = reg.getOrThrow(BuiltinStructures.FORTRESS);
        var pair = nether.getChunkSource().getGenerator().findNearestMapStructure(nether, HolderSet.direct(h), from,
                100, false);
        if (pair == null) {
            return null;
        }
        BlockPos lp = pair.getFirst();
        ChunkAccess ch = nether.getChunk(lp.getX() >> 4, lp.getZ() >> 4, ChunkStatus.STRUCTURE_STARTS);
        StructureStart st = ch.getStartForStructure(h.value());
        if (st == null || !st.isValid() || st.getPieces().isEmpty()) {
            return null;
        }
        BoundingBox bb = st.getBoundingBox();
        BoundingBox sb = st.getPieces().get(0).getBoundingBox();
        return new int[] {lp.getX(), lp.getZ(), bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ(),
                (sb.minX() + sb.maxX()) / 2, sb.maxY(), (sb.minZ() + sb.maxZ()) / 2};
    }
}
