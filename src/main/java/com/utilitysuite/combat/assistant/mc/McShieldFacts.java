package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.assistant.stages.ShieldFacts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.component.DataComponents;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;

/**
 * {@link ShieldFacts} over the client level and the local player. One entity lookup per {@link #refresh}; the
 * getters return the primitives read then. Client thread only, allocation free per tick.
 * <p>
 * Target shield disable: vanilla 26.3 {@code BlocksAttacks.disable} plays the shield's disable sound
 * ({@code SoundEvents.SHIELD_BREAK}) at the blocker through {@code Level.playSound}; the client receives it as a sound
 * packet and {@code ClientLevel.playSeededSound} fires {@link PlayLevelSoundEvent.AtPosition}.
 * The constructor listens to that event (NeoForge event, no mixin) and records the game time of a shield-break sound
 * at the current target's position. A silent entity plays no sound: the disable then stays unobserved (-1).
 */
public final class McShieldFacts implements ShieldFacts {

    /** A shield-break sound this close to the target's feet is the target's. */
    private static final double SOUND_MATCH_SQ = 1.5 * 1.5;
    private static final int DISABLE_TICKS = 100;

    private int targetId = -1;
    private boolean known;
    private boolean ownReady;
    private int ownUse;
    private float headYaw = Float.NaN;
    private int targetUse;
    private boolean targetAxe;
    private long gameTime;
    /** Game time of the last shield-break sound at the target, and that target's id. */
    private long breakTime = Long.MIN_VALUE;
    private int breakId = -1;

    public McShieldFacts() {
        NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, this::onSound);
    }

    @Override
    public void refresh(int targetId) {
        this.targetId = targetId;
        known = false;
        ownReady = false;
        ownUse = 0;
        headYaw = Float.NaN;
        targetUse = 0;
        targetAxe = false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            return;
        }
        gameTime = level.getGameTime();
        ItemStack off = p.getOffhandItem();
        boolean shield = !off.isEmpty() && off.has(DataComponents.BLOCKS_ATTACKS);
        ItemStack main = p.getMainHandItem();
        // Minecraft.startUseItem tries the main hand first: food, a bow, a trident or a shield there would take USE
        boolean mainFree = main.isEmpty() || (main.getUseAnimation() == ItemUseAnimation.NONE
                && !main.has(DataComponents.BLOCKS_ATTACKS));
        ownReady = shield && mainFree && !p.getCooldowns().isOnCooldown(off);
        if (p.isUsingItem() && p.getUseItem().has(DataComponents.BLOCKS_ATTACKS)) {
            ownUse = p.getTicksUsingItem();
        }
        Entity e = targetId < 0 ? null : level.getEntity(targetId);
        if (!(e instanceof LivingEntity t) || !t.isAlive()) {
            return;
        }
        known = true;
        headYaw = t.getYHeadRot();
        if (t.isUsingItem() && t.getUseItem().has(DataComponents.BLOCKS_ATTACKS)) {
            // the use state of a remote player is synced (living entity flags) and counted down client-side
            targetUse = Math.max(1, t.getTicksUsingItem());
        }
        // 26.3: Player.blockUsingItem disables a blocker for the attacker's weapon component
        // (Weapon.disableBlockingForSeconds, 5 s on axes); the attacker's main hand decides
        Weapon weapon = t.getMainHandItem().get(DataComponents.WEAPON);
        targetAxe = weapon != null && weapon.disableBlockingForSeconds() > 0f;
    }

    private void onSound(PlayLevelSoundEvent.AtPosition event) {
        if (event.getSound() == null || event.getSound().value() != SoundEvents.SHIELD_BREAK.value()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || event.getLevel() != level || targetId < 0) {
            return;
        }
        Entity e = level.getEntity(targetId);
        if (e == null) {
            return;
        }
        Vec3 pos = event.getPosition();
        if (e.distanceToSqr(pos) <= SOUND_MATCH_SQ) {
            breakTime = level.getGameTime();
            breakId = targetId;
        }
    }

    @Override
    public boolean targetKnown() {
        return known;
    }

    @Override
    public boolean ownShieldReady() {
        return ownReady;
    }

    @Override
    public int ownShieldUseTicks() {
        return ownUse;
    }

    @Override
    public float targetHeadYaw() {
        return headYaw;
    }

    @Override
    public int targetShieldUseTicks() {
        return targetUse;
    }

    @Override
    public boolean targetHoldsAxe() {
        return targetAxe;
    }

    @Override
    public int targetShieldDisabledTicks() {
        if (breakId != targetId || breakTime == Long.MIN_VALUE) {
            return -1;
        }
        long left = breakTime + DISABLE_TICKS - gameTime;
        return left <= 0 ? 0 : (int) Math.min(DISABLE_TICKS, left);
    }
}
