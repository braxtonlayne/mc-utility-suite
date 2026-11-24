package com.utilitysuite.mixin.world;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the hashed world seed stored on the client {@link BiomeManager} for 48-to-64 bit seed lifting. */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {

    @Accessor("biomeZoomSeed")
    long utilitysuite$biomeZoomSeed();
}
