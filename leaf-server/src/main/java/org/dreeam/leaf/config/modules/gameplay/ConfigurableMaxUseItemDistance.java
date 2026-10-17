package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class ConfigurableMaxUseItemDistance extends ConfigModule {

    public String basePath() {
        return ConfigCategory.GAMEPLAY.basePath() + ".player";
    }

    public static double maxUseItemDistance = 1.0000001;

    @Override
    public void onLoaded() {
        maxUseItemDistance = globalConfig.getDouble(basePath() + ".max-use-item-distance", maxUseItemDistance, """
                The max distance of UseItem for players.
                Set to -1 to disable max-distance-check.
                NOTE: if set to -1 to disable the check,
                players are able to use some packet modules of hack clients,
                and NoCom Exploit!!""");
    }
}
