package com.utilitysuite.combat.trigger;

import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatWorld;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.util.EnumSet;
import java.util.List;

/**
 * Trigger assist: swings when the player's own crosshair already rests on a valid living target in reach and the
 * weapon is fully charged. No aim help and no rotation of any kind. The swing is an ATTACK key tap through the core
 * under a short WORLD_INTERACT lease, after a small humanizer reaction delay. Manual clicks win (the arbiter's manual
 * override on WORLD_INTERACT pauses it), and it stands aside while another module (the Combat Assistant) holds the
 * channel. Off by default. Allocation free per tick.
 */
public final class TriggerAssist implements IModule, IConfigurable, LeaseListener {

    public static final String ID = "trigger_assist";
    public static final String PLAYERS = "players";
    public static final String HOSTILES = "hostiles";
    public static final String PASSIVE = "passive";
    public static final String CRIT_WAIT = "wait_for_crit";
    /** A target farther than reach minus this is about to leave reach: never defer for a crit then. */
    static final double EDGE_MARGIN = 0.5;
    static final int REACTION_MEDIAN = 3;
    static final int REACTION_MIN = 1;
    static final int REACTION_MAX = 7;

    private final CombatWorld world;
    private final AllyRoster roster;
    private final TriggerGate gate = new TriggerGate();
    private final LeaseRequest request;

    private boolean enabled;
    private boolean players = true;
    private boolean hostiles = true;
    private boolean passive;
    private boolean critWait;
    private ControlLease lease;
    private int releaseIn;
    private int swings;

    public TriggerAssist(CombatWorld world, AllyRoster roster) {
        this.world = world;
        this.roster = roster;
        this.request = new LeaseRequest(ID, ControlPriority.COMBAT, EnumSet.of(ControlChannel.WORLD_INTERACT), 0,
                this);
    }

    @Override
    public void onTick() {
        if (lease != null && --releaseIn <= 0) {
            release();
        }
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            return;
        }
        IActionArbiter arbiter = Services.arbiter();
        boolean manual = arbiter.isManualOverrideActive(ControlChannel.WORLD_INTERACT);
        int id = -1;
        boolean living = false;
        boolean kind = false;
        boolean ally = false;
        boolean edge = false;
        if (mc.hitResult instanceof EntityHitResult ehr) {
            Entity e = ehr.getEntity();
            id = e.getId();
            living = e instanceof LivingEntity le && le.isAlive() && !le.isRemoved() && !(e instanceof ArmorStand);
            kind = e instanceof Player ? players : (e instanceof Enemy ? hostiles : passive);
            ally = e instanceof Player && roster.isNeverTarget(e.getUUID());
            if (critWait) {
                double dx = ehr.getLocation().x - p.getX();
                double dy = ehr.getLocation().y - (p.getY() + p.getEyeHeight());
                double dz = ehr.getLocation().z - p.getZ();
                double near = p.entityInteractionRange() - EDGE_MARGIN;
                edge = dx * dx + dy * dy + dz * dz > near * near;
            }
        }
        boolean canCrit = !p.isInWater() && !p.onClimbable() && !p.isPassenger()
                && !p.hasEffect(MobEffects.BLINDNESS);
        boolean hold = gate.critHold(critWait, p.onGround(), canCrit, p.getDeltaMovement().y, (float) p.fallDistance, edge);
        // vanilla only picks entities inside the interaction range, so a crosshair hit is in reach
        boolean valid = TriggerGate.valid(id, living, kind, ally, id >= 0, p.getAttackStrengthScale(0.5f),
                mc.gui.screen() != null, p.isUsingItem(), manual, hold) && !arbiter.isAutomationStopped()
                && !arbiter.isHeldBy(ControlChannel.WORLD_INTERACT, CombatAssistant.ID); // the assistant swings itself
        int delay = valid && gate.needsDelay(id)
                ? Services.humanizer().reactionTicks(REACTION_MEDIAN, REACTION_MIN, REACTION_MAX) : 0;
        if (!gate.tick(id, valid, delay)) {
            return;
        }
        ControlLease l = lease != null && lease.isActive() ? lease : arbiter.request(request).orElse(null);
        if (l == null) {
            return;
        }
        lease = l;
        if (Services.input().scheduleKeyTap(l, SimKey.ATTACK, 1, 1)) {
            swings++;
        }
        releaseIn = 3; // keep the lease until the tap has been pressed and released
    }


    private void release() {
        ControlLease l = lease;
        lease = null;
        if (l != null && l.isActive()) {
            Services.arbiter().release(l);
        }
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l == lease) {
            lease = null;
            gate.reset();
        }
    }

    public int swings() {
        return swings;
    }

    // ---- IModule / IConfigurable ----------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.COMBAT;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            release();
            gate.reset();
        }
    }

    @Override
    public void onStopAll() {
        lease = null;
        gate.reset();
    }

    @Override
    public void onWorldUnload() {
        lease = null;
        gate.reset();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        players = profile.getBool(ID, PLAYERS, true);
        hostiles = profile.getBool(ID, HOSTILES, true);
        passive = profile.getBool(ID, PASSIVE, false);
        critWait = profile.getBool(ID, CRIT_WAIT, false);
    }

    @Override
    public String displayName() {
        return "Trigger Assist";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(PLAYERS, "Players", true, "", "Swing at players under the crosshair (never allies)."),
                SettingSpec.bool(HOSTILES, "Hostile mobs", true, "", "Swing at hostile mobs under the crosshair."),
                SettingSpec.bool(PASSIVE, "Passive mobs", false, "", "Also swing at animals and other passive mobs."),
                SettingSpec.bool(CRIT_WAIT, "Wait for falling crit", false, "",
                        "While airborne and still rising, hold the swing until the fall starts so it crits. Capped at "
                                + TriggerGate.MAX_CRIT_DEFER + " ticks and skipped when the target is at the edge of reach."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
