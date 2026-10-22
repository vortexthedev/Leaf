package ru.minecomplex.mac.bukkit.net;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.common.*;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import org.dreeam.leaf.network.LeafPackets;
import org.dreeam.leaf.network.PacketEvent;
import org.dreeam.leaf.network.PacketUser;
import ru.minecomplex.mac.packet.NativeRuntime;
import ru.minecomplex.mac.player.MacPlayer;
import ru.minecomplex.mac.player.connection.PlayerConnectionContext;
import ru.minecomplex.mac.replay.Recording;
import ru.minecomplex.mac.replay.ReplayConnection;

/** Leaf owns native interception, cancellation and writes for protocol 777. */
public final class LeafPacketListener implements AutoCloseable {
    private final java.util.logging.Logger logger;
    private volatile boolean closed;
    private final Sessions sessions;
    private final LeafPacketCodec codec;
    private final Map<PacketUser, ReplayConnection> inputs = new ConcurrentHashMap<>();

    public LeafPacketListener(java.util.logging.Logger logger, Sessions sessions, net.minecraft.core.RegistryAccess registries) {
        this.logger = logger;
        this.sessions = sessions;
        this.codec = new LeafPacketCodec(registries);
    }

    public void disconnected(PacketUser user) {
        inputs.remove(user);
        sessions.stop(user);
        sessions.clearConfiguration(user);
    }

    private ReplayConnection input(PacketUser user) {
        return inputs.computeIfAbsent(user, key -> new ReplayConnection(new PlayerConnectionContext(key.getName(), key.getUniqueId())));
    }

    public void receive(PacketEvent<Packet<?>> event) {
        if (closed) return;
        if (event.getProtocol() != ConnectionProtocol.PLAY || event.getUser().getProtocolVersion() != NativeRuntime.PROTOCOL) return;
        SessionHandle handle = sessions.get(event.getUser());
        if (handle == null) return;
        var packet = codec.encode(event.getPacket(), event.getProtocol(), Recording.Stage.SERVERBOUND, System.nanoTime());
        var received = input(event.getUser()).receive(packet);
        if (handle.session().receive(received, packet.nanos()) == MacPlayer.InboundDecision.CANCEL) {
                event.setCancelled(true);
                handle.diagnostics().roundTrip(handle.session().sync().lastRoundTripNanos());
        }

    }

    public void send(PacketEvent<Packet<?>> event) {
        if (closed) return;
        if (event.getUser().getProtocolVersion() != NativeRuntime.PROTOCOL) return;
        if (event.getProtocol() != ConnectionProtocol.PLAY && event.getProtocol() != ConnectionProtocol.CONFIGURATION) return;
        // Leaf dispatches every bundle child separately and completes their sent tasks together, in order.
        // Encoding the bundle container itself is invalid; child callbacks provide the actual play packets.
        if (event.getPacket() instanceof ClientboundBundlePacket) return;
        var channel = event.getUser().getChannel();
        var observer = (PacketWriteObserver) channel.pipeline().get(PacketWriteObserver.NAME);
        if (observer == null) {
            observer = new PacketWriteObserver();
            channel.pipeline().addBefore(org.dreeam.leaf.network.PacketInterceptor.NAME, PacketWriteObserver.NAME, observer);
        }
        var watched = event.getPacket();
        var pending = observer.expect(watched);
        var snapshot = new Recording.Packet[1];
        var captureFailure = new RuntimeException[1];
        pending.capture = () -> {
            try {
                var packet = event.getPacket();
                var stage = event.getProtocol() == ConnectionProtocol.PLAY ? Recording.Stage.CLIENTBOUND
                        : packet instanceof ClientboundRegistryDataPacket || packet instanceof ClientboundFinishConfigurationPacket
                        ? Recording.Stage.REGISTRY : Recording.Stage.CONFIGURATION;
                snapshot[0] = codec.encode(packet,event.getProtocol(),stage,System.nanoTime());
            } catch (RuntimeException failure) { captureFailure[0] = failure; }
        };
        var writes = observer;
        event.runAfterSent(() -> {
            writes.forget(watched, pending);
            if (closed) return;
            if (event.isCancelled()) { commit(event,null); return; }
            if (!pending.successful() || captureFailure[0] != null) {
                sessions.stop(event.getUser());
                sessions.clearConfiguration(event.getUser());
                inputs.remove(event.getUser());
                logger.warning("MAC stopped tracking a connection after an unsuccessful packet write");
                return;
            }
            commit(event,snapshot[0]);
        });
    }

    private void commit(PacketEvent<Packet<?>> event, Recording.Packet snapshot) {
        if (closed) return;
        PacketUser user = event.getUser();
        if (event.isCancelled()) {
            var handle = sessions.get(user);
            if (handle != null) handle.diagnostics().outboundCancelled();
            return;
        }
        Packet<?> nativePacket = event.getPacket();
        if (nativePacket instanceof ClientboundStartConfigurationPacket) {
            sessions.stop(user);
            sessions.clearConfiguration(user);
            inputs.remove(user);
            return;
        }
        ReplayConnection connection = input(user);
        if (event.getProtocol() == ConnectionProtocol.CONFIGURATION) {
            boolean registry = nativePacket instanceof ClientboundRegistryDataPacket
                    || nativePacket instanceof ClientboundFinishConfigurationPacket;
            if (!registry && !(nativePacket instanceof ClientboundUpdateTagsPacket)) return;
            var packet = snapshot;
            if (registry) connection.registry(packet);
            sessions.configuration(user, packet, registry ? null : connection.configurationTags(packet));
            return;
        }
        SessionHandle handle = sessions.get(user);
        if (handle == null) {
            if (!(nativePacket instanceof ClientboundLoginPacket)) return;
            handle = sessions.start(user);
            if (handle == null) return;
            sessions.takeConfiguration(user, handle.session());
        }
        var packet = snapshot;
        var sent = connection.send(packet);
        if (handle.session().send(sent, packet.nanos())) handle.requestMarkerAfterCurrent();
    }

    @Override public void close() {
        closed = true;
        for (var user : LeafPackets.users()) {
            var channel = user.getChannel();
            channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(PacketWriteObserver.NAME) instanceof PacketWriteObserver) channel.pipeline().remove(PacketWriteObserver.NAME);
            });
        }
        inputs.clear();
    }
}
