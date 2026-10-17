package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class VirtualThreadSupport extends ConfigModule {

    public String basePath() {
        return ConfigCategory.PERF.basePath() + ".use-virtual-thread";
    }

    public static boolean bukkitAsyncScheduler = false;
    public static boolean foliaAsyncScheduler = false;
    public static boolean asyncChatExecutor = true;
    public static boolean downloadPool = false;

    @Override
    public void onLoaded() {
        bukkitAsyncScheduler = globalConfig.getBoolean(basePath() + ".bukkit-async-scheduler", bukkitAsyncScheduler,
            "Use the new Virtual Thread introduced in JDK 21 for CraftAsyncScheduler.");
        foliaAsyncScheduler = globalConfig.getBoolean(basePath() + ".folia-async-scheduler", foliaAsyncScheduler,
            "Use the new Virtual Thread introduced in JDK 21 for FoliaAsyncScheduler.");
        asyncChatExecutor = globalConfig.getBoolean(basePath() + ".async-chat-executor", asyncChatExecutor,
            "Use the new Virtual Thread introduced in JDK 21 for Async Chat Executor.");
        downloadPool = globalConfig.getBoolean(basePath() + ".download-pool", downloadPool,
            "Use the new Virtual Thread introduced in JDK 21 for profile fetching executor.");
    }
}
