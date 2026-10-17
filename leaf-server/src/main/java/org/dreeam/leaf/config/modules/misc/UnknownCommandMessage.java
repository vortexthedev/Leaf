package org.dreeam.leaf.config.modules.misc;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class UnknownCommandMessage extends ConfigModule {

    public String basePath() {
        return ConfigCategory.MISC.basePath() + ".message";
    }

    public static String unknownCommandMessage = "default";

    @Override
    public void onLoaded() {
        unknownCommandMessage = globalConfig.getString(basePath() + ".unknown-command", unknownCommandMessage, """
                Unknown command message, using MiniMessage format, set to "default" to use vanilla message,
                placeholder:
                <message>, show message of the command exception.
                <detail>, shows detail of the command exception.""");
    }
}
