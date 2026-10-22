package ru.minecomplex.mac.frame;

import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import ru.minecomplex.mac.player.transaction.Marker;

/**
 * Serverbound packets that are not part of a tick: responses sent while the client processes clientbound packets,
 * and packets of the client's netty thread. See {@code docs/research/architecture.md} §4.
 */
public sealed interface BetweenTicks {

    long inboundSeq();

    /**
     * A pong of our marker: every clientbound packet up to the marker is now processed.
     */
    record MarkerAcknowledged(long inboundSeq, Marker marker, int skipped) implements BetweenTicks {
    }

    /**
     * A pong of somebody else's ping.
     */
    record ForeignPong(long inboundSeq, int id) implements BetweenTicks {
    }

    /**
     * The client applied a teleport.
     *
     * @param teleportSeq   wire position of the {@code player_position} packet
     * @param positionReply the handler's {@code PosRot} (protocols without {@code TELEPORT_ACCEPT_WITH_POSITION}),
     *                      {@code null} if it carries the position itself or the reply was missing
     * @param skipped       earlier teleports that were never accepted (0 for vanilla)
     * @param missingReply  the handler's {@code PosRot} did not follow (impossible for vanilla)
     * @param insideTick    the accept arrived after the tick's own packets had started (impossible for vanilla)
     */
    record TeleportAccepted(long inboundSeq, long teleportSeq, ClientboundPlayerPositionPacket teleport,
                            ServerboundAcceptTeleportationPacket accept, ServerboundMovePlayerPacket positionReply,
                            int skipped, boolean missingReply, boolean insideTick)
            implements BetweenTicks {
    }

    /**
     * An {@code accept_teleportation} whose id matches no teleport sent to the client.
     */
    record UnknownTeleportAccept(long inboundSeq, ServerboundAcceptTeleportationPacket accept) implements BetweenTicks {
    }

    /**
     * The client applied a {@code player_rotation} and answered with {@code Rot}.
     *
     * @param exact whether the reply's values were verified against the packet (both axes absolute); otherwise the
     *              classification is the ambiguous one of {@link FrameFlag#AMBIGUOUS_ROTATION_RESPONSE}
     */
    record RotationApplied(long inboundSeq, long rotationSeq, ClientboundPlayerRotationPacket rotation,
                           ServerboundMovePlayerPacket reply, boolean exact)
            implements BetweenTicks {
    }

    /**
     * {@code keep_alive} / {@code chunk_batch_received}: sent from the client's netty thread, never ordered.
     */
    record NettyThreadPacket(long inboundSeq, Object event) implements BetweenTicks {
    }
}
