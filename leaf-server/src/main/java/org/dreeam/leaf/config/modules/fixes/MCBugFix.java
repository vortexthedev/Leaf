package org.dreeam.leaf.config.modules.fixes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class MCBugFix extends ConfigModule {

    public String basePath() {
        return ConfigCategory.FIXES.basePath() + ".vanilla-bug-fix";
    }

    public static final Logger LOGGER = LogManager.getLogger("Leaf Vanilla Bug Fix");

    public static boolean mc270656 = true;
    public static boolean mc301114 = true;
    public static int mc301114maxCombatEntries = 10240;
    public static boolean mc152094 = false;

    @Override
    public void onLoaded() {
        globalConfig.addCommentRegionBased(basePath(),
            "Fixes for vanilla Minecraft bugs.");
        mc270656 = globalConfig.getBoolean(basePath() + ".mc-270656", mc270656, """
                Whether to fix incorrect granting of 'Who needs rockets?' advancement.
                Mojira link: https://mojira.dev/MC-270656""");
        mc301114 = globalConfig.getBoolean(basePath() + ".mc-301114", mc301114, """
                Whether to fix the memory leak in the combat tracker caused by the mob constantly being damaged.
                Mojira link: https://mojira.dev/MC-301114""");
        mc301114maxCombatEntries = globalConfig.getInt(basePath() + ".mc-301114-max-entries", mc301114maxCombatEntries, "Max allowed entries in mob's combat tracker.");

        mc301114maxCombatEntries = Math.max(1, mc301114maxCombatEntries);

        mc152094 = globalConfig.getBoolean(basePath() + ".mc-152094", mc152094, """
                Whether to fix the bug End City ship generation gets cut at chunk borders.
                Mojira link: https://mojira.dev/MC-152094"""
        );
    }
}
