package com.utilitysuite.world.overlays;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.component.ChargedProjectiles;

/**
 * The projectile the player is about to release (bow at draw, charged crossbow, trident wound up, pearl, snowball,
 * egg, wind charge), shared by the World Radar aim arc and the projectile ribbon. One reused instance per caller;
 * {@link #read} allocates nothing. Client thread.
 */
final class HeldProjectile {

    private static final InteractionHand[] HANDS = InteractionHand.values();

    TrajectoryIntegrator.Kind kind;
    double power = 1.0;
    boolean pearl;

    /** Refreshes the fields; returns false (kind null) when nothing throwable is ready. */
    boolean read(LocalPlayer self) {
        kind = null;
        power = 1.0;
        pearl = false;
        for (InteractionHand hand : HANDS) {
            ItemStack stack = self.getItemInHand(hand);
            Item item = stack.getItem();
            if (item instanceof BowItem) {
                if (self.isUsingItem() && self.getUsedItemHand() == hand) {
                    power = TrajectoryIntegrator.bowPower(self.getTicksUsingItem());
                    if (power >= 0.1) {
                        kind = TrajectoryIntegrator.Kind.ARROW;
                    }
                }
            } else if (item instanceof CrossbowItem) {
                if (CrossbowItem.isCharged(stack) && !stack.getOrDefault(DataComponents.CHARGED_PROJECTILES,
                        ChargedProjectiles.EMPTY).contains(Items.FIREWORK_ROCKET)) {
                    kind = TrajectoryIntegrator.Kind.CROSSBOW_ARROW;
                }
            } else if (item instanceof TridentItem) {
                if (self.isUsingItem() && self.getUsedItemHand() == hand && self.getTicksUsingItem() >= 10) {
                    kind = TrajectoryIntegrator.Kind.TRIDENT;
                }
            } else if (item instanceof EnderpearlItem || item instanceof SnowballItem || item instanceof EggItem) {
                kind = TrajectoryIntegrator.Kind.THROWN;
                pearl = item instanceof EnderpearlItem;
            } else if (item instanceof WindChargeItem) {
                kind = TrajectoryIntegrator.Kind.WIND_CHARGE;
            }
            if (kind != null) {
                return true;
            }
        }
        return false;
    }
}
