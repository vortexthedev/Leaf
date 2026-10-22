package ru.minecomplex.mac.frame;

import net.minecraft.network.protocol.game.*;
import ru.minecomplex.mac.mechanics.movement.RotationUpdate;
import ru.minecomplex.mac.packet.MoveKind;
import ru.minecomplex.mac.packet.Packets;
import ru.minecomplex.mac.player.transaction.PongResult;
import ru.minecomplex.mac.player.transaction.SyncWindow;

import java.util.*;

/**
 * Splits the serverbound stream into client tick frames and between-tick responses.
 *
 * <p>The main-thread stream of a vanilla client is a sequence of {@code [screen packets of input handling][responses of
 * a packet drain][tick packets] client_tick_end}: a render frame drains the queued packets, runs its ticks, and polls
 * window events before the next {@code runTick}, whose
 * screen callbacks send clicks, closes and chat ({@link Packets#betweenTicks}). Such packets join the next tick's
 * frame with their own windows; a pong after them is the following drain's, not a response inside the tick, and the
 * tick's window is the one at its first own packet. Responses have known causes on the clientbound stream (ping → pong,
 * teleport → accept with position, {@code player_rotation} → {@code Rot}); they are matched to
 * their causes in FIFO order. Netty-thread packets are reported separately and never belong to a frame.
 *
 * <p>Not covered yet (entity/vehicle phases): {@code PosRot} after a teleport of the former vehicle and
 * {@code MoveVehicle} replies to vehicle teleports/corrections; they currently appear as tick packets.
 */
public final class FrameAssembler {

    private final FrameListener listener;

    private final ArrayDeque<Pending<ClientboundPlayerPositionPacket>> teleports = new ArrayDeque<>();

    private final ArrayDeque<Pending<ClientboundPlayerRotationPacket>> rotations = new ArrayDeque<>();
    private final List<Object> packets = new ArrayList<>();
    private final List<SyncWindow> packetWindows = new ArrayList<>();
    private final Set<FrameFlag> flags = EnumSet.noneOf(FrameFlag.class);
    private BetweenTicks.TeleportAccepted awaitingReply;
    private long firstSeq = -1;

    /**
     * Whether a packet only the tick produces has arrived: a response after it is inside the tick.
     */
    private boolean tickStarted;

    /**
     * The window at the tick's first own packet.
     */
    private SyncWindow windowAtStart;

    private int input;

    private boolean inputSent;

    private ServerboundMovePlayerPacket movement;

    private int movements;

    private boolean ambiguousRotationForNextFrame;

    private long frameIndex;

    public FrameAssembler(FrameListener listener) {
        this.listener = listener;
    }

    /**
     * Handler replies are sent with {@code onGround = false} and {@code horizontalCollision = false}
     * ({@code ClientPacketListener.handleMovePlayer / handleRotatePlayer}).
     */
    private static boolean isHandlerReply(ServerboundMovePlayerPacket move, MoveKind kind) {
        return Packets.kind(move) == kind && !move.isOnGround() && !move.horizontalCollision();
    }

    /**
     * A {@code player_position} for the player left at {@code seq}.
     */
    public void teleportSent(long seq, ClientboundPlayerPositionPacket teleport) {
        teleports.addLast(new Pending<>(seq, teleport));
    }

    /**
     * A {@code player_rotation} left at {@code seq}.
     */
    public void rotationSent(long seq, ClientboundPlayerRotationPacket rotation) {
        rotations.addLast(new Pending<>(seq, rotation));
    }

    /**
     * A pong was processed by the synchronization timeline.
     */
    public void pong(long inboundSeq, int id, PongResult result) {
        flushAwaitingReply(true);
        if (tickStarted) {
            flags.add(FrameFlag.RESPONSE_INSIDE_TICK);
            if (result.ours()) {
                flags.add(FrameFlag.WINDOW_WIDENED);
            }
        }
        if (result.ours()) {
            listener.betweenTicks(new BetweenTicks.MarkerAcknowledged(inboundSeq, result.marker(), result.skipped()));
        } else {
            listener.betweenTicks(new BetweenTicks.ForeignPong(inboundSeq, id));
        }
    }

    /**
     * Any other serverbound event.
     *
     * @param window the synchronization window at this packet
     */
    public void accept(long inboundSeq, Object event, SyncWindow window) {
        if (Packets.nettyThread(event)) {
            listener.betweenTicks(new BetweenTicks.NettyThreadPacket(inboundSeq, event));
            return;
        }
        if (awaitingReply != null) {
            if (event instanceof ServerboundMovePlayerPacket move && isHandlerReply(move, MoveKind.POS_ROT)) {
                BetweenTicks.TeleportAccepted pending = awaitingReply;
                awaitingReply = null;
                listener.betweenTicks(new BetweenTicks.TeleportAccepted(pending.inboundSeq(), pending.teleportSeq(), pending.teleport(), pending.accept(), move, pending.skipped(), false, pending.insideTick()));
                return;
            }
            flushAwaitingReply(true);
        }
        if (event instanceof ServerboundAcceptTeleportationPacket accept) {
            teleportAccepted(inboundSeq, accept);
        } else if (event instanceof ServerboundClientTickEndPacket) {
            endTick(inboundSeq, window);
        } else if (!(event instanceof ServerboundMovePlayerPacket move && !tickStarted && isRotationReply(inboundSeq, move))) {
            tickPacket(inboundSeq, event, window);
        }
    }

    private void teleportAccepted(long inboundSeq, ServerboundAcceptTeleportationPacket accept) {
        boolean insideTick = tickStarted;
        if (insideTick) {
            flags.add(FrameFlag.RESPONSE_INSIDE_TICK);
        }
        int skipped = 0;
        Pending<ClientboundPlayerPositionPacket> match = null;
        for (Iterator<Pending<ClientboundPlayerPositionPacket>> it = teleports.iterator(); it.hasNext(); ) {
            Pending<ClientboundPlayerPositionPacket> candidate = it.next();
            if (candidate.packet().id() == accept.id()) {
                match = candidate;
                break;
            }
            skipped++;
        }
        if (match == null) {
            listener.betweenTicks(new BetweenTicks.UnknownTeleportAccept(inboundSeq, accept));
            return;
        }
        for (int i = 0; i <= skipped; i++) {
            teleports.removeFirst();
        }
        BetweenTicks.TeleportAccepted accepted = new BetweenTicks.TeleportAccepted(inboundSeq, match.seq(), match.packet(), accept, null, skipped, false, insideTick);
        {
            listener.betweenTicks(accepted);
        }
    }

    /**
     * The handler's {@code PosRot} did not follow the accept: report the accept without it.
     */
    private void flushAwaitingReply(boolean missing) {
        if (awaitingReply == null) {
            return;
        }
        BetweenTicks.TeleportAccepted pending = awaitingReply;
        awaitingReply = null;
        listener.betweenTicks(new BetweenTicks.TeleportAccepted(pending.inboundSeq(), pending.teleportSeq(), pending.teleport(), pending.accept(), null, pending.skipped(), missing, pending.insideTick()));
    }

    private boolean isRotationReply(long inboundSeq, ServerboundMovePlayerPacket move) {
        Pending<ClientboundPlayerRotationPacket> pending = rotations.peekFirst();
        if (pending == null || !isHandlerReply(move, MoveKind.ROT)) {
            return false;
        }
        ClientboundPlayerRotationPacket rotation = pending.packet();
        boolean yKnown = !rotation.relativeY() && Float.isFinite(rotation.yRot());
        boolean xKnown = !rotation.relativeX() && Float.isFinite(rotation.xRot());
        // absolute values do not depend on the client's current rotation
        if (yKnown && Float.compare(move.getYRot(0), RotationUpdate.rotationPacketYRot(0, rotation.yRot(), false)) != 0) {
            return false;
        }
        if (xKnown) {
            float expected = RotationUpdate.storeXRot(0, RotationUpdate.rotationPacketXRot(0, rotation.xRot(), false));
            if (Float.compare(move.getXRot(0), expected) != 0) {
                return false;
            }
        }
        rotations.removeFirst();
        boolean exact = yKnown && xKnown;
        if (!exact) {
            ambiguousRotationForNextFrame = true;
        }
        listener.betweenTicks(new BetweenTicks.RotationApplied(inboundSeq, pending.seq(), rotation, move, exact));
        return true;
    }

    private void tickPacket(long inboundSeq, Object event, SyncWindow window) {
        if (packets.isEmpty()) {
            firstSeq = inboundSeq;
        }
        if (!tickStarted && !Packets.betweenTicks(event)) {
            tickStarted = true;
            windowAtStart = window;
        }
        packets.add(event);
        packetWindows.add(window);
        switch (event) {
            case ServerboundPlayerInputPacket playerInput -> {
                input = Packets.inputFlags(playerInput);
                inputSent = true;
            }
            case ServerboundMovePlayerPacket move -> {
                movement = move;
                if (++movements > 1) {
                    flags.add(FrameFlag.MULTIPLE_MOVEMENTS);
                }
            }
            default -> {
            }
        }
    }

    private void endTick(long inboundSeq, SyncWindow window) {
        flushAwaitingReply(true);
        SyncWindow frameWindow;
        long first;
        if (packets.isEmpty()) {
            frameWindow = window;
            first = inboundSeq;
        } else {
            // a tick of screen packets only: nothing after them answered inside the tick
            SyncWindow start = tickStarted ? windowAtStart : window;
            frameWindow = flags.contains(FrameFlag.WINDOW_WIDENED) ? start.span(window) : start;
            first = firstSeq;
        }
        if (ambiguousRotationForNextFrame) {
            flags.add(FrameFlag.AMBIGUOUS_ROTATION_RESPONSE);
            ambiguousRotationForNextFrame = false;
        }
        ClientTickFrame frame = new ClientTickFrame(frameIndex++, first, inboundSeq, frameWindow, List.copyOf(packets), input, inputSent, movement, flags.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(flags)), List.copyOf(packetWindows));
        packets.clear();
        packetWindows.clear();
        tickStarted = false;
        flags.clear();
        inputSent = false;
        movement = null;
        movements = 0;
        windowAtStart = null;
        firstSeq = -1;
        listener.frame(frame);
    }

    /**
     * Teleports sent and not accepted yet (server intent the client has not confirmed).
     */
    public int pendingTeleports() {
        return teleports.size();
    }

    public int pendingRotations() {
        return rotations.size();
    }

    private record Pending<T>(long seq, T packet) {
    }
}
