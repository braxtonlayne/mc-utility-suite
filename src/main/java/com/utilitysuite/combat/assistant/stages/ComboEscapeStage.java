package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.assistant.SpacingController;
import com.utilitysuite.combat.tactics.ComboEscape;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;

import java.util.List;

/**
 * Getting out of a combo against a player (upgrade pass 2.7), as a {@link Slot#MOVEMENT} stage. After two hits taken
 * without landing one it replaces the built-in movement for the escape window ({@link ComboEscape}): a strafe out of
 * the knockback line toward the side with no hazard, a slide along the wall when pinned, never a straight backpedal.
 * It hands back as soon as a hit of ours lands or the window ends. The aim stays on the opponent (it ran before this
 * slot), so the next charged hit is still there to throw.
 * <p>
 * Players only (mobs do not combo). Not built: the predicted jump reset, which is an owner decision (keep the
 * single-player gate or relax it for the predicted variant). Allocation free.
 */
public final class ComboEscapeStage implements FightStage {

    public static final String KEY_COMBO_ESCAPE = "combo_escape";

    private final ComboEscape escape = new ComboEscape();
    private boolean enabled = true;
    /** Alternates the opening side between escapes when both are open (a fixed side is easy to read). */
    private int nextPreferred = ComboEscape.RIGHT;
    private int escapesSeen;
    private int drivenTicks;

    @Override
    public String id() {
        return "combo_escape";
    }

    @Override
    public Slot slot() {
        return Slot.MOVEMENT;
    }

    @Override
    public boolean step(CombatSnapshot s, FightContext ctx) {
        if (!enabled || !ctx.targetIsPlayer() || ctx.eating()) {
            if (escape.active()) {
                escape.reset();
            }
            return false;
        }
        ComboEscape e = escape;
        if (!e.update(ctx.clock(), ctx.hitsTakenUnanswered(), ctx.lastHitTakenAt(), ctx.lastHitLandedAt(),
                s.hazardFront, s.hazardBack, s.hazardLeft, s.hazardRight, nextPreferred)) {
            return false;
        }
        if (e.escapes() != escapesSeen) {
            escapesSeen = e.escapes();
            nextPreferred = -nextPreferred;
        }
        drivenTicks++;
        ctx.move(e.forward() > 0 ? SpacingController.FORWARD : SpacingController.HOLD, e.side(), false);
        return true;
    }

    @Override
    public void onEngage(FightContext ctx) {
        escape.reset();
    }

    @Override
    public void reset() {
        escape.reset();
    }

    @Override
    public void specs(List<SettingSpec> out) {
        out.add(SettingSpec.bool(KEY_COMBO_ESCAPE, "Combo escape", true, "Tactics",
                "Against a player: after two hits taken without landing one, stop trading from the air and strafe "
                        + "out of the knockback line (along the wall when pinned) until your hit lands or half a "
                        + "second passes. Never backpedals straight."));
    }

    @Override
    public void read(ProfileConfig profile, String moduleId) {
        enabled = profile.getBool(moduleId, KEY_COMBO_ESCAPE, true);
    }

    /** The escape drives the movement right now. */
    public boolean escaping() {
        return escape.active();
    }

    /** Ticks the escape drove the movement (diagnostics, tests). */
    public int drivenTicks() {
        return drivenTicks;
    }

    public ComboEscape escape() {
        return escape;
    }

    public void setEnabled(boolean on) {
        enabled = on;
    }
}
