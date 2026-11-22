package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.tactics.PearlSource;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * {@link PearlSource} over the client level: thrown ender pearls are tracked from entity join/leave events into a
 * small fixed array (no level scans, no allocation per query). Client thread only.
 * <p>
 * CONTRACT STUB: the listener and query signatures are fixed for the volley; the bodies are filled in by the
 * pearl-intercept task. Until then no pearl is ever reported.
 */
public final class PearlWatch implements PearlSource {

    public PearlWatch() {
    }

    public void onJoin(EntityJoinLevelEvent event) {
    }

    public void onLeave(EntityLeaveLevelEvent event) {
    }

    /** Drops every tracked pearl (logout, world unload). */
    public void clear() {
    }

    @Override
    public boolean pearlOf(int throwerEntityId, double[] out) {
        return false;
    }
}
