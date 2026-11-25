package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.ServerTickFeed;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * XP-farm AFK grinder (off by default): stands at its spot and hits hostile mobs in reach, only at full attack charge
 * ({@link AfkGrinderRules}), with a smoothed look and one ATTACK tap through the core per swing. No swings while the
 * player eats (the survival autopilot's auto-eat does the eating) or while the server is stalled (the time-sync
 * heartbeat in {@link ServerTickFeed} is overdue). Before the held weapon breaks it swaps to a hotbar backup, mending
 * first; with no safe weapon left it stops swinging. Mobs are re-scanned every 5 ticks.
 */
public final class AfkGrinderModule extends FieldModule {

    public static final String ID = "afk_grinder";
    private static final int CAP = 32;
    private static final int SCAN_TICKS = 5;

    private final boolean[] hostile = new boolean[CAP];
    private final double[] distSq = new double[CAP];
    private final float[] health = new float[CAP];
    private final Monster[] mobs = new Monster[CAP];
    private final boolean[] weapon = new boolean[9];
    private final int[] damage = new int[9];
    private final int[] maxDamage = new int[9];
    private final boolean[] mending = new boolean[9];
    private final float[] attack = new float[9];

    private double reach = 3.0;
    private double lowDurability = AfkGrinderRules.LOW_DURABILITY;
    private int stallMs = (int) AfkGrinderRules.STALL_MS;
    private boolean swapWeapons = true;

    private Monster target;
    private long tick;
    private ClientLevel mendingLevel;
    private Holder<Enchantment> mendingHolder;
    private String status = "";

    public AfkGrinderModule() {
        super(ID, ControlPriority.GOAL);
        java.util.Arrays.fill(hostile, true);
    }

    public String status() {
        return status;
    }

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        tick++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null) {
            return;
        }
        if (hands.consumePreempted()) {
            stopWork();
            return;
        }
        if (!hands.ensureLease()) {
            return;
        }
        if (swapWeapons && tick % 20 == 0) {
            int slot = pickWeapon(mc.level, player);
            if (slot < 0) {
                status = "No weapon safe to use";
                target = null;
                return;
            }
            if (slot != player.getInventory().selected) {
                hands.selectSlot(slot);
            }
        }
        if (target == null || !target.isAlive() || player.distanceToSqr(target) > reach * reach) {
            target = null;
            if (tick % SCAN_TICKS != 0) {
                return;
            }
            target = scan(mc, player);
            if (target == null) {
                return;
            }
        }
        boolean stalled = AfkGrinderRules.serverStalled(ServerTickFeed.overdueMs(System.currentTimeMillis()), 0.0,
                stallMs);
        boolean swing = AfkGrinderRules.shouldSwing(player.getAttackStrengthScale(0.5f), true, stalled,
                player.isUsingItem());
        status = stalled ? "Server stalled: holding" : "";
        int r = hands.tickEntity(player, target, SimKey.ATTACK, reach, swing);
        if (r == FieldHands.FAILED) {
            target = null;
        }
    }

    private Monster scan(Minecraft mc, LocalPlayer player) {
        AABB box = player.getBoundingBox().inflate(reach + 1.0);
        List<Monster> found = mc.level.getEntitiesOfClass(Monster.class, box);
        int n = 0;
        for (int k = 0; k < found.size() && n < CAP; k++) {
            Monster m = found.get(k);
            mobs[n] = m;
            distSq[n] = player.distanceToSqr(m);
            health[n] = m.getHealth();
            n++;
        }
        int i = AfkGrinderRules.pickTarget(hostile, distSq, health, n, reach * reach);
        Monster pick = i >= 0 ? mobs[i] : null;
        java.util.Arrays.fill(mobs, 0, n, null);
        return pick;
    }

    private int pickWeapon(ClientLevel level, LocalPlayer player) {
        if (mendingLevel != level) {
            mendingLevel = level;
            mendingHolder = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.MENDING);
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().items.get(i);
            boolean w = s.getItem() instanceof SwordItem || s.getItem() instanceof AxeItem;
            weapon[i] = w;
            damage[i] = s.getDamageValue();
            maxDamage[i] = s.getMaxDamage();
            mending[i] = w && EnchantmentHelper.getItemEnchantmentLevel(mendingHolder, s) > 0;
            attack[i] = !w ? 0f : (s.getItem() instanceof SwordItem ? 1f : 0.5f)
                    + (s.getItem() instanceof TieredItem t ? t.getTier().getAttackDamageBonus() : 0f);
        }
        return AfkGrinderRules.weaponSlot(weapon, damage, maxDamage, mending, attack, 9,
                player.getInventory().selected, lowDurability);
    }

    @Override
    protected void stopWork() {
        target = null;
        status = "";
        super.stopWork();
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        super.readSettings(p);
        reach = Math.max(1.0, Math.min(3.0, p.getDouble(ID, "reach", 3.0)));
        lowDurability = Math.max(0.01, Math.min(0.5, p.getDouble(ID, "low_durability", AfkGrinderRules.LOW_DURABILITY)));
        stallMs = Math.max(100, Math.min(5000, p.getInt(ID, "stall_ms", (int) AfkGrinderRules.STALL_MS)));
        swapWeapons = p.getBool(ID, "swap_weapons", true);
    }

    @Override
    public String displayName() {
        return "AFK Grinder";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.decimal("reach", "Reach", 3.0, 1.0, 3.0, "", "Hit mobs within this distance"),
                SettingSpec.decimal("low_durability", "Swap below durability", AfkGrinderRules.LOW_DURABILITY, 0.01,
                        0.5, "", "Swap to a backup weapon (mending first) below this fraction of durability"),
                SettingSpec.integer("stall_ms", "Stall pause (ms)", (int) AfkGrinderRules.STALL_MS, 100, 5000, "",
                        "No swings while the server heartbeat is this late"),
                SettingSpec.bool("swap_weapons", "Swap weapons", true, "",
                        "Keep a weapon with durability in hand; stop instead of breaking the last one"));
    }
}
