package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.assistant.stages.HealFacts;
import com.utilitysuite.jobs.survival.FoodSelector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * Minecraft side of {@link HealFacts}: one scan of the 36 inventory slots per {@link #refresh} (the stage calls it every
 * few ticks while engaged). Reads only; no clicks, no allocation. Client thread only.
 */
public final class McHealFacts implements HealFacts {

    private int hunger = 20;
    private float saturation;
    private int foodSlot = -1;
    private int foodNutrition;
    private float foodSaturation;
    private int foodEatTicks = 32;
    private int splashSlot = -1;
    private int gapples;

    @Override
    public void refresh() {
        LocalPlayer p = Minecraft.getInstance().player;
        foodSlot = -1;
        splashSlot = -1;
        gapples = 0;
        if (p == null) {
            hunger = 20;
            saturation = 0f;
            return;
        }
        hunger = p.getFoodData().getFoodLevel();
        saturation = p.getFoodData().getSaturationLevel();
        Inventory inv = p.getInventory();
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.items.get(i);
            if (st.isEmpty()) {
                continue;
            }
            Item it = st.getItem();
            if (it == Items.GOLDEN_APPLE || it == Items.ENCHANTED_GOLDEN_APPLE) {
                gapples += st.getCount();
                continue;
            }
            if (i >= 9) {
                continue;
            }
            if (it == Items.SPLASH_POTION) {
                PotionContents pc = st.get(DataComponents.POTION_CONTENTS);
                if (splashSlot < 0 && pc != null && (pc.is(Potions.HEALING) || pc.is(Potions.STRONG_HEALING))) {
                    splashSlot = i;
                }
                continue;
            }
            FoodProperties fp = st.get(DataComponents.FOOD);
            if (fp == null || unsafe(it)) {
                continue;
            }
            double score = FoodSelector.score(fp.nutrition(), fp.saturation(), hunger, false);
            if (score > best) {
                best = score;
                foodSlot = i;
                foodNutrition = fp.nutrition();
                foodSaturation = fp.saturation();
                foodEatTicks = fp.eatDurationTicks();
            }
        }
    }

    /** The same foods Auto-Eat never eats (by item identity, no registry lookups). */
    private static boolean unsafe(Item it) {
        return it == Items.ROTTEN_FLESH || it == Items.SPIDER_EYE || it == Items.POISONOUS_POTATO
                || it == Items.PUFFERFISH || it == Items.SUSPICIOUS_STEW || it == Items.CHORUS_FRUIT
                || it == Items.CHICKEN;
    }

    @Override
    public int hunger() {
        return hunger;
    }

    @Override
    public float saturation() {
        return saturation;
    }

    @Override
    public int bestFoodHotbarSlot() {
        return foodSlot;
    }

    @Override
    public int bestFoodNutrition() {
        return foodSlot < 0 ? 0 : foodNutrition;
    }

    @Override
    public float bestFoodSaturation() {
        return foodSlot < 0 ? 0f : foodSaturation;
    }

    @Override
    public int bestFoodEatTicks() {
        return foodEatTicks;
    }

    @Override
    public int splashHealingSlot() {
        return splashSlot;
    }

    @Override
    public int goldenAppleCount() {
        return gapples;
    }
}
