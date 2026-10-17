package org.dreeam.leaf.config.modules.network;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class OptimizeNonFlushPacketSending extends ConfigModule {

    public String basePath() {
        return ConfigCategory.NETWORK.basePath();
    }

    public static boolean enabled = false;

    @Override
    public void onLoaded() {
        enabled = globalConfig.getBoolean(basePath() + ".OptimizeNonFlushPacketSending", enabled, """
                WARNING: This option is NOT compatible with ProtocolLib and may cause
                issues with other plugins that modify packet handling.
                
                Optimizes non-flush packet sending by using Netty's lazyExecute method to avoid
                expensive thread wakeup calls when scheduling packet operations.
                
                Requires server restart to take effect.""");
    }
}
