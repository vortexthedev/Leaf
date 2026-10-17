package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class SpawnerSettings extends ConfigModule {

    public String basePath() {
        return ConfigCategory.GAMEPLAY.basePath() + ".spawner-settings";
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
        globalConfig.addCommentRegionBased(basePath(),
            "This section contains settings for mob spawner blocks.");

        // Global toggle
        enabled = globalConfig.getBoolean(basePath() + ".enabled", enabled,
            "Enable custom spawner settings. Set to true to enable all features below.");

        // Checks section
        globalConfig.addCommentRegionBased(basePath() + ".checks",
            "Various checks that can be enabled or disabled for spawner blocks.");

        lightLevelCheck = globalConfig.getBoolean(basePath() + ".checks.light-level-check", lightLevelCheck,
            "Apply Leaf's additional light-level check. Custom light limits are always checked.");

        spawnerMaxNearbyCheck = globalConfig.getBoolean(basePath() + ".checks.spawner-max-nearby-check", spawnerMaxNearbyCheck,
            "Check if there are the max amount of nearby mobs to spawn the mob");

        checkForNearbyPlayers = globalConfig.getBoolean(basePath() + ".checks.check-for-nearby-players", checkForNearbyPlayers,
            "Check if any players are in a radius to spawn the mob");

        spawnerBlockChecks = globalConfig.getBoolean(basePath() + ".checks.spawner-block-checks", spawnerBlockChecks,
            "Check physical collisions and mob spawn obstructions. Custom light limits are always checked.");

        waterPreventSpawnCheck = globalConfig.getBoolean(basePath() + ".checks.water-prevent-spawn-check", waterPreventSpawnCheck,
            "Checks if there is water around that prevents spawning");
        ignoreSpawnRules = globalConfig.getBoolean(basePath() + ".checks.ignore-spawn-rules", ignoreSpawnRules,
            "Ignore mob-specific spawn rules, like animals needing grass or specific biomes/blocks (does not affect light level or physical obstruction checks).");

        // Delay settings

        minSpawnDelay = globalConfig.getInt(basePath() + ".min-spawn-delay", minSpawnDelay,
            "Minimum delay (in ticks) between spawner spawns. Higher values slow down spawners.");

        maxSpawnDelay = globalConfig.getInt(basePath() + ".max-spawn-delay", maxSpawnDelay,
            "Maximum delay (in ticks) between spawner spawns. Higher values slow down spawners.");
    }
}
