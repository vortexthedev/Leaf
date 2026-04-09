package org.dreeam.leaf.config.modules.misc;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class ConnectionMessage extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.MISC.getBaseKeyName() + ".connection-message";
    }

    public static boolean joinEnabled = true;
    public static String joinMessage = "default";
    public static boolean quitEnabled = true;
    public static String quitMessage = "default";

    @Override
    public void onLoaded() {
        config.addCommentRegionBased(getBasePath(), """
                Connection message, using MiniMessage format, set to "default" to use vanilla join message.
                available placeholders:
                <player_name> - player name
                <player_displayname> - player display name""");

        joinEnabled = config.getBoolean(getBasePath() + ".join.enabled", joinEnabled);
        joinMessage = config.getString(getBasePath() + ".join.message", joinMessage,
            "Join message of player");

        quitEnabled = config.getBoolean(getBasePath() + ".quit.enabled", quitEnabled);
        quitMessage = config.getString(getBasePath() + ".quit.message", quitMessage,
            "Quit message of player");

        // Legacy compatibility
        // TODO: config migration
        joinMessage = joinMessage
            .replace("%player_name%", "<player_name>")
            .replace("%player_displayname%", "<player_displayname>");
        quitMessage = quitMessage
            .replace("%player_name%", "<player_name>")
            .replace("%player_displayname%", "<player_displayname>");
    }
}
