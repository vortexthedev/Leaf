package org.dreeam.leaf.config.modules.misc;

import org.dreeam.leaf.config.ConfigCategory;
import org.dreeam.leaf.config.ConfigModule;

import java.util.Locale;

public class ServerGui extends ConfigModule {

    public static String theme = "system";

    public String basePath() {
        return ConfigCategory.MISC.basePath() + ".gui";
    }

    @Override
    public void onLoaded() {
        globalConfig.addCommentRegionBased(basePath() + ".theme", """
                GUI theme: system, dark, or light.""");
        String configuredTheme = globalConfig.getString(basePath() + ".theme", "system").trim().toLowerCase(Locale.ROOT);
        theme = switch (configuredTheme) {
            case "system", "dark", "light" -> configuredTheme;
            default -> {
                LOGGER.warn("Unknown GUI theme '{}', using system. Expected system, dark, or light.", configuredTheme);
                yield "system";
            }
        };
    }
}
