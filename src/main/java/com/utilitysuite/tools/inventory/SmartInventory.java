package com.utilitysuite.tools.inventory;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.input.ScreenMovementPassthrough;
import com.utilitysuite.core.input.ScreenTextEntry;
import com.utilitysuite.tools.inventory.loadout.GearItem;
import com.utilitysuite.tools.inventory.loadout.Loadout;
import com.utilitysuite.tools.inventory.loadout.LoadoutBook;
import com.utilitysuite.tools.inventory.loadout.LoadoutPlanner;
import com.utilitysuite.tools.inventory.loadout.McLoadouts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

/**
 * SmartInventory: hotbar refill with pinned slots, quick deposit / smart dump, shulker previews, auto-armor and menu
 * movement. Every inventory manipulation takes a short SURVIVAL lease on HOTBAR and queues its clicks through
 * {@code IContainerClicker} (gaussian spacing); the lease is released as soon as the queue drains.
 * <p>
 * Tick cost: one scan of 36 slots every {@code scan_interval} ticks while enabled (no allocation); the hostile check
 * for auto-armor only runs when an upgrade actually exists.
 */
public final class SmartInventory implements IModule, IConfigurable, LeaseListener {

    public static final String ID = "smart_inventory";

    public static final String K_REFILL = "refill";
    public static final String K_REFILL_BLOCKS = "refill_blocks";
    public static final String K_REFILL_FOOD = "refill_food";
    public static final String K_REFILL_PEARLS = "refill_pearls";
    public static final String K_REFILL_ROCKETS = "refill_rockets";
    public static final String K_REFILL_PCT = "refill_threshold_pct";
    public static final String K_PINNED = "pinned_slots";
    public static final String K_SAME_CLASS = "refill_same_class";
    public static final String K_SAME_CLASS_PINNED = "refill_same_class_pinned";
    public static final String K_DEPOSIT_BUTTONS = "deposit_buttons";
    public static final String K_DUMP_KEEP_HOTBAR = "dump_keep_hotbar";
    public static final String K_SHULKER = "shulker_preview";
    public static final String K_ARMOR = "auto_armor";
    public static final String K_ARMOR_RADIUS = "auto_armor_hostile_radius";
    public static final String K_GUI_MOVE = "gui_move";
    public static final String K_GUI_MOVE_SNEAK = "gui_move_sneak";
    public static final String K_SCAN_INTERVAL = "scan_interval_ticks";

    /** Profile key of the saved gear loadouts (compact JSON; not a menu setting, written via the debounced profile). */
    public static final String K_LOADOUTS = "loadouts";

    private enum Op { NONE, REFILL, DEPOSIT, ARMOR, LOADOUT }

    private LoadoutBook loadouts = new LoadoutBook();
    private final LoadoutPlanner loadoutPlanner = new LoadoutPlanner();

    private static final int ARMOR_INTERVAL = 40;
    private static final int COOLDOWN_AFTER_OP = 10;

    private final InventoryModel model = new InventoryModel();
    private final ClickPlan plan = new ClickPlan();
    private final HotbarRefillPlanner refill = new HotbarRefillPlanner();
    private final HotbarRefillPlanner.ItemClasses classes = new HotbarRefillPlanner.ItemClasses();
    private final DepositPlanner deposit = new DepositPlanner();
    private final McInventory mc = new McInventory();
    private final LeaseRequest leaseRequest;

    private boolean enabled = true;
    private boolean refillOn = true;
    private int categoryMask = InventoryModel.FLAG_BLOCK | InventoryModel.FLAG_FOOD | InventoryModel.FLAG_PEARL
            | InventoryModel.FLAG_ROCKET;
    private int refillPct = 25;
    private int pinnedMask;
    private boolean sameClass = true;
    private boolean sameClassPinned;
    private boolean depositButtons = true;
    private boolean dumpKeepHotbar;
    private boolean shulkerPreview = true;
    private boolean autoArmor;
    private int armorRadius = 12;
    private boolean guiMove = true;
    private boolean guiMoveSneak;
    private int scanInterval = 10;

    private Op op = Op.NONE;
    private ControlLease lease;
    private int opMenuId = -1;
    private int opTicks;
    private int ticks;
    private int cooldown;
    private int hintSlot = -1;
    private int hintItem = -1;
    private String lastResult = "";

    public SmartInventory() {
        this.leaseRequest = new LeaseRequest(ID, ControlPriority.SURVIVAL, EnumSet.of(ControlChannel.HOTBAR), 200, this);
        com.utilitysuite.core.api.ISuiteActions actions = Services.actionsOrNull();
        if (actions != null) {
            for (String preset : Loadout.PRESET_NAMES) {
                String key = preset.toLowerCase(java.util.Locale.ROOT);
                actions.register(new com.utilitysuite.core.api.SuiteAction("equip_loadout_" + key,
                        "Equip loadout " + preset, "gear kit armor hotbar swap " + key,
                        () -> postResult(equipLoadout(preset))));
                actions.register(new com.utilitysuite.core.api.SuiteAction("save_loadout_" + key,
                        "Save loadout " + preset, "gear kit armor hotbar remember " + key,
                        () -> postResult(saveLoadout(preset))));
            }
        }
    }

    // ---- IModule ----------------------------------------------------------------------------------------------------

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
            endOp();
            ScreenMovementPassthrough.stop();
        }
    }

    @Override
    public void onTick() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        tickMenuMovement(client);
        ticks++;
        if (op != Op.NONE) {
            tickOp(player);
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (ticks % scanInterval != 0) {
            return;
        }
        boolean noScreen = client.screen == null && player.containerMenu == player.inventoryMenu;
        boolean armorDue = autoArmor && ticks % ARMOR_INTERVAL == 0 && noScreen;
        if (!refillOn && !armorDue) {
            return;
        }
        mc.read(player, model, armorDue);
        if (refillOn) {
            mc.readClasses(player, classes);
        }
        if (client.screen != null) {
            refill.observe(model, classes);
            return;
        }
        if (!noScreen || Services.arbiter().isAutomationStopped()) {
            return;
        }
        plan.clear();
        if (refillOn && refill.plan(model, classes, categoryMask, refillPct, pinnedMask, sameClass, sameClassPinned,
                plan) > 0) {
            start(Op.REFILL, player.inventoryMenu.containerId, player);
            return;
        }
        if (refillOn) {
            reserveHint(player);
        }
        if (armorDue && ArmorRanker.hasUpgrade(model) && safeForArmor(player)) {
            plan.clear();
            if (ArmorRanker.plan(model, plan) > 0) {
                start(Op.ARMOR, player.inventoryMenu.containerId, player);
            }
        }
    }

    /**
     * A pinned stack ran low with no reserve in the main inventory: if a shulker box holds more, post one notice. The
     * box scan runs once per depletion (slot + item), not per scan; nothing is opened.
     */
    private void reserveHint(LocalPlayer player) {
        int h = refill.depletedPinned(model, categoryMask, refillPct, pinnedMask);
        if (h < 0) {
            hintSlot = -1;
            hintItem = -1;
            return;
        }
        int item = refill.itemAt(model, h);
        if (h == hintSlot && item == hintItem) {
            return;
        }
        hintSlot = h;
        hintItem = item;
        int more = ShulkerPreview.countInShulkers(player.getInventory(), item);
        if (more > 0 && !Services.modState().isCleanScreen()) {
            Services.visuals().notices().post(com.utilitysuite.core.api.visual.NoticeKind.INFO,
                    "Slot " + (h + 1) + " is out: a shulker box has " + more + " more", null);
        }
    }

    private void tickMenuMovement(Minecraft client) {
        if (guiMove && qualifiesForMenuMovement(client.screen)) {
            ScreenMovementPassthrough.apply(Services.input(), Services.arbiter(), guiMoveSneak);
        } else if (ScreenMovementPassthrough.isActive()) {
            ScreenMovementPassthrough.stop();
        }
    }

    /**
     * Inventory and container screens only; never chat, signs, books (not container screens), anvils or any screen
     * the user is typing into (creative search, recipe book search, nested or overlay text fields: see
     * {@link ScreenTextEntry}).
     */
    static boolean qualifiesForMenuMovement(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>) || screen instanceof AnvilScreen) {
            return false;
        }
        return !ScreenTextEntry.isActive(screen);
    }

    private boolean safeForArmor(LocalPlayer player) {
        // Not while any combat-or-higher automation owns a channel.
        for (ControlChannel ch : ControlChannel.all()) {
            ControlLease holder = Services.arbiter().holder(ch).orElse(null);
            if (holder != null && holder.priority().level() >= ControlPriority.COMBAT.level()) {
                return false;
            }
        }
        double r = armorRadius;
        AABB box = player.getBoundingBox().inflate(r);
        return player.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                e -> e instanceof Enemy && e.isAlive()).isEmpty();
    }

    private void start(Op kind, int menuId, LocalPlayer player) {
        ControlLease l = Services.arbiter().request(leaseRequest).orElse(null);
        if (l == null) {
            cooldown = scanInterval;
            return;
        }
        lease = l;
        for (int i = 0; i < plan.size(); i++) {
            if (!Services.clicker().queueClick(l, plan.slot(i), plan.button(i), plan.click(i))) {
                break;
            }
        }
        op = kind;
        opMenuId = menuId;
        opTicks = 0;
    }

    private void tickOp(LocalPlayer player) {
        opTicks++;
        ControlLease l = lease;
        if (l == null || !l.isActive()) {
            endOp();
            return;
        }
        if (player.containerMenu.containerId != opMenuId) {
            lastResult = "Inventory action cancelled (menu changed)";
            endOp();
            return;
        }
        if (Services.clicker().pending() == 0 && opTicks > 1) {
            endOp();
        }
    }

    private void endOp() {
        ControlLease l = lease;
        lease = null;
        if (l != null) {
            Services.clicker().cancel(l);
            l.close();
        }
        if (op != Op.NONE) {
            cooldown = COOLDOWN_AFTER_OP;
        }
        op = Op.NONE;
        opMenuId = -1;
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l == lease) {
            lease = null;
            op = Op.NONE;
            opMenuId = -1;
            cooldown = 20;
        }
    }

    @Override
    public void onStopAll() {
        endOp();
    }

    @Override
    public void onWorldUnload() {
        endOp();
        refill.reset();
        ScreenMovementPassthrough.stop();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        readSettings(profile);
        refill.reset();
    }

    // ---- quick deposit / smart dump (UI thread = client thread) -----------------------------------------------------

    /** True when the open screen is a storage container that the deposit buttons and hotkey apply to. */
    public static boolean isStorageMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof HopperMenu
                || menu instanceof DispenserMenu;
    }

    /**
     * Deposits into the open container: matching items ({@code all == false}) or everything except pinned hotbar
     * slots ({@code all == true}). Returns a short status for the UI.
     */
    public String depositToOpenContainer(boolean all) {
        return depositToOpenContainer(all, dumpKeepHotbar);
    }

    /** As {@link #depositToOpenContainer(boolean)} with an explicit "leave the whole hotbar alone" choice. */
    public String depositToOpenContainer(boolean all, boolean keepHotbar) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || !enabled) {
            return "Smart Inventory is off";
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu || !isStorageMenu(menu)) {
            return "No container open";
        }
        if (op != Op.NONE) {
            return "Busy";
        }
        if (Services.arbiter().isAutomationStopped()) {
            return "Automation is stopped";
        }
        mc.read(player, model, false);
        mc.readMenu(player, menu);
        plan.clear();
        int moved = deposit.plan(model, mc.menuSlotOfInv, mc.cItem, mc.cKey, mc.cCount, mc.cMax, mc.containerSlots,
                all ? DepositPlanner.Mode.ALL : DepositPlanner.Mode.MATCHING, pinnedMask, keepHotbar, plan);
        if (moved == 0) {
            lastResult = all ? "Nothing to dump (or container full)" : "No matching items (or container full)";
            return lastResult;
        }
        start(Op.DEPOSIT, menu.containerId, player);
        lastResult = op == Op.DEPOSIT ? "Depositing " + moved + " stacks" : "Inventory busy (another module)";
        return lastResult;
    }

    /** True while a deposit/refill/armor operation is running. */
    // ---- gear loadouts (I1) -----------------------------------------------------------------------------------------

    /** Saved loadouts in order (UI). */
    public List<Loadout> loadouts() {
        return loadouts.all();
    }

    /** Renames a saved loadout (command palette entries follow). */
    public String renameLoadout(String from, String to) {
        if (!loadouts.rename(from, to)) {
            lastResult = to == null || to.isBlank() ? "Type a new name first"
                    : loadouts.get(from) == null ? "No loadout named " + from : "A loadout named " + to.trim()
                    + " already exists";
            return lastResult;
        }
        persistLoadouts();
        lastResult = "Renamed " + from + " to " + to.trim();
        return lastResult;
    }

    /** Deletes a saved loadout (its command palette entries go too). */
    public String deleteLoadout(String name) {
        if (!loadouts.remove(name)) {
            lastResult = "No loadout named " + name;
            return lastResult;
        }
        persistLoadouts();
        lastResult = "Deleted loadout " + name;
        return lastResult;
    }

    private void persistLoadouts() {
        Services.modState().profile().set(ID, K_LOADOUTS, loadouts.encode());
        Services.modState().markDirty();
        syncLoadoutActions();
    }

    /** Custom-named loadouts registered as palette actions (presets have fixed actions). */
    private final java.util.Set<String> customActionKeys = new java.util.HashSet<>();

    /** Registers equip actions for custom-named loadouts and drops the ones whose loadout is gone. */
    private void syncLoadoutActions() {
        com.utilitysuite.core.api.ISuiteActions actions = Services.actionsOrNull();
        if (actions == null) {
            return;
        }
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Loadout l : loadouts.all()) {
            if (LoadoutBook.isPreset(l.name())) {
                continue;
            }
            String key = LoadoutBook.actionKey(l.name());
            if (key.isEmpty()) {
                continue;
            }
            keep.add(key);
            String name = l.name();
            actions.register(new com.utilitysuite.core.api.SuiteAction("equip_loadout_custom_" + key,
                    "Equip loadout " + name, "gear kit armor hotbar swap custom " + key,
                    () -> postResult(equipLoadout(name))));
        }
        for (String old : customActionKeys) {
            if (!keep.contains(old)) {
                actions.unregister("equip_loadout_custom_" + old);
            }
        }
        customActionKeys.clear();
        customActionKeys.addAll(keep);
    }

    /** Saves the current armour, offhand and hotbar under {@code name} (replacing one with the same name). */
    public String saveLoadout(String name) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return "Not in a world";
        }
        Loadout l = McLoadouts.capture(name, player.getInventory());
        if (l.filledSlots() == 0) {
            lastResult = "Nothing to save: armour, offhand and hotbar are empty";
            return lastResult;
        }
        loadouts.put(l);
        persistLoadouts();
        lastResult = "Saved loadout " + l.name() + " (" + l.filledSlots() + " items)";
        return lastResult;
    }

    /**
     * Equips a saved loadout with the fewest clicks, from the inventory and from an open chest, shulker box or ender
     * chest. Armour found in a container is pulled into the inventory first; equip again after closing it.
     */
    public String equipLoadout(String name) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || !enabled) {
            return "Smart Inventory is off";
        }
        Loadout l = loadouts.get(name);
        if (l == null) {
            lastResult = "No loadout named " + name + " (save one first)";
            return lastResult;
        }
        if (op != Op.NONE) {
            return "Busy";
        }
        if (Services.arbiter().isAutomationStopped()) {
            return "Automation is stopped";
        }
        AbstractContainerMenu menu = player.containerMenu;
        boolean own = menu == player.inventoryMenu;
        if (!own && !(menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu)) {
            lastResult = "Close this screen first";
            return lastResult;
        }
        int containerSlots = own ? 0 : menu.slots.size() - 36;
        if (own && client.screen != null && !(client.screen instanceof AbstractContainerScreen<?>)) {
            return "Close this screen first";
        }
        GearItem[] pos = McLoadouts.positions(player.getInventory(), menu, containerSlots);
        plan.clear();
        LoadoutPlanner.Result r = loadoutPlanner.plan(l, pos, containerSlots, McLoadouts.lockedArmor(
                player.getInventory()), plan);
        String tail = (r.missing().isEmpty() ? "" : ", missing " + String.join(", ", r.missing()))
                + (r.deferred() > 0 ? ", close the container and equip again for armour" : "");
        if (plan.isEmpty()) {
            lastResult = l.name() + ": nothing to move" + tail;
            return lastResult;
        }
        start(Op.LOADOUT, menu.containerId, player);
        lastResult = op == Op.LOADOUT ? "Equipping " + l.name() + " (" + plan.size() + " clicks)" + tail
                : "Inventory busy (another module)";
        return lastResult;
    }

    private static void postResult(String text) {
        if (!Services.modState().isCleanScreen()) {
            Services.visuals().notices().post(com.utilitysuite.core.api.visual.NoticeKind.INFO, text, null);
        }
    }

    public boolean isBusy() {
        return op != Op.NONE;
    }

    public String lastResult() {
        return lastResult;
    }

    public boolean depositButtonsEnabled() {
        return enabled && depositButtons;
    }

    public boolean shulkerPreviewEnabled() {
        return enabled && shulkerPreview;
    }

    public int pinnedMask() {
        return pinnedMask;
    }

    /** UI: toggles a pinned hotbar slot (0-8) and saves it. */
    public void togglePinned(int hotbarSlot) {
        pinnedMask = PinnedSlots.toggle(pinnedMask, hotbarSlot);
        Services.modState().profile().set(ID, K_PINNED, PinnedSlots.format(pinnedMask));
        Services.modState().markDirty();
    }

    // ---- settings ---------------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Smart Inventory";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(K_REFILL, "Hotbar refill", true, "Hotbar refill",
                        "Refill running-low hotbar stacks from the main inventory."),
                SettingSpec.bool(K_REFILL_BLOCKS, "Blocks", true, "Hotbar refill", ""),
                SettingSpec.bool(K_REFILL_FOOD, "Food", true, "Hotbar refill", ""),
                SettingSpec.bool(K_REFILL_PEARLS, "Ender pearls", true, "Hotbar refill", ""),
                SettingSpec.bool(K_REFILL_ROCKETS, "Firework rockets", true, "Hotbar refill", ""),
                SettingSpec.integer(K_REFILL_PCT, "Refill below %", 25, 1, 90, "Hotbar refill",
                        "Refill when a stack drops below this share of a full stack."),
                SettingSpec.text(K_PINNED, "Pinned slots", "", "Hotbar refill",
                        "Hotbar slots 1-9 that are never overwritten or dumped, e.g. 1,2,9."),
                SettingSpec.bool(K_SAME_CLASS, "Allow same class", true, "Hotbar refill",
                        "When a tool, weapon, totem or food runs out, refill the slot with the best remaining item of the same kind (unpinned slots)."),
                SettingSpec.bool(K_SAME_CLASS_PINNED, "Allow same class (pinned)", false, "Hotbar refill",
                        "Also let pinned slots take a different item of the same kind.").asAdvanced(),
                SettingSpec.bool(K_DEPOSIT_BUTTONS, "Deposit buttons", true, "Deposit",
                        "Show Deposit / Dump buttons on chests, shulker boxes, hoppers and dispensers."),
                SettingSpec.bool(K_DUMP_KEEP_HOTBAR, "Dump keeps hotbar", false, "Deposit",
                        "Dump leaves the whole hotbar alone (pinned slots are always kept)."),
                SettingSpec.bool(K_SHULKER, "Shulker preview", true, "Tooltips",
                        "Show shulker box contents as an item grid in the tooltip."),
                SettingSpec.bool(K_ARMOR, "Auto-armor", false, "Auto-armor",
                        "Equip better armor from the inventory when no hostile mob is near and no fight is running."),
                SettingSpec.integer(K_ARMOR_RADIUS, "Hostile radius", 12, 4, 32, "Auto-armor",
                        "No auto-armor while a hostile mob is within this many blocks."),
                SettingSpec.bool(K_GUI_MOVE, "Move in menus", true, "Menu movement",
                        "Keep walking/jumping with your own keys while an inventory or container is open."),
                SettingSpec.bool(K_GUI_MOVE_SNEAK, "Include sneak", false, "Menu movement",
                        "Also pass the sneak key through (shift-clicking will then crouch)."),
                SettingSpec.integer(K_SCAN_INTERVAL, "Scan interval (ticks)", 10, 2, 40, "Hotbar refill",
                        "How often the hotbar is checked.").asAdvanced());
    }

    @Override
    public void onSettingChanged(String key) {
        readSettings(Services.modState().profile());
    }

    private void readSettings(ProfileConfig p) {
        refillOn = p.getBool(ID, K_REFILL, true);
        int mask = 0;
        mask |= p.getBool(ID, K_REFILL_BLOCKS, true) ? InventoryModel.FLAG_BLOCK : 0;
        mask |= p.getBool(ID, K_REFILL_FOOD, true) ? InventoryModel.FLAG_FOOD : 0;
        mask |= p.getBool(ID, K_REFILL_PEARLS, true) ? InventoryModel.FLAG_PEARL : 0;
        mask |= p.getBool(ID, K_REFILL_ROCKETS, true) ? InventoryModel.FLAG_ROCKET : 0;
        categoryMask = mask;
        refillPct = clamp(p.getInt(ID, K_REFILL_PCT, 25), 1, 90);
        pinnedMask = PinnedSlots.parse(p.getString(ID, K_PINNED, ""));
        sameClass = p.getBool(ID, K_SAME_CLASS, true);
        sameClassPinned = p.getBool(ID, K_SAME_CLASS_PINNED, false);
        depositButtons = p.getBool(ID, K_DEPOSIT_BUTTONS, true);
        dumpKeepHotbar = p.getBool(ID, K_DUMP_KEEP_HOTBAR, false);
        shulkerPreview = p.getBool(ID, K_SHULKER, true);
        autoArmor = p.getBool(ID, K_ARMOR, false);
        armorRadius = clamp(p.getInt(ID, K_ARMOR_RADIUS, 12), 4, 32);
        guiMove = p.getBool(ID, K_GUI_MOVE, true);
        guiMoveSneak = p.getBool(ID, K_GUI_MOVE_SNEAK, false);
        scanInterval = clamp(p.getInt(ID, K_SCAN_INTERVAL, 10), 2, 40);
        loadouts = LoadoutBook.decode(p.getString(ID, K_LOADOUTS, ""));
        syncLoadoutActions();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
