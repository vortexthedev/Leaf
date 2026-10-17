package org.dreeam.leaf.config.modules.fixes;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class PreventMoveIntoWeakLoadedChunks extends ConfigModule {

    public String basePath() {
        return ConfigCategory.FIXES.basePath() + ".prevent-moving-into-weak-loaded-chunks";
    }

    public static boolean enabled = false;
    public static boolean projectiles = false;

    public static boolean isProjectileEnabled() {
        return enabled && projectiles;
    }

    @Override
    public void onLoaded() {
        globalConfig.addCommentRegionBased(basePath(),
            "Prevents entities from moving into weak loaded chunks.");

        enabled = globalConfig.getBoolean(basePath() + ".enabled", enabled, "Set to true to enable features below.");

        projectiles = globalConfig.getBoolean(basePath() + ".projectiles", projectiles, "Prevents projectiles from moving into weak loaded chunks.");
    }
}
