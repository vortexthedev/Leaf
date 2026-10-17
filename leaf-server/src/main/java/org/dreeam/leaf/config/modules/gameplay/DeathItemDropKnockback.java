package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class DeathItemDropKnockback extends ConfigModule {

    public String basePath() {
        return ConfigCategory.GAMEPLAY.basePath() + ".death-item-drop-knockback";
    }

    public static boolean dropAround = true;
    public static double horizontalForce = 0.5;
    public static double verticalForce = 0.2;

    @Override
    public void onLoaded() {
        dropAround = globalConfig.getBoolean(basePath() + ".drop-around", dropAround,
            "If true, items will drop randomly around the player on death.");

        horizontalForce = globalConfig.getDouble(basePath() + ".horizontal-force", horizontalForce,
            "Base speed for horizontal velocity when randomly dropping items.");

        verticalForce = globalConfig.getDouble(basePath() + ".vertical-force", verticalForce,
            "Upward motion for randomly dropped items.");
    }
}
