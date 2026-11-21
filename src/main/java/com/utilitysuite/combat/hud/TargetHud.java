package com.utilitysuite.combat.hud;

import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.CombatWorld;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.List;

/**
 * Target HUD and visual range alert.
 * <ul>
 *   <li>Target HUD: one compact pill above the hotbar for the current target (the Combat Assistant's target while
 *       engaged, otherwise the living entity under the crosshair, held briefly after the crosshair leaves it): health,
 *       armour wear, held item and totem pops. The text is cached and rebuilt only when a shown value changes.</li>
 *   <li>Visual range alert: one status message when another player enters render range (allies excluded).</li>
 * </ul>
 * Off by default. Nothing renders when there is no target; hidden by Clean-Screen, F1 and open screens. Totem pops
 * arrive through {@link #onTotemPop} (entity event 35, forwarded by {@code mixin.combat}).
 */
public final class TargetHud implements IModule, IConfigurable {

    public static final String ID = "target_hud";
    public static final String SHOW_HUD = "show_hud";
    public static final String RANGE_ALERT = "range_alert";
    static final int HOLD_TICKS = 40;
    static final double HOLD_RANGE = 12.0;
    private static final int BG = 0x90000000;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int LOW = 0xFFE05050;

    private static TargetHud instance;

    private final CombatWorld world;
    private final CombatAssistant assistant;
    private final AllyRoster roster;
    private final PopCounter pops = new PopCounter();

    private boolean enabled;
    private boolean showHud = true;
    private boolean rangeAlert = true;

    private int targetId = -1;
    private int holdTicks;
    // values behind the cached line
    private int shownId = -1;
    private int shownHalfHearts = -1;
    private int shownArmorPct = -1;
    private Item shownItem;
    private int shownPops = -1;
    private String line;
    private boolean lowHealth;

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
        }
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
        if (!showHud) {
            targetId = -1;
            return;
        }
        int id = -1;
        if (assistant.state() == CombatState.ENGAGED && assistant.targetId() >= 0) {
            id = assistant.targetId();
        } else {
            Minecraft mc = Minecraft.getInstance();
            if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity le
                    && le.isAlive()) {
                id = le.getId();
            }
        }
        if (id >= 0) {
            targetId = id;
            holdTicks = HOLD_TICKS;
        } else if (targetId >= 0 && (--holdTicks <= 0 || !world.isLivingAlive(targetId)
                || world.distanceSqToLocal(targetId) > HOLD_RANGE * HOLD_RANGE)) {
            targetId = -1;
        }
        if (targetId >= 0) {
            refresh();
        }
    }

    /** Rebuilds the cached line only when a shown value changed. */
    private void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            targetId = -1;
            return;
        }
        Entity e = mc.level.getEntity(targetId);
        if (!(e instanceof LivingEntity t)) {
            targetId = -1;
            return;
        }
        int halfHearts = Math.round((t.getHealth() + t.getAbsorptionAmount()) * 2f);
        int armor = armorPercent(t);
        Item held = t.getMainHandItem().getItem();
        int popCount = pops.count(targetId);
        if (targetId == shownId && halfHearts == shownHalfHearts && armor == shownArmorPct && held == shownItem
                && popCount == shownPops && line != null) {
            return;
        }
        shownId = targetId;
        shownHalfHearts = halfHearts;
        shownArmorPct = armor;
        shownItem = held;
        shownPops = popCount;
        lowHealth = t.getHealth() <= t.getMaxHealth() * 0.3f;
        line = TargetLine.format(t.getName().getString(), halfHearts / 2.0, armor,
                t.getMainHandItem().isEmpty() ? null : t.getMainHandItem().getHoverName().getString(), popCount);
    }

    /** Average remaining durability of worn damageable armour, percent; -1 when none is worn. */
    static int armorPercent(LivingEntity t) {
        int n = 0;
        int sum = 0;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack st = t.getItemBySlot(slot);
            if (!st.isEmpty() && st.isDamageableItem() && st.getMaxDamage() > 0) {
                sum += 100 - (st.getDamageValue() * 100) / st.getMaxDamage();
                n++;
            }
        }
        return n == 0 ? -1 : sum / n;
    }

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    /** Gui layer (registered above the crosshair). Draws nothing without a target. */
    public void render(GuiGraphics g, DeltaTracker delta) {
        if (!enabled || targetId < 0 || line == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || Services.modState().isCleanScreen()) {
            return;
        }
        Font font = mc.font;
        int w = font.width(line);
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() - 72;
        g.fill(x - 3, y - 2, x + w + 3, y + 9, BG);
        g.drawString(font, line, x, y, lowHealth ? LOW : TEXT, true);
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
            line = null;
        }
    }

    @Override
    public void onWorldUnload() {
        targetId = -1;
        line = null;
        shownId = -1;
        pops.clear();
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
                SettingSpec.bool(SHOW_HUD, "Target info", true, "",
                        "One line for the current target: health, armour wear, held item, totem pops."),
                SettingSpec.bool(RANGE_ALERT, "Visual range alert", true, "",
                        "Status message when another player comes into render range (allies excluded)."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
