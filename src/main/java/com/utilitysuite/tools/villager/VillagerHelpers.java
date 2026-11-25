package com.utilitysuite.tools.villager;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.api.visual.NoticeKind;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Villager Helpers (opt-in, default off): bulk trading and the librarian enchantment roller.
 *
 * <p><b>Bulk trade.</b> With a trade screen open and a trade picked, the bulk-trade key repeats that offer: each step
 * refills the payment slots through the merchant menu's select-trade path (the same calls the trade list button
 * makes, plus {@link ServerboundSelectTradePacket}) and shift-takes the result through {@link IContainerClicker} under
 * a HOTBAR lease. Vanilla's shift-take repeats the trade while the refilled payment lasts, so one step can complete
 * several trades. Stops when the inputs run out, the offer locks, the inventory has no room, the screen closes, the
 * key is pressed again or the user takes over ({@link BulkTradeRun}).
 *
 * <p><b>Librarian roller.</b> Start it with the roller key while looking at the librarian's lectern; that one position
 * is remembered and is the only block it ever breaks or places. It opens the trades, reads the offers and stops on the
 * wanted book; otherwise it closes the screen, breaks the lectern, waits for the villager to lose the profession,
 * places the lectern back from the hotbar, waits for the profession and repeats, up to the cycle cap. It stops at once
 * if the villager has been traded with (xp above 0 or level above novice). Decisions live in {@link LibrarianRoller};
 * camera, keys and hotbar go through one lease (manual input ends it and the run).
 */
public final class VillagerHelpers implements IModule, IConfigurable, LeaseListener {

    public static final String ID = "villager_helpers";
    public static final String KEY_BULK_TRADE = "key.utilitysuite.bulk_trade";
    public static final String KEY_LIBRARIAN_ROLLER = "key.utilitysuite.librarian_roller";

    public static final String K_BULK_MIN_TICKS = "bulk_min_ticks";
    public static final String K_BULK_MAX_TRADES = "bulk_max_trades";
    public static final String K_ROLL_ENCHANT = "roll_enchantment";
    public static final String K_ROLL_MIN_LEVEL = "roll_min_level";
    public static final String K_ROLL_MAX_PRICE = "roll_max_price";
    public static final String K_ROLL_MAX_CYCLES = "roll_max_cycles";

    static final int RESULT_SLOT = 2;
    static final int FIRST_INV_SLOT = 3;
    static final int INTERACT_COOLDOWN = 20;
    static final int PLACE_COOLDOWN = 10;
    static final double VILLAGER_SEARCH_SQ = 8.0 * 8.0;

    private boolean enabled;
    private int bulkMinTicks = 4;
    private int bulkMaxTrades;
    private String rollEnchant = "minecraft:mending";
    private int rollMinLevel = 1;
    private int rollMaxPrice;
    private int rollMaxCycles = 50;

    private KeyMapping bulkKey;
    private KeyMapping rollerKey;

    private final BulkTradeRun bulk = new BulkTradeRun();
    private ControlLease bulkLease;
    private LeaseRequest bulkRequest;
    private boolean bulkNotice;

    private final LibrarianRoller roller = new LibrarianRoller();
    private final LibrarianRoller.Facts facts = new LibrarianRoller.Facts();
    private ControlLease rollLease;
    private LeaseRequest rollRequest;
    private final BlockPos.MutableBlockPos lecternPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int villagerId = -1;
    private int actionCooldown;
    private boolean attackHeld;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TOOLS;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            bulk.stop(BulkTradeRun.Stop.MANUAL);
            roller.stop(LibrarianRoller.Stop.MANUAL);
            roller.reset();
            releaseBulk();
            releaseRoll();
        }
    }

    public BulkTradeRun bulk() {
        return bulk;
    }

    public LibrarianRoller roller() {
        return roller;
    }

    // ---- keys -----------------------------------------------------------------------------------------------------

    /** Mod bus. */
    public void onRegisterKeys(RegisterKeyMappingsEvent event) {
        bulkKey = new KeyMapping(KEY_BULK_TRADE, KeyConflictContext.GUI, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
                com.utilitysuite.core.input.KeybindManager.CATEGORY);
        rollerKey = new KeyMapping(KEY_LIBRARIAN_ROLLER, KeyConflictContext.IN_GAME, InputConstants.UNKNOWN,
                com.utilitysuite.core.input.KeybindManager.CATEGORY);
        event.register(bulkKey);
        event.register(rollerKey);
    }

    public KeyMapping bulkKey() {
        return bulkKey;
    }

    public KeyMapping rollerKey() {
        return rollerKey;
    }

    /** Game bus: the bulk-trade key inside a trade screen starts or stops a run. */
    public void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        KeyMapping k = bulkKey;
        if (!enabled || k == null || !(event.getScreen() instanceof MerchantScreen ms)) {
            return;
        }
        if (!k.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }
        event.setCanceled(true);
        if (bulk.running()) {
            bulk.stop(BulkTradeRun.Stop.MANUAL);
            return;
        }
        int idx = selectedOffer(ms.getMenu());
        if (idx < 0) {
            say(NoticeKind.WARN, "Bulk trade: pick a trade first");
            return;
        }
        bulk.start(idx);
        bulkNotice = true;
    }

    // ---- tick -----------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        tickBulk(mc, player);
        tickRollerKey(mc, player);
        tickRoller(mc, player);
    }

    private void tickBulk(Minecraft mc, LocalPlayer player) {
        if (!bulk.running()) {
            if (bulkNotice) {
                bulkNotice = false;
                releaseBulk();
                say(NoticeKind.INFO, "Bulk trade stopped: " + bulk.stopReason().name().toLowerCase().replace('_', ' ')
                        + " (" + bulk.trades() + " steps)");
            }
            return;
        }
        if (bulkLease == null || !bulkLease.isActive()) {
            Optional<ControlLease> got = Services.arbiter().request(bulkRequest());
            if (got.isEmpty()) {
                return;
            }
            bulkLease = got.get();
        }
        if (Services.clicker().pending() > 0) {
            return; // the previous take is still queued
        }
        boolean open = mc.screen instanceof MerchantScreen;
        MerchantMenu menu = open ? ((MerchantScreen) mc.screen).getMenu() : null;
        MerchantOffers offers = menu == null ? null : menu.getOffers();
        int i = bulk.offerIndex();
        if (offers == null || i >= offers.size()) {
            bulk.stop(BulkTradeRun.Stop.SCREEN_CLOSED);
            return;
        }
        MerchantOffer offer = offers.get(i);
        boolean inputs = hasInputs(menu, offer);
        boolean room = hasRoom(menu, offer.getResult());
        BulkTradeRun.Action a = bulk.tick(true, offer.getUses(), offer.getMaxUses(), inputs, room, bulkMinTicks,
                bulkMaxTrades);
        if (a == BulkTradeRun.Action.TRADE && mc.getConnection() != null) {
            // The trade-list button's own path: select, move payment in, tell the server.
            menu.setSelectionHint(i);
            menu.tryMoveItems(i);
            mc.getConnection().send(new ServerboundSelectTradePacket(i));
            Services.clicker().queueClick(bulkLease, RESULT_SLOT, 0, IContainerClicker.Click.QUICK_MOVE);
        }
    }

    /** The picked offer: payment slots satisfy it and the result slot shows its result. -1 when none. */
    static int selectedOffer(MerchantMenu menu) {
        MerchantOffers offers = menu.getOffers();
        ItemStack a = menu.getSlot(0).getItem();
        ItemStack b = menu.getSlot(1).getItem();
        ItemStack r = menu.getSlot(RESULT_SLOT).getItem();
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer o = offers.get(i);
            if (!r.isEmpty() && ItemStack.isSameItemSameComponents(r, o.getResult()) && o.satisfiedBy(a, b)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasInputs(MerchantMenu menu, MerchantOffer offer) {
        ItemStack ca = offer.getCostA();
        ItemStack cb = offer.getCostB();
        return count(menu, ca) >= ca.getCount() && (cb.isEmpty() || count(menu, cb) >= cb.getCount());
    }

    private static int count(MerchantMenu menu, ItemStack want) {
        int n = 0;
        int size = menu.slots.size();
        for (int s = 0; s < size; s++) {
            if (s == RESULT_SLOT) {
                continue;
            }
            ItemStack st = menu.getSlot(s).getItem();
            if (ItemStack.isSameItem(st, want)) {
                n += st.getCount();
            }
        }
        return n;
    }

    private static boolean hasRoom(MerchantMenu menu, ItemStack result) {
        int size = menu.slots.size();
        for (int s = FIRST_INV_SLOT; s < size; s++) {
            ItemStack st = menu.getSlot(s).getItem();
            if (st.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(st, result) && st.getCount() + result.getCount()
                    <= st.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    // ---- roller ---------------------------------------------------------------------------------------------------

    private void tickRollerKey(Minecraft mc, LocalPlayer player) {
        KeyMapping k = rollerKey;
        if (k == null) {
            return;
        }
        boolean pressed = false;
        while (k.consumeClick()) {
            pressed = true;
        }
        if (!pressed) {
            return;
        }
        if (roller.running()) {
            roller.stop(LibrarianRoller.Stop.MANUAL);
            return;
        }
        startRoller(mc, player);
    }

    /** Protected-area guard: only a lectern the user is looking at becomes the roller's lectern. */
    private void startRoller(Minecraft mc, LocalPlayer player) {
        if (BookOfferMatch.normalize(rollEnchant).isEmpty()) {
            say(NoticeKind.WARN, "Librarian roller: set the wanted enchantment first");
            return;
        }
        HitResult hr = mc.hitResult;
        if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK
                || !mc.level.getBlockState(bhr.getBlockPos()).is(Blocks.LECTERN)) {
            say(NoticeKind.WARN, "Librarian roller: look at the librarian's lectern to start");
            return;
        }
        lecternPos.set(bhr.getBlockPos());
        Villager best = null;
        double bestD = VILLAGER_SEARCH_SQ;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Villager v && !v.isBaby()) {
                double d = v.distanceToSqr(lecternPos.getX() + 0.5, lecternPos.getY() + 0.5, lecternPos.getZ() + 0.5);
                boolean lib = v.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN;
                if (lib && d < bestD) {
                    best = v;
                    bestD = d;
                }
            }
        }
        if (best == null) {
            say(NoticeKind.WARN, "Librarian roller: no librarian near that lectern");
            return;
        }
        if (best.getVillagerData().getLevel() > 1) {
            say(NoticeKind.WARN, "Librarian roller: that librarian is past novice (already traded with)");
            return;
        }
        villagerId = best.getId();
        actionCooldown = 0;
        roller.start(rollMaxCycles);
        say(NoticeKind.INFO, "Librarian roller started");
    }

    private void tickRoller(Minecraft mc, LocalPlayer player) {
        if (roller.phase() == LibrarianRoller.Phase.IDLE) {
            return;
        }
        if (!roller.running()) {
            finishRoller();
            return;
        }
        if (rollLease == null || !rollLease.isActive()) {
            if (Services.arbiter().isManualOverrideActive(ControlChannel.CAMERA)) {
                return;
            }
            Optional<ControlLease> got = Services.arbiter().request(rollRequest());
            if (got.isEmpty()) {
                return;
            }
            rollLease = got.get();
        }
        Villager v = mc.level.getEntity(villagerId) instanceof Villager vv && vv.isAlive() ? vv : null;
        gatherFacts(mc, player, v);
        LibrarianRoller.Action a = roller.tick(facts);
        IInputSmoother input = Services.input();
        if (a != LibrarianRoller.Action.BREAK_LECTERN && attackHeld) {
            input.setKeyHeld(rollLease, SimKey.ATTACK, false);
            attackHeld = false;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
        }
        switch (a) {
            case INTERACT_VILLAGER -> interact(mc, input, player, v);
            case CLOSE_SCREEN -> {
                if (mc.screen instanceof MerchantScreen) {
                    player.closeContainer();
                }
            }
            case BREAK_LECTERN -> breakLectern(mc, input, player);
            case PLACE_LECTERN -> placeLectern(mc, input, player);
            default -> {
            }
        }
        if (!roller.running()) {
            finishRoller();
        }
    }

    private void gatherFacts(Minecraft mc, LocalPlayer player, Villager v) {
        facts.clear();
        facts.villagerPresent = v != null;
        if (v != null) {
            VillagerProfession p = v.getVillagerData().getProfession();
            facts.librarian = p == VillagerProfession.LIBRARIAN;
            facts.unemployed = p == VillagerProfession.NONE;
        }
        facts.lecternAtPos = mc.level.getBlockState(lecternPos).is(Blocks.LECTERN);
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().items.get(i).is(Items.LECTERN)) {
                facts.lecternInHotbar = true;
                break;
            }
        }
        if (mc.screen instanceof MerchantScreen ms && !ms.getMenu().getOffers().isEmpty()) {
            MerchantMenu menu = ms.getMenu();
            facts.offersShown = true;
            facts.traderXp = menu.getTraderXp();
            facts.traderLevel = menu.getTraderLevel();
            LibrarianRoller.Phase ph = roller.phase();
            if (ph == LibrarianRoller.Phase.OPEN || ph == LibrarianRoller.Phase.CHECK) {
                facts.wantedOffered = wantedOffered(menu.getOffers());
            }
        }
    }

    /** Reads the offers once per check (OPEN/CHECK phases only). */
    private boolean wantedOffered(MerchantOffers offers) {
        String target = BookOfferMatch.normalize(rollEnchant);
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer o = offers.get(i);
            ItemStack res = o.getResult();
            if (!res.is(Items.ENCHANTED_BOOK)) {
                continue;
            }
            ItemEnchantments ench = res.get(DataComponents.STORED_ENCHANTMENTS);
            if (ench == null) {
                continue;
            }
            int price = o.getCostA().is(Items.EMERALD) ? o.getCostA().getCount()
                    : o.getCostB().is(Items.EMERALD) ? o.getCostB().getCount() : -1;
            for (Object2IntMap.Entry<Holder<Enchantment>> e : ench.entrySet()) {
                if (BookOfferMatch.matches(e.getKey().getRegisteredName(), e.getIntValue(), price, target,
                        rollMinLevel, rollMaxPrice)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void interact(Minecraft mc, IInputSmoother input, LocalPlayer player, Villager v) {
        if (v == null || mc.screen != null) {
            return;
        }
        aimAt(input, player, v.getX(), v.getY() + v.getBbHeight() * 0.6, v.getZ());
        if (actionCooldown == 0 && input.isRotationSettled(3.0f) && mc.crosshairPickEntity == v) {
            input.scheduleKeyTap(rollLease, SimKey.USE, 1, 3);
            actionCooldown = INTERACT_COOLDOWN;
        }
    }

    private void breakLectern(Minecraft mc, IInputSmoother input, LocalPlayer player) {
        // Guard: only the remembered position, and only while it holds a lectern.
        if (!mc.level.getBlockState(lecternPos).is(Blocks.LECTERN)) {
            return;
        }
        aimAt(input, player, lecternPos.getX() + 0.5, lecternPos.getY() + 0.5, lecternPos.getZ() + 0.5);
        HitResult hr = mc.hitResult;
        boolean onTarget = input.isRotationSettled(3.0f) && hr instanceof BlockHitResult bhr
                && hr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(lecternPos);
        if (onTarget != attackHeld) {
            input.setKeyHeld(rollLease, SimKey.ATTACK, onTarget);
            attackHeld = onTarget;
        }
    }

    private void placeLectern(Minecraft mc, IInputSmoother input, LocalPlayer player) {
        if (!mc.level.getBlockState(lecternPos).canBeReplaced()) {
            return;
        }
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().items.get(i).is(Items.LECTERN)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return;
        }
        if (player.getInventory().selected != slot) { // read-only check; the switch goes through the core
            input.selectHotbarSlot(rollLease, slot);
            return;
        }
        // Aim at the top face of the block under the remembered spot, so the lectern lands exactly there.
        aimAt(input, player, lecternPos.getX() + 0.5, lecternPos.getY() - 0.02, lecternPos.getZ() + 0.5);
        cursor.set(lecternPos.getX(), lecternPos.getY() - 1, lecternPos.getZ());
        HitResult hr = mc.hitResult;
        boolean onTarget = input.isRotationSettled(3.0f) && hr instanceof BlockHitResult bhr
                && hr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(cursor)
                && bhr.getDirection() == Direction.UP;
        if (onTarget && actionCooldown == 0) {
            input.scheduleKeyTap(rollLease, SimKey.USE, 1, 3);
            actionCooldown = PLACE_COOLDOWN;
        }
    }

    private void aimAt(IInputSmoother input, LocalPlayer player, double hx, double hy, double hz) {
        double dx = hx - player.getX(), dy = hy - player.getEyeY(), dz = hz - player.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        input.setRotationTarget(rollLease, RotationTarget.visible(yaw, pitch));
    }

    private void finishRoller() {
        LibrarianRoller.Stop s = roller.stopReason();
        releaseRoll();
        roller.reset();
        if (s == LibrarianRoller.Stop.SUCCESS) {
            say(NoticeKind.DONE, "Librarian roller: wanted book found after " + roller.cycles() + " rerolls");
        } else {
            say(NoticeKind.WARN, "Librarian roller stopped: " + s.name().toLowerCase().replace('_', ' ')
                    + " (" + roller.cycles() + " rerolls)");
        }
    }

    // ---- leases ---------------------------------------------------------------------------------------------------

    private LeaseRequest bulkRequest() {
        if (bulkRequest == null) {
            bulkRequest = new LeaseRequest(ID, ControlPriority.IDLE, EnumSet.of(ControlChannel.HOTBAR), 0, this);
        }
        return bulkRequest;
    }

    private LeaseRequest rollRequest() {
        if (rollRequest == null) {
            rollRequest = new LeaseRequest(ID, ControlPriority.IDLE, EnumSet.of(ControlChannel.CAMERA,
                    ControlChannel.WORLD_INTERACT, ControlChannel.HOTBAR), 0, this);
        }
        return rollRequest;
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        boolean userOrStop = reason == LeaseEndReason.MANUAL_OVERRIDE || reason == LeaseEndReason.STOP_ALL;
        if (l == bulkLease) {
            bulkLease = null;
            if (userOrStop) {
                bulk.stop(BulkTradeRun.Stop.MANUAL);
            }
        } else if (l == rollLease) {
            rollLease = null;
            attackHeld = false;
            if (userOrStop) {
                roller.stop(LibrarianRoller.Stop.MANUAL);
            }
        }
    }

    private void releaseBulk() {
        ControlLease l = bulkLease;
        bulkLease = null;
        if (l != null && l.isActive()) {
            Services.clicker().cancel(l);
            Services.arbiter().release(l);
        }
    }

    private void releaseRoll() {
        ControlLease l = rollLease;
        rollLease = null;
        attackHeld = false;
        if (l != null && l.isActive()) {
            Services.input().releaseFor(l);
            Services.arbiter().release(l);
        }
    }

    @Override
    public void onStopAll() {
        bulk.stop(BulkTradeRun.Stop.MANUAL);
        roller.stop(LibrarianRoller.Stop.MANUAL);
        roller.reset();
        bulkLease = null;
        rollLease = null;
        attackHeld = false;
    }

    @Override
    public void onWorldUnload() {
        onStopAll();
        villagerId = -1;
    }

    @Override
    public String phase() {
        if (roller.running()) {
            return roller.phase().name();
        }
        return bulk.running() ? "BULK_TRADE" : null;
    }

    private static void say(NoticeKind kind, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !Services.modState().isCleanScreen()) {
            Services.visuals().notices().post(kind, text, null);
        }
    }

    // ---- settings -------------------------------------------------------------------------------------------------

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        bulkMinTicks = p.getInt(ID, K_BULK_MIN_TICKS, 4);
        bulkMaxTrades = p.getInt(ID, K_BULK_MAX_TRADES, 0);
        rollEnchant = p.getString(ID, K_ROLL_ENCHANT, "minecraft:mending");
        rollMinLevel = p.getInt(ID, K_ROLL_MIN_LEVEL, 1);
        rollMaxPrice = p.getInt(ID, K_ROLL_MAX_PRICE, 0);
        rollMaxCycles = p.getInt(ID, K_ROLL_MAX_CYCLES, 50);
    }

    @Override
    public String displayName() {
        return "Villager Helpers";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer(K_BULK_MIN_TICKS, "Ticks between trades", 4, 1, 40, "Bulk trade",
                        "Minimum pause between two bulk-trade steps."),
                SettingSpec.integer(K_BULK_MAX_TRADES, "Max steps per run", 0, 0, 999, "Bulk trade",
                        "Stops a bulk-trade run after this many steps. 0 = until inputs, stock or room run out."),
                SettingSpec.text(K_ROLL_ENCHANT, "Wanted enchantment", "minecraft:mending", "Librarian roller",
                        "Enchantment id of the book to roll for, e.g. minecraft:mending or efficiency."),
                SettingSpec.integer(K_ROLL_MIN_LEVEL, "Minimum level", 1, 1, 10, "Librarian roller",
                        "Lowest acceptable enchantment level on the book."),
                SettingSpec.integer(K_ROLL_MAX_PRICE, "Max emerald price", 0, 0, 64, "Librarian roller",
                        "Highest acceptable emerald price. 0 = any price."),
                SettingSpec.integer(K_ROLL_MAX_CYCLES, "Max rerolls", 50, 1, 500, "Librarian roller",
                        "Stops after this many lectern rerolls without the wanted book."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
