package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class ConfigurableMaxUseItemDistance extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.GAMEPLAY.getBaseKeyName() + ".player";
    }

    public static double maxUseItemDistance = 1.0000001;

    @Override
    public void onLoaded() {
        maxUseItemDistance = config.getDouble(getBasePath() + ".max-use-item-distance", maxUseItemDistance, """
                The max distance of UseItem for players.
                Set to -1 to disable max-distance-check.
                NOTE: if set to -1 to disable the check,
                players are able to use some packet modules of hack clients,
                and NoCom Exploit!!""");
    }
}
