package org.dreeam.leaf.orebfuscator.util;

import org.jspecify.annotations.NullMarked;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Worker pool of Orebfuscator with a separate scheduler thread for delayed tasks.
 */
@NullMarked
public final class OrebfuscatorExecutor implements Executor {

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
        runnable -> new Thread(OrebfuscatorThread.THREAD_GROUP, runnable, "orebfuscator-scheduler"));
    private final ThreadPoolExecutor workers;

    public OrebfuscatorExecutor(final int threads) {
        this.workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), OrebfuscatorThread::new);
    }

    @Override
    public void execute(final Runnable command) {
        Objects.requireNonNull(command);

        // already on a worker, run directly instead of queueing again
        if (Thread.currentThread() instanceof OrebfuscatorThread) {
            command.run();
        } else {
            this.workers.execute(command);
        }
    }

    public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
        return this.scheduler.schedule(command, delay, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command, final long initialDelay, final long period, final TimeUnit unit) {
        return this.scheduler.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    public boolean isShutdown() {
        return this.scheduler.isShutdown() || this.workers.isShutdown();
    }

    public void shutdown() {
        this.scheduler.shutdownNow();
        this.workers.shutdownNow();
    }
}
