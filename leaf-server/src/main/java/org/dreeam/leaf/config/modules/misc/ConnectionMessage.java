package org.dreeam.leaf.config.modules.misc;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class ConnectionMessage extends ConfigModule {

    public String basePath() {
        return ConfigCategory.MISC.basePath() + ".connection-message";
    }

    public static boolean joinEnabled = true;
    public static String joinMessage = "default";
    public static boolean quitEnabled = true;
    public static String quitMessage = "default";

    @Override
    public void onLoaded() {
        globalConfig.addCommentRegionBased(basePath(), """
                Connection message, using MiniMessage format, set to "default" to use vanilla join message.
                available placeholders:
                <player_name> - player name
                <player_displayname> - player display name""");

        joinEnabled = globalConfig.getBoolean(basePath() + ".join.enabled", joinEnabled);
        joinMessage = globalConfig.getString(basePath() + ".join.message", joinMessage, "Join message of player");

        quitEnabled = globalConfig.getBoolean(basePath() + ".quit.enabled", quitEnabled);
        quitMessage = globalConfig.getString(basePath() + ".quit.message", quitMessage, "Quit message of player");

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
