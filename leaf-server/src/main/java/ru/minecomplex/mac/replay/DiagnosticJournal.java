package ru.minecomplex.mac.replay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Session-thread serialization, bounded asynchronous disk journal. A capture contains a complete MACR prefix,
 * never a truncated ring presented as replayable. Disk/compression run only on the supplied executor.
 */
public final class DiagnosticJournal implements SessionRecorder, AutoCloseable {
    @FunctionalInterface private interface IO { void run() throws IOException; }
    private record Work(long weight, IO action) { }
    private final ConcurrentLinkedQueue<Work> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicLong queued = new AtomicLong();
    private final Executor executor;
    private final Path spool;
    private final long maxBytes, maxQueued;
    private final Consumer<String> failure;
    private static final class Buffer extends ByteArrayOutputStream {
        void release() { reset(); buf = new byte[32]; }
    }
    private final Buffer bytes = new Buffer();
    private final ReplayFormat.Writer encoder;
    private volatile String problem;
    private boolean closed;
    private long length;
    /** Worker-thread only. */
    private OutputStream output;
    private OutputStream observations;
    private long observationLength;

    public DiagnosticJournal(Path spool, String description, Executor executor,
                             long maxBytes, long maxQueued, Consumer<String> failure) throws IOException {
        if (maxBytes < 1 || maxQueued < 1) throw new IllegalArgumentException("Invalid journal limits");
        this.spool = spool;
        this.executor = executor;
        this.maxBytes = maxBytes;
        this.maxQueued = maxQueued;
        this.failure = failure;
        encoder = new ReplayFormat.Writer(bytes, description);
        appendEncoded();
    }

    @Override public void packet(Recording.Packet packet) { encode(() -> encoder.packet(packet)); }
    @Override public void markerAllocated(long nanos, int id) { encode(() -> encoder.markerAllocated(nanos, id)); }

    private void encode(IO encode) {
        if (closed || problem != null) return;
        try { encode.run(); appendEncoded(); }
        catch (IOException | RuntimeException e) { fail("serialization: " + e); }
    }

    private void appendEncoded() {
        int size = bytes.size();
        if (length + observationLength + size > maxBytes) { bytes.reset(); fail("session journal size limit reached (" + maxBytes + " bytes)"); return; }
        byte[] encoded = bytes.toByteArray();
        bytes.reset();
        length += size;
        submit(size, () -> {
            if (output == null) {
                Files.createDirectories(spool.getParent());
                output = new java.io.BufferedOutputStream(Files.newOutputStream(spool,
                        java.nio.file.StandardOpenOption.CREATE_NEW), 65536);
            }
            output.write(encoded);
        }, false);
    }

    /** Non-packet facts queried from the server (currently positional precipitation biome queries). */
    public void observation(String line) {
        if (closed || problem != null) return;
        byte[] data = line.getBytes(StandardCharsets.UTF_8);
        if (length + observationLength + data.length > maxBytes) { fail("session journal size limit reached"); return; }
        observationLength += data.length;
        submit(data.length, () -> {
            if (observations == null) observations = new java.io.BufferedOutputStream(Files.newOutputStream(observationPath()), 16384);
            observations.write(data);
        }, false);
    }

    private Path observationPath() { return spool.resolveSibling(spool.getFileName() + ".environment"); }

    /** Snapshot at this exact input boundary; the archive is published atomically only after all entries are written. */
    public void capture(Path archive, String report, String tags, Consumer<Path> saved) {
        if (closed || problem != null) return;
        long prefix = length;
        submit(512L + report.length() * 2L + tags.length() * 2L, () -> {
            output.flush();
            Path partial = archive.resolveSibling(archive.getFileName() + ".partial");
            try {
                Files.createDirectories(archive.getParent());
                try (var zip = new ZipOutputStream(Files.newOutputStream(partial))) {
                    zip.putNextEntry(new ZipEntry("recording.macr"));
                    try (var in = Files.newInputStream(spool)) {
                        byte[] buffer = new byte[65536];
                        long remaining = prefix;
                        while (remaining > 0) {
                            int read = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                            if (read < 0) throw new IOException("Journal prefix missing");
                            zip.write(buffer, 0, read);
                            remaining -= read;
                        }
                    }
                    zip.closeEntry();
                    if (observations != null) observations.flush();
                    zip.putNextEntry(new ZipEntry("biomes.tsv"));
                    if (observations != null) Files.copy(observationPath(), zip);
                    zip.closeEntry();
                    text(zip, "diagnostic.txt", report);
                    text(zip, "recording.tags", tags);
                }
                Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE);
                saved.accept(archive);
            } finally { Files.deleteIfExists(partial); }
        }, false);
    }

    private static void text(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void submit(long size, IO action, boolean cleanup) {
        long weight = Math.max(256, size);
        if (!cleanup && problem != null) return;
        if (queued.addAndGet(weight) > maxQueued && !cleanup) {
            queued.addAndGet(-weight);
            fail("diagnostic writer queue limit reached");
            return;
        }
        queue.add(new Work(weight, action));
        schedule();
    }

    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) return;
        try { executor.execute(this::drain); }
        catch (RuntimeException rejected) {
            scheduled.set(false);
            fail("diagnostic executor rejected work: " + rejected);
            // No file was opened by this task. A live executor is required until close has drained.
        }
    }

    private void drain() {
        try {
            Work work;
            while ((work = queue.poll()) != null) {
                try { work.action.run(); }
                catch (IOException | RuntimeException e) { fail("diagnostic I/O: " + e); }
                finally { queued.addAndGet(-work.weight); }
            }
        } finally {
            scheduled.set(false);
            if (!queue.isEmpty()) schedule();
        }
    }

    private synchronized void fail(String message) {
        if (problem != null) return;
        problem = message;
        failure.accept(message);
    }

    public String problem() { return problem; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        bytes.release();
        submit(256, () -> {
            try { if (output != null) output.close(); }
            finally {
                try { if (observations != null) observations.close(); }
                finally { Files.deleteIfExists(spool); Files.deleteIfExists(observationPath()); }
            }
        }, true);
    }
}
