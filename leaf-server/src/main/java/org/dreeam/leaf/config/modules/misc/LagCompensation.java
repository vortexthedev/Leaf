package org.dreeam.leaf.config.modules.misc;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class LagCompensation extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.MISC.getBaseKeyName() + ".lag-compensation";
    }

    public static boolean enabled = false;
    public static boolean enableForWater = false;
    public static boolean enableForLava = false;

    @Override
    public void onLoaded() {
        config.addCommentRegionBased(getBasePath(), """
                This section contains lag compensation features,
                which could ensure basic playing experience during a lag.""");

        enabled = config.getBoolean(getBasePath() + ".enabled", enabled);
        enableForWater = config.getBoolean(getBasePath() + ".enable-for-water", enableForWater);
        enableForLava = config.getBoolean(getBasePath() + ".enable-for-lava", enableForLava);
    }
}
