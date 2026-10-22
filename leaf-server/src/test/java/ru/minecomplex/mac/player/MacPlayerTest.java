package ru.minecomplex.mac.player;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import ru.minecomplex.mac.frame.BetweenTicks;
import ru.minecomplex.mac.frame.ClientTickFrame;
import ru.minecomplex.mac.frame.FrameListener;
import ru.minecomplex.mac.fixture.inbound.Pong;
import ru.minecomplex.mac.fixture.outbound.BundleDelimiter;
import ru.minecomplex.mac.fixture.outbound.JoinGame;
import ru.minecomplex.mac.fixture.outbound.OtherOutbound;
import ru.minecomplex.mac.fixture.outbound.Ping;
import ru.minecomplex.mac.fixture.outbound.Respawn;
import ru.minecomplex.mac.player.transaction.SyncWindow;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class MacPlayerTest {
    private final List<BetweenTicks> between = new ArrayList<>();
    private final MacPlayer session = new MacPlayer(new FrameListener() {
        @Override
        public void betweenTicks(BetweenTicks event) {
            between.add(event);
        }

        @Override
        public void frame(ClientTickFrame frame) {
        }
    });

    @Test
    void markerBecomesPartOfTheTimelineOnlyWhenObserved() {
        int id = session.allocateMarker(0);
        assertEquals(0, session.sync().outstandingCount());
        Wire.send(session, new OtherOutbound("chunk"), 0);
        Wire.send(session, new Ping(id), 10);
        assertEquals(1, session.sync().outstandingCount());
        assertEquals(new SyncWindow(-1, 1), session.currentWindow());

        assertEquals(MacPlayer.InboundDecision.CANCEL, Wire.receive(session, new Pong(id), 50));
        assertEquals(new SyncWindow(1, 2), session.currentWindow());
        assertTrue(between.getFirst() instanceof BetweenTicks.MarkerAcknowledged);
        assertEquals(40, session.sync().lastRoundTripNanos());
    }

    @Test
    void foreignPingsAndPongsPassThrough() {
        Wire.send(session, new Ping(12345), 0);
        assertEquals(0, session.sync().outstandingCount());
        assertEquals(MacPlayer.InboundDecision.PASS, Wire.receive(session, new Pong(12345), 1));
        assertTrue(between.getFirst() instanceof BetweenTicks.ForeignPong);
    }

    @Test
    void cancelledMarkerIsForgotten() {
        for (int i = 0; i < 100; i++) {
            session.allocateMarker(0);
        }
        int id = session.allocateMarker(0);
        Wire.send(session, new Ping(id), 0);
        assertEquals(1, session.sync().outstandingCount());
    }

    @Test
    void bundlesBlockMarkers() {
        assertTrue(session.canWriteMarker());
        Wire.send(session, BundleDelimiter.INSTANCE, 0);
        assertFalse(session.canWriteMarker());
        Wire.send(session, new OtherOutbound("add_entity"), 0);
        Wire.send(session, BundleDelimiter.INSTANCE, 0);
        assertTrue(session.canWriteMarker());
    }

    @Test
    void worldEpochsStartAtLoginAndDimensionChange() {
        Wire.send(session, new JoinGame(1, "minecraft:overworld", -64, 384, 10), 0);
        Wire.send(session, new OtherOutbound("chunk"), 0);
        Wire.send(session, new Respawn("minecraft:the_nether", 0, 0, 256), 0);
        assertEquals(2, session.timeline().worldEpoch());
        assertEquals(4, session.timeline().worldEpochStartSeq());
        // A death respawn in the same dimension keeps the client's level and chunks
        Wire.send(session, new Respawn("minecraft:the_nether", 0, 0, 256), 0);
        assertEquals(2, session.timeline().worldEpoch());
    }
}
