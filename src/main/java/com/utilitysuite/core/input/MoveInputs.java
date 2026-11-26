package com.utilitysuite.core.input;

import com.utilitysuite.mixin.input.ClientInputAccessor;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Writes the local player's movement input in 26.3, where {@code ClientInput} holds an immutable
 * {@link Input} record ({@code keyPresses}) and a normalized {@code moveVector} (x = strafe/left, y = forward) instead
 * of the mutable fields of 1.21.1's {@code Input}. Both are replaced, never mutated, so this class caches every
 * {@link Input} combination (128) and the nine unit move vectors: the per-tick write paths allocate nothing in the
 * common case (keys-only values). Other values (analog impulses) allocate one {@link Vec2}, as vanilla does each tick.
 * <p>
 * The sneak / item-use multipliers are applied by vanilla after {@code MovementInputUpdateEvent}
 * ({@code LocalPlayer.modifyInput}), so values written here are raw: |v| &lt;= 1. Client thread only.
 */
public final class MoveInputs {

    private static final Input[] INPUTS = new Input[128];
    private static final Vec2[] UNIT = new Vec2[9];

    static {
        for (int i = 0; i < 128; i++) {
            INPUTS[i] = new Input((i & 1) != 0, (i & 2) != 0, (i & 4) != 0, (i & 8) != 0, (i & 16) != 0,
                    (i & 32) != 0, (i & 64) != 0);
        }
        for (int fx = -1; fx <= 1; fx++) {
            for (int fy = -1; fy <= 1; fy++) {
                UNIT[(fx + 1) * 3 + (fy + 1)] = new Vec2(fx, fy).normalized();
            }
        }
    }

    private MoveInputs() {
    }

    /** The shared {@link Input} for these flags (no allocation). */
    public static Input input(boolean forward, boolean backward, boolean left, boolean right, boolean jump,
                              boolean shift, boolean sprint) {
        int i = (forward ? 1 : 0) | (backward ? 2 : 0) | (left ? 4 : 0) | (right ? 8 : 0) | (jump ? 16 : 0)
                | (shift ? 32 : 0) | (sprint ? 64 : 0);
        return INPUTS[i];
    }

    /** Forward impulse of the move vector (1.21.1 {@code forwardImpulse}). */
    public static float forward(ClientInput in) {
        return in.getMoveVector().y;
    }

    /** Strafe impulse of the move vector, positive = left (1.21.1 {@code leftImpulse}). */
    public static float left(ClientInput in) {
        return in.getMoveVector().x;
    }

    /**
     * Sets the move vector to (left, forward), normalized like vanilla's when longer than 1. Keeps the current
     * instance when the values are unchanged.
     */
    public static void setMove(ClientInput in, float forward, float left) {
        Vec2 cur = in.getMoveVector();
        if (cur.x == left && cur.y == forward) {
            return;
        }
        ((ClientInputAccessor) in).utilitysuite$setMoveVector(vector(left, forward));
    }

    /** Replaces the four direction flags plus jump and shift; sprint is kept. */
    public static void setKeys(ClientInput in, boolean up, boolean down, boolean left, boolean right, boolean jump,
                               boolean shift) {
        Input k = in.keyPresses;
        in.keyPresses = input(up, down, left, right, jump, shift, k.sprint());
    }

    private static Vec2 vector(float x, float y) {
        if ((x == -1f || x == 0f || x == 1f) && (y == -1f || y == 0f || y == 1f)) {
            return UNIT[((int) x + 1) * 3 + ((int) y + 1)];
        }
        float sq = x * x + y * y;
        if (sq > 1.0f) {
            float inv = (float) (1.0 / Math.sqrt(sq));
            return new Vec2(x * inv, y * inv);
        }
        return new Vec2(x, y);
    }
}
