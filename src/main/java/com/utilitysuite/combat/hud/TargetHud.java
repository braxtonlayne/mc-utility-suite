package com.utilitysuite.combat.hud;

import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.CombatWorld;
import com.utilitysuite.combat.assistant.TacticsPlanner;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.IWorldMarks;
import com.utilitysuite.core.api.visual.MarkKind;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.List;

/**
 * Target HUD and visual range alert.
 * <ul>
 *   <li>Target HUD: publishes the current target (the Combat Assistant's target while engaged, otherwise the living
 *       entity under the crosshair, held briefly after the crosshair leaves it) as the {@code PRIMARY_TARGET} world
 *       mark; the Shell draws the reticle, health bar and tag from it. Health fraction, distance, name (with totem
 *       pops), the tactic word while engaged, {@code FLAG_NO_LOS} while the assistant's own sample has no line of
 *       sight, and The Read's charge and strafe side when {@link ReadRingModule} offers them for this target
 *       ({@link #offerRead}). The label string is rebuilt only when the name or the pop count changes.</li>
 *   <li>Visual range alert: one status message when another player enters render range (allies excluded).</li>
 * </ul>
 * Off by default. The mark is cleared when there is no target, when the module or the setting is turned off and on
 * world unload. Totem pops arrive through {@link #onTotemPop} (entity event 35, forwarded by {@code mixin.combat}).
 */
public final class TargetHud implements IModule, IConfigurable {

    public static final String ID = "target_hud";
    public static final String SHOW_HUD = "show_hud";
    public static final String RANGE_ALERT = "range_alert";
    static final int HOLD_TICKS = 40;
    static final double HOLD_RANGE = 12.0;
    /** Ticks an offered read stays valid (The Read offers it every tick). */
    static final int READ_FRESH_TICKS = 2;
    private static final int UNCLAIMED = -2;

    /** Tactic words by {@code TacticsPlanner.Tactic} ordinal, built once ("HOLD GAP"); null for NONE. */
    private static final String[] TACTIC_WORDS = tacticWords();

    private static TargetHud instance;

    // The Read's offer for the primary mark (both writers run on the client thread).
    private static int readTarget = -1;
    private static float readCharge = -1f;
    private static int readSide;
    private static int readFresh;

    private final CombatWorld world;
    private final CombatAssistant assistant;
    private final AllyRoster roster;
    private final PopCounter pops = new PopCounter();
    private final CombatSnapshot snap = new CombatSnapshot();

    private boolean enabled;
    private boolean showHud = true;
    private boolean rangeAlert = true;

    private int targetId = -1;
    private int holdTicks;
    private int slot = UNCLAIMED;
    private boolean published;
    // values behind the cached label
    private int shownId = -1;
    private int shownPops = -1;
    private String label;

    public TargetHud(CombatWorld world, CombatAssistant assistant, AllyRoster roster) {
        this.world = world;
        this.assistant = assistant;
        this.roster = roster;
        instance = this;
    }

    /** Entity event 35 (totem of undying used), client thread. */
    public static void onTotemPop(int entityId) {
        TargetHud h = instance;
        if (h != null) {
            h.pops.pop(entityId);
            if (h.assistant != null) {
                h.assistant.onTotemPop(entityId); // post-pop pressure
            }
        }
    }

    /**
     * The Read's charge (0..1) and predicted strafe side (+1 right, -1 left, 0 none) for {@code targetId}, offered
     * every tick by {@link ReadRingModule} in its reticle style. Used for the primary mark when it is that target.
     */
    static void offerRead(int targetId, float charge01, int side) {
        readTarget = targetId;
        readCharge = charge01;
        readSide = side;
        readFresh = READ_FRESH_TICKS;
    }

    public int pops(int entityId) {
        return pops.count(entityId);
    }

    public void onJoin(EntityJoinLevelEvent event) {
        if (!enabled || !rangeAlert || !event.getLevel().isClientSide()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        if (!(event.getEntity() instanceof Player other) || self == null || other == self
                || roster.contains(other.getUUID()) || Services.modState().isCleanScreen()) {
            return;
        }
        self.displayClientMessage(Component.translatable("message.utilitysuite.visual_range",
                other.getName().getString()), true);
    }

    public void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            pops.forget(event.getEntity().getId());
        }
    }

    @Override
    public void onTick() {
        if (readFresh > 0) {
            readFresh--;
        }
        if (!enabled || !showHud) {
            targetId = -1;
            clearMark();
            return;
        }
        int id = -1;
        boolean engaged = assistant.state() == CombatState.ENGAGED && assistant.targetId() >= 0;
        if (engaged) {
            id = assistant.targetId();
        } else {
            Minecraft mc = Minecraft.getInstance();
            if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity le
                    && le.isAlive()) {
                id = le.getId();
            }
        }
        boolean aimed = id >= 0 && !engaged;
        if (id >= 0) {
            targetId = id;
            holdTicks = HOLD_TICKS;
        } else if (targetId >= 0 && (--holdTicks <= 0 || !world.isLivingAlive(targetId)
                || world.distanceSqToLocal(targetId) > HOLD_RANGE * HOLD_RANGE)) {
            targetId = -1;
        }
        if (targetId >= 0) {
            publish(engaged, aimed);
        } else {
            clearMark();
        }
    }

    /** Writes the primary mark for the current target. */
    private void publish(boolean engaged, boolean aimed) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(targetId);
        if (!(e instanceof LivingEntity t)) {
            targetId = -1;
            clearMark();
            return;
        }
        IWorldMarks marks = Services.visuals().marks();
        int s = slot;
        if (s == UNCLAIMED) {
            s = slot = marks.claim(ID);
        }
        if (s < 0) {
            return; // table full: nothing to draw on
        }
        int popCount = pops.count(targetId);
        if (targetId != shownId || popCount != shownPops || label == null) {
            shownId = targetId;
            shownPops = popCount;
            String name = t.getName().getString();
            label = popCount > 0 ? name + "  POP " + popCount : name;
        }
        float max = t.getMaxHealth();
        float health = max > 0f ? Math.min(1f, (t.getHealth() + t.getAbsorptionAmount()) / max) : -1f;
        double d2 = world.distanceSqToLocal(targetId);
        float dist = d2 >= Double.MAX_VALUE ? -1f : (float) Math.sqrt(d2);
        int flags = IWorldMarks.FLAG_TAG;
        // The crosshair is on it: in sight by definition. Otherwise ask the assistant's own sample (its LOS rules).
        if (!aimed && world.sample(targetId, snap) && !snap.lineOfSight) {
            flags |= IWorldMarks.FLAG_NO_LOS;
        }
        String word = null;
        if (engaged && assistant.targetId() == targetId) {
            TacticsPlanner.Tactic tactic = assistant.tactic();
            word = tactic == null ? null : TACTIC_WORDS[tactic.ordinal()];
        }
        boolean read = readFresh > 0 && readTarget == targetId;
        marks.setEntity(s, MarkKind.PRIMARY_TARGET, targetId, health, read ? readCharge : -1f, dist, label, word,
                flags);
        marks.setSide(s, read ? readSide : 0);
        published = true;
    }

    private void clearMark() {
        if (published && slot >= 0) {
            Services.visuals().marks().clear(slot);
        }
        published = false;
    }

    private static String[] tacticWords() {
        TacticsPlanner.Tactic[] all = TacticsPlanner.Tactic.values();
        String[] words = new String[all.length];
        for (TacticsPlanner.Tactic t : all) {
            words[t.ordinal()] = t == TacticsPlanner.Tactic.NONE ? null : t.name().replace('_', ' ');
        }
        return words;
    }

    /**
     * Former GUI layer (still registered by the combat wiring): the target is now drawn by the Shell's reticle from
     * the published mark, so this draws nothing.
     */
    public void render(GuiGraphics g, DeltaTracker delta) {
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
            targetId = -1;
            label = null;
            clearMark();
        }
    }

    @Override
    public void onWorldUnload() {
        targetId = -1;
        label = null;
        shownId = -1;
        pops.clear();
        readFresh = 0;
        readTarget = -1;
        clearMark();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        showHud = profile.getBool(ID, SHOW_HUD, true);
        rangeAlert = profile.getBool(ID, RANGE_ALERT, true);
    }

    @Override
    public String displayName() {
        return "Target HUD";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(SHOW_HUD, "Target reticle", true, "",
                        "Lock-on brackets on the current target with health, distance, tactic and totem pops."),
                SettingSpec.bool(RANGE_ALERT, "Visual range alert", true, "",
                        "Status message when another player comes into render range (allies excluded)."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
