package org.dreeam.leaf.network;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * A registered {@link PacketHandler}.
 */
public final class PacketRegistration implements Registration {

    final Plugin plugin;
    final PacketFlow flow;
    final @Nullable PacketType<?> type;
    final PacketPriority priority;
    final boolean ignoreCancelled;
    final PacketHandler<Packet<?>> handler;
    final long order;
    final boolean handling;

    PacketRegistration(
        final Plugin plugin,
        final PacketFlow flow,
        final @Nullable PacketType<?> type,
        final PacketPriority priority,
        final boolean ignoreCancelled,
        final PacketHandler<Packet<?>> handler,
        final long order
    ) {
        this(plugin, flow, type, priority, ignoreCancelled, handler, order, false);
    }

    PacketRegistration(
        final Plugin plugin,
        final PacketFlow flow,
        final @Nullable PacketType<?> type,
        final PacketPriority priority,
        final boolean ignoreCancelled,
        final PacketHandler<Packet<?>> handler,
        final long order,
        final boolean handling
    ) {
        this.plugin = plugin;
        this.flow = flow;
        this.type = type;
        this.priority = priority;
        this.ignoreCancelled = ignoreCancelled;
        this.handler = handler;
        this.order = order;
        this.handling = handling;
    }

    @Override
    public Plugin plugin() {
        return this.plugin;
    }

    public PacketFlow flow() {
        return this.flow;
    }

    /**
     * @return the packet type this handler listens to, or {@code null} if it listens to every packet of its {@link #flow()}
     */
    public @Nullable PacketType<?> type() {
        return this.type;
    }

    public PacketPriority priority() {
        return this.priority;
    }

    public boolean ignoreCancelled() {
        return this.ignoreCancelled;
    }

    public boolean isHandling() {
        return this.handling;
    }

    @Override
    public void unregister() {
        LeafPackets.unregister(this);
    }
}
