package ru.minecomplex.mac.frame;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;

import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.inbound.KeepAlive;
import ru.minecomplex.mac.fixture.inbound.Movement;
import ru.minecomplex.mac.fixture.inbound.PlayerInput;
import ru.minecomplex.mac.fixture.inbound.TeleportAccept;
import ru.minecomplex.mac.fixture.inbound.TickEnd;
import ru.minecomplex.mac.fixture.outbound.PlayerPosition;
import ru.minecomplex.mac.fixture.outbound.PlayerRotation;
import ru.minecomplex.mac.player.transaction.Marker;
import ru.minecomplex.mac.player.transaction.PongResult;
import ru.minecomplex.mac.player.transaction.SyncWindow;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class FrameAssemblerTest {

    private static final SyncWindow W0 = new SyncWindow(-1, 5);

    private static final SyncWindow W1 = new SyncWindow(4, 9);

    private final List<ClientTickFrame> frames = new ArrayList<>();

    private final List<BetweenTicks> between = new ArrayList<>();

    private final FrameListener listener = new FrameListener() {

        @Override
        public void betweenTicks(BetweenTicks event) {
            between.add(event);
        }

        @Override
        public void frame(ClientTickFrame frame) {
            frames.add(frame);
        }
    };

    private long seq;

    private FrameAssembler assembler() {
        return new FrameAssembler(listener);
    }

    private void send(FrameAssembler assembler, InboundEvent event, SyncWindow window) {
        assembler.accept(seq++, Wire.of(event), window);
    }

    private static PlayerPosition absoluteTeleport(int id, double x, double y, double z) {
        return new PlayerPosition(id, x, y, z, 0, 0, 0, 10, 20, 0);
    }

    @Test
    void tickFramesCarryInputMovementAndWindow() {
        FrameAssembler assembler = assembler();
        send(assembler, new PlayerInput(PlayerInput.FORWARD | PlayerInput.SPRINT), W0);
        send(assembler, Movement.pos(1, 2, 3, true, false), W0);
        send(assembler, TickEnd.INSTANCE, W0);
        send(assembler, TickEnd.INSTANCE, W1);
        assertEquals(2, frames.size());
        ClientTickFrame first = frames.get(0);
        assertEquals(0, first.firstInboundSeq());
        assertEquals(2, first.tickEndSeq());
        assertTrue(first.inputSent());
        assertTrue((first.input() & ru.minecomplex.mac.fixture.inbound.PlayerInput.FORWARD) != 0 && (first.input() & ru.minecomplex.mac.fixture.inbound.PlayerInput.SPRINT) != 0);
        assertEquals(1, first.movement().getX(0));
        assertEquals(W0, first.window());
        assertEquals(2, first.packets().size());
        ClientTickFrame second = frames.get(1);
        assertFalse(second.inputSent());
        assertTrue((second.input() & ru.minecomplex.mac.fixture.inbound.PlayerInput.FORWARD) != 0, "input persists until the client sends a new one");
        assertNull(second.movement());
        assertEquals(W1, second.window());
        assertTrue(second.flags().isEmpty());
    }

    @Test
    void pongsAreBetweenTicks() {
        FrameAssembler assembler = assembler();
        assembler.pong(seq++, 1, PongResult.acknowledged(new Marker(1, 4, 0), 0));
        send(assembler, Movement.status(true, false), W1);
        send(assembler, TickEnd.INSTANCE, W1);
        assertInstanceOf(BetweenTicks.MarkerAcknowledged.class, between.getFirst());
        assertTrue(frames.getFirst().flags().isEmpty());
        assertEquals(1, frames.getFirst().firstInboundSeq());
    }

    @Test
    void pongInsideTickWidensTheWindowAndIsFlagged() {
        FrameAssembler assembler = assembler();
        send(assembler, Movement.status(true, false), W0);
        assembler.pong(seq++, 1, PongResult.acknowledged(new Marker(1, 4, 0), 0));
        send(assembler, TickEnd.INSTANCE, W1);
        ClientTickFrame frame = frames.getFirst();
        assertTrue(frame.has(FrameFlag.RESPONSE_INSIDE_TICK));
        assertTrue(frame.has(FrameFlag.WINDOW_WIDENED));
        assertEquals(new SyncWindow(-1, 9), frame.window());
    }

    /**
     * A screen packet from input handling (escape: {@code container_close}) precedes the next drain: the pong after it is
     * that drain's, the tick's window is the one after it, and the screen packet keeps its own.
     */
    @Test
    void screenPacketsBeforeTheDrainKeepTheirWindowAndDoNotWidenTheTick() {
        FrameAssembler assembler = assembler();
        send(assembler, new ru.minecomplex.mac.fixture.inbound.ContainerClosed(0), W0);
        assembler.pong(seq++, 1, PongResult.acknowledged(new Marker(1, 4, 0), 0));
        send(assembler, Movement.status(true, false), W1);
        send(assembler, TickEnd.INSTANCE, W1);
        ClientTickFrame frame = frames.getFirst();
        assertTrue(frame.flags().isEmpty(), frame.flags().toString());
        assertEquals(W1, frame.window());
        assertEquals(List.of(W0, W1), frame.packetWindows());
        assertEquals(0, frame.firstInboundSeq());
    }

    /**
     * A tick of screen packets only: its window is the one at its tick end.
     */
    @Test
    void aTickOfScreenPacketsOnlyHasTheWindowAtItsEnd() {
        FrameAssembler assembler = assembler();
        send(assembler, new ru.minecomplex.mac.fixture.inbound.ContainerClosed(0), W0);
        assembler.pong(seq++, 1, PongResult.acknowledged(new Marker(1, 4, 0), 0));
        send(assembler, TickEnd.INSTANCE, W1);
        assertEquals(W1, frames.getFirst().window());
        assertTrue(frames.getFirst().flags().isEmpty());
    }

    @Test
    void foreignPongIsReported() {
        FrameAssembler assembler = assembler();
        assembler.pong(seq++, 42, PongResult.foreign());
        BetweenTicks.ForeignPong pong = assertInstanceOf(BetweenTicks.ForeignPong.class, between.getFirst());
        assertEquals(42, pong.id());
    }

    @Test
    void protocol777AcceptCarriesPositionAndNeedsNoReply() {
        FrameAssembler assembler = assembler();
        assembler.teleportSent(3, Wire.of(absoluteTeleport(5, 10, 64, 10)));
        send(assembler, TeleportAccept.withPosition(5, 10, 64, 10, 10, 20), W1);
        BetweenTicks.TeleportAccepted accepted = assertInstanceOf(BetweenTicks.TeleportAccepted.class, between.getFirst());
        assertTrue(Wire.<ru.minecomplex.mac.fixture.inbound.TeleportAccept>in(accepted.accept()).hasPosition());
        assertNull(accepted.positionReply());
        assertFalse(accepted.missingReply());
        send(assembler, Movement.posRot(10, 64, 10, 10, 20, false, false), W1);
        send(assembler, TickEnd.INSTANCE, W1);
        assertEquals(1, frames.getFirst().packets().size(), "a PosRot after a 777 accept is a tick packet");
    }

    @Test
    void acceptsSkipOrMissTeleports() {
        FrameAssembler assembler = assembler();
        assembler.teleportSent(1, Wire.of(absoluteTeleport(1, 0, 0, 0)));
        assembler.teleportSent(2, Wire.of(absoluteTeleport(2, 0, 0, 0)));
        send(assembler, TeleportAccept.withPosition(2, 0, 0, 0, 0, 0), W1);
        assertEquals(1, assertInstanceOf(BetweenTicks.TeleportAccepted.class, between.get(0)).skipped());
        send(assembler, TeleportAccept.withPosition(9, 0, 0, 0, 0, 0), W1);
        assertInstanceOf(BetweenTicks.UnknownTeleportAccept.class, between.get(1));
    }

    @Test
    void absoluteRotationReplyIsMatchedExactly() {
        FrameAssembler assembler = assembler();
        assembler.rotationSent(2, Wire.of(new PlayerRotation(90, false, -400, false)));
        send(assembler, Movement.rot(90, -90, false, false), W1);
        send(assembler, TickEnd.INSTANCE, W1);
        BetweenTicks.RotationApplied applied = assertInstanceOf(BetweenTicks.RotationApplied.class, between.getFirst());
        assertTrue(applied.exact());
        assertTrue(frames.getFirst().packets().isEmpty());
        assertFalse(frames.getFirst().has(FrameFlag.AMBIGUOUS_ROTATION_RESPONSE));
    }

    @Test
    void negativeZeroYawIsKeptUntilRotationPacketsGoThroughCalculateAbsolute() {
        FrameAssembler modern = assembler();
        modern.rotationSent(2, Wire.of(new PlayerRotation(-0.0f, false, 0, false)));
        // 0.0F + -0.0F = +0.0F in calculateAbsolute
        send(modern, Movement.rot(0.0f, 0, false, false), W1);
        assertTrue(assertInstanceOf(BetweenTicks.RotationApplied.class, between.getFirst()).exact());
    }

    @Test
    void nonMatchingRotIsATickPacket() {
        FrameAssembler assembler = assembler();
        assembler.rotationSent(2, Wire.of(new PlayerRotation(90, false, 0, false)));
        send(assembler, Movement.rot(45.5f, 3, false, false), W0);
        send(assembler, TickEnd.INSTANCE, W0);
        assertTrue(between.isEmpty());
        assertEquals(45.5f, frames.getFirst().movement().getYRot(0));
        assertEquals(1, assembler.pendingRotations());
    }

    @Test
    void relativeRotationReplyIsAmbiguous() {
        FrameAssembler assembler = assembler();
        assembler.rotationSent(2, Wire.of(new PlayerRotation(15, true, 0, true)));
        send(assembler, Movement.rot(123, 4, false, false), W1);
        send(assembler, TickEnd.INSTANCE, W1);
        assertFalse(assertInstanceOf(BetweenTicks.RotationApplied.class, between.getFirst()).exact());
        assertTrue(frames.getFirst().has(FrameFlag.AMBIGUOUS_ROTATION_RESPONSE));
    }

    @Test
    void rotationReplyInsideTickIsATickPacket() {
        FrameAssembler assembler = assembler();
        assembler.rotationSent(2, Wire.of(new PlayerRotation(90, false, 0, false)));
        send(assembler, new PlayerInput(0), W1);
        send(assembler, Movement.rot(90, 0, false, false), W1);
        send(assembler, TickEnd.INSTANCE, W1);
        assertTrue(between.isEmpty());
        assertEquals(2, frames.getFirst().packets().size());
    }

    @Test
    void nettyThreadPacketsNeverJoinFrames() {
        FrameAssembler assembler = assembler();
        send(assembler, Movement.status(true, false), W0);
        send(assembler, new KeepAlive(7), W0);
        send(assembler, TickEnd.INSTANCE, W0);
        assertInstanceOf(BetweenTicks.NettyThreadPacket.class, between.getFirst());
        assertEquals(1, frames.getFirst().packets().size());
        assertTrue(frames.getFirst().flags().isEmpty());
    }

    @Test
    void multipleMovementsAreFlagged() {
        FrameAssembler assembler = assembler();
        send(assembler, Movement.pos(0, 0, 0, true, false), W0);
        send(assembler, Movement.pos(0, 1, 0, true, false), W0);
        send(assembler, TickEnd.INSTANCE, W0);
        assertTrue(frames.getFirst().has(FrameFlag.MULTIPLE_MOVEMENTS));
        assertEquals(1.0, frames.getFirst().movement().getY(0), "the last movement packet is the frame's movement");
    }
}
