package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;
import org.dreeam.leaf.config.annotations.Experimental;

public class MutableBlockPos extends ConfigModule {

    public String basePath() {
        return ConfigCategory.PERF.basePath();
    }

    @Experimental
    public static boolean enabled = false;

    @Override
    public void onLoaded() {
        enabled = globalConfig.getBoolean(basePath() + ".reuse-random-ticking-blockpos", enabled,
            """
                Experimental feature.
                Reuse BlockPos to reduce memory allocation slightly and improve performance on random ticking.
                May conflict with certain plugins or operations. Disable if position issues occur.""");
    }
}
