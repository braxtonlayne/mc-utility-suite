package com.utilitysuite.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.utilitysuite.core.api.SecondaryView;
import net.minecraft.client.renderer.LevelRenderer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Picture-in-picture full render: no {@code RenderLevelStageEvent} (and no {@code SubmitCustomGeometryEvent}, 26.3's
 * hook for submitting custom world geometry) is posted while the second level pass runs, so no overlay (ours or
 * another mod's) draws into the panel, recurses, or runs its per-frame work twice. Why a mixin: the events are not
 * cancellable and listeners cannot be skipped from outside.
 * <p>
 * 26.3 (checked against NeoForge 26.3.0.118): {@code ClientHooks.dispatchRenderStage} (the 1.21.1 target) is gone;
 * {@code LevelRenderer} posts one subclass per stage ({@code AfterSky}, {@code AfterOpaqueBlocks}, ...) directly with
 * {@code NeoForge.EVENT_BUS.post(...)}, some of them from lambdas, so every {@code IEventBus.post} call in the class
 * is wrapped (method {@code "*"}, lambdas included). Other events posted there pass through untouched; the posted
 * event is returned unposted only for the two kinds above while {@link SecondaryView#active()} (one static read
 * otherwise, nothing allocated).
 */
@Mixin(LevelRenderer.class)
public abstract class RenderStageSecondaryPassMixin {

    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;",
            remap = false))
    private Event utilitysuite$skipInSecondaryPass(IEventBus bus, Event event, Operation<Event> original) {
        if (SecondaryView.active()
                && (event instanceof RenderLevelStageEvent || event instanceof SubmitCustomGeometryEvent)) {
            return event;
        }
        return original.call(bus, event);
    }
}
