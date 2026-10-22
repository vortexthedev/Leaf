package ru.minecomplex.mac.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

import ru.minecomplex.mac.engine.MovementCheckRunner;
import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Replays diagnostic archives ({@code /mac} automatic captures) several times and prints, per archive, the heap bytes the
 * replaying thread allocated and the time per client tick, and a fingerprint of every tick report (bit-exact positions,
 * offsets, gaps, notes). A refactoring that keeps behaviour keeps the fingerprint. Not part of the build:
 *
 * <pre>
 * mvn -o -q -pl core test -Dtest=ReplayBenchmarkTest -Dmac.bench=a.zip,b.zip [-Dmac.bench.rounds=3]
 *     [-Dmac.bench.dump=dir] (every report of the first round, one line each, to dir/archive.reports.txt)
 * </pre>
 */
@EnabledIfSystemProperty(named = "mac.bench", matches = ".+")
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class ReplayBenchmarkTest {

    @Test
    void replayArchives() throws Exception {
        int rounds = Integer.getInteger("mac.bench.rounds", 3);
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        List<String> lines = new ArrayList<>();
        for (String name : System.getProperty("mac.bench").split(",")) {
            Path file = Path.of(name.trim());
            String fingerprint = null;
            long bestBytes = Long.MAX_VALUE, bestNanos = Long.MAX_VALUE;
            int frames = 0;
            for (int round = 0; round < rounds; round++) {
                // the archive's biome trace is consumed by a replay
                DiagnosticArchive archive = DiagnosticArchive.read(file);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                StringBuilder dump = round == 0 && System.getProperty("mac.bench.dump") != null ? new StringBuilder() : null;
                int[] count = {0};
                long bytes = threads.getCurrentThreadAllocatedBytes();
                long start = System.nanoTime();
                Replayer.replay(archive.recording(), new FrameListener() {
                    @Override
                    public void betweenTicks(BetweenTicks event) {
                    }

                    @Override
                    public void frame(ClientTickFrame frame) {
                    }
                }, archive.tags(), report -> {
                    count[0]++;
                    String line = describe(report);
                    digest.update(line.getBytes(StandardCharsets.UTF_8));
                    if (dump != null) dump.append(line);
                }, archive.biomes());
                long nanos = System.nanoTime() - start;
                bytes = threads.getCurrentThreadAllocatedBytes() - bytes;
                if (dump != null) Files.writeString(Path.of(System.getProperty("mac.bench.dump"),
                        file.getParent().getFileName() + ".reports.txt"), dump, StandardCharsets.UTF_8);
                String print = HexFormat.of().formatHex(digest.digest());
                if (fingerprint != null && !fingerprint.equals(print)) {
                    throw new AssertionError("Replay is not deterministic: " + file);
                }
                fingerprint = print;
                frames = count[0];
                bestBytes = Math.min(bestBytes, bytes);
                bestNanos = Math.min(bestNanos, nanos);
            }
            String line = String.format("%s frames=%d bytes/frame=%d micros/frame=%.1f fingerprint=%s",
                    file.getParent().getFileName(), frames, bestBytes / Math.max(1, frames),
                    bestNanos / 1000.0 / Math.max(1, frames), fingerprint);
            lines.add(line);
            System.out.println("[bench] " + line);
        }
        String out = System.getProperty("mac.bench.out");
        if (out != null) Files.write(Path.of(out), lines, StandardCharsets.UTF_8);
    }

    private static String describe(MovementCheckRunner.TickReport report) {
        return report.frame() + "|" + report.status() + "|" + Double.doubleToRawLongBits(report.offset()) + "|"
                + bits(report.predicted()) + "|" + bits(report.reported()) + "|" + report.inFlight() + "|"
                + new TreeSet<>(report.gaps()) + "|" + report.note() + "|" + report.vehicle() + "\n";
    }

    private static String bits(Vec3d vector) {
        if (vector == null) return "null";
        return Double.doubleToRawLongBits(vector.x()) + "," + Double.doubleToRawLongBits(vector.y()) + ","
                + Double.doubleToRawLongBits(vector.z());
    }
}
