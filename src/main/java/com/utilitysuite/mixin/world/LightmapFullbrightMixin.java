package com.utilitysuite.mixin.world;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.utilitysuite.world.WorldHooks;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Scout camera fullbright: the lightmap is computed as if night vision were active at full strength. Nothing is written
 * to the options (the gamma setting and options.txt stay untouched), and turning it off restores the normal lightmap on
 * the next update (vanilla re-extracts it every client tick). The Atmosphere studio's Warm Twilight preset uses the
 * same path at a partial strength ({@link WorldHooks#lightLift()}), so dark areas are lifted part of the way instead of
 * flat fullbright.
 * <p>
 * Why a mixin: the lightmap has no event hook. This reuses vanilla's own night-vision path instead of replacing the
 * computation. 26.3 (checked against the sources): {@code LightTexture.updateLightTexture} (the 1.21.1 target) is gone;
 * {@code LightmapRenderStateExtractor.extract(LightmapRenderState, float)} does
 * {@code if (player.hasEffect(NIGHT_VISION)) nightVisionEffectIntensity = GameRenderer.nightVisionScale(player, pt)}.
 * The first {@code LocalPlayer.hasEffect} call there (the night-vision check) reports true and the scale it then reads
 * is 1.0 (or the partial lift). The scale call is wrapped rather than modified because vanilla's implementation reads
 * the effect instance, which does not exist when the effect is faked; a real night-vision effect keeps its own
 * (stronger) scale.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapFullbrightMixin {

    @ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;hasEffect(Lnet/minecraft/core/Holder;)Z", ordinal = 0))
    private boolean utilitysuite$fullbrightNightVision(boolean original) {
        return original || WorldHooks.fullbright() || WorldHooks.lightLift() > 0f;
    }

    @WrapOperation(method = "extract", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;nightVisionScale(Lnet/minecraft/world/entity/LivingEntity;F)F"))
    private float utilitysuite$fullbrightScale(LivingEntity entity, float partialTick, Operation<Float> original) {
        if (WorldHooks.fullbright()) {
            return 1.0F;
        }
        float lift = WorldHooks.lightLift();
        if (lift <= 0f) {
            return original.call(entity, partialTick);
        }
        return entity.hasEffect(MobEffects.NIGHT_VISION) ? Math.max(lift, original.call(entity, partialTick)) : lift;
    }
}
