package ru.minecomplex.mac.player;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.Pong;
import ru.minecomplex.mac.fixture.inbound.TickEnd;
import ru.minecomplex.mac.fixture.outbound.OtherOutbound;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.outbound.Ping;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.fixture.inbound.TeleportAccept;
import ru.minecomplex.mac.player.transaction.SyncWindow;
import ru.minecomplex.mac.player.transaction.Synced;

/**
 * Drives a {@link MacPlayer} with a simulated vanilla client over random network timing and checks the
 * prefix model of {@code transactions.md} §2: for every tick, the clientbound prefix the client really had
 * processed lies inside the frame's window, and the value the client really had is one of the candidates.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class SynchronizationPropertyTest {

    /** One clientbound packet as the simulated client sees it. */
    private record Down(OutboundEvent event, int value) {
    }

    /** What the client had processed when it ran a tick. */
    private record Truth(int value, long processedThroughSeq) {
    }

    private static final class Simulation implements FrameListener {
        final Random random;
        final MacPlayer session;
        final Synced<Integer> synced = new Synced<>(0);

        final List<Down> sent = new ArrayList<>();
        int delivered;
        int processed;
        int clientValue;
        int serverValue;

        final ArrayDeque<InboundEvent> upstream = new ArrayDeque<>();
        final ArrayDeque<Truth> truths = new ArrayDeque<>();
        /** Independent bookkeeping for the tightness check: wire positions of markers, pongs seen by the server. */
        final List<Long> markerSeqs = new ArrayList<>();
        int pongsReceived;
        /** Wire positions of teleports and the accepts the server received (an accept acknowledges like a pong). */
        final List<Long> teleportSeqs = new ArrayList<>();
        int acceptsReceived;
        int teleportId;
        int accepts;
        final boolean acceptWithPosition;
        final java.util.Map<Long, long[]> stateAtInbound = new java.util.HashMap<>();
        long lastCertain = -1;
        int framesChecked;
        int uncertainFrames;
        int pongs;

        Simulation(long seed) {
            random = new Random(seed);
            session = new MacPlayer(this);
            acceptWithPosition = true;
        }

        void serverSends() {
            int kind = random.nextInt(10);
            if (kind < 3 && session.canWriteMarker()) {
                OutboundEvent ping = new Ping(session.allocateMarker(0));
                Wire.send(session, ping, 0);
                markerSeqs.add(session.timeline().lastOutbound());
                sent.add(new Down(ping, 0));
            } else if (kind < 7) {
                serverValue++;
                Wire.send(session, new OtherOutbound("value"), 0);
                synced.sent(session.timeline().lastOutbound(), serverValue);
                sent.add(new Down(null, serverValue));
            } else if (kind == 9) {
                // a teleport: the client accepts it while handling it (handleMovePlayer)
                PlayerPosition teleport = new PlayerPosition(++teleportId, 0.5, 64.0, 0.5, 0.0, 0.0, 0.0, 0.0F, 0.0F, 0);
                Wire.send(session, teleport, 0);
                teleportSeqs.add(session.timeline().lastOutbound());
                sent.add(new Down(teleport, 0));
            } else {
                Wire.send(session, new OtherOutbound("noise"), 0);
                sent.add(new Down(null, -1));
            }
        }

        void networkDeliversDown() {
            delivered += random.nextInt(sent.size() - delivered + 1);
        }

        void clientRunsFrame() {
            while (processed < delivered) {
                Down packet = sent.get(processed++);
                if (packet.event() instanceof Ping ping) {
                    upstream.addLast(new Pong(ping.id()));
                } else if (packet.event() instanceof PlayerPosition teleport) {
                    if (acceptWithPosition) {
                        upstream.addLast(TeleportAccept.withPosition(teleport.teleportId(), 0.5, 64.0, 0.5, 0.0F, 0.0F));
                    } else {
                        upstream.addLast(TeleportAccept.idOnly(teleport.teleportId()));
                        upstream.addLast(Movement.posRot(0.5, 64.0, 0.5, 0.0F, 0.0F, false, false));
                    }
                } else if (packet.value() > 0) {
                    clientValue = packet.value();
                }
            }
            int ticks = random.nextInt(4);
            for (int i = 0; i < ticks; i++) {
                if (random.nextBoolean()) {
                    upstream.addLast(Movement.pos(clientValue, 64, 0, true, false));
                }
                upstream.addLast(TickEnd.INSTANCE);
                truths.addLast(new Truth(clientValue, processed - 1L));
            }
        }

        void networkDeliversUp() {
            int count = random.nextInt(upstream.size() + 1);
            for (int i = 0; i < count; i++) {
                InboundEvent event = upstream.removeFirst();
                long inboundSeq = session.timeline().lastInbound() + 1;
                stateAtInbound.put(inboundSeq, new long[]{pongsReceived, session.timeline().lastOutbound(), acceptsReceived});
                MacPlayer.InboundDecision decision = Wire.receive(session, event, 0);
                if (event instanceof TeleportAccept) acceptsReceived++;
                if (event instanceof Pong) {
                    pongsReceived++;
                    assertEquals(MacPlayer.InboundDecision.CANCEL, decision, "our pongs are cancelled");
                }
            }
        }

        @Override
        public void betweenTicks(BetweenTicks event) {
            if (event instanceof BetweenTicks.MarkerAcknowledged ack) {
                assertEquals(0, ack.skipped(), "a vanilla client never skips markers");
                pongs++;
            }
            if (event instanceof BetweenTicks.TeleportAccepted accepted) {
                assertEquals(0, accepted.skipped(), "a vanilla client never skips teleports");
                accepts++;
            }
        }

        @Override
        public void frame(ClientTickFrame frame) {
            Truth truth = truths.removeFirst();
            SyncWindow window = frame.window();
            assertTrue(frame.flags().isEmpty(), "vanilla frames have no structural flags: " + frame.flags());
            assertTrue(window.certainThrough() >= lastCertain, "windows never go backwards");
            lastCertain = window.certainThrough();
            assertTrue(window.certainThrough() <= truth.processedThroughSeq(),
                    "everything certain was processed: " + window + " vs " + truth);
            assertTrue(truth.processedThroughSeq() < window.possibleBefore(),
                    "nothing beyond the window was processed: " + window + " vs " + truth);
            assertEquals(expectedWindow(frame.firstInboundSeq()), window, "window is as tight as the acknowledgements allow");
            List<Integer> candidates = synced.candidates(window);
            assertTrue(candidates.contains(truth.value()), candidates + " must contain " + truth.value());
            if (frame.movement() != null) {
                assertEquals(truth.value(), (int) frame.movement().getX(0));
            }
            if (candidates.size() > 1) {
                uncertainFrames++;
            }
            framesChecked++;
        }

        /**
         * Certain through the last acknowledged marker or accepted teleport; possible before the next marker or anything
         * unobserved.
         */
        SyncWindow expectedWindow(long firstInboundSeq) {
            long[] state = stateAtInbound.get(firstInboundSeq);
            int acknowledged = (int) state[0];
            long lastOutbound = state[1];
            int accepted = (int) state[2];
            long certain = Math.max(acknowledged > 0 ? markerSeqs.get(acknowledged - 1) : -1,
                    accepted > 0 ? teleportSeqs.get(accepted - 1) : -1);
            long possibleBefore = lastOutbound + 1;
            if (markerSeqs.size() > acknowledged && markerSeqs.get(acknowledged) <= lastOutbound) {
                possibleBefore = markerSeqs.get(acknowledged);
            }
            return new SyncWindow(certain, Math.max(possibleBefore, certain + 1));
        }

        void run(int steps) {
            for (int i = 0; i < steps; i++) {
                switch (random.nextInt(4)) {
                    case 0 -> serverSends();
                    case 1 -> networkDeliversDown();
                    case 2 -> clientRunsFrame();
                    default -> networkDeliversUp();
                }
            }
            delivered = sent.size();
            clientRunsFrame();
            while (!upstream.isEmpty()) {
                networkDeliversUp();
            }
        }
    }

    @Test
    void windowsContainTheClientsTruth() {
        int frames = 0;
        int uncertain = 0;
        int pongs = 0;
        int accepts = 0;
        for (long seed = 0; seed < 400; seed++) {
            Simulation simulation = new Simulation(seed);
            simulation.run(400);
            assertTrue(simulation.truths.isEmpty(), "every client tick produced a frame");
            frames += simulation.framesChecked;
            uncertain += simulation.uncertainFrames;
            pongs += simulation.pongs;
            accepts += simulation.accepts;
        }
        assertTrue(frames > 10_000, "enough frames exercised: " + frames);
        assertTrue(uncertain > 0 && uncertain < frames, "both certain and uncertain frames exercised: " + uncertain);
        assertTrue(pongs > 1_000, "enough acknowledgements exercised: " + pongs);
        assertTrue(accepts > 1_000, "enough teleport acceptances exercised: " + accepts);
    }
}
