package org.dreeam.leaf.config.modules.async;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class AsyncMobSpawning extends ConfigModule {

    public String basePath() {
        return ConfigCategory.ASYNC.basePath() + ".async-mob-spawning";
    }

    public static boolean enabled = true;
    private static boolean asyncMobSpawningInitialized;

    @Override
    public void onLoaded() {
        globalConfig.addCommentRegionBased(basePath(), """
                Whether or not asynchronous mob spawning should be enabled.
                On servers with many entities, this can improve performance by up to 15%. You must have
                paper's per-player-mob-spawns setting set to true for this to work.
                One quick note - this does not actually spawn mobs async (that would be very unsafe).
                This just offloads some expensive calculations that are required for mob spawning.""");

        // This prevents us from changing the value during a reload.
        if (asyncMobSpawningInitialized) {
            globalConfig.getConfigSection(basePath());
            return;
        }
        asyncMobSpawningInitialized = true;

        enabled = globalConfig.getBoolean(basePath() + ".enabled", enabled);
    }
}
