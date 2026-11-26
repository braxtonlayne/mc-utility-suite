package com.utilitysuite.devtest;

import com.utilitysuite.core.api.RenderBudget;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.budget.EntityCullHooks;
import com.utilitysuite.world.budget.RenderGovernorModule;
import com.utilitysuite.world.changes.ChangeTrackerModule;
import com.utilitysuite.world.changes.SnapshotDiff;
import com.utilitysuite.world.overlays.ProjectileRibbonModule;
import com.utilitysuite.world.overlays.TrajectoryIntegrator;
import com.utilitysuite.world.stealth.StealthVisionModule;
import com.utilitysuite.world.trial.TrialBlocks;
import com.utilitysuite.world.trial.TrialChamberModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Block/entity/render driven world modules in game: {@link TrialChamberModule}, {@link ChangeTrackerModule},
 * {@link StealthVisionModule}, {@link ProjectileRibbonModule} and {@link RenderGovernorModule}. All on the arena.
 */
final class WorldExtraOverlayTests {

    private WorldExtraOverlayTests() {
    }

    static void add(Scenario s) {
        trialChambers(s);
        changeTracker(s);
        stealthVision(s);
        projectileRibbon(s);
        renderGovernor(s);
    }

    /** "(dx,dy,dz)=block" relative to the arena centre, for diff diagnostics. */
    private static String rel(long p, net.minecraft.client.multiplayer.ClientLevel lvl) {
        int x = BlockPosPacking.x(p), y = BlockPosPacking.y(p), z = BlockPosPacking.z(p);
        String block = lvl == null ? "?" : lvl.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).getBlock()
                .builtInRegistryHolder().key().identifier().getPath();
        return "(" + (x - Arena.cx) + "," + (y - Arena.FLOOR) + "," + (z - Arena.cz) + ")=" + block;
    }

    private static int ax(int dx) {
        return Arena.cx + dx;
    }

    private static int ay(int dy) {
        return Arena.FLOOR + dy;
    }

    private static int az(int dz) {
        return Arena.cz + dz;
    }

    private static String at(int dx, int dy, int dz) {
        return ax(dx) + " " + ay(dy) + " " + az(dz);
    }

    // ---- Trial Chambers --------------------------------------------------------------------------------------------

    /** Classification code the module currently shows for an arena block, or -1. */
    private static int trialCode(TrialChamberModule m, int dx, int dy, int dz) {
        long[] pos = (long[]) WorldExtraScenario.field(m, "pos");
        int[] code = (int[]) WorldExtraScenario.field(m, "code");
        int count = WorldExtraScenario.intField(m, "count");
        long want = BlockPosPacking.pack(ax(dx), ay(dy), az(dz));
        for (int i = 0; pos != null && i < count; i++) {
            if (pos[i] == want) {
                return code[i];
            }
        }
        return -1;
    }

    private static String trialDescribe(int c) {
        return c < 0 ? "not listed" : TrialBlocks.label(c) + " (code " + c + ")";
    }

    /** Diagnostics: what the shared section scanner holds for an arena block. */
    private static String scannerAt(TrialChamberModule m, int dx, int dy, int dz) {
        Object runtime = WorldExtraScenario.field(m, "runtime");
        if (!(runtime instanceof com.utilitysuite.world.WorldRuntime rt)) {
            return "no runtime";
        }
        var sc = rt.scanner();
        long p = BlockPosPacking.pack(ax(dx), ay(dy), az(dz));
        Object loaded = WorldExtraScenario.field(sc, "loadedChunks");
        boolean chunkLoaded = loaded instanceof it.unimi.dsi.fastutil.longs.LongOpenHashSet set
                && set.contains(BlockPosPacking.chunk(ax(dx) >> 4, az(dz) >> 4));
        Object q = WorldExtraScenario.field(sc, "queue");
        int queued = q instanceof it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue fq ? fq.size() : -1;
        return "scanner value " + sc.observed.get(p) + ", mask " + sc.mask() + ", chunk tracked " + chunkLoaded
                + ", queue " + queued + ", render list " + sc.observed.renderCount() + ", index " + sc.observed.size();
    }

    private static void trialChambers(Scenario s) {
        // Normal vault (6,1,6), ominous trial spawner (-6,1,6), vault that already rewarded us (-6,1,-6).
        s.run("trial: place vaults and an ominous spawner", () -> {
            int[] u = UUIDUtil.uuidToIntArray(H.p().getUUID());
            Harness.command("setblock " + at(6, 1, 6) + " minecraft:vault");
            Harness.command("setblock " + at(-6, 1, 6) + " minecraft:trial_spawner[ominous=true]");
            Harness.command("setblock " + at(-6, 1, -6) + " minecraft:vault{server_data:{rewarded_players:[[I;"
                    + u[0] + "," + u[1] + "," + u[2] + "," + u[3] + "]]}}");
            H.enable(TrialChamberModule.ID, true);
        });
        s.cmd(() -> Arena.tp(6.5, 4.5, 0f));
        s.waitUpTo("trial: vault opens for us", 200, () -> {
            TrialChamberModule m = H.module(TrialChamberModule.ID);
            int c = trialCode(m, 6, 1, 6);
            return c >= 0 && TrialBlocks.status(c) == TrialBlocks.STATUS_ACTIVE;
        });
        s.run("trial: open vault + ominous spawner", () -> {
            TrialChamberModule m = H.module(TrialChamberModule.ID);
            int v = trialCode(m, 6, 1, 6);
            BlockState vs = H.block(ax(6), ay(1), az(6));
            s.check("trial_chambers: placed vault outlined as open while we stand by it",
                    v >= 0 && TrialBlocks.kind(v) == TrialBlocks.KIND_VAULT
                            && TrialBlocks.status(v) == TrialBlocks.STATUS_ACTIVE && !TrialBlocks.ominous(v),
                    trialDescribe(v) + "; block state "
                            + (vs.getBlock() instanceof VaultBlock ? vs.getValue(VaultBlock.STATE) : vs));
            int sp = trialCode(m, -6, 1, 6);
            BlockState ss = H.block(ax(-6), ay(1), az(6));
            boolean ominousNow = ss.getBlock() instanceof TrialSpawnerBlock && ss.getValue(TrialSpawnerBlock.OMINOUS);
            s.check("trial_chambers: ominous trial spawner outlined as ominous spawner",
                    sp >= 0 && TrialBlocks.kind(sp) == TrialBlocks.KIND_SPAWNER && TrialBlocks.ominous(sp) == ominousNow
                            && ominousNow,
                    trialDescribe(sp) + "; block state " + ss + "; " + scannerAt(m, -6, 1, 6));
        });
        s.cmd(() -> Arena.tp(-5.5, -4.5, 180f));
        s.waitUpTo("trial: rewarded vault inferred looted", 200, () -> {
            int c = trialCode(H.module(TrialChamberModule.ID), -6, 1, -6);
            return c >= 0 && TrialBlocks.status(c) == TrialBlocks.STATUS_LOOTED;
        });
        s.run("trial: looted vault", () -> {
            TrialChamberModule m = H.module(TrialChamberModule.ID);
            int c = trialCode(m, -6, 1, -6);
            int other = trialCode(m, 6, 1, 6);
            BlockState vs = H.block(ax(-6), ay(1), az(-6));
            s.check("trial_chambers: vault that already rewarded us is marked looted (others are not)",
                    c >= 0 && TrialBlocks.status(c) == TrialBlocks.STATUS_LOOTED
                            && other >= 0 && TrialBlocks.status(other) != TrialBlocks.STATUS_LOOTED,
                    "rewarded vault " + trialDescribe(c) + " (state "
                            + (vs.getBlock() instanceof VaultBlock ? vs.getValue(VaultBlock.STATE) : vs)
                            + "); other vault " + trialDescribe(other) + "; " + scannerAt(m, -6, 1, -6));
            Shots.take(s, "worldextra-trial");
        });
        s.always("trial: cleanup", () -> {
            H.enable(TrialChamberModule.ID, false);
            Harness.command("setblock " + at(6, 1, 6) + " minecraft:air");
            Harness.command("setblock " + at(-6, 1, 6) + " minecraft:air");
            Harness.command("setblock " + at(-6, 1, -6) + " minecraft:air");
            Harness.command("kill @e[type=minecraft:item]");
            Harness.command(Arena.tp(0.5, 0.5, 0f));
        });
        s.waitTicks(10);
    }

    // ---- Change Tracker --------------------------------------------------------------------------------------------

    private static void changeTracker(Scenario s) {
        s.run("changes: small radii, module on", () -> {
            H.setting(ChangeTrackerModule.ID, "mark_radius", 1);
            H.setting(ChangeTrackerModule.ID, "coarse_radius", 1);
            H.setting(ChangeTrackerModule.ID, "waypoint_radius", 0); // saved waypoints nearby would add their chunks
            H.enable(ChangeTrackerModule.ID, true);
        });
        s.waitUpTo("changes: files open", 60, () -> {
            ChangeTrackerModule m = H.module(ChangeTrackerModule.ID);
            return m.filesOpen();
        });
        s.run("changes: clear old snapshots, set up blocks", () -> {
            ChangeTrackerModule m = H.module(ChangeTrackerModule.ID);
            s.note(m.clearAll());
            Harness.command("setblock " + at(-3, 1, 3) + " minecraft:gold_block");
            Harness.command("setblock " + at(3, 1, 3) + " minecraft:air");
        });
        s.waitTicks(10);
        s.run("changes: press the snapshot key", () -> {
            ChangeTrackerModule m = H.module(ChangeTrackerModule.ID);
            KeyMapping key = (KeyMapping) WorldExtraScenario.field(m, "snapshotKey");
            Field cc = KeyMapping.class.getDeclaredField("clickCount");
            cc.setAccessible(true);
            cc.setInt(key, cc.getInt(key) + 1); // one press of the (unbound) snapshot key
        });
        s.waitUpTo("changes: snapshot captured", 100, () -> {
            ChangeTrackerModule m = H.module(ChangeTrackerModule.ID);
            Object q = WorldExtraScenario.field(m, "queue");
            return m.storedChunks() >= 9 && q instanceof it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue fq
                    && fq.isEmpty();
        });
        s.run("changes: snapshot check, then change blocks", () -> {
            ChangeTrackerModule m = H.module(ChangeTrackerModule.ID);
            s.check("change_tracker: snapshot key stores the 3x3 chunks around you", m.storedChunks() == 9,
                    m.storedChunks() + " chunks stored; status '" + m.status() + "'");
            Harness.command("setblock " + at(3, 1, 3) + " minecraft:diamond_block");
            Harness.command("setblock " + at(-3, 1, 3) + " minecraft:air");
        });
        s.waitTicks(10);
        s.run("changes: show changes", () -> H.setting(ChangeTrackerModule.ID, "show_diff", true));
        s.waitUpTo("changes: diff computed", 200, () -> WorldExtraScenario.field(H.module(ChangeTrackerModule.ID),
                "diff") != null);
        s.run("changes: diff check", () -> {
            ChangeTrackerModule m = H.module(ChangeTrackerModule.ID);
            SnapshotDiff.Result r = (SnapshotDiff.Result) WorldExtraScenario.field(m, "diff");
            long added = BlockPosPacking.pack(ax(3), ay(1), az(3));
            long removed = BlockPosPacking.pack(ax(-3), ay(1), az(3));
            boolean hasAdded = r != null && contains(r.added(), added);
            boolean hasRemoved = r != null && contains(r.removed(), removed);
            s.check("change_tracker: diff shows the placed block as new and the broken block as removed",
                    hasAdded && hasRemoved && r.added().length <= 4 && r.removed().length <= 4,
                    r == null ? "no diff" : "status '" + m.status() + "', placed listed " + hasAdded
                            + ", removed listed " + hasRemoved + ", added " + r.added().length + " removed "
                            + r.removed().length + " in " + r.comparedChunks() + " chunks");
            if (r != null) {
                StringBuilder sb = new StringBuilder("change diff positions (relative to the arena centre):");
                net.minecraft.client.multiplayer.ClientLevel lvl = H.mc().level;
                for (long p : r.added()) {
                    sb.append(" +").append(rel(p, lvl));
                }
                for (long p : r.removed()) {
                    sb.append(" -").append(rel(p, lvl));
                }
                s.note(sb.toString());
            }
        });
        s.waitUpTo("changes: snapshot saved to disk", 260, () -> {
            Path f = changeFile();
            return f != null && Files.isRegularFile(f);
        });
        s.run("changes: file check", () -> {
            Path f = changeFile();
            boolean ok = f != null && Files.isRegularFile(f);
            long size = ok ? Files.size(f) : -1;
            s.check("change_tracker: snapshots saved compactly (gzip, off-thread)", ok && size > 0 && size < 256 * 1024,
                    f + " " + size + " bytes");
        });
        s.always("changes: cleanup", () -> {
            ChangeTrackerModule m = H.module(ChangeTrackerModule.ID);
            H.setting(ChangeTrackerModule.ID, "show_diff", false);
            if (m.isEnabled() && m.filesOpen()) {
                m.clearAll();
            }
            H.setting(ChangeTrackerModule.ID, "mark_radius", 2);
            H.setting(ChangeTrackerModule.ID, "coarse_radius", 6);
            H.setting(ChangeTrackerModule.ID, "waypoint_radius", 1);
            H.enable(ChangeTrackerModule.ID, false);
            Harness.command("setblock " + at(3, 1, 3) + " minecraft:air");
            Harness.command("setblock " + at(-3, 1, 3) + " minecraft:air");
        });
        s.waitTicks(10);
    }

    private static Path changeFile() {
        try {
            ChangeTrackerModule m = H.module(ChangeTrackerModule.ID);
            if (!m.filesOpen()) {
                return null;
            }
            Method f = ChangeTrackerModule.class.getDeclaredMethod("file");
            f.setAccessible(true);
            return (Path) f.invoke(m);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static boolean contains(long[] a, long v) {
        for (long x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    // ---- Stealth Vision --------------------------------------------------------------------------------------------

    private record Source(int kind, boolean alert, LivingEntity e) {
    }

    private static Source source(StealthVisionModule m, Class<?> type) {
        LivingEntity[] src = (LivingEntity[]) WorldExtraScenario.field(m, "src");
        int[] kind = (int[]) WorldExtraScenario.field(m, "kind");
        boolean[] alert = (boolean[]) WorldExtraScenario.field(m, "alert");
        int count = WorldExtraScenario.intField(m, "count");
        for (int i = 0; src != null && i < count; i++) {
            if (type.isInstance(src[i])) {
                return new Source(kind[i], alert[i], src[i]);
            }
        }
        return null;
    }

    private static String sculkState(StealthVisionModule m) {
        int n = WorldExtraScenario.intField(m, "sculkCount");
        byte[] hear = (byte[]) WorldExtraScenario.field(m, "sculkHear");
        StringBuilder sb = new StringBuilder(n + " sensor(s)");
        for (int i = 0; hear != null && i < n; i++) {
            sb.append(' ').append(hear[i] == 1 ? "hears" : hear[i] == 2 ? "muffled" : "out-of-range");
        }
        return sb.toString();
    }

    private static int sculkHear(StealthVisionModule m) {
        int n = WorldExtraScenario.intField(m, "sculkCount");
        byte[] hear = (byte[]) WorldExtraScenario.field(m, "sculkHear");
        return n > 0 && hear != null ? hear[0] : -1;
    }

    private static void stealthVision(Scenario s) {
        s.cmd(() -> "summon minecraft:warden " + (ax(6) + 0.5) + " " + ay(1) + " " + (az(0) + 0.5)
                + " {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
        s.cmd(() -> "summon minecraft:enderman " + (ax(-6) + 0.5) + " " + ay(1) + " " + (az(0) + 0.5)
                + " {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
        s.cmd(() -> "summon minecraft:piglin_brute " + (ax(0) + 0.5) + " " + ay(1) + " " + (az(8) + 0.5)
                + " {NoAI:1b,Silent:1b,PersistenceRequired:1b,IsImmuneToZombification:1b}");
        s.cmd(() -> "setblock " + at(3, 1, -5) + " minecraft:sculk_sensor");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        s.run("stealth: on, look at the enderman", () -> {
            H.enable(StealthVisionModule.ID, true);
            WorldExtraScenario.lookAtAbs(ax(-6) + 0.5, ay(1) + 2.55, az(0) + 0.5);
        });
        s.waitTicks(30);
        s.run("stealth: sources", () -> {
            StealthVisionModule m = H.module(StealthVisionModule.ID);
            Source w = source(m, net.minecraft.world.entity.monster.warden.Warden.class);
            Source e = source(m, net.minecraft.world.entity.monster.Enderman.class);
            Source b = source(m, net.minecraft.world.entity.monster.piglin.PiglinBrute.class);
            s.check("stealth_vision: Warden 6 blocks away hears you (no wool)", w != null && w.alert(),
                    "warden " + (w == null ? "not tracked" : "alert=" + w.alert()) + ", sources "
                            + WorldExtraScenario.intField(m, "count"));
            s.check("stealth_vision: staring at the Enderman's head is flagged", e != null && e.alert(),
                    "enderman " + (e == null ? "not tracked" : "alert=" + e.alert()) + ", look pitch "
                            + Harness.fmt("%.1f", H.p().getXRot()) + " yaw " + Harness.fmt("%.1f", H.p().getYRot()));
            s.check("stealth_vision: Piglin Brute 8 blocks away with sight is flagged", b != null && b.alert(),
                    "brute " + (b == null ? "not tracked" : "alert=" + b.alert()));
            s.check("stealth_vision: sculk sensor 6 blocks away hears you", sculkHear(m) == 1, sculkState(m));
            Shots.take(s, "worldextra-stealth");
            WorldExtraScenario.lookAtAbs(ax(0) + 0.5, ay(1) + 1.0, az(-10) + 0.5); // look away
        });
        s.waitTicks(3);
        s.run("stealth: look away, then pumpkin", () -> {
            StealthVisionModule m = H.module(StealthVisionModule.ID);
            Source e = source(m, net.minecraft.world.entity.monster.Enderman.class);
            s.check("stealth_vision: looking away clears the Enderman stare", e != null && !e.alert(),
                    "enderman " + (e == null ? "not tracked" : "alert=" + e.alert()));
            Harness.command("item replace entity @s armor.head with minecraft:carved_pumpkin");
            WorldExtraScenario.lookAtAbs(ax(-6) + 0.5, ay(1) + 2.55, az(0) + 0.5);
        });
        s.waitTicks(10);
        s.run("stealth: pumpkin check, wool around the sensor", () -> {
            StealthVisionModule m = H.module(StealthVisionModule.ID);
            Source e = source(m, net.minecraft.world.entity.monster.Enderman.class);
            boolean pumpkin = H.p().getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                    .is(net.minecraft.world.item.Items.CARVED_PUMPKIN);
            s.check("stealth_vision: a carved pumpkin makes the Enderman stare safe", pumpkin && e != null && !e.alert(),
                    "pumpkin worn " + pumpkin + ", enderman " + (e == null ? "not tracked" : "alert=" + e.alert()));
            Harness.command("item replace entity @s armor.head with minecraft:air");
            Harness.command("fill " + at(2, 1, -6) + " " + at(4, 2, -4) + " minecraft:white_wool");
            Harness.command("setblock " + at(3, 1, -5) + " minecraft:sculk_sensor");
        });
        s.waitTicks(40);
        s.run("stealth: wool check", () -> {
            StealthVisionModule m = H.module(StealthVisionModule.ID);
            s.check("stealth_vision: wool around the sculk sensor muffles you", sculkHear(m) == 2, sculkState(m));
        });
        s.always("stealth: cleanup", () -> {
            H.enable(StealthVisionModule.ID, false);
            Harness.command("item replace entity @s armor.head with minecraft:air");
            Harness.command("kill @e[type=minecraft:warden]");
            Harness.command("kill @e[type=minecraft:enderman]");
            Harness.command("kill @e[type=minecraft:piglin_brute]");
            Harness.command("fill " + at(2, 1, -6) + " " + at(4, 2, -4) + " minecraft:air");
            Harness.command("kill @e[type=minecraft:item]");
            Harness.command("effect clear @s minecraft:darkness");
        });
        s.waitTicks(10);
    }

    // ---- Projectile Ribbon -----------------------------------------------------------------------------------------

    private static void projectileRibbon(Scenario s) {
        double[] predicted = new double[3];
        double[] start = new double[3];
        boolean[] thrown = new boolean[1];
        s.cmd("clear @s");
        s.cmd("give @s minecraft:ender_pearl 1");
        s.cmd(() -> Arena.tp(0.5, -6.5, 0f));
        s.waitTicks(10);
        s.run("ribbon: hold a pearl, aim at the floor", () -> {
            H.p().getInventory().setSelectedSlot(0);
            H.enable(ProjectileRibbonModule.ID, true);
            WorldExtraScenario.lookAt(0.5, 1.0, 3.5);
        });
        s.waitTicks(10);
        s.run("ribbon: predicted landing, throw", () -> {
            ProjectileRibbonModule m = H.module(ProjectileRibbonModule.ID);
            TrajectoryIntegrator sim = (TrajectoryIntegrator) WorldExtraScenario.field(m, "sim");
            boolean hit = sim != null && sim.hit();
            s.check("projectile_ribbon: pearl path predicted onto the floor", hit
                            && Math.abs(sim.hitY() - ay(1)) < 0.3 && sim.count() > 3,
                    sim == null ? "no sim" : Harness.fmt("hit=%s at (%.2f, %.2f, %.2f), %d points, held %s", hit,
                            sim.hitX(), sim.hitY(), sim.hitZ(), sim.count(), H.p().getMainHandItem()));
            if (hit) {
                predicted[0] = sim.hitX();
                predicted[1] = sim.hitY();
                predicted[2] = sim.hitZ();
                start[0] = H.p().getX();
                start[2] = H.p().getZ();
                H.mc().gameMode.useItem(H.p(), InteractionHand.MAIN_HAND);
                thrown[0] = true;
            }
        });
        s.waitUpTo("ribbon: pearl landed", 100, () -> !thrown[0]
                || Math.hypot(H.p().getX() - start[0], H.p().getZ() - start[2]) > 2.0);
        s.waitTicks(5);
        s.run("ribbon: pearl landing vs prediction", () -> {
            if (!thrown[0]) {
                return;
            }
            double err = Math.hypot(H.p().getX() - predicted[0], H.p().getZ() - predicted[2]);
            s.check("projectile_ribbon: real pearl lands where the ribbon said", err < 1.0,
                    Harness.fmt("landed %s, predicted (%.2f, %.2f, %.2f): %.2f blocks off", H.posStr(),
                            predicted[0], predicted[1], predicted[2], err));
        });

        // Bow: draw fully (the user holding right click), read the prediction, release, find where the arrow sticks.
        double[] arrowLast = new double[3];
        int[] stable = new int[1];
        boolean[] drawn = new boolean[1];
        s.cmd("clear @s");
        s.cmd("give @s minecraft:bow 1");
        s.cmd("give @s minecraft:arrow 4");
        s.cmd(() -> Arena.tp(0.5, -6.5, 0f));
        s.waitTicks(10);
        s.run("ribbon: draw the bow", () -> {
            H.p().getInventory().setSelectedSlot(0);
            WorldExtraScenario.lookAt(0.5, 1.0, 1.5);
            H.mc().options.keyUse.setDown(true); // the user holding right click (test scaffolding)
        });
        s.waitTicks(30);
        s.run("ribbon: arrow prediction, release", () -> {
            ProjectileRibbonModule m = H.module(ProjectileRibbonModule.ID);
            TrajectoryIntegrator sim = (TrajectoryIntegrator) WorldExtraScenario.field(m, "sim");
            Object held = WorldExtraScenario.field(m, "held");
            Object kind = held == null ? null : WorldExtraScenario.field(held, "kind");
            Object power = held == null ? null : WorldExtraScenario.field(held, "power");
            boolean ok = sim != null && sim.hit() && kind == TrajectoryIntegrator.Kind.ARROW
                    && power instanceof Double d && d >= 0.99 && H.p().isUsingItem();
            s.check("projectile_ribbon: full-draw arrow path predicted onto the floor",
                    ok && Math.abs(sim.hitY() - ay(1)) < 0.3,
                    sim == null ? "no sim" : Harness.fmt("kind=%s power=%s using=%s hit=%s at (%.2f, %.2f, %.2f)",
                            kind, power, H.p().isUsingItem(), sim.hit(), sim.hitX(), sim.hitY(), sim.hitZ()));
            drawn[0] = ok;
            if (ok) {
                predicted[0] = sim.hitX();
                predicted[1] = sim.hitY();
                predicted[2] = sim.hitZ();
            }
            H.mc().options.keyUse.setDown(false);
        });
        s.waitUpTo("ribbon: arrow stuck", 100, () -> {
            if (!drawn[0]) {
                return true;
            }
            Arrow a = nearestArrow();
            if (a == null) {
                return false;
            }
            double d = Math.abs(a.getX() - arrowLast[0]) + Math.abs(a.getY() - arrowLast[1])
                    + Math.abs(a.getZ() - arrowLast[2]);
            arrowLast[0] = a.getX();
            arrowLast[1] = a.getY();
            arrowLast[2] = a.getZ();
            stable[0] = d < 1.0e-3 ? stable[0] + 1 : 0;
            return stable[0] >= 5;
        });
        s.run("ribbon: arrow vs prediction", () -> {
            if (!drawn[0]) {
                return;
            }
            Arrow a = nearestArrow();
            double err = a == null ? -1 : Math.hypot(a.getX() - predicted[0], a.getZ() - predicted[2]);
            s.check("projectile_ribbon: real arrow sticks where the ribbon said", a != null && err < 1.0,
                    a == null ? "no arrow found" : Harness.fmt("arrow at (%.2f, %.2f, %.2f), predicted (%.2f, %.2f,"
                            + " %.2f): %.2f blocks off", a.getX(), a.getY(), a.getZ(), predicted[0], predicted[1],
                            predicted[2], err));
        });
        s.always("ribbon: cleanup", () -> {
            H.mc().options.keyUse.setDown(false);
            H.enable(ProjectileRibbonModule.ID, false);
            Harness.command("kill @e[type=minecraft:arrow]");
            Harness.command("kill @e[type=minecraft:ender_pearl]");
            Harness.command("kill @e[type=minecraft:item]");
            Harness.command("clear @s");
            Harness.command(Arena.tp(0.5, 0.5, 0f));
        });
        s.waitTicks(10);
    }

    private static Arrow nearestArrow() {
        List<Arrow> list = H.mc().level.getEntitiesOfClass(Arrow.class, H.p().getBoundingBox().inflate(32));
        Arrow best = null;
        double bd = Double.MAX_VALUE;
        for (Arrow a : list) {
            double d = a.distanceToSqr(H.p());
            if (d < bd) {
                bd = d;
                best = a;
            }
        }
        return best;
    }

    // ---- FPS Governor ---------------------------------------------------------------------------------------------

    private static Display.ItemDisplay displayNear(double x, double y, double z) {
        List<Display.ItemDisplay> list = H.mc().level.getEntitiesOfClass(Display.ItemDisplay.class,
                new AABB(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1));
        return list.isEmpty() ? null : list.get(0);
    }

    /** Asks the real render dispatcher (with the cull mixin) whether it would draw {@code e} this frame. */
    private static boolean wouldRender(Entity e) {
        // 26.3: the frame's cull frustum lives on the camera (LevelExtractor reads camera.getCullFrustum())
        var camera = H.mc().gameRenderer.mainCamera();
        Frustum f = camera.getCullFrustum();
        Vec3 cam = camera.position();
        return f != null && H.mc().getEntityRenderDispatcher().shouldRender(e, f, cam.x, cam.y, cam.z,
                H.mc().getDeltaTracker().getGameTimeDeltaPartialTick(false));
    }

    private static void renderGovernor(Scenario s) {
        int[] oldLimit = {-1};
        s.run("governor: cap the frame rate at 30, target 120", () -> {
            oldLimit[0] = H.mc().options.framerateLimit().get();
            H.mc().options.framerateLimit().set(30);
            H.setting(RenderGovernorModule.ID, "target_fps", 120);
            H.enable(RenderGovernorModule.ID, true);
        });
        s.waitUpTo("governor: steps detail down", 400, () -> RenderBudget.tier() >= 1);
        s.run("governor: step-down check, item displays near and far", () -> {
            RenderGovernorModule m = H.module(RenderGovernorModule.ID);
            s.check("render_governor: 1% low under target steps the render budget down",
                    RenderBudget.tier() >= 1 && m.phase() != null && m.lastOnePercentLow() < 120,
                    Harness.fmt("tier %d, phase %s, 1%% low %.1f fps (cap 30, target 120)", RenderBudget.tier(),
                            m.phase(), m.lastOnePercentLow()));
            H.setting(RenderGovernorModule.ID, "cull_item_displays", true);
            H.setting(RenderGovernorModule.ID, "cull_from_tier", 1);
            H.setting(RenderGovernorModule.ID, "cull_distance", 8.0);
            Harness.command("summon minecraft:item_display " + (ax(0) + 0.5) + " " + (ay(1) + 1.5) + " "
                    + (az(-3) + 0.5) + " {item:{id:\"minecraft:diamond_block\",count:1}}");
            Harness.command("summon minecraft:item_display " + (ax(0) + 0.5) + " " + (ay(1) + 1.5) + " "
                    + (az(7) + 0.5) + " {item:{id:\"minecraft:gold_block\",count:1}}");
            Harness.command(Arena.tp(0.5, -6.5, 0f));
        });
        s.waitTicks(20);
        s.run("governor: entity cull check", () -> {
            WorldExtraScenario.lookAt(0.5, 2.5, 7.5);
        });
        s.waitTicks(5);
        s.run("governor: entity cull result", () -> {
            Display.ItemDisplay near = displayNear(ax(0) + 0.5, ay(1) + 1.5, az(-3) + 0.5);
            Display.ItemDisplay far = displayNear(ax(0) + 0.5, ay(1) + 1.5, az(7) + 0.5);
            boolean nearDrawn = near != null && wouldRender(near);
            boolean farDrawn = far != null && wouldRender(far);
            Vec3 cam = H.mc().gameRenderer.mainCamera().position();
            s.check("render_governor: far item display hidden while detail is reduced, near one drawn",
                    near != null && far != null && nearDrawn && !farDrawn && RenderBudget.tier() >= 1,
                    "near " + (near == null ? "missing" : "drawn=" + nearDrawn) + ", far "
                            + (far == null ? "missing" : "drawn=" + farDrawn + Harness.fmt(" at %.1f blocks",
                            far.position().distanceTo(cam))) + ", tier " + RenderBudget.tier());
            H.mc().options.framerateLimit().set(oldLimit[0]);
            H.setting(RenderGovernorModule.ID, "target_fps", 20);
        });
        s.waitUpTo("governor: steps detail back up", 600, () -> RenderBudget.tier() == 0);
        s.run("governor: step-up and off checks", () -> {
            RenderGovernorModule m = H.module(RenderGovernorModule.ID);
            int tierUp = RenderBudget.tier();
            s.check("render_governor: detail comes back once the 1% low clears the target",
                    tierUp == 0 && m.phase() == null,
                    Harness.fmt("tier %d, phase %s, 1%% low %.1f fps (target 20, cap %d)", tierUp, m.phase(),
                            m.lastOnePercentLow(), oldLimit[0]));
            RenderBudget.setTier(2); // force a reduced tier, then switching the governor off must restore it
            H.enable(RenderGovernorModule.ID, false);
            Display.ItemDisplay far = displayNear(ax(0) + 0.5, ay(1) + 1.5, az(7) + 0.5);
            boolean farDrawn = far != null && wouldRender(far);
            s.check("render_governor: off restores full detail at once (tier 0, nothing culled)",
                    RenderBudget.tier() == 0 && far != null && farDrawn && !EntityCullHooks.cull(far, 0, 0, 0),
                    "tier " + RenderBudget.tier() + ", far display drawn=" + farDrawn);
        });
        s.always("governor: cleanup", () -> {
            if (oldLimit[0] > 0) {
                H.mc().options.framerateLimit().set(oldLimit[0]);
            }
            H.setting(RenderGovernorModule.ID, "target_fps", 60);
            H.setting(RenderGovernorModule.ID, "cull_item_displays", false);
            H.setting(RenderGovernorModule.ID, "cull_from_tier", 2);
            H.setting(RenderGovernorModule.ID, "cull_distance", 32.0);
            H.enable(RenderGovernorModule.ID, false);
            RenderBudget.reset();
            Harness.command("kill @e[type=minecraft:item_display]");
            Harness.command(Arena.tp(0.5, 0.5, 0f));
        });
        s.waitTicks(10);
        s.run("worldextra: modules back off", () -> {
            StringBuilder on = new StringBuilder();
            for (String id : WorldExtraScenario.MODULES) {
                if (H.module(id).isEnabled()) {
                    on.append(id).append(' ');
                }
            }
            s.check("worldextra: every module switched back off", on.length() == 0,
                    on.length() == 0 ? "all " + WorldExtraScenario.MODULES.length + " off" : "still on: " + on);
            s.note(String.format(Locale.ROOT, "render tier after %d", RenderBudget.tier()));
        });
    }
}
