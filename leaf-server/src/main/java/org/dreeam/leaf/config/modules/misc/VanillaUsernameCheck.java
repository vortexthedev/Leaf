package org.dreeam.leaf.config.modules.misc;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;
import org.dreeam.leaf.config.LeafConfig;
import org.dreeam.leaf.config.annotations.Experimental;

import java.util.regex.Pattern;

public class VanillaUsernameCheck extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.MISC.getBaseKeyName() + ".vanilla-username-check";
    }

    public static boolean enforceSkullValidation = true;

    @Override
    public void onLoaded() {
        enforceSkullValidation = config.getBoolean(getBasePath() + ".enforce-skull-validation", enforceSkullValidation, """
                Enforce skull validation,
                preventing skulls with invalid names from disconnecting the client.""");
    }
}
