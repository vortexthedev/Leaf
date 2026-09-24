package org.dreeam.leaf.config.modules.gameplay;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class Knockback extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.GAMEPLAY.getBaseKeyName() + ".knockback";
    }

    public static boolean snowballCanKnockback = false;
    public static boolean eggCanKnockback = false;
    public static boolean canPlayerKnockbackZombie = true;
    public static boolean oldBlastProtectionKnockbackBehavior = false;
    public static boolean useLegacyTrackerTicking = false;

    @Override
    public void onLoaded() {
        snowballCanKnockback = config.getBoolean(getBasePath() + ".snowball-knockback-players", snowballCanKnockback,
                "Make snowball can knockback players.");
        eggCanKnockback = config.getBoolean(getBasePath() + ".egg-knockback-players", eggCanKnockback,
                "Make egg can knockback players.");
        canPlayerKnockbackZombie = config.getBoolean(getBasePath() + ".can-player-knockback-zombie", canPlayerKnockbackZombie,
                "Make players can knockback zombie.");
        oldBlastProtectionKnockbackBehavior = config.getBoolean(getBasePath() + ".old-blast-protection-explosion-knockback", oldBlastProtectionKnockbackBehavior);
        useLegacyTrackerTicking =  config.getBoolean(getBasePath() + ".use-legacy-tracker-ticking", useLegacyTrackerTicking);
    }
}
