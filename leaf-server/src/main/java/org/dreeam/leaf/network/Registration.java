package org.dreeam.leaf.network;

import org.bukkit.plugin.Plugin;

/**
 * A registered handler or listener. It is removed with {@link #unregister()} or when its plugin is disabled.
 */
public interface Registration {

    Plugin plugin();

    void unregister();
}
