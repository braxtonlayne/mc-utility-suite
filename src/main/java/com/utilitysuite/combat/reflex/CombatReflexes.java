package com.utilitysuite.combat.reflex;

import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.mc.MinecraftCombatWorld;
import com.utilitysuite.combat.coach.ThreatMath;
import com.utilitysuite.combat.coach.ThreatTracker;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.EntityHitResult;

import java.util.EnumSet;
import java.util.List;

/**
 * Defensive combat reflexes on top of the {@link ThreatTracker}: the shield reflex (A5), projectile dodge and the
 * creeper/TNT back-off (A4), plus the danger-model feed (PROJECTILE, EXPLOSION, MELEE).
 * <p>
 * Runs from the client tick, not the module tick, so it also works while the module list ticks nothing. Gating: each
 * reflex runs when this module and its setting are on and either the Combat Assistant is on, or the assistant is off
 * and the autopilot allows that reflex ({@link IAutopilot#mayAct} is asked before every action, and the action is
 * noted with constant reason strings).
 * <p>
 * Input: while the assistant is engaged its COMBAT lease is used (no preemption of the fight); otherwise an
 * autopilot chain lease, otherwise a short EMERGENCY lease of only the channels needed. Keys only, through
 * {@link IInputSmoother}. The camera is turned only by the less-legit "turn to block" option, smoothed and capped by
 * the core. Manual input wins: nothing starts while the user holds the channel. Allocation free per tick (a
 * {@link RotationTarget} only when a turn starts).
 */
public final class CombatReflexes implements IModule, IConfigurable, LeaseListener {

    public static final String ID = "combat_reflexes";
    public static final String SHIELD = "shield";
    public static final String DODGE = "dodge";
    public static final String ESCAPE = "threat_escape";
    public static final String SHIELD_TURN = "shield_turn";
    public static final String FRONT_CONE = "shield_front_cone";

    static final String REASON_SHIELD = "shield raised against an incoming hit";
    static final String REASON_DODGE = "strafe out of a projectile's path";
    static final String REASON_ESCAPE = "back off from a lit fuse";
    static final String DECLINED_BUSY = "hands busy";

    private final CombatAssistant assistant;
    private final ThreatTracker threats;
    private final MinecraftCombatWorld world;
    private final DodgePlanner planner = new DodgePlanner();
    private final LeaseRequest interactRequest;
    private final LeaseRequest turnRequest;
    private final LeaseRequest moveRequest;

    private boolean enabled = true;
    private boolean shieldOn = true;
    private boolean dodgeOn = true;
    private boolean escapeOn = true;
    private boolean shieldTurn;
    private double frontCone = ShieldDecision.DEFAULT_FRONT_CONE_DEG;

    private ControlLease ownInteract;
    private ControlLease ownMove;
    private ControlLease shieldLease;
    private ControlLease escapeLease;
    private boolean raised;
    private boolean raisedViaAutopilot;
    private boolean escaping;
    private boolean escapeViaAutopilot;
    private SimKey escKey;
    private SimKey escKey2;
    private boolean escSprint;
    private int dodgeCooldown;
    private float turnYaw = Float.NaN;
    private boolean published;

    private int shieldRaises;
    private int dodges;
    private int escapes;

    public CombatReflexes(CombatAssistant assistant, ThreatTracker threats, MinecraftCombatWorld world) {
        this.assistant = assistant;
        this.threats = threats;
        this.world = world;
        this.interactRequest = new LeaseRequest(ID, ControlPriority.EMERGENCY,
                EnumSet.of(ControlChannel.WORLD_INTERACT), 0, this);
        this.turnRequest = new LeaseRequest(ID, ControlPriority.EMERGENCY,
                EnumSet.of(ControlChannel.WORLD_INTERACT, ControlChannel.CAMERA), 0, this);
        this.moveRequest = new LeaseRequest(ID, ControlPriority.EMERGENCY, EnumSet.of(ControlChannel.MOVEMENT), 0,
                this);
    }

    // =================================================================================================================
    // tick
    // =================================================================================================================

    /** Client tick (post). Cheap early-out when nothing is armed and nothing is held. */
    public void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        IAutopilot ap = Services.autopilotOrNull();
        boolean assistantOn = assistant.isEnabled();
        boolean apOn = ap != null && ap.isOn();
        boolean shieldArmed = enabled && shieldOn && (assistantOn || apOn && ap.allows(IAutopilot.Reflex.SHIELD));
        boolean dodgeArmed = enabled && dodgeOn && (assistantOn || apOn && ap.allows(IAutopilot.Reflex.DODGE));
        boolean escapeArmed = enabled && escapeOn
                && (assistantOn || apOn && ap.allows(IAutopilot.Reflex.THREAT_ESCAPE));
        boolean feed = ap != null && (apOn || assistantOn);
        if (p == null || mc.level == null) {
            return;
        }
        if (!shieldArmed && !dodgeArmed && !escapeArmed && !feed) {
            if (raised || escaping || published) {
                standDown(ap);
            }
            return;
        }
        threats.evaluate();
        if (feed) {
            threats.publish(Services.danger());
            published = true;
        } else if (published) {
            ThreatTracker.clearPublished(Services.danger());
            published = false;
        }
        if (dodgeCooldown > 0) {
            dodgeCooldown--;
        }
        IActionArbiter arbiter = Services.arbiter();
        if (arbiter.isAutomationStopped()) {
            return;
        }
        tickShield(p, mc, ap, assistantOn, shieldArmed, arbiter);
        tickEscape(p, ap, assistantOn, escapeArmed, arbiter);
        if (!escaping && !raised) {
            tickDodge(p, ap, assistantOn, dodgeArmed, arbiter);
        }
        releaseIdleLeases();
    }

    private void tickShield(LocalPlayer p, Minecraft mc, IAutopilot ap, boolean assistantOn, boolean armed,
                            IActionArbiter arbiter) {
        if (!armed) {
            if (raised) {
                lowerShield(ap);
            }
            return;
        }
        // most urgent threat that is worth a block
        int ticks = Integer.MAX_VALUE;
        float dmg = 0f;
        float bearing = Float.NaN;
        if (threats.projTicks < ticks && threats.projDamage >= ShieldDecision.MIN_DAMAGE) {
            ticks = threats.projTicks;
            dmg = threats.projDamage;
            bearing = threats.projBearing;
        }
        if (threats.blastTicks < ticks) {
            ticks = threats.blastTicks;
            dmg = threats.blastDamage;
            bearing = threats.blastBearing;
        }
        if (threats.meleeTicks < ticks) {
            ticks = threats.meleeTicks;
            dmg = threats.meleeDamage;
            bearing = threats.meleeBearing;
        }
        float rel = Float.isNaN(bearing) ? Float.NaN : wrap(bearing - p.getYRot());
        boolean wantSwing = p.getAttackStrengthScale(0.5f) >= 0.95f && mc.hitResult instanceof EntityHitResult ehr
                && ehr.getEntity() instanceof LivingEntity le && le.isAlive();
        boolean usable = shieldUsable(p);
        ShieldDecision.Act act = ShieldDecision.decide(usable, raised, ticks, dmg, rel, frontCone, shieldTurn,
                wantSwing);
        switch (act) {
            case RAISE, TURN_AND_RAISE -> {
                if (p.isUsingItem() || arbiter.isManualOverrideActive(ControlChannel.WORLD_INTERACT)) {
                    return; // eating, drawing a bow, or the user's own right click: theirs wins
                }
                boolean turn = act == ShieldDecision.Act.TURN_AND_RAISE;
                boolean viaAp = !assistantOn;
                if (viaAp && !ap.mayAct(IAutopilot.Reflex.SHIELD, ShieldDecision.RAISE_TICKS, dmg)) {
                    return;
                }
                ControlLease l = interactLease(ap, turn, viaAp);
                if (l == null) {
                    return;
                }
                if (turn && l.covers(ControlChannel.CAMERA)) {
                    float yaw = bearing;
                    if (Float.isNaN(turnYaw) || Math.abs(wrap(yaw - turnYaw)) > 2f) {
                        turnYaw = yaw;
                        Services.input().setRotationTarget(l, new RotationTarget(yaw, p.getXRot(),
                                RotationMode.VISIBLE, 1f));
                    }
                }
                if (!raised && Services.input().setKeyHeld(l, SimKey.USE, true)) {
                    raised = true;
                    raisedViaAutopilot = viaAp;
                    shieldLease = l;
                    shieldRaises++;
                    if (viaAp) {
                        ap.noteActed(IAutopilot.Reflex.SHIELD, REASON_SHIELD);
                    }
                }
            }
            case LOWER -> lowerShield(ap);
            default -> {
            }
        }
    }

    private void lowerShield(IAutopilot ap) {
        ControlLease l = shieldLease;
        if (l != null && l.isActive()) {
            Services.input().setKeyHeld(l, SimKey.USE, false);
            if (!Float.isNaN(turnYaw) && l.covers(ControlChannel.CAMERA) && l == ownInteract) {
                Services.input().clearRotation(l);
            }
        }
        raised = false;
        shieldLease = null;
        turnYaw = Float.NaN;
        if (raisedViaAutopilot && ap != null) {
            ap.noteDone(IAutopilot.Reflex.SHIELD);
        }
        raisedViaAutopilot = false;
    }

    private void tickEscape(LocalPlayer p, IAutopilot ap, boolean assistantOn, boolean armed, IActionArbiter arbiter) {
        boolean want = armed && threats.blastTicks != Integer.MAX_VALUE
                && !arbiter.isManualOverrideActive(ControlChannel.MOVEMENT);
        if (want) {
            float yaw = p.getYRot();
            want = probeHazards(yaw) && planner.planEscape(threats.blastRx, threats.blastRz, threats.blastTicks,
                    threats.blastRadius, yaw, hazF, hazB, hazL, hazR);
        }
        if (!want) {
            if (escaping) {
                stopEscape(ap);
            }
            return;
        }
        boolean viaAp = !assistantOn;
        if (!escaping && viaAp && !ap.mayAct(IAutopilot.Reflex.THREAT_ESCAPE, planner.tapTicks(),
                threats.blastDamage)) {
            return;
        }
        ControlLease l = escaping && escapeLease != null && escapeLease.isActive() ? escapeLease : moveLease(ap,
                IAutopilot.Reflex.THREAT_ESCAPE, viaAp);
        if (l == null) {
            return;
        }
        if (!escaping) {
            escaping = true;
            escapeViaAutopilot = viaAp;
            escapes++;
            if (viaAp) {
                ap.noteActed(IAutopilot.Reflex.THREAT_ESCAPE, REASON_ESCAPE);
            }
        }
        escapeLease = l;
        holdEscapeKeys(l, planner.key(), planner.key2(), planner.sprint());
    }

    private void holdEscapeKeys(ControlLease l, SimKey k1, SimKey k2, boolean sprint) {
        IInputSmoother in = Services.input();
        if (escKey != null && escKey != k1 && escKey != k2) {
            in.setKeyHeld(l, escKey, false);
        }
        if (escKey2 != null && escKey2 != k1 && escKey2 != k2) {
            in.setKeyHeld(l, escKey2, false);
        }
        if (k1 != null && k1 != escKey && k1 != escKey2) {
            in.setKeyHeld(l, k1, true);
        }
        if (k2 != null && k2 != escKey && k2 != escKey2) {
            in.setKeyHeld(l, k2, true);
        }
        if (sprint != escSprint) {
            in.setKeyHeld(l, SimKey.SPRINT, sprint);
        }
        escKey = k1;
        escKey2 = k2;
        escSprint = sprint;
    }

    private void stopEscape(IAutopilot ap) {
        ControlLease l = escapeLease;
        if (l != null && l.isActive()) {
            holdEscapeKeys(l, null, null, false);
        }
        escKey = null;
        escKey2 = null;
        escSprint = false;
        escaping = false;
        escapeLease = null;
        if (escapeViaAutopilot && ap != null) {
            ap.noteDone(IAutopilot.Reflex.THREAT_ESCAPE);
        }
        escapeViaAutopilot = false;
    }

    private void tickDodge(LocalPlayer p, IAutopilot ap, boolean assistantOn, boolean armed, IActionArbiter arbiter) {
        if (!armed || dodgeCooldown > 0 || threats.projTicks == Integer.MAX_VALUE
                || threats.projDamage < ShieldDecision.MIN_DAMAGE
                || arbiter.isManualOverrideActive(ControlChannel.MOVEMENT)) {
            return;
        }
        float yaw = p.getYRot();
        if (!probeHazards(yaw) || !planner.planDodge(threats.projRx, threats.projRy, threats.projRz,
                threats.projVx, threats.projVy, threats.projVz, yaw, hazF, hazB, hazL, hazR)
                || planner.startInTicks() > 0) {
            return;
        }
        boolean viaAp = !assistantOn;
        if (viaAp && !ap.mayAct(IAutopilot.Reflex.DODGE, planner.tapTicks(), threats.projDamage)) {
            return;
        }
        ControlLease l = moveLease(ap, IAutopilot.Reflex.DODGE, viaAp);
        if (l == null) {
            return;
        }
        if (Services.input().scheduleKeyTap(l, planner.key(), planner.tapTicks(), planner.tapTicks() + 1)) {
            dodges++;
            dodgeCooldown = planner.tapTicks() + 2;
            if (viaAp) {
                ap.noteActed(IAutopilot.Reflex.DODGE, REASON_DODGE);
                ap.noteDone(IAutopilot.Reflex.DODGE); // a tap finishes on its own
            }
        }
    }

    // ---- hazards around the player in view-relative directions (4 probes, only while a threat is live) -------------
    private boolean hazF;
    private boolean hazB;
    private boolean hazL;
    private boolean hazR;

    private boolean probeHazards(float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        hazF = world.hazardAtOffset(fx, fz);
        hazB = world.hazardAtOffset(-fx, -fz);
        hazL = world.hazardAtOffset(fz, -fx);
        hazR = world.hazardAtOffset(-fz, fx);
        return true;
    }

    // ---- leases -------------------------------------------------------------------------------------------------

    private ControlLease interactLease(IAutopilot ap, boolean withCamera, boolean viaAp) {
        ControlLease a = assistant.lease();
        if (a != null && a.covers(ControlChannel.WORLD_INTERACT) && (!withCamera || a.covers(ControlChannel.CAMERA))) {
            return a;
        }
        if (viaAp && ap != null) {
            ControlLease c = ap.chainLease(IAutopilot.Reflex.SHIELD, ID);
            if (c != null && c.isActive() && c.covers(ControlChannel.WORLD_INTERACT)
                    && (!withCamera || c.covers(ControlChannel.CAMERA))) {
                return c;
            }
        }
        if (ownInteract != null && ownInteract.isActive()
                && (!withCamera || ownInteract.covers(ControlChannel.CAMERA))) {
            return ownInteract;
        }
        if (ownInteract != null) {
            Services.arbiter().release(ownInteract);
            ownInteract = null;
        }
        ownInteract = Services.arbiter().request(withCamera ? turnRequest : interactRequest).orElse(null);
        return ownInteract;
    }

    private ControlLease moveLease(IAutopilot ap, IAutopilot.Reflex reflex, boolean viaAp) {
        ControlLease a = assistant.lease();
        if (a != null && a.covers(ControlChannel.MOVEMENT)) {
            return a;
        }
        if (viaAp && ap != null) {
            ControlLease c = ap.chainLease(reflex, ID);
            if (c != null && c.isActive() && c.covers(ControlChannel.MOVEMENT)) {
                return c;
            }
        }
        if (ownMove != null && ownMove.isActive()) {
            return ownMove;
        }
        ownMove = Services.arbiter().request(moveRequest).orElse(null);
        return ownMove;
    }

    /** Own leases are held only while a shield is up, an escape runs or a dodge tap is in flight. */
    private void releaseIdleLeases() {
        if (ownInteract != null && !raised) {
            ControlLease l = ownInteract;
            ownInteract = null;
            if (l.isActive()) {
                Services.arbiter().release(l);
            }
        }
        if (ownMove != null && !escaping && dodgeCooldown == 0) {
            ControlLease l = ownMove;
            ownMove = null;
            if (l.isActive()) {
                Services.arbiter().release(l);
            }
        }
    }

    private void standDown(IAutopilot ap) {
        if (raised) {
            lowerShield(ap);
        }
        if (escaping) {
            stopEscape(ap);
        }
        dodgeCooldown = 0;
        releaseIdleLeases();
        if (published) {
            ThreatTracker.clearPublished(Services.danger());
            published = false;
        }
    }

    @Override
    public void onPreempted(ControlLease lease, LeaseEndReason reason, String byModuleId) {
        if (lease == ownInteract) {
            ownInteract = null;
        }
        if (lease == ownMove) {
            ownMove = null;
        }
        if (lease == shieldLease) {
            raised = false; // the core released the key with the lease
            shieldLease = null;
            turnYaw = Float.NaN;
        }
        if (lease == escapeLease) {
            escaping = false;
            escapeLease = null;
            escKey = null;
            escKey2 = null;
            escSprint = false;
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /**
     * A shield that a use-key press would raise: in the main hand, or in the offhand behind a main-hand item that
     * does nothing on use (a sword or axe; never food, a bow or a block, which would be used instead).
     */
    static boolean shieldUsable(LocalPlayer p) {
        ItemStack main = p.getItemInHand(InteractionHand.MAIN_HAND);
        if (main.getItem() instanceof ShieldItem) {
            return !p.getCooldowns().isOnCooldown(main.getItem());
        }
        ItemStack off = p.getItemInHand(InteractionHand.OFF_HAND);
        if (!(off.getItem() instanceof ShieldItem) || p.getCooldowns().isOnCooldown(off.getItem())) {
            return false;
        }
        return !(main.getItem() instanceof BlockItem) && main.getUseAnimation() == UseAnim.NONE;
    }

    static float wrap(float deg) {
        float d = deg % 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d <= -180f) {
            d += 360f;
        }
        return d;
    }

    public int shieldRaises() {
        return shieldRaises;
    }

    public int dodges() {
        return dodges;
    }

    public int escapes() {
        return escapes;
    }

    // =================================================================================================================
    // IModule / IConfigurable
    // =================================================================================================================

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
            standDown(Services.autopilotOrNull());
        }
    }

    /** Work happens in {@link #clientTick} so the reflexes also run while the Combat Assistant module is off. */
    @Override
    public void onTick() {
    }

    @Override
    public void onStopAll() {
        // Stop-All revoked every lease already (the core released the keys)
        ownInteract = null;
        ownMove = null;
        shieldLease = null;
        escapeLease = null;
        raised = false;
        escaping = false;
        escKey = null;
        escKey2 = null;
        escSprint = false;
        turnYaw = Float.NaN;
        dodgeCooldown = 0;
    }

    @Override
    public void onWorldUnload() {
        onStopAll();
        published = false;
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        shieldOn = profile.getBool(ID, SHIELD, true);
        dodgeOn = profile.getBool(ID, DODGE, true);
        escapeOn = profile.getBool(ID, ESCAPE, true);
        shieldTurn = profile.getBool(ID, SHIELD_TURN, false);
        frontCone = profile.getDouble(ID, FRONT_CONE, ShieldDecision.DEFAULT_FRONT_CONE_DEG);
    }

    @Override
    public String displayName() {
        return "Combat Reflexes";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(SHIELD, "Shield reflex", true, "",
                        "Raise a held shield just before an arrow, blast or melee hit from the front; lower it to swing."),
                SettingSpec.bool(DODGE, "Dodge projectiles", true, "",
                        "Tap a strafe key to step out of an arrow's path. Never steps off a ledge."),
                SettingSpec.bool(ESCAPE, "Back off from fuses", true, "",
                        "Walk away from a hissing creeper or lit TNT until outside the blast. The camera stays put."),
                SettingSpec.bool(SHIELD_TURN, "Turn to block", false, "",
                        "Less legit: turn (smoothly, speed-capped) to block hits from outside the view.").asAdvanced(),
                SettingSpec.decimal(FRONT_CONE, "Shield front cone", ShieldDecision.DEFAULT_FRONT_CONE_DEG, 30, 90, "",
                        "Half-angle in degrees a threat must be inside before the shield reacts.").asAdvanced());
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }

    static float relativeYaw(double dx, double dz, float viewYaw) {
        return ThreatMath.relativeYaw(dx, dz, viewYaw);
    }
}
