package ru.minecomplex.mac.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.util.math.Vec3d;
import ru.minecomplex.mac.engine.MovementCheckRunner;
import ru.minecomplex.mac.player.MacPlayer;
import ru.minecomplex.mac.world.BlockTags;
import ru.minecomplex.mac.world.ResolvedBlockTags;

/**
 * Replays a session recorded on the server ({@code /mac record}) through the same model and prints the movement probe's
 * reports in the form {@code /mac debug} uses. Not part of the build: it runs only with {@code -Dmac.replay=<file>}
 * ({@code <name>.macr.gz}; the block tags are read from {@code <name>.tags} next to it):
 *
 * <pre>
 * mvn -o -q -pl core test -Dtest=RecordedSessionReplayTest -Dmac.replay=path/to/player-20261011-200000.macr.gz
 *     [-Dmac.replay.threshold=1e-4] [-Dmac.replay.context=8] [-Dmac.replay.from=N -Dmac.replay.to=M -Dmac.replay.all=true]
 *     [-Dmac.replay.requireExact=true] (fail if any comparison has a nonzero offset)
 * </pre>
 *
 * Frames whose offset exceeds the threshold are printed with the frames before them ({@code context}); {@code all}
 * prints every frame of the range. The report also goes to {@code <name>.report.txt}.
 */
@EnabledIfSystemProperty(named = "mac.replay", matches = ".+")
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class RecordedSessionReplayTest {

    @Test
    void replayRecordedSession() throws IOException {
        Path file = Path.of(System.getProperty("mac.replay"));
        Recording recording;
        DiagnosticArchive archive = file.toString().endsWith(".zip") ? DiagnosticArchive.read(file) : null;
        if (archive != null) recording = archive.recording();
        else try (InputStream in = open(file)) {
            recording = ReplayFormat.read(in);
        }
        String name = file.getFileName().toString().replaceFirst("\\.macr(\\.gz)?$", "");
        Path tagsFile = file.resolveSibling(name + ".tags");
        BlockTags tags = archive == null ? BlockTags.NONE : archive.tags();
        if (Files.exists(tagsFile)) {
            try (Reader in = Files.newBufferedReader(tagsFile, StandardCharsets.UTF_8)) {
                tags = ResolvedBlockTags.ofKeys(TagsFile.read(in));
            }
        }
        double threshold = Double.parseDouble(System.getProperty("mac.replay.threshold", "1e-4"));
        int context = Integer.getInteger("mac.replay.context", 8);
        long from = Long.getLong("mac.replay.from", 0L), to = Long.getLong("mac.replay.to", Long.MAX_VALUE);
        boolean all = Boolean.getBoolean("mac.replay.all");

        Path reportFile = file.resolveSibling(name + ".report.txt");
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(reportFile, StandardCharsets.UTF_8))) {
            Report report = new Report(out, threshold, context, from, to, all);
            report.line(recording.description() + " | " + recording.entries().size() + " inputs | tags "
                    + (archive != null ? "bundled" : Files.exists(tagsFile) ? tagsFile.getFileName() : "none"));
            MacPlayer session = Replayer.replay(recording, new FrameListener() {
                @Override
                public void betweenTicks(BetweenTicks event) {
                }

                @Override
                public void frame(ClientTickFrame frame) {
                }
            }, tags, report::accept, archive == null ? null : archive.biomes());
            report.summary();
            if (Boolean.getBoolean("mac.replay.requireExact")) {
                org.junit.jupiter.api.Assertions.assertNull(session.probeFailure(), "Replay predictor failure");
                org.junit.jupiter.api.Assertions.assertEquals(0, report.off + report.close, "Every compared offset must be zero");
                org.junit.jupiter.api.Assertions.assertTrue(report.compared > 0, "Replay must compare movement");
            }
            if (session.probeFailure() != null) {
                report.line("probe failed: " + session.probeFailure());
                for (StackTraceElement element : session.probeFailure().getStackTrace()) report.line("    at " + element);
            }
            report.line("report: " + reportFile);
        }
    }

    private static InputStream open(Path file) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file));
        return file.toString().endsWith(".gz") ? new GZIPInputStream(in) : in;
    }

    private static final class Report {
        private final PrintWriter out;
        private final double threshold;
        private final int context;
        private final long from, to;
        private final boolean all;
        private final ArrayDeque<String> recent = new ArrayDeque<>();
        private long compared, exact, close, off, skipped;

        Report(PrintWriter out, double threshold, int context, long from, long to, boolean all) {
            this.out = out;
            this.threshold = threshold;
            this.context = context;
            this.from = from;
            this.to = to;
            this.all = all;
        }

        void accept(MovementCheckRunner.TickReport report) {
            String text = format(report);
            if (report.status() == MovementCheckRunner.Status.SKIPPED) skipped++;
            else {
                compared++;
                if (report.offset() == 0.0) exact++;
                else if (report.offset() <= threshold) close++;
                else off++;
            }
            boolean inRange = report.frame() >= from && report.frame() <= to;
            if (inRange && (all || report.status() != MovementCheckRunner.Status.SKIPPED && report.offset() > threshold)) {
                if (!all) {
                    if (!recent.isEmpty()) line("  ...");
                    for (String before : recent) line("  " + before);
                }
                recent.clear();
                line((all ? "" : ">> ") + text);
                return;
            }
            recent.addLast(text);
            while (recent.size() > context) recent.removeFirst();
        }

        void summary() {
            line(String.format(Locale.ROOT, "compared %d | exact %d | <=%.0e %d | larger %d | skipped %d", compared, exact,
                    threshold, close, off, skipped));
        }

        void line(String text) {
            System.out.println(text);
            out.println(text);
        }

        private static String format(MovementCheckRunner.TickReport report) {
            if (report.status() == MovementCheckRunner.Status.SKIPPED) {
                return "#" + report.frame() + " пропуск — " + report.note();
            }
            Vec3d p = report.predicted(), r = report.reported();
            String where = r == null
                    ? String.format(Locale.ROOT, "без позиции, пред %.6f %.6f %.6f", p.x(), p.y(), p.z())
                    : String.format(Locale.ROOT, "клиент %.6f %.6f %.6f | пред−клиент %s %s %s", r.x(), r.y(), r.z(),
                            signed(p.x() - r.x()), signed(p.y() - r.y()), signed(p.z() - r.z()));
            String extra = (report.inFlight().isEmpty() ? "" : " | в пути: " + String.join(", ", report.inFlight()))
                    + (report.gaps().isEmpty() ? "" : " | пробелы: " + report.gaps())
                    + (report.note().isEmpty() ? "" : " | " + report.note());
            String subject = report.vehicle() == null ? "" : report.vehicle() + " ";
            return String.format(Locale.ROOT, "#%d %sΔ%s | %s%s", report.frame(), subject,
                    report.offset() == 0.0 ? "0" : String.format(Locale.ROOT, "%.2e", report.offset()), where, extra);
        }

        private static String signed(double delta) {
            return delta == 0.0 ? "0" : String.format(Locale.ROOT, "%+.2e", delta);
        }
    }
}
