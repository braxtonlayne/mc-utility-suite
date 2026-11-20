package com.utilitysuite.combat.coach;

import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.CombatWorld;
import com.utilitysuite.combat.assistant.ShieldBreakSequencer;
import com.utilitysuite.combat.assistant.SpacingController;
import com.utilitysuite.combat.assistant.SustainPlanner;
import com.utilitysuite.combat.assistant.SwingGate;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Overlay-only fight coach. Runs the assistant's own decision code (spacing band, cooldown readiness, shield-break
 * opportunity, retreat) for the current opponent and publishes a {@link CoachReadout} for the HUD. Requests no
 * leases and never touches input. Draws nothing while Clean-Screen is on or when no opponent is nearby.
 * <p>
 * Opponent: the assistant's target while engaged; otherwise the recent confirmed opponent; otherwise the living,
 * non-ally entity under the crosshair within {@value #CROSSHAIR_RANGE} blocks. Allocation free per tick.
 */
public final class FightCoach implements IModule, IConfigurable {

    public static final String ID = "fight_coach";
    static final double OPPONENT_RANGE = 8.0;
    static final double CROSSHAIR_RANGE = 6.0;
    public static final String SHOW_RANGE = "show_range";
    public static final String SHOW_SWING = "show_swing";
    public static final String SHOW_DANGER = "show_danger";

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
    private float lastHealth = -1f;
    private float lastHitDamage;

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
        if (cleanScreen.getAsBoolean()) {
            return;
        }
        int target = pickOpponent();
        if (target < 0 || !world.sample(target, snap)) {
            return;
        }
        CombatSnapshot s = snap;
        CombatSettings cfg = assistant.config();
        float eff = s.effectiveHealth();
        if (lastHealth >= 0f && eff < lastHealth - 0.01f) {
            lastHitDamage = lastHealth - eff;
        }
        lastHealth = eff;

        readout.visible = true;
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
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        showRange = profile.getBool(ID, SHOW_RANGE, true);
        showSwing = profile.getBool(ID, SHOW_SWING, true);
        showDanger = profile.getBool(ID, SHOW_DANGER, true);
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
                        "Warn about incoming projectiles and nearby explosions."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
