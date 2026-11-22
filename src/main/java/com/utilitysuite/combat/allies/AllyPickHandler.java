package com.utilitysuite.combat.allies;

import com.utilitysuite.core.api.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Middle-click (the pick-block binding) on a player under the crosshair adds or removes them on the current world's
 * ally list. Only cancels the vanilla action in that case; pick-block on blocks and other entities is untouched.
 */
public final class AllyPickHandler {

    private final AllyRosterModule module;

    public AllyPickHandler(AllyRosterModule module) {
        this.module = module;
    }

    public void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isPickBlock() || !module.middleClickEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.hitResult instanceof EntityHitResult ehr)
                || !(ehr.getEntity() instanceof Player target) || target == mc.player) {
            return;
        }
        String name = target.getGameProfile().getName();
        boolean added = module.togglePlayer(target.getUUID(), name);
        event.setCanceled(true);
        event.setSwingHand(false);
        if (!Services.modState().isCleanScreen()) {
            Services.visuals().notices().post(
                    added ? com.utilitysuite.core.api.visual.NoticeKind.DONE
                            : com.utilitysuite.core.api.visual.NoticeKind.INFO,
                    Component.translatable(added ? "message.utilitysuite.ally_added"
                            : "message.utilitysuite.ally_removed", name).getString(), null);
        }
    }
}
