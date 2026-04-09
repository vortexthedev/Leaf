package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class CheckSurvivalBeforeGrowth extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName() + ".check-survival-before-growth";
    }

    public static boolean cactusCheckSurvivalBeforeGrowth = false;

    @Override
    public void onLoaded() {
        cactusCheckSurvivalBeforeGrowth = config.getBoolean(getBasePath() + ".cactus-check-survival", cactusCheckSurvivalBeforeGrowth,"""
                    Check if a cactus can survive before growing.""");
    }
}
