package com.utilitysuite.devtest;

import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.jobs.goal.MinecraftInventoryView;
import com.utilitysuite.jobs.planner.CrafterTemplate;
import com.utilitysuite.jobs.storage.StorageMemory;
import com.utilitysuite.jobs.storage.StorageMemoryModule;
import com.utilitysuite.jobs.storage.hall.HallSorterModule;
import com.utilitysuite.jobs.storage.hall.StorageCategories;
import com.utilitysuite.tools.inventory.SmartInventory;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Scenario: the station and storage jobs in a real world. Each section builds its blocks on the arena, lets Storage
 * Memory remember them through the normal right-click / screen-close path, runs the job through the goal engine and
 * reads the result back from the integrated server's block entities. Sections: gear loadout, crafter template
 * save/apply, storage hall sort (category moves, partial-stack merges, enchanted-book grouping), furnace array,
 * brewing with bottle filling, and the carried ender chest fetch. Every section cleans up its blocks, inventory and
 * module switches. Select it alone with {@code -PplaytestOnly=StationJobs}.
 */
final class StationJobsScenario {

    private static final int Y = 1;
    /** Last snapshot of every job seen on the event bus (finished jobs leave the engine's lists). */
    private static final Map<Long, JobSnapshot> LAST = new ConcurrentHashMap<>();
    private static boolean subscribed;

    private StationJobsScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("14-stations", "Station & storage jobs: loadout, crafter, hall sort, furnaces,"
                + " brewing + bottles, carried ender chest");
        if (!subscribed) {
            subscribed = true;
            Services.events().subscribe(SuiteEvent.JobChanged.class, e -> {
                if (e.snapshot() != null) {
                    LAST.put(e.snapshot().jobId(), e.snapshot());
                }
            });
        }
        Arena.ensure(s);
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd("clear @s");
        s.cmd("effect give @s minecraft:saturation 1200 0 true");
        s.cmd("effect give @s minecraft:resistance 1200 4 true");
        s.cmd(() -> Arena.fill(-12, 1, -12, 12, 4, -10, "minecraft:air"));
        s.cmd(() -> Arena.fill(-12, 1, 8, 12, 4, 12, "minecraft:air"));
        // furnace-array, brewing and crafter goals are behind a Goal Engine setting (off by default)
        s.run("station jobs on", () -> H.setting(com.utilitysuite.jobs.goal.GoalEngine.ID, "station_jobs", true));
        loadout(s);
        crafter(s);
        hall(s);
        furnaces(s);
        brewing(s);
        enderChest(s);
        s.always("disarm", () -> {
            cancelAll();
            if (H.mc().screen != null) {
                H.p().closeContainer();
                H.mc().setScreen(null);
            }
            H.enable(HallSorterModule.ID, false);
            H.enable(SmartInventory.ID, false);
            H.setting(StorageMemoryModule.ID, "ender_chest_source", false);
            H.setting(com.utilitysuite.jobs.goal.GoalEngine.ID, "station_jobs", false);
            Harness.command(Arena.fill(-12, 1, -12, 12, 4, -10, "minecraft:air"));
            Harness.command(Arena.fill(-12, 1, 8, 12, 4, 12, "minecraft:air"));
            Harness.command(Arena.fill(-4, 1, -4, 4, 3, 4, "minecraft:air"));
            Harness.command("clear @s");
            Harness.command("kill @e[type=minecraft:item]");
        });
        return s;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    static int ax(int dx) {
        return Arena.cx + dx;
    }

    static int ay() {
        return Arena.FLOOR + Y;
    }

    static int az(int dz) {
        return Arena.cz + dz;
    }

    static String at(int dx, int dz) {
        return ax(dx) + " " + ay() + " " + az(dz);
    }

    static StorageMemory memory() {
        StorageMemoryModule m = H.module(StorageMemoryModule.ID);
        return m.memory();
    }

    static String dim() {
        return H.mc().level.dimension().location().toString();
    }

    static void cancelAll() {
        var g = Services.goals();
        for (JobSnapshot q : g.queuedJobs()) {
            g.cancel(q.jobId());
        }
        g.activeJob().ifPresent(a -> g.cancel(a.jobId()));
    }

    /** Runs {@code f} on the integrated server thread and waits for the result. */
    static <T> T onServer(Supplier<T> f) throws Exception {
        MinecraftServer server = H.mc().getSingleplayerServer();
        return server.submit(f).get(5, TimeUnit.SECONDS);
    }

    /** Server-side copy of a container block's slots (empty list when there is no container). */
    static List<ItemStack> contents(int dx, int dz) throws Exception {
        BlockPos pos = new BlockPos(ax(dx), ay(), az(dz));
        return onServer(() -> {
            BlockEntity be = H.mc().getSingleplayerServer().overworld().getBlockEntity(pos);
            List<ItemStack> out = new ArrayList<>();
            if (be instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    out.add(c.getItem(i).copy());
                }
            }
            return out;
        });
    }

    static String id(ItemStack st) {
        return BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

    /** "id[variant]" -> total count. */
    static Map<String, Integer> totals(List<ItemStack> slots) {
        Map<String, Integer> m = new TreeMap<>();
        for (ItemStack st : slots) {
            if (!st.isEmpty()) {
                String v = StorageMemoryModule.variantKey(st);
                m.merge(id(st).replace("minecraft:", "") + (v.isEmpty() ? "" : "[" + v + "]"), st.getCount(),
                        Integer::sum);
            }
        }
        return m;
    }

    static int total(List<ItemStack> slots, String key) {
        return totals(slots).getOrDefault(key, 0);
    }

    /** Number of slots holding the item (any variant). */
    static int stacks(List<ItemStack> slots, String itemId) {
        int n = 0;
        for (ItemStack st : slots) {
            if (!st.isEmpty() && id(st).equals(itemId)) {
                n++;
            }
        }
        return n;
    }

    static String describe(List<ItemStack> slots) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < slots.size(); i++) {
            ItemStack st = slots.get(i);
            if (!st.isEmpty()) {
                String v = StorageMemoryModule.variantKey(st);
                sb.append(i).append(':').append(st.getCount()).append(' ').append(id(st).replace("minecraft:", ""))
                        .append(v.isEmpty() ? "" : "[" + v + "]").append(", ");
            }
        }
        return sb.append('}').toString().replace(", }", "}");
    }

    /**
     * Right-clicks a container or station block through the client's normal interaction path (fires the same
     * RightClickBlock / screen open / screen close events a player does), waits for the contents to sync, closes it.
     * The player must already stand within reach (teleport first).
     */
    static void openAndClose(Scenario s, String name, int dx, int dz) {
        int[] openedAt = {-1};
        s.step("open " + name, t -> {
            if (t == 0) {
                BlockPos pos = new BlockPos(ax(dx), ay(), az(dz));
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false);
                H.mc().gameMode.useItemOn(H.p(), InteractionHand.MAIN_HAND, hit);
                return false;
            }
            if (H.mc().screen instanceof AbstractContainerScreen<?>) {
                if (openedAt[0] < 0) {
                    openedAt[0] = t;
                }
                if (t - openedAt[0] >= 10) {
                    H.p().closeContainer();
                    return true;
                }
                return false;
            }
            if (t > 60) {
                s.note(name + ": no container screen opened within 3 s (screen " + H.mc().screen + ")");
                return true;
            }
            return false;
        });
        s.waitTicks(3);
    }

    /** Stand two blocks south of an arena block, facing it. */
    static void standBy(Scenario s, int dx, int dz) {
        s.cmd(() -> "tp @s " + (ax(dx) + 0.5) + " " + ay() + " " + (az(dz) + 2.5) + " 180 20");
        s.waitTicks(5);
    }

    /** Parses and submits a goal, then waits (without aborting) until it finishes or the timeout passes. */
    static void runJob(Scenario s, String label, String goal, int timeoutTicks, long[] id,
                       Scenario.Step whileRunning) {
        String[] last = {""};
        s.run("submit " + label, () -> {
            ParseResult r = Services.goals().parse(goal);
            if (!r.isOk()) {
                s.check(label + ": parse '" + goal + "'", false, r.error());
                id[0] = -1;
                return;
            }
            id[0] = Services.goals().submit(r.request());
            s.note(label + ": submitted job #" + id[0] + " " + r.request().label());
        });
        s.step(label + " runs", t -> {
            if (id[0] < 0) {
                return true;
            }
            String d = state(id[0]);
            if (!d.equals(last[0])) {
                s.note(label + " t=" + t + " " + d + " at " + H.posStr());
                last[0] = d;
            }
            if (whileRunning != null) {
                whileRunning.tick(t);
            }
            if (GoalScenario.terminal(id[0])) {
                return true;
            }
            if (t > timeoutTicks) {
                s.note(label + ": timed out after " + timeoutTicks / 20 + " s: " + d);
                Services.goals().cancel(id[0]);
                return true;
            }
            return false;
        });
    }

    /** Live snapshot of a job, else the last one seen on the event bus. */
    static String state(long id) {
        JobSnapshot j = GoalScenario.job(id).orElse(LAST.get(id));
        if (j == null) {
            return "job " + id + " unknown";
        }
        return j.status() + " step='" + j.currentStep() + "' " + j.progressCurrent() + "/" + j.progressTarget()
                + " reason='" + j.reason() + "'";
    }

    static boolean completed(long id) {
        JobSnapshot j = GoalScenario.job(id).orElse(LAST.get(id));
        return j != null && j.status() == JobStatus.COMPLETED;
    }

    static int potions(String key) {
        int n = 0;
        var inv = H.p().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && key.equals(MinecraftInventoryView.potionKey(st, id(st)))) {
                n += st.getCount();
            }
        }
        return n;
    }

    // ---- 5: loadouts ----------------------------------------------------------------------------------------------

    private static void loadout(Scenario s) {
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        s.cmd("clear @s");
        s.run("smart inventory on", () -> {
            H.setting(SmartInventory.ID, SmartInventory.K_REFILL, false);
            H.enable(SmartInventory.ID, true);
        });
        s.cmd("item replace entity @s armor.head with minecraft:iron_helmet");
        s.cmd("item replace entity @s armor.chest with minecraft:diamond_chestplate");
        s.cmd("item replace entity @s armor.feet with minecraft:golden_boots");
        s.cmd("item replace entity @s weapon.offhand with minecraft:shield");
        s.cmd("item replace entity @s hotbar.0 with minecraft:diamond_sword");
        s.cmd("item replace entity @s hotbar.1 with minecraft:bow");
        s.cmd("item replace entity @s hotbar.2 with minecraft:cooked_beef 16");
        s.waitTicks(5);
        s.run("save loadout", () -> {
            SmartInventory si = H.module(SmartInventory.ID);
            String r = si.saveLoadout("playtest kit");
            s.check("loadout: save captures armour, offhand and hotbar", r.startsWith("Saved loadout"), r);
        });
        // scramble: everything into the main inventory, junk in the hotbar
        s.cmd("clear @s");
        s.cmd("item replace entity @s inventory.4 with minecraft:iron_helmet");
        s.cmd("item replace entity @s inventory.11 with minecraft:diamond_chestplate");
        s.cmd("item replace entity @s inventory.20 with minecraft:golden_boots");
        s.cmd("item replace entity @s inventory.7 with minecraft:shield");
        s.cmd("item replace entity @s hotbar.6 with minecraft:diamond_sword");
        s.cmd("item replace entity @s inventory.2 with minecraft:bow");
        s.cmd("item replace entity @s inventory.15 with minecraft:cooked_beef 16");
        s.cmd("item replace entity @s hotbar.0 with minecraft:dirt 8");
        s.waitTicks(5);
        String[] result = {""};
        s.run("equip loadout", () -> {
            SmartInventory si = H.module(SmartInventory.ID);
            result[0] = si.equipLoadout("playtest kit");
        });
        s.waitUpTo("loadout equipped", 20 * 10, () -> !((SmartInventory) H.module(SmartInventory.ID)).isBusy());
        s.waitTicks(10);
        s.run("loadout result", () -> {
            var p = H.p();
            var inv = p.getInventory();
            boolean ok = p.getItemBySlot(EquipmentSlot.HEAD).is(net.minecraft.world.item.Items.IRON_HELMET)
                    && p.getItemBySlot(EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE)
                    && p.getItemBySlot(EquipmentSlot.FEET).is(net.minecraft.world.item.Items.GOLDEN_BOOTS)
                    && p.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)
                    && inv.getItem(0).is(net.minecraft.world.item.Items.DIAMOND_SWORD)
                    && inv.getItem(1).is(net.minecraft.world.item.Items.BOW)
                    && inv.getItem(2).is(net.minecraft.world.item.Items.COOKED_BEEF) && inv.getItem(2).getCount() == 16;
            s.check("loadout: equip restores armour, offhand and hotbar from a scrambled inventory", ok,
                    "'" + result[0] + "' -> head " + p.getItemBySlot(EquipmentSlot.HEAD) + ", chest "
                            + p.getItemBySlot(EquipmentSlot.CHEST) + ", feet " + p.getItemBySlot(EquipmentSlot.FEET)
                            + ", offhand " + p.getOffhandItem() + ", hotbar " + inv.getItem(0) + " / "
                            + inv.getItem(1) + " / " + inv.getItem(2) + "; last result '"
                            + ((SmartInventory) H.module(SmartInventory.ID)).lastResult() + "'");
        });
        s.run("loadout cleanup", () -> {
            SmartInventory si = H.module(SmartInventory.ID);
            si.deleteLoadout("playtest kit");
            H.setting(SmartInventory.ID, SmartInventory.K_REFILL, true);
            H.enable(SmartInventory.ID, false);
        });
        s.cmd("clear @s");
    }

    // ---- 2: crafter templates -------------------------------------------------------------------------------------

    /** Bucket: iron in slots 0, 2, 4; every other slot locked. */
    private static final int CRAFTER_MASK = (1 << 1) | (1 << 3) | (1 << 5) | (1 << 6) | (1 << 7) | (1 << 8);

    private static void crafter(Scenario s) {
        int cx = 4;
        int cz = -11;
        s.cmd(() -> "setblock " + at(cx, cz) + " minecraft:crafter{disabled_slots:[I;1,3,5,6,7,8]}");
        s.cmd(() -> "item replace block " + at(cx, cz) + " container.0 with minecraft:iron_ingot");
        s.cmd(() -> "item replace block " + at(cx, cz) + " container.2 with minecraft:iron_ingot");
        s.cmd(() -> "item replace block " + at(cx, cz) + " container.4 with minecraft:iron_ingot");
        standBy(s, cx, cz);
        openAndClose(s, "crafter", cx, cz);
        s.run("crafter remembered", () -> {
            var st = memory().stations(dim(), BlockPos.asLong(ax(cx), ay(), az(cz)), 4,
                    java.util.Set.of("minecraft:crafter"));
            s.check("crafter: remembered as a station after opening it", !st.isEmpty(), "stations " + st);
        });
        long[] save = {-1};
        runJob(s, "crafter save", "crafter save playtest_bucket", 20 * 60, save, null);
        s.run("crafter save result", () -> {
            CrafterTemplate t = memory().crafterTemplate("playtest_bucket");
            boolean ok = completed(save[0]) && t != null && t.disabledMask() == CRAFTER_MASK
                    && "minecraft:iron_ingot".equals(t.pattern().get(0))
                    && "minecraft:iron_ingot".equals(t.pattern().get(2))
                    && "minecraft:iron_ingot".equals(t.pattern().get(4)) && t.pattern().get(8) == null;
            s.check("crafter: 'crafter save' stores slot locks and items", ok, "job " + state(save[0])
                    + "; template " + t);
        });
        // wipe the crafter: no locks, a wrong item in a slot that must end up locked
        s.cmd(() -> "data merge block " + at(cx, cz)
                + " {disabled_slots:[I;],Items:[{Slot:1b,id:\"minecraft:cobblestone\",count:1}]}");
        s.cmd("clear @s");
        s.cmd("give @s minecraft:iron_ingot 5");
        s.waitTicks(5);
        s.run("crafter wiped", () -> s.note("crafter before apply: " + describe(contents(cx, cz)) + " locks "
                + crafterMask(cx, cz)));
        long[] apply = {-1};
        runJob(s, "crafter apply", "crafter apply playtest_bucket", 20 * 60, apply, null);
        s.waitTicks(10);
        s.run("crafter apply result", () -> {
            List<ItemStack> c = contents(cx, cz);
            int mask = crafterMask(cx, cz);
            boolean items = c.size() == 9 && c.get(0).is(net.minecraft.world.item.Items.IRON_INGOT)
                    && c.get(2).is(net.minecraft.world.item.Items.IRON_INGOT)
                    && c.get(4).is(net.minecraft.world.item.Items.IRON_INGOT)
                    && c.get(1).isEmpty() && c.get(3).isEmpty() && c.get(5).isEmpty() && c.get(6).isEmpty()
                    && c.get(7).isEmpty() && c.get(8).isEmpty();
            s.check("crafter: 'crafter apply' restores the locks and seeds the items", completed(apply[0])
                    && items && mask == CRAFTER_MASK, "job " + state(apply[0]) + "; crafter " + describe(c)
                    + ", locks " + Integer.toBinaryString(mask) + " (want " + Integer.toBinaryString(CRAFTER_MASK)
                    + "); cobblestone carried " + H.count("minecraft:cobblestone"));
        });
        s.run("crafter cleanup", () -> {
            memory().removeCrafterTemplate("playtest_bucket");
            // stations are not re-verified when their block goes: forget it so the next run has one crafter
            memory().forgetStation(dim(), BlockPos.asLong(ax(cx), ay(), az(cz)));
            Harness.command("setblock " + at(cx, cz) + " minecraft:air");
        });
        s.cmd("clear @s");
    }

    static int crafterMask(int dx, int dz) throws Exception {
        BlockPos pos = new BlockPos(ax(dx), ay(), az(dz));
        return onServer(() -> {
            BlockEntity be = H.mc().getSingleplayerServer().overworld().getBlockEntity(pos);
            int m = 0;
            if (be instanceof CrafterBlockEntity cb) {
                for (int i = 0; i < 9; i++) {
                    if (cb.isSlotDisabled(i)) {
                        m |= 1 << i;
                    }
                }
            }
            return m;
        });
    }

    // ---- 1: storage hall sort -------------------------------------------------------------------------------------

    private static final int[] HALL_X = {-7, -5, -3, -1};
    private static final int HALL_Z = -11;
    private static final String SHARP = "{levels:{\"minecraft:sharpness\":3}}";
    private static final String EFF = "{levels:{\"minecraft:efficiency\":2}}";
    private static final String UNB = "{levels:{\"minecraft:unbreaking\":3}}";

    private static String book(String ench) {
        return "minecraft:enchanted_book[minecraft:stored_enchantments=" + ench + "]";
    }

    private static void hall(Scenario s) {
        for (int x : HALL_X) {
            s.cmd(() -> "setblock " + at(x, HALL_Z) + " minecraft:chest[facing=south]");
        }
        // A (ores): two partial iron stacks + misplaced logs; B (wood): partial logs + misplaced diamonds;
        // C (books): sharpness + efficiency; D (books, mixed): unbreaking, two efficiency, cobblestone
        String[][] fill = {
                {"0", "minecraft:iron_ingot 20", "1", "minecraft:iron_ingot 30", "2", "minecraft:oak_log 10"},
                {"0", "minecraft:oak_log 40", "1", "minecraft:diamond 5"},
                {"0", book(SHARP), "1", book(EFF)},
                {"0", book(UNB), "1", book(EFF), "2", book(EFF), "3", "minecraft:cobblestone 16"}};
        for (int c = 0; c < fill.length; c++) {
            int x = HALL_X[c];
            for (int i = 0; i < fill[c].length; i += 2) {
                String slot = fill[c][i];
                String item = fill[c][i + 1];
                s.cmd(() -> "item replace block " + at(x, HALL_Z) + " container." + slot + " with " + item);
            }
        }
        for (int c = 0; c < HALL_X.length; c++) {
            standBy(s, HALL_X[c], HALL_Z);
            openAndClose(s, "hall chest " + (char) ('A' + c), HALL_X[c], HALL_Z);
        }
        String[] tags = {StorageCategories.ORES, StorageCategories.WOOD, StorageCategories.BOOKS,
                StorageCategories.BOOKS};
        s.run("hall: remembered + tagged", () -> {
            StringBuilder sb = new StringBuilder();
            boolean all = true;
            for (int c = 0; c < HALL_X.length; c++) {
                long pos = BlockPos.asLong(ax(HALL_X[c]), ay(), az(HALL_Z));
                boolean known = memory().contains(dim(), pos) && memory().slots(dim(), pos) != null;
                all &= known;
                memory().setTag(dim(), pos, tags[c]);
                sb.append((char) ('A' + c)).append(known ? " slots known" : " NOT remembered").append(", ");
            }
            s.check("hall: Storage Memory records every chest's slots on close", all, sb.toString());
            H.enable(HallSorterModule.ID, true);
        });
        s.cmd(() -> "tp @s " + (ax(-4) + 0.5) + " " + ay() + " " + (az(-7) + 0.5) + " 180 0");
        s.waitTicks(5);
        long[] job = {-1};
        runJob(s, "sort hall", "sort hall", 20 * 180, job, null);
        s.waitTicks(10);
        s.run("hall result", () -> {
            List<List<ItemStack>> c = new ArrayList<>();
            for (int x : HALL_X) {
                c.add(contents(x, HALL_Z));
            }
            String all = "A " + describe(c.get(0)) + " | B " + describe(c.get(1)) + " | C " + describe(c.get(2))
                    + " | D " + describe(c.get(3));
            s.check("hall: 'sort hall' job completes", completed(job[0]), "job " + state(job[0]) + "; " + all);
            boolean moved = total(c.get(0), "diamond") == 5 && total(c.get(0), "oak_log") == 0
                    && total(c.get(1), "oak_log") == 50 && total(c.get(1), "diamond") == 0;
            s.check("hall: misplaced items moved to their category chest (logs -> wood, diamonds -> ores)", moved,
                    all);
            boolean merged = stacks(c.get(0), "minecraft:iron_ingot") == 1 && total(c.get(0), "iron_ingot") == 50
                    && stacks(c.get(1), "minecraft:oak_log") == 1;
            s.check("hall: partial stacks merged (iron 20+30 in one slot, logs 40+10 in one slot)", merged, all);
            Map<String, Integer> cBooks = totals(c.get(2));
            Map<String, Integer> dBooks = totals(c.get(3));
            int sharpC = sum(cBooks, "sharpness");
            int effC = sum(cBooks, "efficiency");
            int effD = sum(dBooks, "efficiency");
            int unbD = sum(dBooks, "unbreaking");
            boolean books = sharpC == 1 && effC == 0 && effD == 3 && unbD == 1
                    && total(c.get(3), "cobblestone") == 16;
            s.check("hall: enchanted books grouped per enchantment (efficiency joins the 2 in D, sharpness stays in C)",
                    books, all);
            Map<String, Integer> sum = new TreeMap<>();
            for (List<ItemStack> x : c) {
                totals(x).forEach((k, v) -> sum.merge(k.replaceAll("\\[.*]", "[book]"), v, Integer::sum));
            }
            boolean kept = sum.getOrDefault("iron_ingot", 0) == 50 && sum.getOrDefault("oak_log", 0) == 50
                    && sum.getOrDefault("diamond", 0) == 5 && sum.getOrDefault("enchanted_book[book]", 0) == 5
                    && sum.getOrDefault("cobblestone", 0) == 16;
            s.check("hall: nothing lost or left in the player's inventory", kept && H.count("minecraft:iron_ingot")
                    + H.count("minecraft:oak_log") + H.count("minecraft:diamond")
                    + H.count("minecraft:enchanted_book") == 0, "hall totals " + sum + "; carried iron "
                    + H.count("minecraft:iron_ingot") + " logs " + H.count("minecraft:oak_log") + " diamonds "
                    + H.count("minecraft:diamond") + " books " + H.count("minecraft:enchanted_book"));
        });
        s.run("hall cleanup", () -> {
            H.enable(HallSorterModule.ID, false);
            for (int c = 0; c < HALL_X.length; c++) {
                memory().setTag(dim(), BlockPos.asLong(ax(HALL_X[c]), ay(), az(HALL_Z)), "");
            }
            Harness.command(Arena.fill(-8, 1, HALL_Z, 0, 1, HALL_Z, "minecraft:air"));
            Harness.command("clear @s");
        });
        s.waitTicks(20); // Storage Memory's budgeted verification forgets the removed chests
    }

    private static int sum(Map<String, Integer> m, String enchant) {
        int n = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getKey().startsWith("enchanted_book") && e.getKey().contains(enchant)) {
                n += e.getValue();
            }
        }
        return n;
    }

    // ---- 6a: furnace array ----------------------------------------------------------------------------------------

    private static void furnaces(Scenario s) {
        int[] fx = {-7, -5};
        int fz = 10;
        for (int x : fx) {
            s.cmd(() -> "setblock " + at(x, fz) + " minecraft:furnace[facing=north]");
        }
        // the furnaces sit on the south edge facing the centre: open them from the north side
        for (int i = 0; i < fx.length; i++) {
            int x = fx[i];
            s.cmd(() -> "tp @s " + (ax(x) + 0.5) + " " + ay() + " " + (az(fz) - 1.5) + " 0 20");
            s.waitTicks(5);
            openAndClose(s, "furnace " + (i + 1), x, fz);
        }
        s.cmd("clear @s");
        s.cmd("give @s minecraft:raw_iron 8");
        s.cmd("give @s minecraft:coal 2");
        s.cmd(() -> "tp @s " + (ax(-6) + 0.5) + " " + ay() + " " + (az(6) + 0.5) + " 0 0");
        s.waitTicks(5);
        long[] job = {-1};
        int[] before = new int[1];
        s.run("ingots before", () -> before[0] = H.count("minecraft:iron_ingot"));
        runJob(s, "furnace array", "furnaces 8 raw_iron", 20 * 180, job, null);
        s.run("furnace result", () -> {
            int got = H.count("minecraft:iron_ingot") - before[0];
            String fur = "furnace 1 " + describe(contents(fx[0], fz)) + ", furnace 2 " + describe(contents(fx[1], fz));
            s.check("furnaces: 'furnaces 8 raw_iron' smelts across both remembered furnaces and collects 8 ingots",
                    completed(job[0]) && got >= 8, "ingots +" + got + ", raw iron left "
                            + H.count("minecraft:raw_iron") + ", coal left " + H.count("minecraft:coal") + "; job "
                            + state(job[0]) + "; " + fur);
        });
        s.run("furnace cleanup", () -> {
            for (int x : fx) {
                Harness.command("setblock " + at(x, fz) + " minecraft:air");
                memory().forgetStation(dim(), BlockPos.asLong(ax(x), ay(), az(fz)));
            }
        });
        s.cmd("clear @s");
    }

    // ---- 3 + 6b: brewing (fills its own water bottles first) ------------------------------------------------------

    private static void brewing(Scenario s) {
        int bx = 1;
        int bz = 10;
        String water = com.utilitysuite.jobs.planner.BrewingPlanner.WATER;
        String weakness = com.utilitysuite.jobs.planner.BrewingPlanner.key(
                com.utilitysuite.jobs.planner.BrewingPlanner.POTION, "minecraft:weakness");
        s.cmd(() -> "setblock " + at(bx, bz) + " minecraft:brewing_stand");
        s.cmd(() -> "tp @s " + (ax(bx) + 0.5) + " " + ay() + " " + (az(bz) - 1.5) + " 0 20");
        s.waitTicks(5);
        openAndClose(s, "brewing stand", bx, bz);
        s.cmd("clear @s");
        s.cmd("give @s minecraft:glass_bottle 3");
        s.cmd("give @s minecraft:fermented_spider_eye 1");
        s.cmd("give @s minecraft:blaze_powder 1");
        s.cmd(() -> "tp @s " + (ax(2) + 0.5) + " " + ay() + " " + (az(3) + 0.5) + " 0 0");
        s.waitTicks(5);
        int[] maxWater = new int[1];
        boolean[] sawFill = new boolean[1];
        long[] job = {-1};
        runJob(s, "brew", "brew 3 weakness", 20 * 180, job, t -> {
            maxWater[0] = Math.max(maxWater[0], potions(water));
            GoalScenario.job(job[0]).ifPresent(j -> {
                if (j.currentStep() != null && j.currentStep().toLowerCase().contains("water")) {
                    sawFill[0] = true;
                }
            });
            return false;
        });
        s.run("brew result", () -> {
            int weak = potions(weakness);
            s.check("bottles: FILL_BOTTLES fills 3 glass bottles at the arena pool", maxWater[0] >= 3,
                    "most water bottles carried " + maxWater[0] + ", fill step seen " + sawFill[0]
                            + ", glass bottles left " + H.count("minecraft:glass_bottle") + "; job " + state(job[0]));
            s.check("brewing: 'brew 3 weakness' brews 3 potions of weakness and takes them out", completed(job[0])
                    && weak >= 3, "weakness " + weak + ", water " + potions(water) + ", eye left "
                    + H.count("minecraft:fermented_spider_eye") + "; stand " + describe(contents(bx, bz)) + "; job "
                    + state(job[0]));
        });
        s.run("brew cleanup", () -> {
            Harness.command("setblock " + at(bx, bz) + " minecraft:air");
            memory().forgetStation(dim(), BlockPos.asLong(ax(bx), ay(), az(bz)));
        });
        s.cmd("clear @s");
    }

    // ---- 4: carried ender chest -----------------------------------------------------------------------------------

    private static void enderChest(Scenario s) {
        int ex = 0;
        int ez = -2;
        s.cmd("clear @s");
        s.cmd("item replace entity @s enderchest.0 with minecraft:ender_pearl 16");
        s.cmd("item replace entity @s enderchest.1 with minecraft:string 5");
        s.run("ender source on", () -> H.setting(StorageMemoryModule.ID, "ender_chest_source", true));
        // record the mirror once through a placed ender chest, then remove that chest
        s.cmd(() -> "setblock " + at(ex, ez) + " minecraft:ender_chest[facing=south]");
        standBy(s, ex, ez);
        openAndClose(s, "ender chest", ex, ez);
        s.cmd(() -> "setblock " + at(ex, ez) + " minecraft:air");
        s.waitTicks(40); // the removed chest's record is dropped by the budgeted verification
        s.run("ender mirror", () -> {
            s.note("window active " + H.mc().isWindowActive() + ", mouse grabbed "
                    + H.mc().mouseHandler.isMouseGrabbed() + " (a held attack only mines with the mouse grabbed"
                    + " unless MinecraftAttackHoldMixin applies)");
            var snap = memory().enderMirror().snapshot();
            boolean placed = false;
            for (var e : memory().entries()) {
                placed |= StorageMemory.ENDER_CHEST.equals(e.containerType());
            }
            s.check("ender: mirror recorded from the opened chest, no placed ender chest remembered",
                    snap.known() && snap.count("minecraft:ender_pearl") == 16 && !placed,
                    "mirror " + snap.contents() + ", placed ender chest remembered " + placed);
        });
        s.cmd("give @s minecraft:ender_chest 1");
        s.cmd("give @s minecraft:diamond_pickaxe[minecraft:enchantments={levels:{\"minecraft:silk_touch\":1}}]");
        s.cmd(() -> "tp @s " + (ax(0) + 0.5) + " " + ay() + " " + (az(2) + 0.5) + " 0 0");
        s.waitTicks(5);
        long[] job = {-1};
        runJob(s, "ender fetch", "gather 6 ender_pearl", 20 * 120, job, null);
        s.waitTicks(20);
        s.run("ender result", () -> {
            int pearls = H.count("minecraft:ender_pearl");
            int chests = H.count("minecraft:ender_chest");
            String placedAt = "";
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            BlockPos c = H.p().blockPosition();
            for (int dx = -8; dx <= 8; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dz = -8; dz <= 8; dz++) {
                        m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                        if (H.mc().level.getBlockState(m).is(net.minecraft.world.level.block.Blocks.ENDER_CHEST)) {
                            placedAt += m.toShortString() + " ";
                        }
                    }
                }
            }
            int[] left = new int[1];
            onServer(() -> {
                var sp = H.mc().getSingleplayerServer().getPlayerList().getPlayer(H.p().getUUID());
                left[0] = sp == null ? -1 : sp.getEnderChestInventory().countItem(net.minecraft.world.item.Items.ENDER_PEARL);
                return null;
            });
            s.check("ender: 'gather 6 ender_pearl' takes them from the carried ender chest", completed(job[0])
                    && pearls >= 6, "pearls carried " + pearls + ", left in ender chest " + left[0] + "; job "
                    + state(job[0]));
            s.check("ender: the placed ender chest is mined back with Silk Touch (carried again, none left placed)",
                    chests == 1 && placedAt.isEmpty(), "ender chests carried " + chests + ", placed "
                            + (placedAt.isEmpty() ? "none" : placedAt) + ", silk pick carried "
                            + H.count("minecraft:diamond_pickaxe"));
        });
        s.run("ender cleanup", () -> H.setting(StorageMemoryModule.ID, "ender_chest_source", false));
        s.cmd("clear @s");
        s.cmd("item replace entity @s enderchest.0 with minecraft:air");
        s.cmd("item replace entity @s enderchest.1 with minecraft:air");
    }
}
