package com.utilitysuite.tools.player;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IAutopilot;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Offhand manager: keeps the right item in the offhand by {@link OffhandRules} (totem at low health or while gliding,
 * shield against ranged threats, else the preferred item). One SWAP click (inventory slot to offhand, button 40) through
 * {@link IContainerClicker} under a HOTBAR lease, after a human reaction delay, then a cooldown. Never while a screen is
 * open or the player is using an item. With the module off it still acts for the opt-in autopilot when
 * {@code allows(Reflex.OFFHAND)}, limited to the totem rules. Inventory scans are budgeted (every
 * {@link #SCAN_INTERVAL} ticks, or at once when health or gliding changes); the ranged-threat scan runs every
 * {@link #THREAT_INTERVAL} ticks. When the local player's totem pops ({@link #onLocalTotemPop}, entity event 35
 * forwarded by {@code mixin.combat}) the next ticks scan without waiting for the interval ({@link OffhandScanGate});
 * the refill still starts after the usual reaction delay.
 */
public final class OffhandManager implements IModule, IConfigurable, LeaseListener {

    public static final String ID = "offhand_manager";
    static final int OFFHAND_BUTTON = 40;
    static final int SCAN_INTERVAL = 5;
    static final int THREAT_INTERVAL = 10;
    static final double THREAT_RANGE_SQ = 24 * 24;
    static final int LEASE_TIMEOUT = 40;

    public static final String K_TOTEM_LOW = "totem_low_health";
    public static final String K_TOTEM_HP = "totem_health";
    public static final String K_TOTEM_GLIDE = "totem_gliding";
    public static final String K_SHIELD = "shield_ranged";
    public static final String K_PREFERRED = "preferred_item";
    public static final String K_COOLDOWN = "cooldown_ticks";

    private boolean enabled;
    private final OffhandRules.State state = new OffhandRules.State();
    private final OffhandRules.Config config = new OffhandRules.Config();
    private Item preferred = Items.AIR;
    private int cooldownSetting = 10;

    private int clock;
    private final OffhandScanGate scanGate = new OffhandScanGate();
    private float lastHealth = -1;
    private boolean lastGliding;
    private boolean rangedNear;
    private int totemSlot = -1;
    private int shieldSlot = -1;
    private int preferredSlot = -1;
    private OffhandRules.Want planned = OffhandRules.Want.KEEP;
    private int plannedDelay;
    private ControlLease lease;
    private int leaseTicks;
    private boolean actingForAutopilot;
    private LeaseRequest totemRequest;
    private LeaseRequest normalRequest;

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
            reset();
        }
    }

    @Override
    public void onTick() {
        // driven by onClientTick so the autopilot fallback also runs while the module is off
    }

    /** Game bus, ClientTickEvent.Post. Cheap early-out when neither the module nor the autopilot reflex is on. */
    public void onClientTick(ClientTickEvent.Post event) {
        IAutopilot ap = null;
        if (!enabled) {
            ap = Services.autopilotOrNull();
            if (ap == null || !ap.allows(IAutopilot.Reflex.OFFHAND)) {
                if (lease != null || planned != OffhandRules.Want.KEEP) {
                    reset();
                }
                return;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.isDeadOrDying()) {
            return;
        }
        tickLease();
        if (state.cooldownTicks > 0) {
            state.cooldownTicks--;
        }
        config.totemRulesOnly = !enabled;
        clock++;
        if (clock % THREAT_INTERVAL == 0 && !config.totemRulesOnly && config.shieldVsRanged) {
            rangedNear = scanRanged(mc, player);
        }
        float hp = player.getHealth();
        boolean gliding = player.isFallFlying();
        boolean changed = hp != lastHealth || gliding != lastGliding;
        lastHealth = hp;
        lastGliding = gliding;
        if (planned != OffhandRules.Want.KEEP) {
            if (--plannedDelay <= 0) {
                perform(mc, player, ap);
            }
            return;
        }
        if (!scanGate.due(changed, clock)) {
            return;
        }
        fill(mc, player);
        OffhandRules.Want w = OffhandRules.decide(state, config);
        if (w == OffhandRules.Want.KEEP || slotFor(w) < 0) {
            return;
        }
        if (ap != null && !ap.mayAct(IAutopilot.Reflex.OFFHAND, 2, hp <= config.totemHealth ? 20f : 4f)) {
            return;
        }
        planned = w;
        scanGate.clear();
        int median = w == OffhandRules.Want.TOTEM ? 3 : 6;
        plannedDelay = Services.humanizer().reactionTicks(median, 1, median * 3);
    }

    /**
     * The local player's totem of undying was used (entity event 35). Client thread. Only makes the next ticks scan;
     * what is swapped in, and after which reaction delay, is decided as on any other scan.
     */
    public void onLocalTotemPop() {
        scanGate.popped();
    }

    private void perform(Minecraft mc, LocalPlayer player, IAutopilot ap) {
        OffhandRules.Want w = planned;
        planned = OffhandRules.Want.KEEP;
        fill(mc, player); // re-check: the world may have changed during the reaction delay
        if (OffhandRules.decide(state, config) != w) {
            return;
        }
        int inv = slotFor(w);
        if (inv < 0) {
            return;
        }
        IActionArbiter arbiter = Services.arbiter();
        if (arbiter.isAutomationStopped() || arbiter.isManualOverrideActive(ControlChannel.HOTBAR)) {
            return;
        }
        if (lease == null || !lease.isActive()) {
            Optional<ControlLease> got = arbiter.request(w == OffhandRules.Want.TOTEM ? totemRequest()
                    : normalRequest());
            if (got.isEmpty()) {
                return;
            }
            lease = got.get();
            leaseTicks = 0;
        }
        if (Services.clicker().queueClick(lease, menuSlotOf(inv), OFFHAND_BUTTON, IContainerClicker.Click.SWAP)) {
            state.cooldownTicks = cooldownSetting;
            actingForAutopilot = !enabled;
            if (actingForAutopilot && ap != null) {
                ap.noteActed(IAutopilot.Reflex.OFFHAND, "offhand " + w.name().toLowerCase());
            }
        }
    }

    private void tickLease() {
        ControlLease l = lease;
        if (l == null) {
            return;
        }
        leaseTicks++;
        if (!l.isActive() || Services.clicker().pending() == 0 || leaseTicks > LEASE_TIMEOUT) {
            releaseLease();
        }
    }

    private void releaseLease() {
        ControlLease l = lease;
        lease = null;
        if (l != null && l.isActive()) {
            Services.arbiter().release(l);
        }
        if (actingForAutopilot) {
            actingForAutopilot = false;
            IAutopilot ap = Services.autopilotOrNull();
            if (ap != null) {
                ap.noteDone(IAutopilot.Reflex.OFFHAND);
            }
        }
    }

    /** Fills {@link #state} from the player (36 slot reads, no allocation). */
    private void fill(Minecraft mc, LocalPlayer player) {
        Inventory inv = player.getInventory();
        state.health = player.getHealth();
        state.gliding = player.isFallFlying();
        state.rangedThreatNear = rangedNear;
        state.projectileDanger = Services.danger().has(HazardKind.PROJECTILE);
        state.screenOpen = mc.gui.screen() != null;
        state.usingItem = player.isUsingItem();
        state.clickPending = lease != null;
        ItemStack off = player.getOffhandItem();
        state.offhandHolds = off.is(Items.TOTEM_OF_UNDYING) ? OffhandRules.Want.TOTEM
                : off.is(Items.SHIELD) ? OffhandRules.Want.SHIELD
                : (preferred != Items.AIR && off.is(preferred)) ? OffhandRules.Want.PREFERRED
                : OffhandRules.Want.KEEP;
        totemSlot = -1;
        shieldSlot = -1;
        preferredSlot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getNonEquipmentItems().get(i);
            if (s.isEmpty()) {
                continue;
            }
            if (totemSlot < 0 && s.is(Items.TOTEM_OF_UNDYING)) {
                totemSlot = i;
            } else if (shieldSlot < 0 && s.is(Items.SHIELD)) {
                shieldSlot = i;
            }
            if (preferredSlot < 0 && preferred != Items.AIR && s.is(preferred)) {
                preferredSlot = i;
            }
        }
        state.haveTotem = totemSlot >= 0;
        state.haveShield = shieldSlot >= 0;
        state.havePreferred = preferredSlot >= 0;
    }

    private int slotFor(OffhandRules.Want w) {
        return switch (w) {
            case TOTEM -> totemSlot;
            case SHIELD -> shieldSlot;
            case PREFERRED -> preferredSlot;
            case KEEP -> -1;
        };
    }

    private static boolean scanRanged(Minecraft mc, LocalPlayer player) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof RangedAttackMob && e.isAlive() && e.distanceToSqr(player) <= THREAT_RANGE_SQ) {
                return true;
            }
        }
        return false;
    }

    /** Inventory index (0..8 hotbar, 9..35 main) to the player-menu slot index. */
    static int menuSlotOf(int invIndex) {
        return invIndex < 9 ? 36 + invIndex : invIndex;
    }

    private LeaseRequest totemRequest() {
        if (totemRequest == null) {
            totemRequest = new LeaseRequest(ID, ControlPriority.SURVIVAL, EnumSet.of(ControlChannel.HOTBAR),
                    LEASE_TIMEOUT, this);
        }
        return totemRequest;
    }

    private LeaseRequest normalRequest() {
        if (normalRequest == null) {
            normalRequest = new LeaseRequest(ID, ControlPriority.IDLE, EnumSet.of(ControlChannel.HOTBAR),
                    LEASE_TIMEOUT, this);
        }
        return normalRequest;
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l == lease) {
            lease = null;
            planned = OffhandRules.Want.KEEP;
            state.cooldownTicks = Math.max(state.cooldownTicks, cooldownSetting);
        }
    }

    private void reset() {
        planned = OffhandRules.Want.KEEP;
        scanGate.clear();
        if (lease != null) {
            Services.clicker().cancel(lease);
        }
        releaseLease();
    }

    @Override
    public void onStopAll() {
        planned = OffhandRules.Want.KEEP;
        scanGate.clear();
        lease = null;
        actingForAutopilot = false;
    }

    @Override
    public void onWorldUnload() {
        onStopAll();
        rangedNear = false;
        lastHealth = -1;
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        config.totemOnLowHealth = p.getBool(ID, K_TOTEM_LOW, true);
        config.totemHealth = (float) p.getDouble(ID, K_TOTEM_HP, 10.0);
        config.totemWhileGliding = p.getBool(ID, K_TOTEM_GLIDE, true);
        config.shieldVsRanged = p.getBool(ID, K_SHIELD, true);
        cooldownSetting = p.getInt(ID, K_COOLDOWN, 10);
        String id = p.getString(ID, K_PREFERRED, "").trim();
        preferred = Items.AIR;
        if (!id.isEmpty()) {
            Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
                preferred = BuiltInRegistries.ITEM.getValue(rl);
            }
        }
        config.preferredEnabled = preferred != Items.AIR;
    }

    @Override
    public String displayName() {
        return "Offhand Manager";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(K_TOTEM_LOW, "Totem at low health", true, "Rules",
                        "Rule 1: a totem goes to the offhand at or below the health threshold."),
                SettingSpec.decimal(K_TOTEM_HP, "Totem health threshold", 10.0, 1.0, 20.0, "Rules",
                        "Health points (2 = one heart)."),
                SettingSpec.bool(K_TOTEM_GLIDE, "Totem while gliding", true, "Rules",
                        "Rule 2: a totem while flying with an elytra (crashes and falls)."),
                SettingSpec.bool(K_SHIELD, "Shield against ranged threats", true, "Rules",
                        "Rule 3: a shield while a skeleton, pillager or other ranged mob is near, or a projectile is "
                                + "incoming."),
                SettingSpec.text(K_PREFERRED, "Preferred item", "", "Rules",
                        "Rule 4: otherwise keep this item (registry id, e.g. minecraft:torch). Empty = leave the "
                                + "offhand alone."),
                SettingSpec.integer(K_COOLDOWN, "Swap cooldown (ticks)", 10, 0, 100, "Advanced",
                        "Minimum time between two swaps.").asAdvanced());
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
