package ru.minecomplex.mac.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import ru.minecomplex.mac.fixture.inbound.HeldSlot;
import ru.minecomplex.mac.fixture.outbound.ChunkData;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.Ping;
import ru.minecomplex.mac.world.Precipitation;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class DiagnosticJournalTest {
    @TempDir Path directory;
    private static final class Worker implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void drain() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
    }

    @Test void liveSessionArchiveReplaysSameFramesAndProbeReports() throws Exception {
        var worker = new Worker();
        var problems = new ArrayList<String>();
        var journal = new DiagnosticJournal(directory.resolve("live.journal"), "integration", worker,
                1048576, 1048576, problems::add);
        var frames = new ArrayList<ru.minecomplex.mac.frame.ClientTickFrame>();
        var listener = new ru.minecomplex.mac.frame.FrameListener() {
            public void frame(ru.minecomplex.mac.frame.ClientTickFrame frame) { frames.add(frame); }
            public void betweenTicks(ru.minecomplex.mac.frame.BetweenTicks event) { }
        };
        var session = new ru.minecomplex.mac.player.MacPlayer(
                listener);
        session.record(journal);
        var reports = new ArrayList<ru.minecomplex.mac.engine.MovementCheckRunner.TickReport>();
        var diagnostics = new ArrayList<Long>();
        session.probeListener(reports::add);
        session.diagnosticListener((frame, report) -> {
            diagnostics.add(frame.index());
            assertTrue(session.probe().diagnosticState().contains("inventory selected="));
        });
        Wire.send(session, new JoinGame(1, "minecraft:overworld", 0, 64, 8), 1);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            Wire.send(session, new ChunkData(x, z, new int[]{0,0,0,0}, new int[]{1,1,1,1},
                    new int[]{1,0,0,0}, new long[0]), 1);
        Wire.send(session, new ru.minecomplex.mac.fixture.outbound.PlayerPosition(1, 0, 16, 0, 0, 0, 0, 0F, 0F, 0), 1);
        int marker = session.allocateMarker(2);
        Wire.send(session, new Ping(marker), 3);
        Wire.receive(session, new ru.minecomplex.mac.fixture.inbound.Pong(marker), 4);
        Wire.receive(session, ru.minecomplex.mac.fixture.inbound.PlayerLoaded.INSTANCE, 5);
        for (int i = 0; i < 5; i++) {
            Wire.receive(session, ru.minecomplex.mac.fixture.inbound.Movement.pos(i, 16, 0, false, false), 6+i*2);
            Wire.receive(session, ru.minecomplex.mac.fixture.inbound.TickEnd.INSTANCE, 7+i*2);
        }
        session.probeListener(null); // Closing live chat debug must leave automatic diagnostics running.
        Wire.receive(session, ru.minecomplex.mac.fixture.inbound.Movement.pos(6, 16, 0, false, false), 16);
        Wire.receive(session, ru.minecomplex.mac.fixture.inbound.TickEnd.INSTANCE, 17);
        assertFalse(frames.isEmpty());
        assertTrue(reports.stream().anyMatch(report -> report.status() == ru.minecomplex.mac.engine.MovementCheckRunner.Status.COMPARED),
                "Exercise actual movement prediction, not only loading skips");
        assertEquals(frames.size(), diagnostics.size());
        Path zip = directory.resolve("live.zip");
        journal.capture(zip, "integration", "", ignored -> { });
        journal.close(); worker.drain();
        assertTrue(problems.isEmpty(), problems.toString());
        var bundle = DiagnosticArchive.read(zip);
        var replayFrames = new ArrayList<ru.minecomplex.mac.frame.ClientTickFrame>();
        var replayReports = new ArrayList<ru.minecomplex.mac.engine.MovementCheckRunner.TickReport>();
        var replay = Replayer.replay(bundle.recording(), new ru.minecomplex.mac.frame.FrameListener() {
            public void frame(ru.minecomplex.mac.frame.ClientTickFrame frame) { replayFrames.add(frame); }
            public void betweenTicks(ru.minecomplex.mac.frame.BetweenTicks event) { }
        }, bundle.tags(), replayReports::add, bundle.biomes());
        assertNull(session.probeFailure());
        assertNull(replay.probeFailure());
        assertEquals(ru.minecomplex.mac.Wire.view(frames), ru.minecomplex.mac.Wire.view(replayFrames));
        assertEquals(reports, replayReports.subList(0, reports.size()));
        assertEquals(session.currentWindow(), replay.currentWindow());
    }

    private static Recording.Packet out(long nanos, ru.minecomplex.mac.fixture.outbound.OutboundEvent event) {
        return ru.minecomplex.mac.fixture.FixturePackets.packet(nanos, Recording.Stage.CLIENTBOUND,
                ru.minecomplex.mac.Wire.of(event, ru.minecomplex.mac.Wire.FACTS));
    }

    private static Recording.Packet in(long nanos, ru.minecomplex.mac.fixture.inbound.InboundEvent event) {
        return ru.minecomplex.mac.fixture.FixturePackets.packet(nanos, Recording.Stage.SERVERBOUND,
                ru.minecomplex.mac.Wire.of(event, ru.minecomplex.mac.Wire.FACTS));
    }

    @Test void capturesExactPrefixWithMarkersAndImmutablePayloadsAndCleansSpool() throws Exception {
        var worker = new Worker();
        var problems = new ArrayList<String>();
        Path spool = directory.resolve("test.journal"), zip = directory.resolve("incident.zip");
        var journal = new DiagnosticJournal(spool, "build=test", worker, 1048576, 1048576, problems::add);
        var connection = new ru.minecomplex.mac.fixture.FixturePackets.Connection(ru.minecomplex.mac.Wire.FACTS);
        var login = connection.clientbound(1, ru.minecomplex.mac.Wire.of(new JoinGame(1, "minecraft:overworld", 0, 64, 8),
                ru.minecomplex.mac.Wire.FACTS));
        for (var packet : login) journal.packet(packet);
        var chunk = out(2, new ChunkData(0, 0, new int[]{0}, new int[]{1}, new int[]{1}, new long[0]));
        byte[] chunkBytes = chunk.payload().clone();
        journal.packet(chunk);
        chunk.payload()[0]++;
        journal.markerAllocated(3, 0);
        journal.packet(out(4, new Ping(0)));
        journal.packet(in(5, new HeldSlot(2)));
        var trace = new BiomeTrace(new Precipitation.Source() {
            public Precipitation.Facts noiseBiome(String d, int x, int y, int z) { return new Precipitation.Facts(true, .125F, false); }
            public Integer seaLevel(String d) { return 63; }
        }, journal::observation);
        trace.noiseBiome("minecraft:overworld", 1, 2, 3);
        trace.seaLevel("minecraft:overworld");
        var saved = new ArrayList<Path>();
        journal.capture(zip, "trigger=42", "", saved::add);
        journal.packet(in(6, new HeldSlot(7)));
        journal.close(); journal.close();
        assertFalse(Files.exists(spool), "Disk I/O must wait for worker");
        worker.drain();
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(List.of(zip), saved);
        var archive = DiagnosticArchive.read(zip);
        assertEquals(4 + login.size(), archive.recording().entries().size());
        assertArrayEquals(chunkBytes, ((Recording.Packet) archive.recording().entries().get(login.size())).payload(), "the payload as journaled");
        assertEquals(in(5, new HeldSlot(2)), archive.recording().entries().getLast());
        assertEquals("trigger=42", archive.report());
        assertEquals(new Precipitation.Facts(true, .125F, false), archive.biomes().noiseBiome("minecraft:overworld", 1, 2, 3));
        assertEquals(63, archive.biomes().seaLevel("minecraft:overworld"));
        assertFalse(Files.exists(spool));
        assertFalse(Files.exists(directory.resolve("test.journal.environment")));
        assertFalse(Files.exists(directory.resolve("incident.zip.partial")));
    }

    @Test void boundedQueueAndJournalFailExplicitlyWithoutExportingTruncatedReplay() throws Exception {
        for (boolean queueLimit : new boolean[]{false, true}) {
            var worker = new Worker();
            var problems = new ArrayList<String>();
            Path spool = directory.resolve(queueLimit + ".journal"), zip = directory.resolve(queueLimit + ".zip");
            var journal = new DiagnosticJournal(spool, "test", worker,
                    queueLimit ? 1048576 : 128, queueLimit ? 256 : 1048576, problems::add);
            for (int i = 0; i < 100; i++) journal.packet(in(i, new HeldSlot(i % 9)));
            journal.capture(zip, "must not publish", "", path -> fail("Incomplete capture published"));
            journal.close();
            worker.drain();
            assertNotNull(journal.problem());
            assertEquals(1, problems.size());
            assertFalse(Files.exists(zip));
            assertFalse(Files.exists(spool));
        }
    }

    @Test void ioFailureDoesNotEscapeIntoSessionOrLeavePublishedArchive() throws Exception {
        Path parentFile = directory.resolve("file");
        Files.writeString(parentFile, "not a directory");
        var worker = new Worker();
        var problems = new ArrayList<String>();
        var journal = new DiagnosticJournal(parentFile.resolve("spool"), "test", worker, 4096, 4096, problems::add);
        journal.capture(directory.resolve("bad.zip"), "", "", path -> fail("Invalid journal exported"));
        journal.close();
        assertDoesNotThrow(worker::drain);
        assertEquals(1, problems.size());
        assertFalse(Files.exists(directory.resolve("bad.zip")));
    }

    @Test void biomeTraceRetainsUnknownAndFrameSpecificAnswers() throws Exception {
        var lines = new StringBuilder();
        var source = new BiomeTrace(new Precipitation.Source() {
            public Precipitation.Facts noiseBiome(String d, int x, int y, int z) { return null; }
            public Integer seaLevel(String d) { return 63; }
        }, lines::append);
        source.seaLevel("minecraft:overworld");
        source.noiseBiome("minecraft:overworld", 0, 0, 0);
        var replay = BiomeTrace.read(new java.io.StringReader(lines.toString()));
        assertEquals(63, replay.seaLevel("minecraft:overworld"));
        assertNull(replay.noiseBiome("minecraft:overworld", 0, 0, 0));
        replay.nextFrame(1);
        assertNull(replay.seaLevel("minecraft:overworld"), "Do not invent answers for queries made by changed code");
    }
}
