package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.assistant.stages.RangedFacts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Minecraft side of {@link RangedFacts}: reads the local player's main hand. Reads only, no allocation. Client thread.
 */
public final class McRangedFacts implements RangedFacts {

    private int weapon;
    private int useTicks;
    private boolean charged;
    private int chargeTicks = 25;

    @Override
    public void refresh() {
        LocalPlayer p = Minecraft.getInstance().player;
        weapon = NONE;
        useTicks = 0;
        charged = false;
        if (p == null) {
            return;
        }
        ItemStack stack = p.getMainHandItem();
        if (stack.getItem() instanceof BowItem) {
            weapon = BOW;
        } else if (stack.getItem() instanceof CrossbowItem) {
            weapon = CROSSBOW;
            charged = CrossbowItem.isCharged(stack);
            chargeTicks = CrossbowItem.getChargeDuration(stack, p);
        } else if (stack.getItem() instanceof TridentItem
                && EnchantmentHelper.getTridentSpinAttackStrength(stack, p) <= 0.0f) {
            weapon = TRIDENT; // a Riptide trident launches us instead of flying
        }
        if (weapon != NONE && p.isUsingItem() && p.getUseItem() == stack) {
            useTicks = p.getTicksUsingItem();
        }
    }

    @Override
    public int weapon() {
        return weapon;
    }

    @Override
    public int useTicks() {
        return useTicks;
    }

    @Override
    public boolean crossbowCharged() {
        return charged;
    }

    @Override
    public int crossbowChargeTicks() {
        return chargeTicks;
    }
}
