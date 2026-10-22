package ru.minecomplex.mac.bukkit.net;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.configuration.file.FileConfiguration;
import ru.minecomplex.mac.replay.DiagnosticJournal;
import ru.minecomplex.mac.replay.IncidentWindow;
import ru.minecomplex.mac.replay.TagsFile;

/** Automatic capture lifecycle. Session/model reads stay on the connection's event loop; I/O is serialized here. */
public final class AutomaticDiagnostics implements AutoCloseable {
    private final java.util.logging.Logger logger;
    private final Path directory;
    private final String environment;
    private final ru.minecomplex.mac.bukkit.debug.ServerBlockTags tags;
    private final boolean enabled;
    private final int before, after, cooldown, maxIncidents, maxSessions, retained;
    private final long journalBytes, archiveBytes;
    private final double threshold;
    private final AtomicInteger active = new AtomicInteger();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "MAC automatic diagnostics");
        thread.setDaemon(true);
        return thread;
    });

    public AutomaticDiagnostics(java.util.logging.Logger logger, Path directory, String environment,
                                ru.minecomplex.mac.bukkit.debug.ServerBlockTags tags, FileConfiguration config) {
        this.logger = logger;
        this.directory = directory;
        this.environment = environment;
        this.tags = tags;
        enabled = config.getBoolean("diagnostics.enabled", true);
        before = clamp(config.getInt("diagnostics.before-ticks", 40), 1, 200);
        after = clamp(config.getInt("diagnostics.after-ticks", 20), 0, 100);
        cooldown = clamp(config.getInt("diagnostics.cooldown-ticks", 200), 20, 12000);
        maxIncidents = clamp(config.getInt("diagnostics.max-incidents-per-session", 8), 1, 100);
        maxSessions = clamp(config.getInt("diagnostics.max-sessions", 16), 1, 128);
        retained = clamp(config.getInt("diagnostics.retained-archives", 20), 1, 1000);
        journalBytes = clamp(config.getInt("diagnostics.journal-mib", 64), 1, 1024) * 1048576L;
        archiveBytes = clamp(config.getInt("diagnostics.archive-total-mib", 256), 1, 16384) * 1048576L;
        double configured = config.getDouble("diagnostics.offset-threshold", 1e-5);
        threshold = Double.isFinite(configured) && configured >= 0 ? configured : 1e-5;
        logger.info("Automatic MAC diagnostics " + (enabled ? "enabled" : "disabled") + ": " + before + " before / "
                + after + " after, threshold=" + threshold + ", directory=" + directory);
        writer.execute(() -> {
            try {
                Files.createDirectories(directory);
                // Only MAC-owned unfinished files are removed; archives survive restart.
                try (var paths = Files.list(directory)) {
                    for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("mac-auto-")
                            && (p.toString().endsWith(".journal") || p.toString().endsWith(".environment")
                            || p.toString().endsWith(".partial"))).toList())
                        Files.deleteIfExists(path);
                }
                prune();
            } catch (IOException e) { logger.log(Level.WARNING, "Cannot initialize automatic MAC diagnostics", e); }
        });
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    /** Before the first play input; a mid-session journal must never masquerade as a complete replay. */
    Capture start(SessionHandle handle, ru.minecomplex.mac.world.Precipitation.Source biomes) {
        if (!enabled) return null;
        if (active.incrementAndGet() > maxSessions) {
            active.decrementAndGet();
            logger.warning("Automatic MAC diagnostics session limit reached for " + handle.user().getName());
            return null;
        }
        try { return new Capture(handle, biomes); }
        catch (IOException | RuntimeException e) {
            active.decrementAndGet();
            logger.log(Level.WARNING, "Cannot start automatic MAC diagnostics", e);
            return null;
        }
    }

    final class Capture implements AutoCloseable {
        final DiagnosticJournal journal;
        private final IncidentWindow window;
        private final ru.minecomplex.mac.replay.BiomeTrace biomes;
        private final SessionHandle handle;
        private final String identity, base;
        private volatile int number;
        private volatile boolean closed;
        private boolean predictorFailed;

        Capture(SessionHandle handle, ru.minecomplex.mac.world.Precipitation.Source source) throws IOException {
            this.handle = handle;
            identity = "player=" + handle.user().getName() + " uuid=" + handle.user().getUniqueId() + " started=" + Instant.now()
                    + "\n" + environment;
            base = "mac-auto-" + handle.user().getUniqueId() + "-" + UUID.randomUUID();
            journal = new DiagnosticJournal(directory.resolve(base + ".journal"),
                    identity, writer, journalBytes, 16 * 1048576L,
                    message -> logger.warning("Automatic MAC diagnostics for " + handle.user().getName()
                            + " stopped: " + message));
            window = new IncidentWindow(before, after, cooldown, maxIncidents, threshold, this::save);
            biomes = new ru.minecomplex.mac.replay.BiomeTrace(source, journal::observation);
            handle.session().probe().biomes(biomes);
        }

        void tick(ru.minecomplex.mac.frame.ClientTickFrame frame, ru.minecomplex.mac.engine.MovementCheckRunner.TickReport report) {
            if (closed) return;
            if (journal.problem() != null) { close(); return; }
            try {
                if (report == null) {
                    if (!predictorFailed) {
                        predictorFailed = true;
                        window.capture("predictor failed: " + stack(handle.session().probeFailure()));
                        // No future report can arrive once the predictor has failed.
                        window.close();
                    }
                    return;
                }
                String details = "window=" + frame.window() + " flags=" + frame.flags() + " input=" + frame.input()
                        + " packets=" + frame.packets() + "\n" + handle.session().probe().diagnosticState();
                window.tick(report, details);
                biomes.nextFrame(frame.index() + 1);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "Automatic MAC diagnostics failed for " + handle.user().getName(), failure);
                close();
            }
        }

        void request() { if (!closed) window.capture("manual diagnostic capture"); }

        String status() { return journal.problem() != null ? journal.problem() : number >= maxIncidents ? "session incident limit reached"
                : closed ? "closed" : null; }

        private void save(IncidentWindow.Incident incident) {
            try {
                var textTags = new StringWriter();
                TagsFile.write(tags.keys(), textTags);
                String text = identity + "\ntrigger=" + incident.trigger() + " lastFrame=" + incident.lastFrame()
                        + " completeTail=" + incident.completeTail() + " reason=" + incident.reason()
                        + "\ncontext=" + before + " before / " + after + " after; threshold=" + threshold
                        + "\nStates below are model estimates after each tick, not independent client truth."
                        + "\nrecording.macr contains all normalized inputs from session start through this capture."
                        + "\nbiomes.tsv retains queried server biome/sea-level facts, including unknown answers."
                        + "\nThis records model inputs, not an independent dump of the real client's memory.\n\n"
                        + String.join("\n\n", incident.ticks());
                journal.capture(directory.resolve(base + "-f" + incident.trigger() + "-" + (++number) + ".zip"), text,
                        textTags.toString(), path -> {
                            logger.info("MAC diagnostic saved: " + path + " (trigger frame " + incident.trigger() + ")");
                            try { prune(); } catch (IOException e) { logger.log(Level.WARNING, "Cannot prune MAC diagnostics", e); }
                        });
                if (number >= maxIncidents) close();
            } catch (IOException | RuntimeException e) { logger.log(Level.WARNING, "Cannot prepare MAC diagnostic", e); }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try { window.close(); }
            finally { journal.close(); active.decrementAndGet(); }
        }
    }

    private static String stack(Throwable failure) {
        if (failure == null) return "unknown";
        var text = new StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(text));
        return text.toString();
    }

    /** Worker-thread only; one archive is a single unit so retention cannot orphan tags or replay data. */
    private void prune() throws IOException {
        try (var paths = Files.list(directory)) {
            var files = paths.filter(p -> p.getFileName().toString().startsWith("mac-auto-") && p.toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified())).toList();
            long bytes = 0;
            for (var file : files) bytes += Files.size(file);
            int count = files.size();
            for (var file : files) {
                if (count <= retained && bytes <= archiveBytes) break;
                long size = Files.size(file);
                Files.delete(file);
                bytes -= size;
                count--;
            }
        }
    }

    /** Called after every session has closed/enqueued its final capture. */
    @Override public void close() {
        writer.shutdown();
    }

    public void awaitWrites() {
        try {
            if (!writer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
                logger.warning("MAC diagnostic writer is still finishing; unfinished archives use .partial suffix");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
