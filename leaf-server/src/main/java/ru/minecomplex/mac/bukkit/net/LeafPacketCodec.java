package ru.minecomplex.mac.bukkit.net;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import ru.minecomplex.mac.replay.Recording;

/** Native 26.3 codecs provide the payload shared by the model and MACR recordings. */
final class LeafPacketCodec {
    private final ProtocolInfo<?> clientbound;
    private final ProtocolInfo<?> serverbound;

    LeafPacketCodec(RegistryAccess registries) {
        var decorator = RegistryFriendlyByteBuf.decorator(registries);
        clientbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(decorator);
        // These gates affect decoding permissions; packets have already passed Leaf's real decoder.
        serverbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(decorator, new GameProtocols.Context() {
            @Override public boolean hasInfiniteMaterials() { return true; }
            @Override public boolean canUseCommandBlocks() { return true; }
        });
    }

    Recording.Packet encode(Packet<?> packet, ConnectionProtocol protocol, Recording.Stage stage, long nanos) {
        ProtocolInfo<?> info = switch (protocol) {
            case PLAY -> packet.type().flow() == PacketFlow.CLIENTBOUND ? clientbound : serverbound;
            case CONFIGURATION -> ConfigurationProtocols.CLIENTBOUND;
            default -> throw new IllegalArgumentException("MAC does not record " + protocol);
        };
        return encode(packet, info, stage, nanos);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Recording.Packet encode(Packet<?> packet, ProtocolInfo<?> info, Recording.Stage stage, long nanos) {
        var bytes = Unpooled.buffer();
        try {
            ((net.minecraft.network.codec.StreamCodec) info.codec()).encode(bytes, packet);
            int id = new FriendlyByteBuf(bytes).readVarInt();
            byte[] payload = new byte[bytes.readableBytes()];
            bytes.readBytes(payload);
            return new Recording.Packet(nanos, stage, id, payload);
        } finally {
            bytes.release();
        }
    }
}
