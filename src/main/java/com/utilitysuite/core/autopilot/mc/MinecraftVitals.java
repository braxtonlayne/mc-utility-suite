package com.utilitysuite.core.autopilot.mc;

import com.utilitysuite.core.autopilot.VitalsMonitor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * The autopilot's only engine-touching class: reads the local player's health, air and fire each tick into the
 * reusable {@link VitalsMonitor.Vitals}. Client thread; allocation free.
 */
public final class MinecraftVitals implements VitalsMonitor.VitalsSource {

    @Override
    public void sample(VitalsMonitor.Vitals out) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            out.present = false;
            return;
        }
        out.present = true;
        out.health = p.getHealth();
        out.maxHealth = p.getMaxHealth();
        out.air = p.getAirSupply();
        out.maxAir = p.getMaxAirSupply();
        out.fireTicks = p.getRemainingFireTicks();
        out.fireImmune = p.fireImmune() || p.hasEffect(MobEffects.FIRE_RESISTANCE) || p.isCreative()
                || p.isSpectator();
        out.dead = p.isDeadOrDying();
    }

    /** Where black-box dumps go: {@code <game dir>/utilitysuite/blackbox}. */
    public static Path blackBoxDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("utilitysuite").resolve("blackbox");
    }
}
