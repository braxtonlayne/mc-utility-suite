package com.utilitysuite.tools.player;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.NavigationRun;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.jobs.survival.PiglinAdvisor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.IdentityHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Piglin gold-safety guard (roadmap I4b). Status-line warning when an adult piglin is within aggro range and no gold
 * armour is worn (optionally equips a gold piece from the inventory into an empty armour slot with one shift-click
 * under a HOTBAR lease), and a warning while the crosshair is on a container next to bastion blocks (opening it angers
 * piglins). Piglins are tracked from join/leave events; the check runs every 10 ticks and only in the Nether.
 * <p>
 * Opt-in bartering follows {@link PiglinAdvisor#barter} every half second: a calm, non-admiring adult in give range
 * is handed the ingot (smoothed look, then a USE tap on it through {@link IInputSmoother#tapOnEntity} under a
 * WORLD_INTERACT + CAMERA lease), one further away gets a tossed ingot, and with "Collect barter loot" on, worthwhile
 * drops within a few blocks are walked to through a leased navigator run (short range only). Item entities are
 * tracked from join/leave events like piglins; manual input or Stop-All ends any step.
 */
public final class PiglinGuard implements IModule, IConfigurable, LeaseListener {

    public static final String ID = "piglin_guard";
    public static final String K_AUTO_EQUIP = "auto_equip_gold";
    public static final String K_CONTAINER_WARN = "container_warning";

    static final String NO_GOLD = "Piglins near: no gold armour worn";
    static final String CONTAINER = "Bastion container: opening it angers piglins";
    static final String EQUIPPING = "Piglins near: equipping gold armour";

    private static final int MAX_PIGLINS = 32;
    private static final int PERIOD = 10;
    private static volatile String line;

    private boolean enabled;
    private boolean autoEquip;
    private boolean containerWarn = true;
    private boolean barter;
    private int cadenceTicks = 20;
    private int sinceGive = 1_000;
    public static final String K_BARTER = "barter_toss";
    public static final String K_BARTER_CADENCE = "barter_cadence_ticks";
    public static final String K_BARTER_COLLECT = "barter_collect";
    public static final String K_BARTER_HAND = "barter_hand_over";

    /** Drops further than this are left (the walk stays short). */
    static final double COLLECT_RANGE = 6.0;
    private static final int MAX_DROPS = 64;
    private static final int GIVE_TIMEOUT_TICKS = 40;

    private boolean barterCollect;
    private boolean barterHand = true;
    private final ItemEntity[] drops = new ItemEntity[MAX_DROPS];
    // reused primitive decision inputs and result (the half-second barter decision allocates nothing)
    private final PiglinAdvisor.BarterInputs barterIn = new PiglinAdvisor.BarterInputs(MAX_PIGLINS, MAX_DROPS);
    private final PiglinAdvisor.MutableDecision barterOut = new PiglinAdvisor.MutableDecision();
    /** Junk verdict per item type: the registry id string is built once per item type, never per decision. */
    private final IdentityHashMap<Item, Boolean> junkByItem = new IdentityHashMap<>();
    private NavigationRun collectRun;
    private AbstractPiglin giveTarget;
    private int giveTicks;
    private ControlLease handLease;
    private LeaseRequest handRequest;

    private final AbstractPiglin[] piglins = new AbstractPiglin[MAX_PIGLINS];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int ticks;

    private ControlLease lease;
    private LeaseRequest request;

    public static String line() {
        return line;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TOOLS;
    }

    @Override
    public String displayName() {
        return "Piglin Guard";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            line = null;
            stopAll();
        }
    }

    public void onJoin(EntityJoinLevelEvent event) {
        if (enabled && barter && barterCollect && event.getLevel().isClientSide()
                && event.getEntity() instanceof ItemEntity item) {
            for (int i = 0; i < MAX_DROPS; i++) {
                if (drops[i] == null || drops[i].isRemoved()) {
                    drops[i] = item;
                    return;
                }
            }
            return;
        }
        if (event.getLevel().isClientSide() && event.getEntity() instanceof AbstractPiglin p) {
            for (int i = 0; i < MAX_PIGLINS; i++) {
                if (piglins[i] == null) {
                    piglins[i] = p;
                    return;
                }
            }
        }
    }

    public void onLeave(EntityLeaveLevelEvent event) {
        Entity e = event.getEntity();
        if (event.getLevel().isClientSide() && e instanceof ItemEntity) {
            for (int i = 0; i < MAX_DROPS; i++) {
                if (drops[i] == e) {
                    drops[i] = null;
                }
            }
            return;
        }
        if (event.getLevel().isClientSide() && e instanceof AbstractPiglin) {
            for (int i = 0; i < MAX_PIGLINS; i++) {
                if (piglins[i] == e) {
                    piglins[i] = null;
                }
            }
        }
    }

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        if (lease != null && Services.clicker().pending() == 0) {
            releaseLease();
        }
        if (giveTarget != null) {
            tickGive(); // per tick only while handing an ingot over
        }
        if (++ticks % PERIOD != 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !mc.level.dimensionType().piglinSafe()) {
            line = null;
            return;
        }
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < MAX_PIGLINS; i++) {
            AbstractPiglin g = piglins[i];
            if (g != null && g.isAlive() && !g.isBaby()) {
                nearest = Math.min(nearest, Math.sqrt(g.distanceToSqr(p)));
            }
        }
        boolean opening = containerWarn && lookingAtBastionContainer(mc);
        boolean wearsGold = wearsGold(p);
        int goldSlot = autoEquip && !wearsGold && nearest <= PiglinAdvisor.AGGRO_RANGE ? goldToEquip(p) : -1;
        PiglinAdvisor.Guard g = guard(wearsGold, nearest, goldSlot >= 0, opening);
        switch (g) {
            case SAFE -> line = null;
            case WARN_CONTAINER -> line = CONTAINER;
            case WARN_NO_GOLD -> line = NO_GOLD;
            case EQUIP_GOLD -> {
                line = EQUIPPING;
                equip(mc, goldSlot);
            }
        }
        if (barter && g != PiglinAdvisor.Guard.EQUIP_GOLD) {
            barterStep(mc, p);
        }
    }

    /**
     * Opt-in barter step (every {@value #PERIOD} ticks): with gold ingots in the main hand, follows
     * {@link PiglinAdvisor#barter}. GIVE hands the ingot to the piglin (USE tap through the core, when "Hand over" is
     * on; otherwise tossed), TOSS throws one ingot (a THROW click on the selected hotbar slot under a HOTBAR lease),
     * COLLECT walks to a worthwhile drop within {@link #COLLECT_RANGE} under a leased navigator run. Never next to a
     * hostile piglin. The decision inputs are rebuilt at this cadence only.
     */
    private void barterStep(Minecraft mc, LocalPlayer p) {
        sinceGive += PERIOD;
        if (mc.screen != null || giveTarget != null) {
            return;
        }
        if (collectRun != null) {
            if (collectRun.isActive()) {
                return; // walking to a drop
            }
            collectRun = null;
        }
        ItemStack hand = p.getMainHandItem();
        int ingots = hand.is(Items.GOLD_INGOT) ? hand.getCount() : 0;
        PiglinAdvisor.BarterInputs in = barterIn;
        in.clear();
        for (int i = 0; i < MAX_PIGLINS; i++) {
            AbstractPiglin g = piglins[i];
            if (g == null || !g.isAlive()) {
                continue;
            }
            in.addPiglin(g.getId(), Math.sqrt(g.distanceToSqr(p)), !g.isBaby(), g.isAggressive(),
                    !g.getOffhandItem().isEmpty());
        }
        if (barterCollect) {
            for (int i = 0; i < MAX_DROPS; i++) {
                ItemEntity d = drops[i];
                if (d == null || d.isRemoved()) {
                    drops[i] = null;
                    continue;
                }
                double dist = Math.sqrt(d.distanceToSqr(p));
                if (dist <= COLLECT_RANGE) {
                    in.addDrop(d.getId(), dist, junk(d.getItem().getItem()));
                }
            }
        }
        PiglinAdvisor.MutableDecision dec = barterOut;
        PiglinAdvisor.barter(ingots, in, sinceGive, cadenceTicks, barterCollect ? COLLECT_RANGE : 0.0, dec);
        switch (dec.act) {
            case GIVE -> {
                if (barterHand && mc.level.getEntity(dec.targetId) instanceof AbstractPiglin g) {
                    giveTarget = g;
                    giveTicks = 0;
                } else {
                    toss(p);
                }
            }
            case TOSS -> toss(p);
            case COLLECT -> collect(mc, dec.targetId);
            default -> {
            }
        }
    }

    /** {@link PiglinAdvisor#isJunk} cached per item type (the id string is built only on the first sight of a type). */
    private boolean junk(Item item) {
        Boolean cached = junkByItem.get(item);
        if (cached == null) {
            cached = PiglinAdvisor.isJunk(BuiltInRegistries.ITEM.getKey(item).toString(), java.util.Set.of())
                    ? Boolean.TRUE : Boolean.FALSE;
            junkByItem.put(item, cached);
        }
        return cached;
    }

    private void toss(LocalPlayer p) {
        if (lease == null || !lease.isActive()) {
            if (request == null) {
                request = new LeaseRequest(ID, ControlPriority.IDLE, EnumSet.of(ControlChannel.HOTBAR), 0, this);
            }
            lease = Services.arbiter().request(request).orElse(null);
            if (lease == null) {
                return;
            }
        }
        // InventoryMenu hotbar slots are 36..44; THROW with button 0 drops one item
        Services.clicker().queueClick(lease, 36 + p.getInventory().selected, 0, IContainerClicker.Click.THROW);
        sinceGive = 0;
    }

    /** Walks to the drop through a leased navigator run (short range: the decision only offers nearby drops). */
    private void collect(Minecraft mc, int entityId) {
        INavigator nav = Services.navigatorOrNull();
        Entity e = mc.level.getEntity(entityId);
        if (nav == null || !nav.isAvailable() || e == null) {
            return;
        }
        collectRun = nav.goToLeased(ID, ControlPriority.IDLE, e.getBlockX(), e.getBlockY(), e.getBlockZ(), 0)
                .orElse(null);
    }

    /**
     * Hands the ingot over: smoothed visible look at the piglin's body, then one USE tap once the crosshair is on it
     * (the core checks the lease, the settled rotation and the crosshair). Gives up when the piglin turns hostile, starts
     * admiring something else, leaves give range, the ingots run out or the aim times out.
     */
    private void tickGive() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        AbstractPiglin g = giveTarget;
        if (p == null || g == null || !g.isAlive() || g.isAggressive() || !g.getOffhandItem().isEmpty()
                || !p.getMainHandItem().is(Items.GOLD_INGOT) || mc.screen != null
                || g.distanceToSqr(p) > PiglinAdvisor.GIVE_RANGE * PiglinAdvisor.GIVE_RANGE
                || ++giveTicks > GIVE_TIMEOUT_TICKS) {
            endGive();
            return;
        }
        if (handLease == null || !handLease.isActive()) {
            if (handRequest == null) {
                handRequest = new LeaseRequest(ID, ControlPriority.IDLE,
                        EnumSet.of(ControlChannel.WORLD_INTERACT, ControlChannel.CAMERA), 0, this);
            }
            handLease = Services.arbiter().request(handRequest).orElse(null);
            if (handLease == null) {
                endGive();
                return;
            }
        }
        IInputSmoother input = Services.input();
        if (giveTicks == 1 || giveTicks % 5 == 0) {
            double dx = g.getX() - p.getX(), dy = g.getY() + g.getBbHeight() * 0.5 - p.getEyeY();
            double dz = g.getZ() - p.getZ();
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            input.setRotationTarget(handLease, RotationTarget.visible(yaw, pitch));
        }
        if (input.tapOnEntity(handLease, SimKey.USE, g.getId())) {
            sinceGive = 0;
            endGive();
        }
    }

    private void endGive() {
        giveTarget = null;
        giveTicks = 0;
        ControlLease l = handLease;
        handLease = null;
        if (l != null && l.isActive()) {
            Services.input().releaseFor(l);
            Services.arbiter().release(l);
        }
    }

    /** Same decision as {@link PiglinAdvisor#guard} without building the worn-armour list. */
    private PiglinAdvisor.Guard guard(boolean wearsGold, double nearest, boolean goldAvailable, boolean opening) {
        if (opening) {
            return PiglinAdvisor.Guard.WARN_CONTAINER;
        }
        if (nearest > PiglinAdvisor.AGGRO_RANGE || wearsGold) {
            return PiglinAdvisor.Guard.SAFE;
        }
        return autoEquip && goldAvailable ? PiglinAdvisor.Guard.EQUIP_GOLD : PiglinAdvisor.Guard.WARN_NO_GOLD;
    }

    private static boolean wearsGold(LocalPlayer p) {
        var armor = p.getInventory().armor;
        for (int i = 0; i < armor.size(); i++) {
            ItemStack s = armor.get(i);
            if (isGold(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGold(ItemStack s) {
        return s.is(Items.GOLDEN_HELMET) || s.is(Items.GOLDEN_CHESTPLATE) || s.is(Items.GOLDEN_LEGGINGS)
                || s.is(Items.GOLDEN_BOOTS);
    }

    /** Inventory index (0..35) of a gold armour piece whose armour slot is empty, or -1 (never replaces armour). */
    private static int goldToEquip(LocalPlayer p) {
        var items = p.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (isGold(s) && s.getItem() instanceof ArmorItem a) {
                EquipmentSlot slot = a.getEquipmentSlot();
                if (p.getItemBySlot(slot).isEmpty()) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void equip(Minecraft mc, int invIndex) {
        if (mc.screen != null || (lease != null && lease.isActive())) {
            return; // only from the plain inventory menu, one click at a time
        }
        if (request == null) {
            request = new LeaseRequest(ID, ControlPriority.IDLE, EnumSet.of(ControlChannel.HOTBAR), 0, this);
        }
        Optional<ControlLease> got = Services.arbiter().request(request);
        if (got.isEmpty()) {
            return;
        }
        lease = got.get();
        // InventoryMenu: main inventory 9..35 are menu slots 9..35, hotbar 0..8 are menu slots 36..44
        int menuSlot = invIndex < 9 ? 36 + invIndex : invIndex;
        Services.clicker().queueClick(lease, menuSlot, 0, IContainerClicker.Click.QUICK_MOVE);
    }

    private boolean lookingAtBastionContainer(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockState st = mc.level.getBlockState(bh.getBlockPos());
        if (!(st.getBlock() instanceof BaseEntityBlock) || st.is(Blocks.SPAWNER)) {
            return false;
        }
        BlockPos c = bh.getBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    cursor.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    BlockState b = mc.level.getBlockState(cursor);
                    if (b.is(Blocks.GOLD_BLOCK) || b.is(Blocks.GILDED_BLACKSTONE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Ends a hand-over or a walk to a drop and every lease (switch-off, Stop-All, world unload). */
    private void stopAll() {
        endGive();
        if (collectRun != null && collectRun.isActive()) {
            collectRun.cancel();
        }
        collectRun = null;
        releaseLease();
    }

    private void releaseLease() {
        ControlLease l = lease;
        lease = null;
        if (l != null && l.isActive()) {
            Services.clicker().cancel(l);
            Services.arbiter().release(l);
        }
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l == lease) {
            lease = null;
        }
        if (l == handLease) {
            handLease = null;
            giveTarget = null; // manual input wins: drop the hand-over
        }
    }

    @Override
    public void onStopAll() {
        stopAll();
    }

    @Override
    public void onWorldUnload() {
        java.util.Arrays.fill(piglins, null);
        java.util.Arrays.fill(drops, null);
        line = null;
        stopAll();
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(K_AUTO_EQUIP, "Equip gold armour", false, "",
                        "When piglins are near and no gold is worn, shift-click a gold armour piece from the"
                                + " inventory into its empty armour slot (never replaces worn armour)."),
                SettingSpec.bool(K_CONTAINER_WARN, "Bastion container warning", true, "",
                        "Warn while the crosshair is on a container next to gold blocks or gilded blackstone."),
                SettingSpec.bool(K_BARTER, "Barter toss", false, "",
                        "With gold ingots in your main hand, toss one to a calm adult piglin within 6 blocks that is"
                                + " not already admiring one. Stops when a piglin turns hostile."),
                SettingSpec.integer(K_BARTER_CADENCE, "Barter cadence", 20, 10, 200, "ticks",
                        "Minimum ticks between two tossed ingots."),
                SettingSpec.bool(K_BARTER_HAND, "Hand over in reach", true, "",
                        "Within 3 blocks, hand the ingot to the piglin (look at it, then use) instead of tossing."),
                SettingSpec.bool(K_BARTER_COLLECT, "Collect barter loot", false, "",
                        "Walk to worthwhile drops within 6 blocks (junk like gravel and soul sand is left)."));
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        barter = p.getBool(ID, K_BARTER, false);
        cadenceTicks = p.getInt(ID, K_BARTER_CADENCE, 20);
        barterHand = p.getBool(ID, K_BARTER_HAND, true);
        barterCollect = p.getBool(ID, K_BARTER_COLLECT, false);
        autoEquip = p.getBool(ID, K_AUTO_EQUIP, false);
        containerWarn = p.getBool(ID, K_CONTAINER_WARN, true);
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
