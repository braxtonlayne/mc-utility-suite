package com.utilitysuite.combat.coach;

import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.CombatWorld;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.ShieldBreakSequencer;
import com.utilitysuite.combat.assistant.SpacingController;
import com.utilitysuite.combat.assistant.SustainPlanner;
import com.utilitysuite.combat.assistant.SwingGate;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.NoticeKind;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Overlay-only fight coach. Runs the assistant's own decision code (spacing band, cooldown readiness, shield-break
 * opportunity, retreat) for the current opponent and publishes a {@link CoachReadout} for the HUD. Requests no
 * leases and never touches input. Draws nothing while Clean-Screen is on or when no opponent is nearby.
 * <p>
 * Opponent: the assistant's target while engaged; otherwise the recent confirmed opponent; otherwise the living,
 * non-ally entity under the crosshair within {@value #CROSSHAIR_RANGE} blocks. Allocation free per tick.
 * <p>
 * Also reads the opponent's attack recharge into the readout, and counts each fight in a {@link FightTally}: when a
 * fight ends, one "Post-fight summary" notice (landed vs taken, crit rate, average spacing), never under Clean-Screen.
 */
public final class FightCoach implements IModule, IConfigurable {

    public static final String ID = "fight_coach";
    static final double OPPONENT_RANGE = 8.0;
    static final double CROSSHAIR_RANGE = 6.0;
    public static final String SHOW_RANGE = "show_range";
    public static final String SHOW_SWING = "show_swing";
    public static final String SHOW_DANGER = "show_danger";
    public static final String POST_SUMMARY = "post_summary";

    private final CombatWorld world;
    private final CombatAssistant assistant;
    private final AllyRoster roster;
    private final ThreatSource threats;
    private final BooleanSupplier cleanScreen;
    private final CombatSnapshot snap = new CombatSnapshot();
    private final CoachReadout readout = new CoachReadout();

    private boolean enabled;
    private boolean showRange = true;
    private boolean showSwing = true;
    private boolean showDanger = true;
    private boolean postSummary = true;
    private float lastHealth = -1f;
    private float lastHitDamage;
    private final FightTally tally = new FightTally();
    /** Coach tick counter, and the opponent's last swing seen by the coach (id, tick, cooldown period in ticks). */
    private long coachTick;
    private int oppSwingId = -1;
    private long oppSwingTick;
    private double oppPeriod = OpponentModel.DEFAULT_COOLDOWN_TICKS;

    public FightCoach(CombatWorld world, CombatAssistant assistant, AllyRoster roster, ThreatSource threats,
                      BooleanSupplier cleanScreen) {
        this.world = world;
        this.assistant = assistant;
        this.roster = roster;
        this.threats = threats;
        this.cleanScreen = cleanScreen;
    }

    public CoachReadout readout() {
        return readout;
    }

    @Override
    public void onTick() {
        readout.clear();
        coachTick++;
        // the fight tally keeps counting under Clean-Screen (only the summary notice is dropped then)
        int target = pickOpponent();
        if (target < 0 || !world.sample(target, snap)) {
            if (tally.absent()) {
                endFight();
            }
            return;
        }
        CombatSnapshot s = snap;
        CombatSettings cfg = assistant.config();
        float eff = s.effectiveHealth();
        if (lastHealth >= 0f && eff < lastHealth - 0.01f) {
            lastHitDamage = lastHealth - eff;
        }
        lastHealth = eff;
        if (s.targetSwingStarted) {
            oppSwingId = target;
            oppSwingTick = coachTick;
            oppPeriod = swingPeriod(s.targetAttackSpeed);
        }
        if (tally.observe(target, s.horizontalDistance(), s.attackStrength,
                SwingGate.ready(s.attackStrength) && s.fallingForCrit(), s.targetHurtTime, eff,
                s.targetSwingStarted)) {
            endFight();
        }
        if (cleanScreen.getAsBoolean()) {
            return;
        }

        readout.visible = true;
        readOpponentCooldown(target);
        readout.range = showRange ? SpacingController.classify(s.horizontalDistance(), cfg.spacingMin,
                cfg.spacingMax) : 0;
        readout.swingReady = showSwing && SwingGate.ready(s.attackStrength);
        readout.critReady = readout.swingReady && s.fallingForCrit();
        readout.shieldBreak = ShieldBreakSequencer.opportunity(s);
        SustainPlanner.Action a = SustainPlanner.decide(s, cfg.lowHealth, lastHitDamage, cfg.totems, cfg.gapples);
        readout.retreat = a == SustainPlanner.Action.RETREAT || a == SustainPlanner.Action.EAT;
        if (showDanger) {
            threats.evaluate();
            readout.danger = threats.level();
            readout.dangerYawDeg = threats.relativeYawDeg();
        }
    }

    /**
     * The opponent's attack recharge into the readout. While the assistant is engaged with it, its
     * {@link OpponentModel} is the source (the assistant samples first each tick, so the swing edge is its); otherwise
     * the coach's own read of the last swing seen. Never seen swinging counts as ready.
     */
    private void readOpponentCooldown(int target) {
        if (assistant.state() == CombatState.ENGAGED && assistant.targetId() == target) {
            FightContext ctx = assistant.stageContext();
            OpponentModel om = ctx.opponents();
            long now = ctx.clock();
            readout.oppCharge01 = om.attackCharge(target, now);
            readout.oppReadyIn = Math.max(0, om.attackReadyIn(target, now));
            return;
        }
        if (oppSwingId != target) {
            return; // cleared: ready
        }
        long elapsed = coachTick - oppSwingTick;
        readout.oppCharge01 = chargeOf(elapsed, oppPeriod);
        readout.oppReadyIn = readyIn(elapsed, oppPeriod);
    }

    /** Cooldown period in ticks for an attack speed (attacks per second), clamped like {@link OpponentModel}. */
    static double swingPeriod(double attackSpeed) {
        return 20.0 / Math.max(OpponentModel.MIN_ATTACK_SPEED, Math.min(OpponentModel.MAX_ATTACK_SPEED, attackSpeed));
    }

    static float chargeOf(long elapsed, double period) {
        double c = period <= 0.0 ? 1.0 : elapsed / period;
        return (float) (c < 0.0 ? 0.0 : Math.min(c, 1.0));
    }

    static int readyIn(long elapsed, double period) {
        double remaining = period - elapsed;
        return remaining <= 1.0e-9 ? 0 : (int) Math.ceil(remaining - 1.0e-9);
    }

    /** A fight just ended: one summary notice (setting on, enough happened, not under Clean-Screen), then reset. */
    private void endFight() {
        if (postSummary && tally.worthPosting() && !cleanScreen.getAsBoolean()) {
            Services.visuals().notices().post(NoticeKind.INFO, tally.formatLine(), null);
        }
        tally.clearFight();
    }

    private int pickOpponent() {
        if (assistant.state() == CombatState.ENGAGED && assistant.targetId() >= 0) {
            return assistant.targetId();
        }
        int recent = assistant.recentOpponentId();
        if (recent >= 0 && world.distanceSqToLocal(recent) <= OPPONENT_RANGE * OPPONENT_RANGE
                && !roster.isNeverTarget(world.uuidOf(recent))) {
            return recent;
        }
        int aimed = world.crosshairEntityId();
        if (aimed >= 0 && world.isLivingAlive(aimed)
                && world.distanceSqToLocal(aimed) <= CROSSHAIR_RANGE * CROSSHAIR_RANGE
                && !roster.isNeverTarget(world.uuidOf(aimed))) {
            return aimed;
        }
        return -1;
    }

    // ---- IModule ----------------------------------------------------------------------------------------------------

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
            readout.clear();
            tally.reset();
            oppSwingId = -1;
        }
    }

    @Override
    public void onStopAll() {
        // Overlay only: nothing to stop.
    }

    @Override
    public void onWorldUnload() {
        readout.clear();
        lastHealth = -1f;
        lastHitDamage = 0f;
        tally.reset();
        oppSwingId = -1;
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        showRange = profile.getBool(ID, SHOW_RANGE, true);
        showSwing = profile.getBool(ID, SHOW_SWING, true);
        showDanger = profile.getBool(ID, SHOW_DANGER, true);
        postSummary = profile.getBool(ID, POST_SUMMARY, true);
    }

    // ---- IConfigurable ----------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Fight Coach";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(SHOW_RANGE, "Range indicator", true, "",
                        "Small bar under the crosshair: too close / in range / too far."),
                SettingSpec.bool(SHOW_SWING, "Swing-ready tick", true, "",
                        "Marker when the weapon is fully charged (gold while a swing would crit)."),
                SettingSpec.bool(SHOW_DANGER, "Danger warning", true, "",
                        "Warn about incoming projectiles and nearby explosions."),
                SettingSpec.bool(POST_SUMMARY, "Post-fight summary", true, "",
                        "When a fight ends, one notice: hits landed vs taken, crit rate and average spacing."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
