package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;
import org.dreeam.leaf.config.annotations.Experimental;

public class DespawnTime extends ConfigModule {

    public String basePath() {
        return ConfigCategory.PERF.basePath() + ".despawn-time";
    }

    @Experimental
    public static boolean proactiveWeakLoading = false;

    @Override
    public void onLoaded() {
        proactiveWeakLoading = globalConfig.getBoolean(basePath() + ".proactive-weak-loading-despawn", proactiveWeakLoading,
            """
                    Proactive despawn check for weak-loaded entities.
                    This is an experimental feature.""");
    }
}
