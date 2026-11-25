package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.assistant.stages.GapJumpFacts;
import com.utilitysuite.travel.movement.LevelVoxels;
import com.utilitysuite.travel.movement.MovementPhysics;
import net.minecraft.client.Minecraft;

/**
 * Minecraft side of {@link GapJumpFacts}: the client level through a per-tick {@link LevelVoxels} cache. SafeWalk is
 * reported as on (the strictest rule: an automated fight jump never costs health), so the combat module does not
 * reach into the travel module's state. Client thread only.
 */
public final class McGapJumpFacts implements GapJumpFacts {

    private final LevelVoxels voxels = new LevelVoxels();

    @Override
    public MovementPhysics.Voxels voxels() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : voxels.begin(mc.level);
    }

    @Override
    public boolean safeWalk() {
        return true;
    }
}
