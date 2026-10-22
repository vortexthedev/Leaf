package ru.minecomplex.mac.frame;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import ru.minecomplex.mac.player.transaction.SyncWindow;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Everything one client tick sent, delimited by {@code client_tick_end}. All packets of a tick were produced with
 * the same set of processed clientbound packets, described by {@link #window()}. Packets the client sent from its
 * input handling before the drain that preceded the tick ({@link ru.minecomplex.mac.packet.Packets#betweenTicks}) lead the list; each
 * saw the set of its own window ({@link #packetWindows()}).
 *
 * @param index           frame number within the session
 * @param firstInboundSeq wire position of the first packet of the tick (the tick end for an empty tick)
 * @param tickEndSeq      wire position of the {@code client_tick_end}
 * @param window          the window of the tick's own packets
 * @param packets         the tick's packets in wire order, without the tick end and without netty-thread packets
 * @param input           key state used by this tick: the flags of the last {@code player_input} so far
 * @param inputSent       whether this tick sent {@code player_input}
 * @param movement        the tick's player movement packet, {@code null} if it sent none (position within 2e-4 of
 *                        the last sent one, rotation and flags unchanged)
 * @param packetWindows   the window at each packet, parallel to {@code packets}
 */
public record ClientTickFrame(
        long index,
        long firstInboundSeq,
        long tickEndSeq,
        SyncWindow window,
        List<Object> packets,
        int input,
        boolean inputSent,
        ServerboundMovePlayerPacket movement,
        Set<FrameFlag> flags,
        List<SyncWindow> packetWindows
) {
    public ClientTickFrame {
        if (packetWindows.size() != packets.size()) {
            throw new IllegalArgumentException(packetWindows.size() + " windows for " + packets.size() + " packets");
        }
    }

    /**
     * A frame whose packets all saw the tick's window.
     */
    public ClientTickFrame(long index, long firstInboundSeq, long tickEndSeq, SyncWindow window, List<Object> packets,
                           int input, boolean inputSent, ServerboundMovePlayerPacket movement, Set<FrameFlag> flags) {
        this(index, firstInboundSeq, tickEndSeq, window, packets, input, inputSent, movement, flags,
                Collections.nCopies(packets.size(), window));
    }

    public boolean has(FrameFlag flag) {
        return flags.contains(flag);
    }
}
