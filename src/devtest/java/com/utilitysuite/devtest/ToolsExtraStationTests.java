package com.utilitysuite.devtest;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.tools.enchant.EnchantPlanner;
import com.utilitysuite.tools.enchant.EnchantSeedHelper;
import com.utilitysuite.tools.enchant.EnchantTableCalc;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;
import com.utilitysuite.tools.villager.BulkTradeRun;
import com.utilitysuite.tools.villager.LibrarianRoller;
import com.utilitysuite.tools.villager.VillagerHelpers;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Enchant Planner and Villager Helpers for {@link ToolsExtraScenario}: a real enchanting table and trade screens. */
final class ToolsExtraStationTests {

    private ToolsExtraStationTests() {
    }

    static void add(Scenario s) {
        enchant(s);
        bulkTrade(s);
        librarianRoller(s);
    }

    private static Villager nearestVillager() {
        Villager best = null;
        double bestD = 8 * 8;
        for (Entity e : H.mc().level.entitiesForRendering()) {
            if (e instanceof Villager v && v.distanceToSqr(H.p()) < bestD) {
                best = v;
                bestD = v.distanceToSqr(H.p());
            }
        }
        return best;
    }

    private static void closeContainer() {
        if (H.p() != null && H.mc().screen != null) {
            H.p().closeContainer();
        }
        if (H.mc().screen != null) {
            H.mc().setScreen(null);
        }
    }

    // ---- Enchant Planner --------------------------------------------------------------------------------------------

    private static void enchant(Scenario s) {
        int tx = 0;
        int tz = 2;
        EnchantSeedHelper.Snapshot[] snap = new EnchantSeedHelper.Snapshot[1];
        int[] serverSeed = new int[1];
        s.cmd("clear @s");
        s.cmd(() -> "tp @s " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 0.5) + " 0 30");
        s.cmd(() -> "setblock " + (Arena.cx + tx) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + tz)
                + " minecraft:enchanting_table");
        s.cmd("item replace entity @s hotbar.0 with minecraft:iron_pickaxe");
        s.cmd("item replace entity @s hotbar.1 with minecraft:book");
        s.run("EP enable + refresh", () -> {
            H.p().getInventory().selected = 0;
            H.enable(EnchantSeedHelper.ID, true);
            EnchantSeedHelper es = H.module(EnchantSeedHelper.ID);
            es.refresh();
            snap[0] = es.snapshot();
            var sp = H.mc().getSingleplayerServer().getPlayerList().getPlayer(H.p().getUUID());
            serverSeed[0] = sp == null ? Integer.MIN_VALUE : sp.getEnchantmentSeed();
            int n0 = 0;
            for (EnchantPlanner.Offer o : snap[0].offers()) {
                if (o.bookshelves() == 0 && o.cost() > 0) {
                    n0++;
                }
            }
            s.check("Enchant Planner reads the player's enchantment seed and rolls every bookshelf count",
                    "Seed read".equals(snap[0].status()) && snap[0].seed() == serverSeed[0]
                            && snap[0].offers().size() == 16 * 3 && n0 >= 1
                            && snap[0].choices().contains("minecraft:efficiency"),
                    "status '" + snap[0].status() + "', seed " + snap[0].seed() + " (server " + serverSeed[0]
                            + "), offers " + snap[0].offers().size() + ", enabled 0-shelf slots " + n0 + ", choices "
                            + snap[0].choices());
            es.setTargetId("minecraft:efficiency");
            EnchantPlanner.Plan plan = es.plan();
            s.check("the planner gives advice for a wanted enchantment", plan.kind() != EnchantPlanner.Kind.NOTHING
                    && plan.offer() != null, "plan " + plan.kind() + " " + plan.offer());
            es.setTargetId("");
            BlockPos pos = new BlockPos(Arena.cx + tx, Arena.FLOOR + 1, Arena.cz + tz);
            H.mc().gameMode.useItemOn(H.p(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.25, 0), Direction.UP, pos, false));
        });
        s.waitUpTo("EP table opens", 40, () -> H.p().containerMenu instanceof EnchantmentMenu);
        s.run("EP put the pickaxe in", () -> {
            var menu = H.p().containerMenu;
            // hotbar slot 1 is menu slot 29 (0 item, 1 lapis, 2..28 main inventory, 29..37 hotbar)
            H.mc().gameMode.handleInventoryMouseClick(menu.containerId, 29, 0, ClickType.QUICK_MOVE, H.p());
        });
        s.waitUpTo("EP table shows costs", 40, () -> H.p().containerMenu instanceof EnchantmentMenu m
                && m.costs[0] > 0);
        s.waitTicks(5);
        s.run("EP table vs prediction", () -> {
            if (!(H.p().containerMenu instanceof EnchantmentMenu menu)) {
                s.check("enchanting table open", false, "menu " + H.p().containerMenu);
                return;
            }
            var idMap = H.mc().level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
            StringBuilder ev = new StringBuilder();
            boolean ok = (menu.getEnchantmentSeed() & 0xFFFF) == (snap[0].seed() & 0xFFFF);
            ev.append("menu seed low16 ").append(menu.getEnchantmentSeed() & 0xFFFF).append(" vs ")
                    .append(snap[0].seed() & 0xFFFF);
            for (int slot = 0; slot < 3; slot++) {
                EnchantPlanner.Offer o = null;
                for (EnchantPlanner.Offer x : snap[0].offers()) {
                    if (x.bookshelves() == 0 && x.slot() == slot) {
                        o = x;
                    }
                }
                int cost = menu.costs[slot];
                int clue = menu.enchantClue[slot];
                String clueId = "-";
                if (clue >= 0) {
                    var h = idMap.byId(clue);
                    clueId = h == null ? "?" : h.unwrapKey().map(k -> k.location().toString()).orElse("?");
                }
                boolean costOk = o != null && o.cost() == cost;
                boolean clueOk = cost <= 0 || clue < 0 || (o != null && o.contains(clueId, menu.levelClue[slot]));
                ok &= costOk && clueOk;
                ev.append("; slot ").append(slot + 1).append(" table cost ").append(cost).append(" clue ")
                        .append(clueId).append(' ').append(menu.levelClue[slot]).append(" / predicted ")
                        .append(o == null ? "none" : o.cost() + " " + o.enchants());
            }
            boolean exact = EnchantTableCalc.matchesClues(H.mc().level.registryAccess(), menu.getSlot(0).getItem(),
                    serverSeed[0], 0, menu.costs.clone(), menu.enchantClue.clone(), menu.levelClue.clone());
            s.check("the predicted 0-bookshelf offers match what the real enchanting table shows",
                    ok && exact, ev + "; exact clue replay " + exact);
            H.<EnchantSeedHelper>module(EnchantSeedHelper.ID).crackFromOpenMenu();
        });
        s.waitUpTo("EP cracker finishes", 200, () -> !"searching".equals(
                H.<EnchantSeedHelper>module(EnchantSeedHelper.ID).crackStatus()));
        s.run("EP cracker check", () -> {
            EnchantSeedHelper es = H.module(EnchantSeedHelper.ID);
            int[] cands = (int[]) ToolsExtraScenario.field(es, "candidates");
            boolean contains = false;
            if (cands != null) {
                for (int c : cands) {
                    contains |= c == serverSeed[0];
                }
            }
            s.check("the remote seed cracker keeps the true seed among its candidates from one observation",
                    contains, "status '" + es.crackStatus() + "', candidates "
                            + (cands == null ? "none" : cands.length) + ", true seed kept " + contains);
            // swap the pickaxe for a book (books drop one rolled enchantment: a separate path in the roll)
            var menu = H.p().containerMenu;
            H.mc().gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, H.p());
            H.mc().gameMode.handleInventoryMouseClick(menu.containerId, 30, 0, ClickType.QUICK_MOVE, H.p());
        });
        s.waitUpTo("EP table shows book costs", 40, () -> H.p().containerMenu instanceof EnchantmentMenu m
                && m.getSlot(0).getItem().is(Items.BOOK) && m.costs[0] > 0);
        s.waitTicks(5);
        s.run("EP book check", () -> {
            if (!(H.p().containerMenu instanceof EnchantmentMenu menu)) {
                s.check("enchanting table open (book)", false, "menu " + H.p().containerMenu);
                return;
            }
            var access = H.mc().level.registryAccess();
            ItemStack book = new ItemStack(Items.BOOK);
            List<EnchantPlanner.Offer> offers = EnchantTableCalc.offers(access, book, serverSeed[0], 0);
            boolean costs = offers.size() == 3;
            for (int slot = 0; slot < 3 && costs; slot++) {
                costs = offers.get(slot).cost() == menu.costs[slot];
            }
            boolean exact = menu.getSlot(0).getItem().is(Items.BOOK) && EnchantTableCalc.matchesClues(access, book,
                    serverSeed[0], 0, menu.costs.clone(), menu.enchantClue.clone(), menu.levelClue.clone());
            s.check("for a book the predicted costs and shown clues match the real table",
                    costs && exact, "table costs " + java.util.Arrays.toString(menu.costs) + ", clues "
                            + java.util.Arrays.toString(menu.enchantClue) + " lvl "
                            + java.util.Arrays.toString(menu.levelClue) + "; predicted " + offers + "; exact " + exact);
        });

        // enchant the book once (the seed changes), then the helper must read and roll the new seed
        int[] oldSeed = new int[1];
        s.cmd("xp add @s 30 levels");
        s.cmd("item replace entity @s hotbar.2 with minecraft:lapis_lazuli 8");
        s.run("EP lapis in", () -> {
            var menu = H.p().containerMenu;
            H.mc().gameMode.handleInventoryMouseClick(menu.containerId, 31, 0, ClickType.QUICK_MOVE, H.p());
        });
        s.waitTicks(5);
        s.run("EP enchant the book (slot 1)", () -> {
            oldSeed[0] = serverSeed[0];
            H.mc().gameMode.handleInventoryButtonClick(H.p().containerMenu.containerId, 0);
        });
        s.waitUpTo("EP book enchanted", 40, () -> H.p().containerMenu instanceof EnchantmentMenu m
                && m.getSlot(0).getItem().is(Items.ENCHANTED_BOOK));
        s.run("EP close the table", ToolsExtraStationTests::closeContainer);
        s.cmd("clear @s minecraft:enchanted_book");
        s.cmd("clear @s minecraft:iron_pickaxe");
        s.cmd("item replace entity @s hotbar.0 with minecraft:iron_pickaxe");
        s.waitTicks(3);
        s.run("EP refresh with the new seed", () -> {
            H.p().getInventory().selected = 0;
            EnchantSeedHelper es = H.module(EnchantSeedHelper.ID);
            es.refresh();
            snap[0] = es.snapshot();
            var sp = H.mc().getSingleplayerServer().getPlayerList().getPlayer(H.p().getUUID());
            serverSeed[0] = sp == null ? Integer.MIN_VALUE : sp.getEnchantmentSeed();
            s.check("after an enchant the planner reads the new seed", serverSeed[0] != oldSeed[0]
                            && snap[0].seed() == serverSeed[0] && snap[0].offers().size() == 48,
                    "seed " + oldSeed[0] + " -> server " + serverSeed[0] + ", planner " + snap[0].seed() + " ("
                            + snap[0].status() + ")");
            BlockPos pos = new BlockPos(Arena.cx + tx, Arena.FLOOR + 1, Arena.cz + tz);
            H.mc().gameMode.useItemOn(H.p(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.25, 0), Direction.UP, pos, false));
        });
        s.waitUpTo("EP table opens again", 40, () -> H.p().containerMenu instanceof EnchantmentMenu);
        s.run("EP put the pickaxe in again", () -> {
            var menu = H.p().containerMenu;
            if (menu instanceof EnchantmentMenu) {
                H.mc().gameMode.handleInventoryMouseClick(menu.containerId, 29, 0, ClickType.QUICK_MOVE, H.p());
            }
        });
        s.waitUpTo("EP table shows costs again", 40, () -> H.p().containerMenu instanceof EnchantmentMenu m
                && m.getSlot(0).getItem().is(Items.IRON_PICKAXE) && m.costs[0] > 0);
        s.waitTicks(5);
        s.run("EP new seed vs table", () -> {
            if (!(H.p().containerMenu instanceof EnchantmentMenu menu)) {
                s.check("enchanting table open (new seed)", false, "menu " + H.p().containerMenu);
                return;
            }
            boolean costs = true;
            StringBuilder ev = new StringBuilder();
            for (EnchantPlanner.Offer o : snap[0].offers()) {
                if (o.bookshelves() == 0) {
                    costs &= o.cost() == menu.costs[o.slot()];
                    ev.append(o.cost()).append(' ');
                }
            }
            boolean exact = EnchantTableCalc.matchesClues(H.mc().level.registryAccess(), menu.getSlot(0).getItem(),
                    serverSeed[0], 0, menu.costs.clone(), menu.enchantClue.clone(), menu.levelClue.clone());
            s.check("with the new seed the predicted offers match the real table again", costs && exact,
                    "table costs " + java.util.Arrays.toString(menu.costs) + ", predicted " + ev.toString().trim()
                            + ", exact clue replay " + exact);
        });
        s.always("EP cleanup", () -> {
            closeContainer();
            EnchantSeedHelper es = H.module(EnchantSeedHelper.ID);
            es.setTargetId("");
            es.onWorldUnload(); // forget the cracker observations
            H.enable(EnchantSeedHelper.ID, false);
            Harness.command("setblock " + (Arena.cx + tx) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + tz)
                    + " minecraft:air");
            Harness.command("clear @s");
            Harness.command("xp set @s 0 levels");
            Harness.command("xp set @s 0 points");
        });
    }

    // ---- Villager Helpers: bulk trade -------------------------------------------------------------------------------

    private static void bulkTrade(Scenario s) {
        int[] steps = new int[1];
        s.cmd("clear @s");
        s.cmd("kill @e[type=minecraft:villager]");
        s.cmd(() -> "tp @s " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 0.5) + " 0 20");
        s.cmd(() -> "summon minecraft:villager " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 2.5)
                + " {NoAI:1b,PersistenceRequired:1b,Silent:1b,Rotation:[180f,0f],"
                + "VillagerData:{profession:\"minecraft:farmer\",level:1,type:\"minecraft:plains\"},"
                + "Offers:{Recipes:[{buy:{id:\"minecraft:dirt\",count:1},sell:{id:\"minecraft:emerald\",count:1},"
                + "maxUses:5,xp:0,rewardExp:0b,priceMultiplier:0f}]}}");
        s.cmd("item replace entity @s inventory.0 with minecraft:dirt 8");
        s.run("VH enable", () -> {
            H.p().getInventory().selected = 0;
            H.enable(VillagerHelpers.ID, true);
        });
        s.waitUpTo("VH villager loaded", 40, () -> nearestVillager() != null);
        s.run("VH open trades", () -> {
            Villager v = nearestVillager();
            if (v != null) {
                H.mc().gameMode.interact(H.p(), v, InteractionHand.MAIN_HAND);
            }
        });
        s.waitFor("VH trade screen opens", 60, () -> H.mc().screen instanceof MerchantScreen ms
                && !ms.getMenu().getOffers().isEmpty());
        s.run("VH pick the trade", () -> {
            // what the trade-list button does
            MerchantMenu menu = ((MerchantScreen) H.mc().screen).getMenu();
            menu.setSelectionHint(0);
            menu.tryMoveItems(0);
            H.mc().getConnection().send(new ServerboundSelectTradePacket(0));
        });
        s.waitTicks(5);
        s.run("VH press the bulk-trade key", () -> {
            H.physicalKey(GLFW.GLFW_KEY_G, true);
            H.physicalKey(GLFW.GLFW_KEY_G, false);
        });
        s.waitUpTo("VH bulk run ends", 200, () -> {
            VillagerHelpers vh = H.module(VillagerHelpers.ID);
            return !vh.bulk().running() && vh.bulk().stopReason() != BulkTradeRun.Stop.NONE;
        });
        s.waitTicks(5);
        int[] uses = new int[1];
        boolean[] open = new boolean[1];
        String[] arb = new String[1];
        s.run("VH bulk result, close the screen", () -> {
            VillagerHelpers vh = H.module(VillagerHelpers.ID);
            steps[0] = vh.bulk().trades();
            open[0] = H.mc().screen instanceof MerchantScreen;
            uses[0] = open[0] ? ((MerchantScreen) H.mc().screen).getMenu().getOffers().get(0).getUses() : -1;
            arb[0] = H.arbiterState();
            closeContainer(); // unused payment goes back to the inventory
        });
        s.waitTicks(5);
        s.run("VH bulk check", () -> {
            VillagerHelpers vh = H.module(VillagerHelpers.ID);
            int emeralds = H.count("minecraft:emerald");
            int dirt = H.count("minecraft:dirt");
            s.check("bulk trade repeats the picked offer until it locks (5 uses: 5 emeralds for 5 dirt)",
                    emeralds == 5 && dirt == 3 && vh.bulk().stopReason() == BulkTradeRun.Stop.LOCKED && uses[0] == 5
                            && open[0] && !arb[0].contains("HOTBAR=" + VillagerHelpers.ID),
                    "emeralds " + emeralds + ", dirt " + dirt + " after closing (8 given), offer uses " + uses[0]
                            + ", stop " + vh.bulk().stopReason() + " after " + steps[0] + " steps, screen was open "
                            + open[0] + "; " + arb[0]);
        });
        s.always("VH bulk cleanup", () -> {
            closeContainer();
            Harness.command("kill @e[type=minecraft:villager]");
            Harness.command("clear @s");
        });
    }

    // ---- Villager Helpers: librarian roller -------------------------------------------------------------------------

    private static final int ROLLER_TEST_KEY = GLFW.GLFW_KEY_KP_8;

    private static void librarianRoller(Scenario s) {
        boolean[] sawLease = new boolean[1];
        float[] startRot = new float[2];
        float[] maxStep = new float[1];
        float[] lastRot = new float[2];
        String[] pressDiag = {""};
        int lx = -1;
        int lz = 2;
        s.cmd("clear @s");
        s.cmd("kill @e[type=minecraft:villager]");
        s.cmd(() -> "setblock " + (Arena.cx + lx) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + lz)
                + " minecraft:lectern[facing=north]");
        s.cmd(() -> "summon minecraft:villager " + (Arena.cx + 1.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 2.5)
                + " {NoAI:1b,PersistenceRequired:1b,Silent:1b,Rotation:[180f,0f],Xp:0,"
                + "VillagerData:{profession:\"minecraft:librarian\",level:1,type:\"minecraft:plains\"},"
                + "Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:12},buyB:{id:\"minecraft:book\",count:1},"
                + "sell:{id:\"minecraft:enchanted_book\",count:1,components:{\"minecraft:stored_enchantments\":"
                + "{levels:{\"minecraft:mending\":1}}}},maxUses:12,xp:1,rewardExp:1b,priceMultiplier:0.2f}]}}");
        // look at the lectern's middle from the eyes: dx -1, dz +2, dy 1.5 - 2.62
        s.cmd(() -> {
            double dx = lx;
            double dz = lz;
            double dy = 1.5 - 2.62;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            return "tp @s " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 0.5) + " " + yaw + " "
                    + pitch;
        });
        s.run("LIB bind the roller key", () -> {
            VillagerHelpers vh = H.module(VillagerHelpers.ID);
            ToolsExtraScenario.bind(vh.rollerKey(), ROLLER_TEST_KEY);
            H.enable(VillagerHelpers.ID, true);
        });
        s.waitUpTo("LIB looking at the lectern + villager loaded", 40, () -> nearestVillager() != null
                && H.mc().hitResult instanceof BlockHitResult b
                && H.mc().level.getBlockState(b.getBlockPos()).is(net.minecraft.world.level.block.Blocks.LECTERN));
        s.run("LIB press the roller key", () -> {
            startRot[0] = H.p().getYRot();
            startRot[1] = H.p().getXRot();
            lastRot[0] = startRot[0];
            lastRot[1] = startRot[1];
            maxStep[0] = 0;
            sawLease[0] = false;
            var hr = H.mc().hitResult;
            pressDiag[0] = "hit " + (hr instanceof BlockHitResult b ? "block " + b.getBlockPos().toShortString() + " "
                    + H.mc().level.getBlockState(b.getBlockPos()).getBlock() : hr == null ? "-" : hr.getType())
                    + Harness.fmt(", rot %.1f/%.1f", startRot[0], startRot[1]);
            H.physicalKey(ROLLER_TEST_KEY, true);
            H.physicalKey(ROLLER_TEST_KEY, false);
        });
        s.waitTicks(2);
        s.run("LIB after press", () -> pressDiag[0] += ", 2 ticks after the press: phase "
                + H.<VillagerHelpers>module(VillagerHelpers.ID).roller().phase() + ", notices "
                + ToolsExtraScenario.notices());
        s.waitUpTo("LIB roller finds the book", 200, () -> {
            VillagerHelpers vh = H.module(VillagerHelpers.ID);
            float y = H.p().getYRot();
            float p = H.p().getXRot();
            maxStep[0] = Math.max(maxStep[0], Math.max(Math.abs(net.minecraft.util.Mth.wrapDegrees(y - lastRot[0])),
                    Math.abs(p - lastRot[1])));
            lastRot[0] = y;
            lastRot[1] = p;
            sawLease[0] |= Services.arbiter().isHeldBy(ControlChannel.CAMERA, VillagerHelpers.ID);
            return vh.roller().phase() == LibrarianRoller.Phase.IDLE && vh.roller().stopReason()
                    != LibrarianRoller.Stop.NONE;
        });
        s.waitTicks(3);
        s.run("LIB roller check", () -> {
            VillagerHelpers vh = H.module(VillagerHelpers.ID);
            boolean open = H.mc().screen instanceof MerchantScreen;
            String lectern = H.blockId(Arena.cx + lx, Arena.FLOOR + 1, Arena.cz + lz);
            s.check("the librarian roller turns to the villager (smoothly), opens its trades and stops on the wanted"
                            + " book without breaking the lectern",
                    vh.roller().stopReason() == LibrarianRoller.Stop.SUCCESS && vh.roller().cycles() == 0 && open
                            && sawLease[0] && maxStep[0] < 60f && lectern.equals("minecraft:lectern")
                            && !Services.arbiter().isHeldBy(ControlChannel.CAMERA, VillagerHelpers.ID),
                    "stop " + vh.roller().stopReason() + " after " + vh.roller().cycles() + " rerolls, trade screen "
                            + open + ", camera lease seen " + sawLease[0] + Harness.fmt(", largest turn per tick %.1f",
                            maxStep[0]) + " deg, lectern '" + lectern + "', notices " + ToolsExtraScenario.notices()
                            + "; " + H.arbiterState() + "; at the press: " + pressDiag[0]);
            closeContainer();
        });
        s.run("LIB reroll cycle note", () -> s.notTested("librarian reroll cycle (break lectern, profession lost"
                + " and regained)", "needs villager AI to drop and retake the profession (minutes of game time);"
                + " LibrarianRoller's cycle is unit tested"));
        s.always("LIB cleanup", () -> {
            VillagerHelpers vh = H.module(VillagerHelpers.ID);
            closeContainer();
            H.enable(VillagerHelpers.ID, false);
            ToolsExtraScenario.bind(vh.rollerKey(), -1);
            Harness.command("kill @e[type=minecraft:villager]");
            Harness.command("setblock " + (Arena.cx + lx) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + lz)
                    + " minecraft:air");
            Harness.command("kill @e[type=minecraft:item]");
            Harness.command("clear @s");
        });
    }
}
