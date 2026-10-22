package ru.minecomplex.mac.bukkit.net;

import io.netty.channel.*;
import java.util.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;

/** Observes the original promise after Leaf interception, without changing a write. Event-loop confined. */
final class PacketWriteObserver extends ChannelOutboundHandlerAdapter {
    static final String NAME = "mac_write_observer";
    static final class Pending {
        ChannelPromise promise;
        Runnable capture = () -> { };
        boolean successful() { return promise != null && promise.isSuccess(); }
    }
    private final IdentityHashMap<Packet<?>, ArrayDeque<Pending>> pending = new IdentityHashMap<>();
    Pending expect(Packet<?> packet) {
        var result = new Pending();
        pending.computeIfAbsent(packet,p->new ArrayDeque<>()).addLast(result);
        return result;
    }
    void forget(Packet<?> packet, Pending item) {
        var queue=pending.get(packet);
        if(queue!=null) {queue.remove(item);if(queue.isEmpty())pending.remove(packet);}
    }
    private void associate(Packet<?> packet,ChannelPromise promise) {
        var queue=pending.get(packet);
        if(queue!=null) {
            var iterator=queue.descendingIterator();
            while(iterator.hasNext()) {var item=iterator.next();if(item.promise==null){item.promise=promise;item.capture.run();break;}}
        }
        if(packet instanceof ClientboundBundlePacket bundle) for(var child:bundle.subPackets()) associate(child,promise);
    }
    @Override public void write(ChannelHandlerContext context,Object message,ChannelPromise promise) throws Exception {
        if(message instanceof Packet<?> packet) associate(packet,promise);
        context.write(message,promise);
    }
}
