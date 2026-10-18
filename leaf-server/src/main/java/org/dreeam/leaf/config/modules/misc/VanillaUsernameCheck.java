package org.dreeam.leaf.config.modules.misc;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;


public class VanillaUsernameCheck extends ConfigModule {

    public String basePath() {
        return ConfigCategory.MISC.basePath() + ".vanilla-username-check";
    }

    public static boolean enforceSkullValidation = true;

    @Override
    public void onLoaded() {
        enforceSkullValidation = globalConfig.getBoolean(basePath() + ".enforce-skull-validation", enforceSkullValidation, """
                Enforce skull validation,
                preventing skulls with invalid names from disconnecting the client.""");
    }
}
