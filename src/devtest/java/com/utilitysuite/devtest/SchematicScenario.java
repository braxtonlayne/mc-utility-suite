package com.utilitysuite.devtest;

import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.jobs.schematic.SchematicBootstrap;
import com.utilitysuite.jobs.schematic.SchematicJobCard;
import com.utilitysuite.jobs.schematic.SchematicToolkit;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Scenario 6: a tiny .nbt structure, Build Assist placement and the gather-only path. */
final class SchematicScenario {

    /** (dx, dy, dz, block) of the test hut: a 3x3 cobblestone floor plus one plank on top of the centre. */
    static final Object[][] HUT = {
            {0, 0, 0, "minecraft:cobblestone"}, {1, 0, 0, "minecraft:cobblestone"}, {2, 0, 0, "minecraft:cobblestone"},
            {0, 0, 1, "minecraft:cobblestone"}, {1, 0, 1, "minecraft:cobblestone"}, {2, 0, 1, "minecraft:cobblestone"},
            {0, 0, 2, "minecraft:cobblestone"}, {1, 0, 2, "minecraft:cobblestone"}, {2, 0, 2, "minecraft:cobblestone"},
            {1, 1, 1, "minecraft:oak_planks"}};

    private SchematicScenario() {
    }

    static void writeNbt(Path file, int sx, int sy, int sz, Object[][] blocks) throws Exception {
        CompoundTag root = new CompoundTag();
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(sx));
        size.add(IntTag.valueOf(sy));
        size.add(IntTag.valueOf(sz));
        root.put("size", size);
        ListTag palette = new ListTag();
        java.util.List<String> ids = new java.util.ArrayList<>();
        ListTag list = new ListTag();
        for (Object[] b : blocks) {
            String id = (String) b[3];
            int state = ids.indexOf(id);
            if (state < 0) {
                state = ids.size();
                ids.add(id);
                CompoundTag p = new CompoundTag();
                p.putString("Name", id);
                palette.add(p);
            }
            CompoundTag bt = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(IntTag.valueOf((Integer) b[0]));
            pos.add(IntTag.valueOf((Integer) b[1]));
            pos.add(IntTag.valueOf((Integer) b[2]));
            bt.put("pos", pos);
            bt.putInt("state", state);
            list.add(bt);
        }
        root.put("palette", palette);
        root.put("blocks", list);
        root.put("entities", new ListTag());
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(root, file);
    }

    static Scenario build() {
        Scenario s = new Scenario("6-schematic", "Schematic: Build Assist + gather-only");
        IGoalEngine g = Services.goals();
        SchematicToolkit tk = SchematicBootstrap.toolkit();
        int[] anchor = new int[3];
        int[] moves = {0};
        boolean[] sawHiddenNote = {false};
        // Standing spots around the hut (dx, dz, yaw relative to the arena centre): east (start), south, west, north.
        double[][] spots = {{-4.5, -6.5, 90}, {-6.5, -3.5, 180}, {-9.5, -6.5, 270}, {-6.5, -9.5, 0}};
        long[] jobId = {-1};
        String[] lastPhase = {""};
        Arena.ensure(s);
        s.run("write schematics", () -> {
            Path dir = H.mc().gameDirectory.toPath().resolve("schematics");
            writeNbt(dir.resolve("pt_hut.nbt"), 3, 2, 3, HUT);
            writeNbt(dir.resolve("pt_bricks.nbt"), 2, 1, 1, new Object[][] {{0, 0, 0, "minecraft:bricks"},
                    {1, 0, 0, "minecraft:bricks"}});
            s.note("wrote " + dir.resolve("pt_hut.nbt") + " and pt_bricks.nbt");
        });
        s.cmd("clear @s");
        s.cmd("give @s minecraft:cobblestone 16");
        s.cmd("give @s minecraft:oak_planks 4");
        s.run("position", () -> {
            anchor[0] = Arena.cx - 8;
            anchor[1] = Arena.FLOOR + 1;
            anchor[2] = Arena.cz - 8;
            Harness.command(Arena.tp(-4.5, -6.5, 90f));
        });
        s.waitTicks(10);
        int[] midShot = new int[1];
        s.run("submit build", () -> {
            ParseResult r = g.parse("build schematic pt_hut anchor=" + anchor[0] + "," + anchor[1] + "," + anchor[2]);
            s.check("parse build command", r.isOk(), r.isOk() ? r.request().label() : r.error());
            jobId[0] = g.submit(r.request());
        });
        s.step("build runs", t -> {
            Optional<SchematicJobCard> c = tk.jobCard();
            String ph = c.map(x -> x.phase() + " " + x.placedBlocks() + "/" + x.totalBlocks() + " missing="
                    + x.missing() + " notes=" + x.notes()).orElse("no card");
            if (!ph.equals(lastPhase[0])) {
                s.note("t=" + t + " card: " + ph + "; job " + GoalScenario.describe(jobId[0]));
                lastPhase[0] = ph;
            }
            if (c.map(x -> x.notes().stream().anyMatch(n -> n.contains("hidden behind"))).orElse(false)) {
                sawHiddenNote[0] = true;
            }
            // Build Assist walks to hidden cells itself now; the harness only steps in as a late fallback.
            if (placed(anchor) < HUT.length && t > 0 && t % 900 == 0 && moves[0] < 3) {
                // Build Assist only places what it can see from where the player stands; walk around like a user.
                moves[0]++;
                double[] sp = spots[moves[0]];
                Harness.command(Arena.tp(sp[0], sp[1], (float) sp[2]));
                s.note("t=" + t + ": " + placed(anchor) + "/" + HUT.length + " placed; moving the player to spot "
                        + moves[0] + " (" + H.posStr() + ")");
            }
            if (midShot[0] == 0 && placed(anchor) * 2 >= HUT.length) {
                midShot[0] = t;
                Shots.clearChat();
            } else if (midShot[0] > 0 && t == midShot[0] + 3) {
                Shots.take(s, "schematic-building");
            }
            if (placed(anchor) == HUT.length && t > 20) {
                return true;
            }
            if (c.map(x -> x.phase().equals("COMPLETE") || x.phase().equals("FAILED")).orElse(false) && t > 40) {
                return true;
            }
            if (t == 280 && placed(anchor) < HUT.length) {
                StringBuilder miss = new StringBuilder();
                for (Object[] b : HUT) {
                    String have = H.blockId(anchor[0] + (Integer) b[0], anchor[1] + (Integer) b[1], anchor[2] + (Integer) b[2]);
                    if (!have.equals(b[3])) {
                        miss.append(b[0]).append(",").append(b[1]).append(",").append(b[2]).append(" wants ").append(b[3]).append(" has ").append(have).append("; ");
                    }
                }
                s.note("diag t=" + t + ": missing cells (relative to anchor " + anchor[0] + "," + anchor[1] + "," + anchor[2] + "): " + miss);
                Object assist = null;
                try {
                    java.lang.reflect.Field f = SchematicToolkit.class.getDeclaredField("assist");
                    f.setAccessible(true);
                    assist = f.get(tk);
                } catch (ReflectiveOperationException e) {
                    s.note("no assist field: " + e);
                }
                if (assist != null) {
                    s.note("diag t=" + t + ": assist " + H.fields(assist, "plan", "lease", "pausedByPreemption", "leaseRetry",
                            "cooldown", "waitTicks", "windowCount", "idleTicks", "firstOpen", "searchPos", "targetStep",
                            "targetX", "targetY", "targetZ", "targetFace", "aimTicks", "placements")
                            + "; " + H.arbiterState() + "; player " + H.posStr() + " reach "
                            + H.p().blockInteractionRange() + "; main hand " + H.p().getMainHandItem());
                }
            }
            if (t > 20 * 90) {
                throw new Scenario.StepTimeout("build did not finish in 90 s: " + ph + " placed " + placed(anchor));
            }
            return false;
        });
        Shots.add(s, "schematic-done");
        s.run("build result", () -> {
            int n = placed(anchor);
            s.check("Build Assist placed every block", n == HUT.length, n + "/" + HUT.length
                    + " blocks match in the world; card " + lastPhase[0]);
            if (moves[0] > 0) {
                s.check("card says why when blocks are hidden from the player's spot", sawHiddenNote[0],
                        "needed " + moves[0] + " move(s); hidden-blocks note seen=" + sawHiddenNote[0]);
            } else {
                s.note("finished from the first standing spot");
            }
        });
        s.waitUpTo("job completes", 100, () -> GoalScenario.terminal(jobId[0]));
        s.run("job state", () -> s.check("build job completes", GoalScenario.describe(jobId[0]).startsWith(
                "COMPLETED") || GoalScenario.describe(jobId[0]).equals("job gone"), GoalScenario.describe(jobId[0])));
        // --- gather-only: materials missing -> GatherMany submitted ---
        s.run("submit gather-only", () -> {
            ParseResult r = g.parse("build schematic pt_bricks gather-only anchor=" + Arena.cx + "," + (Arena.FLOOR + 1)
                    + "," + (Arena.cz - 8));
            s.check("parse gather-only build", r.isOk() && r.request() instanceof GoalRequest.Build b
                    && b.gatherOnly(), r.isOk() ? r.request().label() : r.error());
            jobId[0] = g.submit(r.request());
        });
        s.waitUpTo("gather submitted", 20 * 30, tk::gatherSubmitted);
        s.run("gather-only result", () -> {
            boolean many = false;
            StringBuilder jobs = new StringBuilder();
            for (JobSnapshot j : g.queuedJobs()) {
                jobs.append(j.goalLabel()).append('[').append(j.status()).append("] ");
                many |= j.goalLabel().contains("item types");
            }
            Optional<JobSnapshot> a = g.activeJob();
            if (a.isPresent()) {
                jobs.append("active ").append(a.get().goalLabel()).append('[').append(a.get().status()).append(']');
                many |= a.get().goalLabel().contains("item types");
            }
            s.check("gather-only submits a GatherMany for the missing bricks", tk.gatherSubmitted() && many,
                    "gatherSubmitted=" + tk.gatherSubmitted() + "; jobs: " + jobs + "; card " + tk.jobCard().map(
                            c -> c.phase() + " missing=" + c.missing()).orElse("none"));
        });
        s.always("cancel", () -> {
            for (JobSnapshot q : g.queuedJobs()) {
                g.cancel(q.jobId());
            }
            g.activeJob().ifPresent(a -> g.cancel(a.jobId()));
            tk.cancel();
        });
        return s;
    }

    static int placed(int[] a) {
        int n = 0;
        for (Object[] b : HUT) {
            if (H.blockId(a[0] + (Integer) b[0], a[1] + (Integer) b[1], a[2] + (Integer) b[2]).equals(b[3])) {
                n++;
            }
        }
        return n;
    }
}
