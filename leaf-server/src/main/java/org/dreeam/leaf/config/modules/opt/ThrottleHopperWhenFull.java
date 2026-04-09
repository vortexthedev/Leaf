package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class ThrottleHopperWhenFull extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName() + ".throttle-hopper-when-full";
    }

    public static boolean enabled = false;
    public static int skipTicks = 8;

    @Override
    public void onLoaded() {
        enabled = config.getBoolean(getBasePath() + ".enabled", enabled, """
                Throttles the hopper if target container is full.""");
        skipTicks = config.getInt(getBasePath() + ".skip-ticks", skipTicks, """
                How many ticks to throttle when the Hopper is throttled.""");
    }
}
