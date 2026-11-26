package com.utilitysuite.mixin.world;

import com.utilitysuite.world.WorldHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sky tint: blends the sky colour vanilla extracted for this frame toward the tint through
 * {@link WorldHooks#skyChannel} (a no-op returning the vanilla channel while no tint is set).
 * <p>
 * 26.3: the sky colour is no longer computed by {@code ClientLevel.getSkyColor} (the 1.21.1 target); it is the
 * {@code EnvironmentAttributes.SKY_COLOR} value that {@code SkyRenderer.extractRenderState(ClientLevel, float, Camera,
 * SkyRenderState)} stores in {@code state.skyColor} (checked against the 26.3 sources). TAIL replaces that field with one
 * reused vector (render thread only), so nothing is allocated per frame. Why a mixin: no event exposes the sky colour.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererColorMixin {

    @Unique
    private static final Vector3f UTILITYSUITE$SKY = new Vector3f();

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void utilitysuite$tintSky(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state,
                                      CallbackInfo ci) {
        Vector3fc c = state.skyColor;
        if (c == null) {
            return;
        }
        double r = WorldHooks.skyChannel(0, c.x());
        double g = WorldHooks.skyChannel(1, c.y());
        double b = WorldHooks.skyChannel(2, c.z());
        if (r != c.x() || g != c.y() || b != c.z()) {
            state.skyColor = UTILITYSUITE$SKY.set((float) r, (float) g, (float) b);
        }
    }
}
