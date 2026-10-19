package org.dreeam.leaf.orebfuscator;

import org.dreeam.leaf.config.LeafConfig;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default Orebfuscator settings for the Leaf config section.
 */
public final class OrebfuscatorSettings {

    // Same version the core expects, see OrebfuscatorConfig#CONFIG_VERSION
    private static final int CONFIG_VERSION = 6;
    private static final Path PLUGIN_CONFIG = Path.of("plugins", "Orebfuscator", "config.yml");

    private OrebfuscatorSettings() {
    }

    /**
     * Settings used when the Leaf config has no Orebfuscator settings yet: the config of the
     * Orebfuscator plugin if it exists, the bundled default otherwise.
     */
    public static Map<String, Object> loadDefaults() {
        if (Files.isRegularFile(PLUGIN_CONFIG)) {
            try {
                final Map<String, Object> imported = parse(Files.readString(PLUGIN_CONFIG, StandardCharsets.UTF_8));
                if (!imported.isEmpty()) {
                    // configs of old plugin versions are not migrated anymore
                    if (!Integer.valueOf(CONFIG_VERSION).equals(imported.get("version"))) {
                        LeafConfig.LOGGER.warn("{} has version {}, only version {} can be imported, using defaults",
                            PLUGIN_CONFIG, imported.get("version"), CONFIG_VERSION);
                        return parse(DefaultSettings.YAML);
                    }
                    LeafConfig.LOGGER.info("Imported Orebfuscator settings from {}", PLUGIN_CONFIG);
                    return imported;
                }
            } catch (IOException | RuntimeException e) {
                LeafConfig.LOGGER.warn("Failed to import Orebfuscator settings from {}, using defaults", PLUGIN_CONFIG, e);
            }
        }

        return parse(DefaultSettings.YAML);
    }

    /**
     * Deep copy of a config section into plain maps and lists, so it no longer depends on the
     * config file instance which is replaced on every reload.
     */
    public static Map<String, Object> copy(final @Nullable Map<?, ?> section) {
        final Map<String, Object> result = new LinkedHashMap<>();
        if (section != null) {
            for (final Map.Entry<?, ?> entry : section.entrySet()) {
                result.put(String.valueOf(entry.getKey()), copyValue(entry.getValue()));
            }
        }
        return result;
    }

    private static @Nullable Object copyValue(final @Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            return copy(map);
        }
        if (value instanceof List<?> list) {
            final List<Object> result = new ArrayList<>(list.size());
            for (final Object item : list) {
                result.add(copyValue(item));
            }
            return result;
        }
        return value;
    }

    private static Map<String, Object> parse(final String yaml) {
        final Object loaded = new Yaml().load(yaml);
        return loaded instanceof Map<?, ?> map ? copy(map) : new LinkedHashMap<>();
    }
}
