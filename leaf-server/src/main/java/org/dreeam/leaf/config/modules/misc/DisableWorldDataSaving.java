package org.dreeam.leaf.config.modules.misc;

import net.minecraft.server.level.ServerLevel;
import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

import java.util.ArrayList;
import java.util.List;

public class DisableWorldDataSaving extends ConfigModules {

    public static List<String> worlds = new ArrayList<>();

    public String getBasePath() {
        return EnumConfigCategory.MISC.getBaseKeyName() + ".disable-world-data-saving";
    }

    @Override
    public void onLoaded() {
        worlds = config.getList(getBasePath() + ".worlds", worlds,
            """
                Worlds listed here will skip world data persistence.
                Changes in chunks/entities remain in memory until unload/restart and are not written to disk.""");
    }

    public static boolean shouldSkipSave(ServerLevel serverLevel) {
        return !worlds.isEmpty() && worlds.contains(serverLevel.getWorld().getName());
    }
}
