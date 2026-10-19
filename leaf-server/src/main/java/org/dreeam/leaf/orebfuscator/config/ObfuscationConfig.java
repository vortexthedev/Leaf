package org.dreeam.leaf.orebfuscator.config;

import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.NullMarked;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An {@code obfuscation} section: blocks hidden when no side of them is visible.
 */
@NullMarked
public final class ObfuscationConfig extends WorldConfig {

    private final boolean layerObfuscation;
    private final Set<BlockParser.ConfigBlockValue> hiddenBlocks = new LinkedHashSet<>();

    ObfuscationConfig(final ConfigurationSection section, final ConfigIssues issues) {
        super(section, issues);

        this.layerObfuscation = section.getBoolean("layerObfuscation", false);
        this.parseHiddenBlocks(section, issues.section("hiddenBlocks"));
        this.parseRandomBlocks(section, issues);
        this.disableOnError(issues);
    }

    private void parseHiddenBlocks(final ConfigurationSection section, final ConfigIssues issues) {
        boolean isEmpty = true;
        for (final String value : section.getStringList("hiddenBlocks")) {
            final BlockParser.ConfigBlockValue parsed = BlockParser.parse(issues, value, true);
            this.hiddenBlocks.add(parsed);
            isEmpty &= parsed.blocks().isEmpty();
        }

        if (isEmpty) {
            issues.error(ConfigIssues.MISSING_OR_EMPTY);
        }
    }

    public boolean layerObfuscation() {
        return this.layerObfuscation;
    }

    Set<BlockParser.ConfigBlockValue> hiddenBlocks() {
        return Collections.unmodifiableSet(this.hiddenBlocks);
    }
}
