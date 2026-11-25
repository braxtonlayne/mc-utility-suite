package com.utilitysuite.mixin.world;

import com.utilitysuite.core.api.RenderBudget;
import com.utilitysuite.world.filters.RenderFilterHooks;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Render filters: drop explosion clouds before they are created; the FPS governor also thins all particles here while
 * the shared {@code RenderBudget} tier is 2 or deeper. Why a mixin: there is no particle-spawn event in
 * NeoForge 21.1. {@code createParticle} is {@code @Nullable} in vanilla, and its caller
 * ({@code LevelRenderer.addParticleInternal}) already handles null. Pass-through unless the flag is set (one volatile
 * read per particle). Target checked against the 1.21.1 Mojang-mapped sources:
 * {@code public Particle createParticle(ParticleOptions, double, double, double, double, double, double)}.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineFilterMixin {

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$dropExplosionParticles(ParticleOptions options, double x, double y, double z,
                                                     double xSpeed, double ySpeed, double zSpeed,
                                                     CallbackInfoReturnable<Particle> cir) {
        if (RenderFilterHooks.hideExplosionParticles) {
            var type = options.getType();
            if (type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER) {
                cir.setReturnValue(null);
                return;
            }
        }
        // FPS governor: under a tight render budget keep only one particle in N (a counter, no allocation).
        if (RenderBudget.dropParticle()) {
            cir.setReturnValue(null);
        }
    }
}
