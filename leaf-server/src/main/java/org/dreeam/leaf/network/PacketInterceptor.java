package org.dreeam.leaf.network;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.Packet;

/**
 * Pipeline handler next to {@code packet_handler}: received packets arrive here decoded, sent packets
 * arrive here before the unbundler and the encoder.
 */
public final class PacketInterceptor extends ChannelDuplexHandler {

    public static final String NAME = "leaf_packets";

    private final PacketUser user;
    // Tasks added by handlers of the packet being written. Only used on the event loop; a handler that sends
    // another packet re-enters write, so each write only takes the tasks it added.
    private final ArrayList<Runnable> sentTasks = new ArrayList<>(0);

    public PacketInterceptor(final PacketUser user) {
        this.user = user;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        LeafPackets.connected(this.user);
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        // Let the connection handle the disconnect first
        ctx.fireChannelInactive();
        LeafPackets.disconnected(this.user);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (!(msg instanceof Packet<?> packet)) {
            ctx.fireChannelRead(msg);
            return;
        }

        final Packet<?> result = LeafPackets.dispatch(this.user, packet, null);
        if (result == null) {
            ReferenceCountUtil.release(msg);
            return;
        }
        ctx.fireChannelRead(result);
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, ChannelPromise promise) {
        if (!(msg instanceof Packet<?> packet) || this.user.consumeSilent(packet)) {
            ctx.write(msg, promise);
            return;
        }

        final int firstTask = this.sentTasks.size();
        final Packet<?> result = LeafPackets.dispatch(this.user, packet, this.sentTasks);
        if (this.sentTasks.size() > firstTask) {
            final List<Runnable> added = this.sentTasks.subList(firstTask, this.sentTasks.size());
            final Runnable[] tasks = added.toArray(new Runnable[0]);
            added.clear();
            // Vanilla passes void promises when it does not care about the result
            promise = promise.unvoid();
            promise.addListener(future -> LeafPackets.runSentTasks(this.user, tasks));
        }

        if (result == null) {
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
            return;
        }
        ctx.write(result, promise);
    }
}
