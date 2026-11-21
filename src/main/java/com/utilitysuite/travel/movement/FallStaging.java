package com.utilitysuite.travel.movement;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.travel.LeaseSlot;
import com.utilitysuite.travel.mobility.ElytraSwapPlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Early staging (idea A2), the in-game half of {@link FallRiskEstimator}: on the ground near a harmful drop, when the
 * risk rises (sprinting at the edge, knockback, a fight on a ledge), PREPARE the clutch: move the best clutch item from
 * the main inventory into an empty hotbar slot (one SWAP click through the core clicker) and preselect its slot. The
 * camera never moves here; the clutch turns only once airborne. When the risk passes and the user has not changed the
 * slot, the previous slot comes back. Manual hotbar input wins (the core preempts the lease).
 * <p>
 * Sampled every {@value #SAMPLE_TICKS} ticks on the ground: five short rays (motion and the four sides) of at most
 * {@value #RAY_STEPS} half-block steps, each with one budgeted depth scan. Allocation free. Client thread only.
 */
final class FallStaging {

    static final int SAMPLE_TICKS = 4;
    static final int RAY_STEPS = 6;
    static final int MAX_DEPTH = 24;
    private static final double[] RAY_X = {1, -1, 0, 0};
    private static final double[] RAY_Z = {0, 0, 1, -1};

    private final FallRiskEstimator risk = new FallRiskEstimator();
    private final LeaseSlot slot = new LeaseSlot("fall_staging", Services::arbiter);
    private final LeaseRequest request = slot.request(ControlPriority.TRAVEL, 0, ControlChannel.HOTBAR);
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private int clock;
    private int previousSlot = -1;
    private int stagedSlot = -1;
    private double edge;
    private double depth;
    boolean enabled = true;

    void tick(Minecraft mc, LocalPlayer p, Item[] clutchItems) {
        if (!enabled || !p.onGround() || mc.screen != null) {
            if (!enabled || mc.screen != null) {
                unstage(p);
            }
            return;
        }
        if (++clock < SAMPLE_TICKS) {
            return;
        }
        clock = 0;
        measure(mc.level, p);
        double vx = p.getDeltaMovement().x;
        double vz = p.getDeltaMovement().z;
        double speed = Math.sqrt(vx * vx + vz * vz);
        int sinceHurt = p.hurtTime > 0 ? 10 - p.hurtTime : 1000;
        LivingEntity attacker = p.getLastHurtByMob();
        int hostiles = attacker != null && attacker.isAlive() && attacker.distanceToSqr(p) < 16.0
                && p.tickCount - p.getLastHurtByMobTimestamp() < 100 ? 1 : 0;
        boolean staged = risk.update(edge, depth, edgeSpeed(speed), sinceHurt, hostiles);
        if (staged) {
            stage(p, clutchItems);
        } else {
            unstage(p);
        }
    }

    /** Speed toward the nearest edge: the motion ray found it when the motion is fast; otherwise standing still. */
    private double edgeSpeed(double speed) {
        return motionRayHit ? speed : 0.0;
    }

    private boolean motionRayHit;

    /** Nearest harmful drop over five rays; fills {@link #edge} and {@link #depth}. */
    private void measure(Level level, LocalPlayer p) {
        edge = 99.0;
        depth = 0.0;
        motionRayHit = false;
        double vx = p.getDeltaMovement().x;
        double vz = p.getDeltaMovement().z;
        double len = Math.sqrt(vx * vx + vz * vz);
        if (len > 0.03) {
            motionRayHit = ray(level, p, vx / len, vz / len);
        }
        for (int i = 0; i < 4; i++) {
            ray(level, p, RAY_X[i], RAY_Z[i]);
        }
    }

    private boolean ray(Level level, LocalPlayer p, double dx, double dz) {
        int fy = p.getBlockY();
        for (int s = 1; s <= RAY_STEPS; s++) {
            double d = s * 0.5;
            if (d >= edge) {
                return false;
            }
            int x = (int) Math.floor(p.getX() + dx * d);
            int z = (int) Math.floor(p.getZ() + dz * d);
            if (!level.isLoaded(pos.set(x, fy, z))) {
                return false;
            }
            if (!level.getBlockState(pos.set(x, fy, z)).getCollisionShape(level, pos).isEmpty()) {
                return false; // a wall: no edge this way
            }
            if (!level.getBlockState(pos.set(x, fy - 1, z)).getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            int deep = 1;
            int min = level.getMinBuildHeight();
            while (deep < MAX_DEPTH && fy - 1 - deep >= min
                    && level.getBlockState(pos.set(x, fy - 1 - deep, z)).getCollisionShape(level, pos).isEmpty()
                    && level.getFluidState(pos).isEmpty()) {
                deep++;
            }
            if (deep > FallRiskEstimator.SAFE_DROP) {
                edge = d;
                depth = deep;
                return true;
            }
        }
        return false;
    }

    private void stage(LocalPlayer p, Item[] items) {
        if (stagedSlot >= 0) {
            return;
        }
        ControlLease l = slot.ensure(request);
        if (l == null) {
            return;
        }
        Inventory inv = p.getInventory();
        int hot = -1;
        int main = -1;
        for (Item item : items) {
            hot = FallClutch.hotbarSlot(inv, item);
            if (hot >= 0) {
                break;
            }
            for (int i = 9; i < 36 && main < 0; i++) {
                if (inv.getItem(i).getItem() == item) {
                    main = i;
                }
            }
            if (main >= 0) {
                break;
            }
        }
        if (hot < 0 && main >= 0) {
            int empty = -1;
            for (int i = 0; i < 9; i++) {
                if (inv.getItem(i).isEmpty()) {
                    empty = i;
                    break;
                }
            }
            if (empty < 0) {
                return; // a full hotbar is left as the user arranged it
            }
            if (Services.clicker().queueClick(l, ElytraSwapPlanner.menuSlot(main), empty,
                    IContainerClicker.Click.SWAP)) {
                hot = empty;
            }
        }
        if (hot < 0) {
            return;
        }
        previousSlot = inv.selected;
        stagedSlot = hot;
        if (inv.selected != hot) {
            Services.input().selectHotbarSlot(l, hot);
        }
    }

    private void unstage(LocalPlayer p) {
        if (stagedSlot < 0) {
            if (slot.active() != null) {
                slot.reset();
            }
            return;
        }
        ControlLease l = slot.active();
        if (l != null && p.getInventory().selected == stagedSlot && previousSlot >= 0
                && previousSlot != stagedSlot) {
            Services.input().selectHotbarSlot(l, previousSlot);
        }
        stagedSlot = -1;
        previousSlot = -1;
        risk.reset();
        slot.reset();
    }

    /** Airborne: the clutch takes over; the staging lease ends (the slot stays selected for the clutch). */
    void handOff() {
        stagedSlot = -1;
        previousSlot = -1;
        slot.reset();
    }

    void release() {
        stagedSlot = -1;
        previousSlot = -1;
        risk.reset();
        slot.reset();
    }

    boolean staged() {
        return stagedSlot >= 0;
    }
}
