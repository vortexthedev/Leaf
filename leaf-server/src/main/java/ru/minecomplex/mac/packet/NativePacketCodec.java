package ru.minecomplex.mac.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import ru.minecomplex.mac.replay.Recording;

/** A connection's native codecs. All encoded/decoded buffers are owned and released here. */
public final class NativePacketCodec {
    private final ProtocolInfo<?> clientbound;
    private final ProtocolInfo<?> serverbound;
    private final int sectionUpdateId;

    public NativePacketCodec(RegistryAccess registries) {
        var buffers = RegistryFriendlyByteBuf.decorator(registries);
        clientbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(buffers);
        serverbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(buffers, new GameProtocols.Context() {
            @Override public boolean hasInfiniteMaterials() { return true; }
            @Override public boolean canUseCommandBlocks() { return true; }
        });
        sectionUpdateId = encode(0, Recording.Stage.CLIENTBOUND,
                new net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket(
                        net.minecraft.core.SectionPos.of(0, 0, 0),
                        new it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap<>())).packetId();
    }

    public net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket bundleDelimiter() {
        var packets = new java.util.ArrayList<Packet<?>>();
        clientbound.bundlerInfo().unbundlePacket(new net.minecraft.network.protocol.game.ClientboundBundlePacket(java.util.List.of()),packets::add);
        return (net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket) packets.getFirst();
    }

    private ProtocolInfo<?> info(Recording.Stage stage) {
        return switch (stage) {
            case SERVERBOUND -> serverbound;
            case CLIENTBOUND -> clientbound;
            case CONFIGURATION, REGISTRY -> ConfigurationProtocols.CLIENTBOUND;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Recording.Packet encode(long nanos, Recording.Stage stage, Packet<?> packet) {
        var bytes = Unpooled.buffer();
        try {
            ((net.minecraft.network.codec.StreamCodec) info(stage).codec()).encode(bytes, packet);
            int id = new FriendlyByteBuf(bytes).readVarInt();
            byte[] payload = new byte[bytes.readableBytes()];
            bytes.readBytes(payload);
            return new Recording.Packet(nanos, stage, id, payload);
        } finally { bytes.release(); }
    }

    public Packet<?> decode(Recording.Packet packet) {
        // Native section decoding allocates arrays from the wire count before reading their entries.
        // Each VarLong needs at least one byte; reject impossible counts before that allocation.
        if (packet.stage() == Recording.Stage.CLIENTBOUND && packet.packetId() == sectionUpdateId) {
            var payload = new FriendlyByteBuf(Unpooled.wrappedBuffer(packet.payload()));
            try {
                payload.readLong();
                int count = payload.readVarInt();
                if (count < 0 || count > payload.readableBytes()) throw new IllegalArgumentException("Invalid section update count " + count);
            } finally { payload.release(); }
        }
        var bytes = Unpooled.buffer();
        try {
            new FriendlyByteBuf(bytes).writeVarInt(packet.packetId());
            bytes.writeBytes(packet.payload());
            Packet<?> result = info(packet.stage()).codec().decode(bytes);
            if (bytes.isReadable()) throw new IllegalArgumentException("Trailing packet bytes: " + packet);
            return result;
        } finally { bytes.release(); }
    }
}
