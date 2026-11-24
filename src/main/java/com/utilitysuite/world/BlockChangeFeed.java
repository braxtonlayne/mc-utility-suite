package com.utilitysuite.world;

import com.utilitysuite.core.api.IBlockChangeFeed;

/**
 * The world module's {@link IBlockChangeFeed}: fans each client block change from {@link WorldHooks} out to the
 * subscribers. Copy-on-write listener array, so publishing allocates nothing. Client thread.
 */
public final class BlockChangeFeed implements IBlockChangeFeed {

    private static final Listener[] NONE = new Listener[0];
    private volatile Listener[] listeners = NONE;

    @Override
    public synchronized void subscribe(Listener listener) {
        Listener[] cur = listeners;
        for (Listener l : cur) {
            if (l == listener) {
                return;
            }
        }
        Listener[] next = java.util.Arrays.copyOf(cur, cur.length + 1);
        next[cur.length] = listener;
        listeners = next;
    }

    @Override
    public synchronized void unsubscribe(Listener listener) {
        Listener[] cur = listeners;
        for (int i = 0; i < cur.length; i++) {
            if (cur[i] == listener) {
                Listener[] next = new Listener[cur.length - 1];
                System.arraycopy(cur, 0, next, 0, i);
                System.arraycopy(cur, i + 1, next, i, cur.length - i - 1);
                listeners = next;
                return;
            }
        }
    }

    /** Called from the block change hook. Allocation free. */
    public void publish(long packedPos) {
        Listener[] cur = listeners;
        for (Listener l : cur) {
            l.onBlockChanged(packedPos);
        }
    }
}
