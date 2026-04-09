package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;
import org.dreeam.leaf.config.annotations.Experimental;

public class MutableBlockPos extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName();
    }

    @Experimental
    public static boolean enabled = false;

    @Override
    public void onLoaded() {
        enabled = config.getBoolean(getBasePath() + ".reuse-random-ticking-blockpos", enabled,
            """
                Experimental feature.
                Reuse BlockPos to reduce memory allocation slightly and improve performance on random ticking.
                May conflict with certain plugins or operations. Disable if position issues occur.""");
    }
}
