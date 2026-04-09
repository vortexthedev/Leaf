package org.dreeam.leaf.config.modules.async;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class AsyncChunkSend extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.ASYNC.getBaseKeyName() + ".async-chunk-send";
    }

    public static boolean enabled = false;
    private static boolean asyncChunkSendInitialized;

    @Override
    public void onLoaded() {
        config.addCommentRegionBased(getBasePath(), """
                Makes chunk packet preparation and sending asynchronous to improve server performance.
                This can significantly reduce main thread load when many players are loading chunks.""");

        if (asyncChunkSendInitialized) {
            config.getConfigSection(getBasePath());
            return;
        }
        asyncChunkSendInitialized = true;

        enabled = config.getBoolean(getBasePath() + ".enabled", enabled);
    }
}
