package org.dreeam.leaf.config.modules.misc;

import net.minecraft.server.level.ServerLevel;
import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;
import org.dreeam.leaf.util.list.WorldList;

import java.util.ArrayList;

public class DisableWorldDataSaving extends ConfigModule {

    public static WorldList worlds = WorldList.EMPTY;

    public String basePath() {
        return ConfigCategory.MISC.basePath() + ".disable-world-data-saving";
    }

    @Override
    public void onLoaded() {
        worlds = new WorldList(globalConfig.getList(basePath() + ".worlds", new ArrayList<>(),
            """
                    Worlds listed here will skip world data persistence.
                    Changes in chunks/entities remain in memory until unload/restart and are not written to disk.
                    '*' works as a wildcard, e.g. 'hub*' matches hub01 and hub02, '*hub*' matches every world
                    whose name contains 'hub'. Entries without '*' are matched exactly."""));
    }

    public static boolean shouldSkipSave(ServerLevel serverLevel) {
        return !worlds.isEmpty() && worlds.contains(serverLevel.getWorld().getName());
    }
}
