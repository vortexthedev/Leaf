package org.dreeam.leaf.network;

import org.bukkit.plugin.Plugin;

public final class PacketFrameRegistration implements Registration {
    final Plugin plugin;
    final PacketPriority priority;
    final boolean ignoreCancelled;
    final PacketFrameHandler handler;
    final long order;

    PacketFrameRegistration(Plugin plugin, PacketPriority priority, boolean ignoreCancelled, PacketFrameHandler handler, long order) {
        this.plugin = plugin;
        this.priority = priority;
        this.ignoreCancelled = ignoreCancelled;
        this.handler = handler;
        this.order = order;
    }

    @Override
    public Plugin plugin() {
        return plugin;
    }

    @Override
    public void unregister() {
        LeafPackets.unregister(this);
    }
}
