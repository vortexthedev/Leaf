package org.dreeam.leaf.config.modules.misc;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class ServerBrand extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.MISC.getBaseKeyName() + ".rebrand";
    }

    public static String serverModName = io.papermc.paper.ServerBuildInfo.buildInfo().brandName();

    @Override
    public void onLoaded() {
        serverModName = config.getString(getBasePath() + ".server-mod-name", serverModName);
    }
}
