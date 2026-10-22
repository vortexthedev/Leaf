package ru.minecomplex.mac;

import java.util.ArrayList;
import java.util.List;
import ru.minecomplex.mac.player.connection.PlayerConnectionContext;
import ru.minecomplex.mac.packet.NativeRuntime;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.fixture.outbound.OutboundEvent;
import ru.minecomplex.mac.fixture.FixtureCodec;

/**
 * Test packets written in the recording schema: tests describe wire input as the records a recording holds and feed
 * the native 26.3 wrappers the replay codec rebuilds from them, as a replayed recording does. Predicted packets are
 * compared as records ({@link #record}).
 */
public final class Wire {
    /** The profile of synthetic sessions (no profile is known). */
    public static final PlayerConnectionContext FACTS = new PlayerConnectionContext(null, null);

    static {
        NativeRuntime.ensure();
    }

    private Wire() {
    }

    @SuppressWarnings("unchecked")
    public static <T extends Object> T of(OutboundEvent event) {
        if (event == null) return null;
        return (T) model(FixtureCodec.decode(event, FACTS));
    }

    @SuppressWarnings("unchecked")
    public static <T extends Object> T of(InboundEvent event) {
        if (event == null) return null;
        return (T) model(FixtureCodec.decode(event, FACTS));
    }

    @SuppressWarnings("unchecked")
    public static <T extends Object> T of(OutboundEvent event, PlayerConnectionContext facts) {
        if (event == null) return null;
        return (T) model(FixtureCodec.decode(event, facts));
    }

    @SuppressWarnings("unchecked")
    public static <T extends Object> T of(InboundEvent event, PlayerConnectionContext facts) {
        if (event == null) return null;
        return (T) model(FixtureCodec.decode(event, facts));
    }

    private static Object model(Object packet) {
        return packet instanceof net.minecraft.network.protocol.Packet<?> nativePacket
                ? ru.minecomplex.mac.packet.PacketDecoder.decode(nativePacket,NativeRuntime.registries()) : packet;
    }

    /** A {@code block_event} with these values. */
    @SuppressWarnings("unchecked")
    public static <T extends Object> T of(ru.minecomplex.mac.world.BlockEvent event) {
        return (T) FixtureCodec.decode(new ru.minecomplex.mac.fixture.outbound.BlockEvent(event.x(), event.y(), event.z(),
                event.action(), event.parameter(), event.blockType()), FACTS);
    }

    /** A {@code block_entity_data} with these values. */
    @SuppressWarnings("unchecked")
    public static <T extends Object> T of(ru.minecomplex.mac.world.BlockEntityData data) {
        return (T) FixtureCodec.decode(new ru.minecomplex.mac.fixture.outbound.BlockEntityData(data.x(), data.y(), data.z(),
                data.type(), data.nbt()), FACTS);
    }

    /** Wrappers of clientbound records, in order. */
    public static List<Object> ofOut(java.util.Collection<? extends OutboundEvent> events) {
        var wrappers = new ArrayList<Object>();
        for (var event : events) wrappers.add(of(event));
        return wrappers;
    }

    /** The record of a serverbound packet (a prediction or a decoded one). */
    @SuppressWarnings("unchecked")
    public static <T extends InboundEvent> T in(Object packet) {
        return (T) FixtureCodec.encodeInbound(packet, FACTS);
    }

    public static OutboundEvent out(Object packet) {
        return FixtureCodec.encodeOutbound(packet, FACTS);
    }

    /** The records of serverbound packets, in order. */
    public static List<InboundEvent> in(List<? extends Object> packets) {
        var records = new ArrayList<InboundEvent>();
        for (var packet : packets) records.add(in(packet));
        return records;
    }

    /**
     * A comparable form of a value holding wrappers (frames, between-tick events, lists of them): wrappers become their
     * records, records become their class and components, recursively. Wrappers have identity equality.
     */
    public static Object view(Object value) {
        if (value instanceof ru.minecomplex.mac.listener.MultiBlockChangeBitRepackHandler.Update) return FixtureCodec.encodeOutbound(value, FACTS);
        if (value instanceof net.minecraft.network.protocol.Packet<?> wrapper) {
            return wrapper.type().flow() == net.minecraft.network.protocol.PacketFlow.SERVERBOUND
                    ? FixtureCodec.encodeInbound(wrapper, FACTS) : FixtureCodec.encodeOutbound(wrapper, FACTS);
        }
        if (value instanceof java.util.Collection<?> collection) {
            var out = new ArrayList<Object>();
            for (var element : collection) out.add(view(element));
            return out;
        }
        if (value instanceof Record record) {
            var out = new ArrayList<Object>();
            out.add(record.getClass().getName());
            for (var component : record.getClass().getRecordComponents()) {
                try {
                    var accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    out.add(view(accessor.invoke(record)));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return out;
        }
        return value;
    }

    /** The packet of {@code event} enters {@code session} as the transport hands a received packet to it. */
    public static ru.minecomplex.mac.player.MacPlayer.InboundDecision receive(ru.minecomplex.mac.player.MacPlayer session,
                                                                                   InboundEvent event, long nanos) {
        return ru.minecomplex.mac.fixture.FixturePackets.receive(session, FixtureCodec.decode(event, FACTS), nanos);
    }

    /** The packet of {@code event} enters {@code session} as the transport hands a sent packet to it. */
    public static boolean send(ru.minecomplex.mac.player.MacPlayer session, OutboundEvent event, long nanos) {
        return ru.minecomplex.mac.fixture.FixturePackets.send(session, FixtureCodec.decode(event, FACTS), nanos);
    }

    /** Wrappers of records, in order. */
    public static List<Object> ofAll(java.util.Collection<? extends InboundEvent> events) {
        var wrappers = new ArrayList<Object>();
        for (var event : events) wrappers.add(of(event));
        return wrappers;
    }
}
