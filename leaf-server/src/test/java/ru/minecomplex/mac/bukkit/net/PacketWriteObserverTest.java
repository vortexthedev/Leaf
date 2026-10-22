package ru.minecomplex.mac.bukkit.net;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.bukkit.plugin.Plugin;
import org.dreeam.leaf.network.*;
import org.junit.jupiter.api.Test;

class PacketWriteObserverTest {
    @org.junit.jupiter.api.BeforeAll static void initialize() { NativeTestRuntime.initialize(); }
    private static final class Harness implements AutoCloseable {
        final Plugin plugin=(Plugin)Proxy.newProxyInstance(Plugin.class.getClassLoader(),new Class<?>[]{Plugin.class},(proxy,method,args)->switch(method.getName()) {
            case "isEnabled" -> true; case "getName", "toString" -> "MAC-test";case "getLogger" -> java.util.logging.Logger.getAnonymousLogger();
            case "hashCode" -> System.identityHashCode(proxy);case "equals" -> proxy==args[0];
            default -> null;
        });
        final PacketWriteObserver observer=new PacketWriteObserver();
        final List<Integer> committed=new ArrayList<>();
        final List<Boolean> cancelled=new ArrayList<>();
        final EmbeddedChannel channel;
        Harness(boolean fail) {
            var connection=new Connection(PacketFlow.SERVERBOUND);
            var user=new PacketUser(connection);
            user.protocolChanged(PacketFlow.CLIENTBOUND,ConnectionProtocol.PLAY);
            channel=new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext context,Object message,ChannelPromise promise) {
                    if(fail)promise.setFailure(new IllegalStateException("wire failure"));else context.write(message,promise);
                }
            },observer,new PacketInterceptor(user));
            connection.channel=channel;
            LeafPackets.listenAll(plugin,PacketFlow.CLIENTBOUND,PacketPriority.MONITOR,false,event->{
                if(event.getPacket() instanceof ClientboundBundlePacket)return;
                var packet=event.getPacket();var pending=observer.expect(packet);
                event.runAfterSent(()->{
                    observer.forget(packet,pending);cancelled.add(event.isCancelled());
                    if(!event.isCancelled()&&pending.successful())committed.add(((ClientboundPingPacket)event.getPacket()).getId());
                });
            });
        }
        @Override public void close(){LeafPackets.unregisterAll(plugin);channel.finishAndReleaseAll();}
    }
    @Test void successfulBundleChildrenCommitInTheirWireOrder() {
        try(var h=new Harness(false)) {
            h.channel.writeOutbound(new ClientboundBundlePacket(List.of(new ClientboundPingPacket(3),new ClientboundPingPacket(5))));
            assertEquals(List.of(3,5),h.committed);
        }
    }
    @Test void failedWritesNeverCommitEvenWhileTheChannelRemainsActive() {
        try(var h=new Harness(true)) {
            var promise=h.channel.newPromise();h.channel.writeAndFlush(new ClientboundPingPacket(7),promise);
            assertTrue(h.channel.isActive());assertFalse(promise.isSuccess());assertTrue(promise.isDone());
            assertEquals(List.of(),h.committed);assertEquals(List.of(false),h.cancelled);
        }
    }
    @Test void cancellationAndReplacementFollowTheFinalLeafEvent() {
        try(var h=new Harness(false)) {
            LeafPackets.listenAll(h.plugin,PacketFlow.CLIENTBOUND,PacketPriority.NORMAL,false,event->{
                if(((ClientboundPingPacket)event.getPacket()).getId()==1)event.setCancelled(true);
                else event.setPacket(new ClientboundPingPacket(9));
            });
            h.channel.writeOutbound(new ClientboundPingPacket(1));
            assertEquals(List.of(),h.committed);
            h.channel.writeOutbound(new ClientboundPingPacket(2));
            assertEquals(List.of(9),h.committed);
            assertEquals(9,((ClientboundPingPacket)h.channel.readOutbound()).getId());
        }
    }
}
