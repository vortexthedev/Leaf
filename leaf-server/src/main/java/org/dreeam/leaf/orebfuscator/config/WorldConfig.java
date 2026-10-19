package org.dreeam.leaf.orebfuscator.config;

import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;
import org.bukkit.configuration.ConfigurationSection;
import org.dreeam.leaf.orebfuscator.util.WeightedRandom;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Settings shared by obfuscation and proximity sections.
 */
@NullMarked
public abstract sealed class WorldConfig permits ObfuscationConfig, ProximityConfig {

    private final String name;
    private boolean enabledValue;
    private boolean enabled;
    protected int minY = DimensionType.MIN_Y;
    protected int maxY = DimensionType.MAX_Y;

    private final List<Pattern> worldMatchers = new ArrayList<>();
    protected final List<WeightedBlockList> weightedBlockLists = new ArrayList<>();

    WorldConfig(final ConfigurationSection section, final ConfigIssues issues) {
        this.name = section.getName();

        this.enabledValue = section.getBoolean("enabled", true);
        final int minY = Mth.clamp(section.getInt("minY", DimensionType.MIN_Y), DimensionType.MIN_Y, DimensionType.MAX_Y);
        final int maxY = Mth.clamp(section.getInt("maxY", DimensionType.MAX_Y), DimensionType.MIN_Y, DimensionType.MAX_Y);
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);

        for (final String world : section.getStringList("worlds")) {
            this.worldMatchers.add(parseWorldMatcher(world));
        }
        if (this.worldMatchers.isEmpty()) {
            issues.section("worlds").error(ConfigIssues.MISSING_OR_EMPTY);
        }
    }

    /**
     * {@code regex(...)} is a regular expression, everything else a name where {@code *} matches anything.
     */
    private static Pattern parseWorldMatcher(final String value) {
        final String[] function = BlockParser.parseFunction(value);
        if (function != null && function[0].equals("regex")) {
            return Pattern.compile(function[1]);
        }
        return Pattern.compile(("\\Q" + value + "\\E").replace("*", "\\E.*\\Q"));
    }

    protected final void parseRandomBlocks(final ConfigurationSection section, ConfigIssues issues) {
        issues = issues.section("randomBlocks");
        final ConfigurationSection lists = section.getConfigurationSection("randomBlocks");
        if (lists == null) {
            issues.error(ConfigIssues.MISSING_OR_EMPTY);
            return;
        }

        for (final String key : lists.getKeys(false)) {
            final ConfigurationSection list = lists.getConfigurationSection(key);
            if (list != null) {
                this.weightedBlockLists.add(new WeightedBlockList(list, issues.section(key)));
            }
        }

        if (this.weightedBlockLists.isEmpty()) {
            issues.error(ConfigIssues.MISSING_OR_EMPTY);
        } else if (this.hasHeightGaps()) {
            issues.warn("Incomplete height coverage detected in randomBlocks. "
                + "One or more Y-levels between the configured minY and maxY are not assigned to any section. "
                + "These uncovered height ranges will use minecraft:air as the fallback replacement material.");
        }
    }

    private boolean hasHeightGaps() {
        yLoop:
        for (int y = this.minY; y <= this.maxY; y++) {
            for (final WeightedBlockList list : this.weightedBlockLists) {
                if (list.matches(y)) {
                    continue yLoop;
                }
            }
            return true;
        }
        return false;
    }

    protected final void disableOnError(final ConfigIssues issues) {
        this.enabled = issues.disableIfError(this.enabledValue);
    }

    public final String name() {
        return this.name;
    }

    public final boolean isEnabled() {
        return this.enabled;
    }

    public final int minY() {
        return this.minY;
    }

    public final int maxY() {
        return this.maxY;
    }

    final boolean matchesWorldName(final String worldName) {
        for (final Pattern matcher : this.worldMatchers) {
            if (matcher.matcher(worldName).matches()) {
                return true;
            }
        }
        return false;
    }

    public final boolean shouldObfuscate(final int y) {
        return y >= this.minY && y <= this.maxY;
    }

    final WeightedRandom[] createWeightedRandoms(final int minBuildHeight, final int height) {
        return WeightedBlockList.create(minBuildHeight, height, this.weightedBlockLists);
    }
}
