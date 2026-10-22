package ru.minecomplex.mac.util;

/**
 * Asserts that per-player state is only touched by the thread that owns it (the player's Netty event loop).
 * Checks run only with assertions enabled ({@code -ea}, on in tests), so production pays nothing.
 */
public final class ThreadConfinement {
    private volatile Thread owner;

    /**
     * Binds to the calling thread on first use.
     */
    public boolean check() {
        Thread current = Thread.currentThread();
        Thread bound = owner;
        if (bound == null) {
            owner = current;
            return true;
        }
        if (bound != current) {
            throw new IllegalStateException("Player state owned by " + bound.getName() + " accessed from " + current.getName());
        }
        return true;
    }

    /**
     * Hands ownership to the calling thread (a channel moved to another event loop).
     */
    public void rebind() {
        owner = Thread.currentThread();
    }
}
