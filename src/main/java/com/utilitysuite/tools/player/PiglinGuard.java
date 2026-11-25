package com.utilitysuite.tools.player;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IModule;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Piglin gold-safety guard (roadmap I4b). Status-line warning when an adult piglin is within aggro range and no gold
 * armour is worn (optionally equips a gold piece from the inventory into an empty armour slot with one shift-click
 * under a HOTBAR lease), and a warning while the crosshair is on a container next to bastion blocks (opening it angers
 * piglins). Piglins are tracked from join/leave events; the check runs every 10 ticks and only in the Nether.
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
            releaseLease();
        }
    }

    public void onJoin(EntityJoinLevelEvent event) {
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
     * Opt-in barter toss: with gold ingots in the main hand and a calm adult piglin within a few blocks that is not
     * already admiring, throws one ingot (a THROW click on the selected hotbar slot under a HOTBAR lease) at the set
     * cadence. Piglins walk to dropped gold themselves. Stops when any piglin in range is aggressive.
     */
    private void barterStep(Minecraft mc, LocalPlayer p) {
        sinceGive += PERIOD;
        if (mc.screen != null) {
            return;
        }
        ItemStack hand = p.getMainHandItem();
        int ingots = hand.is(Items.GOLD_INGOT) ? hand.getCount() : 0;
        boolean hostile = false;
        double idle = Double.POSITIVE_INFINITY;
        for (int i = 0; i < MAX_PIGLINS; i++) {
            AbstractPiglin g = piglins[i];
            if (g == null || !g.isAlive()) {
                continue;
            }
            double d = Math.sqrt(g.distanceToSqr(p));
            if (g.isAggressive() && d <= PiglinAdvisor.AGGRO_RANGE) {
                hostile = true;
            } else if (!g.isBaby() && g.getOffhandItem().isEmpty()) {
                idle = Math.min(idle, d);
            }
        }
        PiglinAdvisor.Act act = PiglinAdvisor.giveStep(ingots, hostile, idle, sinceGive, cadenceTicks);
        if (act != PiglinAdvisor.Act.GIVE && act != PiglinAdvisor.Act.TOSS) {
            return;
        }
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
    }

    @Override
    public void onStopAll() {
        releaseLease();
    }

    @Override
    public void onWorldUnload() {
        java.util.Arrays.fill(piglins, null);
        line = null;
        releaseLease();
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
                        "Minimum ticks between two tossed ingots."));
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        barter = p.getBool(ID, K_BARTER, false);
        cadenceTicks = p.getInt(ID, K_BARTER_CADENCE, 20);
        autoEquip = p.getBool(ID, K_AUTO_EQUIP, false);
        containerWarn = p.getBool(ID, K_CONTAINER_WARN, true);
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
