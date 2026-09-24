package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class VirtualThreadSupport extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.PERF.getBaseKeyName() + ".use-virtual-thread";
    }

    public static boolean bukkitAsyncScheduler = false;
    public static boolean foliaAsyncScheduler = false;
    public static boolean asyncChatExecutor = true;
    public static boolean downloadPool = false;
    public static boolean authPool = true;
    public static boolean paperConfigurationPool = true;

    @Override
    public void onLoaded() {
        bukkitAsyncScheduler = config.getBoolean(getBasePath() + ".bukkit-async-scheduler", bukkitAsyncScheduler,
                "Use the new Virtual Thread introduced in JDK 21 for CraftAsyncScheduler.");
        foliaAsyncScheduler = config.getBoolean(getBasePath() + ".folia-async-scheduler", foliaAsyncScheduler,
                "Use the new Virtual Thread introduced in JDK 21 for FoliaAsyncScheduler.");
        asyncChatExecutor = config.getBoolean(getBasePath() + ".async-chat-executor", asyncChatExecutor,
                "Use the new Virtual Thread introduced in JDK 21 for Async Chat Executor.");
        downloadPool = config.getBoolean(getBasePath() + ".download-pool", downloadPool,
                "Use the new Virtual Thread introduced in JDK 21 for profile fetching executor.");
        authPool = config.getBoolean(getBasePath() + ".auth-pool", authPool,
                "Use the new Virtual Thread introduced in JDK 21 for user authentication.");
        paperConfigurationPool = config.getBoolean(getBasePath() + ".paper-configuration-pool", paperConfigurationPool,
                "Use the new Virtual Thread introduced in JDK 21 for Paper task pool in configuration phase.");
    }
}
