package org.dreeam.leaf.network;

import org.bukkit.plugin.Plugin;

/**
 * A registered {@link ConnectionListener}.
 */
public final class ConnectionRegistration implements Registration {

    final Plugin plugin;
    final ConnectionListener listener;

    ConnectionRegistration(final Plugin plugin, final ConnectionListener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    @Override
    public Plugin plugin() {
        return this.plugin;
    }

    public ConnectionListener listener() {
        return this.listener;
    }

    @Override
    public void unregister() {
        LeafPackets.unregister(this);
    }
}
