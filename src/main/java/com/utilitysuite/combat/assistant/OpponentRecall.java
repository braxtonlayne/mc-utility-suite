package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.combat.tactics.SkillEstimator;
import com.utilitysuite.combat.tactics.memory.HabitRecorder;
import com.utilitysuite.combat.tactics.memory.OpponentMemory;
import com.utilitysuite.combat.tactics.memory.OpponentMemoryStore;
import com.utilitysuite.combat.tactics.memory.RecalledHabits;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Glue between a fight and {@link OpponentMemory} (C6): on engaging a remembered player it seeds the skill prior and
 * posts one hint line; when the fight ends it summarises what {@link OpponentModel} and the skill estimate measured
 * and blends it in (debounced write). Only players, only with the setting on and a store attached. Runs on engage /
 * disengage, never per tick.
 * <p>
 * The fight's pearl / ranged / mace habits come from the assistant's {@link HabitRecorder} (they also fill the
 * summary's pearl-out health and ranged / mace shares); on engage the remembered ones are flattened into a
 * {@link RecalledHabits} the fight loop and its stages read.
 */
final class OpponentRecall {

    private final CombatWorld world;
    private OpponentMemoryStore store;
    private OpponentMemory memory;
    private Consumer<String> hintSink = s -> { };

    OpponentRecall(CombatWorld world) {
        this.world = world;
    }

    void useStore(OpponentMemoryStore store, Consumer<String> hintSink) {
        this.store = store;
        this.memory = null;
        this.hintSink = hintSink == null ? s -> { } : hintSink;
    }

    private OpponentMemory memory() {
        if (memory == null && store != null) {
            memory = store.load();
        }
        return memory;
    }

    /** Engage: returns the prior skill to seed, or NaN when the opponent is unknown (or the feature is off). */
    double onEngage(int id, boolean enabled) {
        return onEngage(id, enabled, null);
    }

    /**
     * Engage, also flattening the remembered habits into {@code habits} (cleared when the opponent is unknown or the
     * feature is off; may be null).
     */
    double onEngage(int id, boolean enabled, RecalledHabits habits) {
        if (habits != null) {
            habits.clear();
        }
        if (!enabled || store == null || !world.isPlayer(id)) {
            return Double.NaN;
        }
        UUID uuid = world.uuidOf(id);
        OpponentMemory m = memory();
        if (uuid == null || m == null) {
            return Double.NaN;
        }
        OpponentMemory.Profile p = m.lookup(world.serverKey(), uuid.toString());
        if (p == null) {
            return Double.NaN;
        }
        if (habits != null) {
            habits.fill(p);
        }
        String hint = OpponentMemory.hint(p);
        if (!hint.isEmpty()) {
            hintSink.accept(hint);
        }
        return OpponentMemory.priorSkill(p, SkillEstimator.UNKNOWN_SKILL);
    }

    /** Fight over: summarise and blend. {@code skill} = the live estimate (NaN when unmeasured). */
    void onFightEnd(int id, boolean enabled, OpponentModel model, double skill, int skillSamples, long now) {
        onFightEnd(id, enabled, model, null, skill, skillSamples, now);
    }

    /**
     * Fight over, with the pearl / ranged / mace habits the fight showed ({@code habits} may be null: then only the
     * movement read is blended, as before).
     */
    void onFightEnd(int id, boolean enabled, OpponentModel model, HabitRecorder habits, double skill,
                    int skillSamples, long now) {
        if (!enabled || store == null || id < 0 || !world.isPlayer(id) || model.samples(id) <= 0) {
            return;
        }
        UUID uuid = world.uuidOf(id);
        OpponentMemory m = memory();
        if (uuid == null || m == null) {
            return;
        }
        boolean won = !world.isLivingAlive(id);
        double reach = model.reachLearned(id) ? model.learnedReach(id, Double.NaN) : Double.NaN;
        boolean h = habits != null && habits.ticks() > 0;
        OpponentMemory.FightSummary f = new OpponentMemory.FightSummary(
                model.strafeBias(id), model.shieldUse(id), h ? habits.pearlOutHp() : Double.NaN,
                h ? habits.rangedRate() : Double.NaN, h ? habits.maceRate() : Double.NaN, reach,
                model.cooldownTicks(id), skillSamples >= SkillEstimator.MIN_SAMPLES ? skill : Double.NaN, won);
        String name = world.describe(id);
        int cut = name.lastIndexOf(" #");
        m.record(world.serverKey(), uuid.toString(), cut > 0 ? name.substring(0, cut) : name, f,
                h ? habits.summary() : null, now);
        store.saveDebounced(m);
    }
}
