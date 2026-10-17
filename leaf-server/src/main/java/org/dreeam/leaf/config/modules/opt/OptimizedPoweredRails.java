package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class OptimizedPoweredRails extends ConfigModule {

    public String basePath() {
        return ConfigCategory.PERF.basePath() + ".optimized-powered-rails";
    }

    public static boolean enabled = false;

    @Override
    public void onLoaded() {
        enabled = globalConfig.getBoolean(basePath(), enabled,
            """
                    Whether to use optimized powered rails.
                    The implementation is based on RailOptimization made by GitHub@FxMorin""");
    }
}
