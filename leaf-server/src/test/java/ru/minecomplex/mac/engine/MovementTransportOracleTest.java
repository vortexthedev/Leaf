package ru.minecomplex.mac.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;

import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.TickEnd;
import ru.minecomplex.mac.fixture.inbound.VehicleMovement;
import ru.minecomplex.mac.replay.Recording;
import ru.minecomplex.mac.replay.Replayer;

/** Real vanilla movement sessions under ordered transport schedules, paired with single-packet position mutations.
 * These test the prediction comparator, not absent Speed/Fly/Timer enforcement checks.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class MovementTransportOracleTest {

    private static final FrameListener IGNORE = new FrameListener() {
        public void frame(ClientTickFrame frame) { }
        public void betweenTicks(BetweenTicks event) { }
    };
    private static final long[][] SCHEDULES = {
            {50_000_000L}, {100_000_000L}, {200_000_000L}, {500_000_000L}, {1_000_000_000L},
            {0,0,0,0,250_000_000L}, {1_000_000L,5_000_000L,250_000_000L,0,15_000_000L}
    };

    @Test
    void legalDelaysBurstsAndJitterPreserveVanillaAndPositionMutantsDiverge() throws Exception {
        int[] sessions = {0}, mutants = {0,0,0,0,0,0};
        new VehicleTransitionOracleTest().recordHotbarSessions((recording, expected) -> {
            assertEquals(expected, replay(recording), recording.description() + " baseline");
            for (long[] schedule : SCHEDULES)
                assertEquals(expected, replay(retime(recording, schedule)), recording.description() + " ordered delay/burst");
            for (int kind = 0; kind < 6; kind++) {
                boolean vehicle = kind < 3;
                var target = expected.stream().filter(r -> r.frame() >= (vehicle ? 8 : 44)
                        && r.frame() < (vehicle ? 32 : 55) && r.status() == MovementCheckRunner.Status.COMPARED
                        && r.gaps().isEmpty() && r.inFlight().isEmpty() && (r.vehicle() != null) == vehicle
                        && r.offset() == 0.0 && r.reported() != null).findFirst();
                if (target.isEmpty()) continue; // Some strider dismounts remain explicitly uncertain.
                var mutated = mutate(recording, target.get().frame(), kind);
                var actual = replay(mutated);
                var divergent = actual.stream().filter(r -> r.frame() == target.get().frame()).findFirst().orElseThrow();
                assertEquals(MovementCheckRunner.Status.COMPARED, divergent.status(), mutated.description());
                assertTrue(divergent.offset() >= .125, mutated.description() + " " + divergent);
                mutants[kind]++;
            }
            sessions[0]++;
        });
        assertEquals(16, sessions[0]);
        for (int count : mutants) assertTrue(count >= 8, "Both pig and strider where resolved; every pig must exercise the mutation");
        System.out.println("Movement transport 26.3: " + sessions[0] + " vanilla sessions, "
                + sessions[0] * SCHEDULES.length + " ordered schedules, position mutants " + java.util.Arrays.toString(mutants));
    }

    static List<MovementCheckRunner.TickReport> replay(Recording recording) {
        var reports = new ArrayList<MovementCheckRunner.TickReport>();
        var session = Replayer.replay(recording, IGNORE, null, reports::add);
        assertNull(session.probeFailure(), recording.description());
        return List.copyOf(reports);
    }

    /** The record of a recorded serverbound packet ({@code null}: another stage). */
    private static ru.minecomplex.mac.fixture.inbound.InboundEvent serverbound(Recording recording, Recording.Entry entry) {
        if (!(entry instanceof Recording.Packet packet) || packet.stage() != Recording.Stage.SERVERBOUND) return null;
        var facts = recording.facts();
        return ru.minecomplex.mac.fixture.FixtureCodec.encodeInbound(ru.minecomplex.mac.packet.PacketDecoder.decode(
                new ru.minecomplex.mac.replay.ReplayConnection(facts).receive(packet)), facts);
    }

    /** Preserve the complete order of application events. This is never called packet reordering or TCP loss. */
    static Recording retime(Recording recording, long[] schedule) {
        var entries = new ArrayList<Recording.Entry>();
        long nanos = 1_000_000_000L;
        int frame = 0;
        for (var entry : recording.entries()) {
            long at = nanos;
            entries.add(switch (entry) {
                case Recording.Packet packet -> new Recording.Packet(at, packet.stage(), packet.packetId(), packet.payload());
                case Recording.MarkerAllocated marker -> new Recording.MarkerAllocated(at, marker.id());
            });
            if (serverbound(recording, entry) instanceof TickEnd) nanos += schedule[frame++ % schedule.length];
        }
        return new Recording(recording.description() + " retimed", List.copyOf(entries));
    }

    private static Recording mutate(Recording recording, long frame, int kind) {
        var entries = new ArrayList<Recording.Entry>();
        long current = 0;
        boolean changed = false;
        for (var entry : recording.entries()) {
            var event = serverbound(recording, entry);
            if (event != null) {
                ru.minecomplex.mac.fixture.inbound.InboundEvent mutant = null;
                if (current == frame && !changed && kind < 3 && event instanceof VehicleMovement v) {
                    mutant = new VehicleMovement(v.x() + (kind == 0 ? .125 : 0),
                            v.y() + (kind == 1 ? .25 : 0), v.z() - (kind == 2 ? .125 : 0), v.yaw(), v.pitch(), v.onGround());
                } else if (current == frame && !changed && kind >= 3 && event instanceof Movement m && m.hasPosition()) {
                    mutant = new Movement(m.kind(), m.x() + (kind == 3 ? .125 : 0),
                            m.y() + (kind == 4 ? .25 : 0), m.z() - (kind == 5 ? .125 : 0), m.yRot(), m.xRot(), m.onGround(), m.horizontalCollision());
                }
                if (mutant != null) {
                    entry = ru.minecomplex.mac.fixture.FixturePackets.packet(entry.nanos(), Recording.Stage.SERVERBOUND,
                            ru.minecomplex.mac.Wire.of(mutant, recording.facts()));
                    changed = true;
                }
                if (event instanceof TickEnd) current++;
            }
            entries.add(entry);
        }
        assertTrue(changed, recording.description() + " frame " + frame + " kind " + kind);
        return new Recording(recording.description() + " position mutant " + kind, List.copyOf(entries));
    }
}
