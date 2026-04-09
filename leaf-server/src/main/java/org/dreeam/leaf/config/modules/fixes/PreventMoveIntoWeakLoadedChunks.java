package org.dreeam.leaf.config.modules.fixes;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class PreventMoveIntoWeakLoadedChunks extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.FIXES.getBaseKeyName() + ".prevent-moving-into-weak-loaded-chunks";
    }

    public static boolean enabled = false;
    public static boolean projectiles = false;

    public static boolean isProjectileEnabled() {
        return enabled && projectiles;
    }

    @Override
    public void onLoaded() {
        config.addCommentRegionBased(getBasePath(),
            "Prevents entities from moving into weak loaded chunks."
        );

        enabled = config.getBoolean(getBasePath() + ".enabled", enabled,
            "Set to true to enable features below."
        );

        projectiles = config.getBoolean(getBasePath() + ".projectiles", projectiles,
            "Prevents projectiles from moving into weak loaded chunks.");
    }
}
