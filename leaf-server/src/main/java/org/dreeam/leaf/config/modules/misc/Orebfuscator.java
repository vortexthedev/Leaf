package org.dreeam.leaf.config.modules.misc;

import io.github.thatsmusic99.configurationmaster.api.ConfigSection;
import org.dreeam.leaf.config.ConfigCategory;
import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.orebfuscator.OrebfuscatorSettings;

import java.util.Map;

public class Orebfuscator extends ConfigModule {

    public String basePath() {
        return ConfigCategory.MISC.basePath() + ".orebfuscator";
    }

    public static boolean enabled = false;
    // Plain copy of the settings section, read once when the built-in Orebfuscator starts
    public static Map<String, Object> settings = Map.of();

    @Override
    public void onLoaded() {
        globalConfig.addCommentRegionBased(basePath(), """
                Built-in Orebfuscator, hides ores and other blocks from x-ray clients.
                Chunks are obfuscated before they are sent, no packet library is needed.
                Remove the Orebfuscator plugin before enabling it. Changes need a restart.""");

        enabled = globalConfig.getBoolean(basePath() + ".enabled", enabled);

        final String settingsPath = basePath() + ".settings";
        globalConfig.addComment(settingsPath, """
                Same format as the config.yml of the Orebfuscator plugin.
                On first start it is imported from plugins/Orebfuscator/config.yml if that file exists.""");

        final ConfigSection section = globalConfig.getConfigSection(settingsPath);
        if (section == null || section.isEmpty()) {
            final Map<String, Object> defaults = OrebfuscatorSettings.loadDefaults();
            globalConfig.set(settingsPath, defaults);
            // the value is stored as plain map, it is only a ConfigSection after the file is loaded again
            settings = OrebfuscatorSettings.copy(defaults);
        } else {
            settings = OrebfuscatorSettings.copy(section);
        }
    }
}
