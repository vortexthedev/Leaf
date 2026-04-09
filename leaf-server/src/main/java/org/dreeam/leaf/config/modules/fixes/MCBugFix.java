package org.dreeam.leaf.config.modules.fixes;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class MCBugFix extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.FIXES.getBaseKeyName() + ".vanilla-bug-fix";
    }

    public static boolean mc270656 = false;

    @Override
    public void onLoaded() {
        config.addCommentRegionBased(getBasePath(),
            "Fixes for vanilla Minecraft bugs."
        );
        mc270656 = config.getBoolean(getBasePath() + ".mc-270656", mc270656,
            """
                Whether to fix incorrect granting of 'Who needs rockets?' advancement.
                Mojira link: https://mojira.dev/MC-270656""");
    }
}
