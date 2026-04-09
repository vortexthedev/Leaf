package org.dreeam.leaf.config.modules.misc;

import org.dreeam.leaf.config.ConfigModules;
import org.dreeam.leaf.config.EnumConfigCategory;

public class UnknownCommandMessage extends ConfigModules {

    public String getBasePath() {
        return EnumConfigCategory.MISC.getBaseKeyName() + ".message";
    }

    public static String unknownCommandMessage = "default";

    @Override
    public void onLoaded() {
        unknownCommandMessage = config.getString(getBasePath() + ".unknown-command", unknownCommandMessage, """
                Unknown command message, using MiniMessage format, set to "default" to use vanilla message,
                placeholder:
                <message>, show message of the command exception.
                <detail>, shows detail of the command exception.""");
    }
}
