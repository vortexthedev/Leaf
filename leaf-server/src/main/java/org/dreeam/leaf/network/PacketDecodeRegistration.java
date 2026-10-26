package org.dreeam.leaf.network;

import org.bukkit.plugin.Plugin;

public final class PacketDecodeRegistration implements Registration {
    final Plugin plugin;
    final PacketPriority priority;
    final boolean ignoreCancelled;
    final PacketDecodeHandler handler;
    final long order;

    PacketDecodeRegistration(Plugin plugin, PacketPriority priority, boolean ignoreCancelled, PacketDecodeHandler handler, long order) {
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
