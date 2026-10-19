package org.dreeam.leaf.orebfuscator.util;

import org.dreeam.leaf.orebfuscator.LeafOrebfuscator;
import org.jspecify.annotations.NullMarked;

import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import java.util.random.RandomGenerator.SplittableGenerator;

/**
 * Worker thread with an own random generator, so random blocks can be picked without contention.
 */
@NullMarked
public final class OrebfuscatorThread extends Thread {

    static final ThreadGroup THREAD_GROUP = new ThreadGroup("orebfuscator");

    private static final SplittableGenerator ROOT_GENERATOR = createRootRandom();
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private static SplittableGenerator createRootRandom() {
        try {
            return SplittableGenerator.of("L64X128StarStarRandom");
        } catch (final IllegalArgumentException e) {
            LeafOrebfuscator.LOGGER.warn("Couldn't create L64X128StarStarRandom, using SplittableRandom as fallback");
            return new SplittableRandom();
        }
    }

    private final RandomGenerator random = ROOT_GENERATOR.split();

    OrebfuscatorThread(final Runnable target) {
        super(THREAD_GROUP, target, "orebfuscator-thread-" + THREAD_ID.getAndIncrement());
        this.setUncaughtExceptionHandler((thread, throwable) ->
            LeafOrebfuscator.LOGGER.error("Uncaught exception in {}", thread.getName(), throwable));
    }

    public RandomGenerator random() {
        return this.random;
    }
}
