package org.dreeam.leaf.orebfuscator.config;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses block names ({@code minecraft:stone}) and block tags ({@code tag(minecraft:coal_ores)}).
 */
@NullMarked
final class BlockParser {

    private static final Pattern FUNCTION_PATTERN = Pattern.compile("^(?<function>\\w+)\\((?<argument>.+)\\)$");

    /**
     * A config entry and the blocks it resolved to, empty if the entry is invalid.
     */
    record ConfigBlockValue(String value, Set<Block> blocks) {

        @Override
        public int hashCode() {
            return this.value.hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            return this == obj || obj instanceof ConfigBlockValue other && this.value.equals(other.value);
        }
    }

    private BlockParser() {
    }

    /**
     * @return the function and argument of values like {@code name(argument)}, null otherwise
     */
    static String @Nullable [] parseFunction(final String value) {
        final Matcher matcher = FUNCTION_PATTERN.matcher(value);
        if (matcher.find()) {
            return new String[]{matcher.group("function").toLowerCase(java.util.Locale.ROOT), matcher.group("argument")};
        }
        return null;
    }

    static ConfigBlockValue parse(final ConfigIssues issues, final String value, final boolean excludeAir) {
        final String[] function = parseFunction(value);
        if (function == null) {
            return parseBlock(issues, value, excludeAir);
        }

        if (function[0].equals("tag")) {
            return parseBlockTag(issues, function[1], excludeAir);
        }

        issues.warn("skipping unknown function literal '%s(...)' with argument '%s'", function[0], function[1]);
        return new ConfigBlockValue(value, Collections.emptySet());
    }

    private static ConfigBlockValue parseBlockTag(final ConfigIssues issues, final String value, final boolean excludeAir) {
        final String name = "tag(" + value + ")";
        final Identifier identifier = Identifier.tryParse(value);
        final Optional<HolderSet.Named<Block>> tag = identifier != null
            ? BuiltInRegistries.BLOCK.get(TagKey.create(Registries.BLOCK, identifier))
            : Optional.empty();
        if (tag.isEmpty()) {
            issues.warn("skipping unknown block tag '%s'", value);
            return new ConfigBlockValue(name, Collections.emptySet());
        }

        final Set<Block> blocks = new LinkedHashSet<>();
        for (final Holder<Block> holder : tag.get()) {
            final Block block = holder.value();
            if (excludeAir && block.defaultBlockState().isAir()) {
                issues.warn("skipping air block '%s' for block tag '%s'", BuiltInRegistries.BLOCK.getKey(block), value);
                continue;
            }
            blocks.add(block);
        }

        if (blocks.isEmpty()) {
            issues.warn("skipping empty block tag '%s'", value);
            return new ConfigBlockValue(name, Collections.emptySet());
        }

        // same name format the plugin used, the tag key is written with its namespace
        return new ConfigBlockValue("tag(" + tag.get().key().location() + ")", Collections.unmodifiableSet(blocks));
    }

    private static ConfigBlockValue parseBlock(final ConfigIssues issues, final String value, final boolean excludeAir) {
        final Identifier identifier = Identifier.tryParse(value);
        final Optional<Block> block = identifier != null ? BuiltInRegistries.BLOCK.getOptional(identifier) : Optional.empty();
        if (block.isEmpty()) {
            issues.warn("skipping unknown block '%s'", value);
        } else if (excludeAir && block.get().defaultBlockState().isAir()) {
            issues.warn("skipping air block '%s'", value);
        } else {
            return new ConfigBlockValue(identifier.toString(), Set.of(block.get()));
        }
        return new ConfigBlockValue(value, Collections.emptySet());
    }
}
