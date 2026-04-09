package org.dreeam.leaf.config.modules.network;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class OptimizeNonFlushPacketSending extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.NETWORK.getBaseKeyName();
    }

    public static boolean enabled = false;

    @Override
    public void onLoaded() {
        enabled = config.getBoolean(getBasePath() + ".OptimizeNonFlushPacketSending", enabled, """
                WARNING: This option is NOT compatible with ProtocolLib and may cause
                issues with other plugins that modify packet handling.
                
                Optimizes non-flush packet sending by using Netty's lazyExecute method to avoid
                expensive thread wakeup calls when scheduling packet operations.
                
                Requires server restart to take effect.""");
    }
}
