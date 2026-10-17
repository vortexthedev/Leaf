package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class Knockback extends ConfigModule {

    public String basePath() {
        return ConfigCategory.GAMEPLAY.basePath() + ".knockback";
    }

    public static boolean snowballCanKnockback = false;
    public static boolean eggCanKnockback = false;
    public static boolean canPlayerKnockbackZombie = true;
    public static boolean oldBlastProtectionKnockbackBehavior = false;
    public static boolean useLegacyTrackerTicking = false;
    private static boolean legacyTrackerTickingInitialized;

    @Override
    public void onLoaded() {
        snowballCanKnockback = globalConfig.getBoolean(basePath() + ".snowball-knockback-players", snowballCanKnockback,
            "Make snowball can knockback players.");
        eggCanKnockback = globalConfig.getBoolean(basePath() + ".egg-knockback-players", eggCanKnockback,
            "Make egg can knockback players.");
        canPlayerKnockbackZombie = globalConfig.getBoolean(basePath() + ".can-player-knockback-zombie", canPlayerKnockbackZombie,
            "Make players can knockback zombie.");
        oldBlastProtectionKnockbackBehavior = globalConfig.getBoolean(basePath() + ".old-blast-protection-explosion-knockback", oldBlastProtectionKnockbackBehavior);

        if (legacyTrackerTickingInitialized) {
            globalConfig.getConfigSection(basePath());
            return;
        }
        legacyTrackerTickingInitialized = true;

        useLegacyTrackerTicking = globalConfig.getBoolean(basePath() + ".use-legacy-tracker-ticking", useLegacyTrackerTicking);
    }
}
