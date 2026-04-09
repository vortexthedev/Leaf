package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class SpawnerSettings extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.GAMEPLAY.getBaseKeyName() + ".spawner-settings";
    }

    // Global toggle
    public static boolean enabled = false;

    // Default values for spawner settings
    public static boolean lightLevelCheck = false;
    public static boolean spawnerMaxNearbyCheck = true;
    public static boolean checkForNearbyPlayers = true;
    public static boolean spawnerBlockChecks = false;
    public static boolean waterPreventSpawnCheck = false;
    public static boolean ignoreSpawnRules = false;

    public static int minSpawnDelay = 200;
    public static int maxSpawnDelay = 800;

    @Override
    public void onLoaded() {
        config.addCommentRegionBased(getBasePath(),
            "This section contains settings for mob spawner blocks.");

        // Global toggle
        enabled = config.getBoolean(getBasePath() + ".enabled", enabled,
                "Enable custom spawner settings. Set to true to enable all features below.");

        // Checks section
        config.addCommentRegionBased(getBasePath() + ".checks",
            "Various checks that can be enabled or disabled for spawner blocks.");

        lightLevelCheck = config.getBoolean(getBasePath() + ".checks.light-level-check", lightLevelCheck,
                "Check if there is the required light level to spawn the mob");

        spawnerMaxNearbyCheck = config.getBoolean(getBasePath() + ".checks.spawner-max-nearby-check", spawnerMaxNearbyCheck,
                "Check if there are the max amount of nearby mobs to spawn the mob");

        checkForNearbyPlayers = config.getBoolean(getBasePath() + ".checks.check-for-nearby-players", checkForNearbyPlayers,
                "Check if any players are in a radius to spawn the mob");

        spawnerBlockChecks = config.getBoolean(getBasePath() + ".checks.spawner-block-checks", spawnerBlockChecks,
                "Check if there are physical blocks obstructing the spawn location, or if custom spawn rules (isValidPosition) fail due to block conditions.");

        waterPreventSpawnCheck = config.getBoolean(getBasePath() + ".checks.water-prevent-spawn-check", waterPreventSpawnCheck,
                "Checks if there is water around that prevents spawning");
        ignoreSpawnRules = config.getBoolean(getBasePath() + ".checks.ignore-spawn-rules", ignoreSpawnRules,
                "Ignore mob-specific spawn rules, like animals needing grass or specific biomes/blocks (does not affect light level or physical obstruction checks).");

        // Delay settings

        minSpawnDelay = config.getInt(getBasePath() + ".min-spawn-delay", minSpawnDelay,
                "Minimum delay (in ticks) between spawner spawns. Higher values slow down spawners.");

        maxSpawnDelay = config.getInt(getBasePath() + ".max-spawn-delay", maxSpawnDelay,
                "Maximum delay (in ticks) between spawner spawns. Higher values slow down spawners.");
    }
}
