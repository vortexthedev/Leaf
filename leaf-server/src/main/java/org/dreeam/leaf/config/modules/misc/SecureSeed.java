package org.dreeam.leaf.config.modules.misc;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class SecureSeed extends ConfigModule {

    public String basePath() {
        return ConfigCategory.MISC.basePath() + ".secure-seed";
    }

    public static boolean enabled = false;

    @Override
    public void onLoaded() {
        globalConfig.addCommentRegionBased(basePath(), """
                Once you enable secure seed, all ores and structures are generated with 1024-bit seed
                instead of using 64-bit seed in vanilla, made seed cracker become impossible.""");

        enabled = globalConfig.getBoolean(basePath() + ".enabled", enabled);
    }
}
