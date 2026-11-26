package com.utilitysuite.mixin.world;

import com.utilitysuite.world.WorldHooks;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Cloud tint: blends the cloud colour of this frame toward the cloud tint through {@link WorldHooks#cloudChannel}
 * (a no-op while no tint is set); alpha is kept.
 * <p>
 * 26.3: the cloud colour is no longer {@code ClientLevel.getCloudColor} (the 1.21.1 target); {@code LevelExtractor.extract}
 * reads {@code EnvironmentAttributes.CLOUD_COLOR} and packs it with its single {@code ARGB.colorFromVector4f} call
 * (checked against the 26.3 sources), whose argument is modified here. One reused vector (render thread only), so
 * nothing is allocated per frame. Why a mixin: no event exposes the cloud colour.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorCloudColorMixin {

    @Unique
    private static final Vector4f UTILITYSUITE$CLOUD = new Vector4f();

    @ModifyArg(method = "extract",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/ARGB;colorFromVector4f(Lorg/joml/Vector4fc;)I"))
    private Vector4fc utilitysuite$tintClouds(Vector4fc c) {
        double r = WorldHooks.cloudChannel(0, c.x());
        double g = WorldHooks.cloudChannel(1, c.y());
        double b = WorldHooks.cloudChannel(2, c.z());
        if (r == c.x() && g == c.y() && b == c.z()) {
            return c;
        }
        return UTILITYSUITE$CLOUD.set((float) r, (float) g, (float) b, c.w());
    }
}
