package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class DeathItemDropKnockback extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.GAMEPLAY.getBaseKeyName() + ".death-item-drop-knockback";
    }

    public static boolean dropAround = true;
    public static double horizontalForce = 0.5;
    public static double verticalForce = 0.2;

    @Override
    public void onLoaded() {
        dropAround = config.getBoolean(getBasePath() + ".drop-around", dropAround,
                "If true, items will drop randomly around the player on death.");

        horizontalForce = config.getDouble(getBasePath() + ".horizontal-force", horizontalForce,
                "Base speed for horizontal velocity when randomly dropping items.");

        verticalForce = config.getDouble(getBasePath() + ".vertical-force", verticalForce,
                "Upward motion for randomly dropped items.");
    }
}
