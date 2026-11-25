package com.utilitysuite.mixin.world;

import com.utilitysuite.world.WorldHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Atmosphere studio sky and cloud recolour: blends the three channels vanilla passes to the {@code new Vec3(r, g, b)}
 * that {@code ClientLevel.getSkyColor(Vec3, float)} and {@code getCloudColor(float)} return toward the preset's target
 * ({@link WorldHooks#skyChannel}, {@link WorldHooks#cloudChannel}; weight 0 = untouched). Modifying the constructor
 * arguments in place adds no allocation per frame (wrapping the return value would build a second Vec3). Vanilla's
 * own rain, thunder and lightning-flash handling has already run, so weather still dims the tinted sky; the fog colour
 * vanilla derives from the sky follows along.
 * <p>Why a mixin: NeoForge 21.1 exposes the fog colour ({@code ViewportEvent.ComputeFogColor}, used by the module)
 * but no event for the sky or cloud colour, and replacing the Overworld's {@code DimensionSpecialEffects} would take
 * over sky rendering for every other mod. Targets checked against the 1.21.1 Mojang-mapped sources: each method ends
 * in a single {@code return new Vec3((double) f2, (double) f3, (double) f4)}.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSkyColorMixin {

    private static final String VEC3_INIT = "Lnet/minecraft/world/phys/Vec3;<init>(DDD)V";

    @ModifyArg(method = "getSkyColor", at = @At(value = "INVOKE", target = VEC3_INIT), index = 0)
    private double utilitysuite$skyRed(double r) {
        return WorldHooks.skyChannel(0, r);
    }

    @ModifyArg(method = "getSkyColor", at = @At(value = "INVOKE", target = VEC3_INIT), index = 1)
    private double utilitysuite$skyGreen(double g) {
        return WorldHooks.skyChannel(1, g);
    }

    @ModifyArg(method = "getSkyColor", at = @At(value = "INVOKE", target = VEC3_INIT), index = 2)
    private double utilitysuite$skyBlue(double b) {
        return WorldHooks.skyChannel(2, b);
    }

    @ModifyArg(method = "getCloudColor", at = @At(value = "INVOKE", target = VEC3_INIT), index = 0)
    private double utilitysuite$cloudRed(double r) {
        return WorldHooks.cloudChannel(0, r);
    }

    @ModifyArg(method = "getCloudColor", at = @At(value = "INVOKE", target = VEC3_INIT), index = 1)
    private double utilitysuite$cloudGreen(double g) {
        return WorldHooks.cloudChannel(1, g);
    }

    @ModifyArg(method = "getCloudColor", at = @At(value = "INVOKE", target = VEC3_INIT), index = 2)
    private double utilitysuite$cloudBlue(double b) {
        return WorldHooks.cloudChannel(2, b);
    }
}
