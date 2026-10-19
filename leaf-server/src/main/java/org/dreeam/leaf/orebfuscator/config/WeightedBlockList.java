package org.dreeam.leaf.orebfuscator.config;

import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.DimensionType;
import org.bukkit.configuration.ConfigurationSection;
import org.dreeam.leaf.orebfuscator.BlockStateInfo;
import org.dreeam.leaf.orebfuscator.util.WeightedRandom;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@code randomBlocks} section: the blocks used as replacement in a height range.
 */
@NullMarked
final class WeightedBlockList {

    /**
     * One weighted random per y level of the world, lists with the same blocks share their instance.
     */
    static WeightedRandom[] create(final int minBuildHeight, final int height, final List<WeightedBlockList> lists) {
        final WeightedRandom[] heightMap = new WeightedRandom[height];

        final List<WeightedBlockList> last = new ArrayList<>();
        final List<WeightedBlockList> next = new ArrayList<>();

        for (int index = 0; index < height; index++) {
            final int y = minBuildHeight + index;
            for (final WeightedBlockList list : lists) {
                if (list.matches(y)) {
                    next.add(list);
                }
            }

            if (index > 0 && last.equals(next)) {
                heightMap[index] = heightMap[index - 1];
            } else {
                final WeightedRandom.Builder builder = WeightedRandom.builder();
                for (final WeightedBlockList list : next) {
                    for (final Map.Entry<BlockParser.ConfigBlockValue, Integer> entry : list.blocks.entrySet()) {
                        final Set<Block> blocks = entry.getKey().blocks();
                        // only the default state of each block is used, like the plugin
                        final double weight = (double) entry.getValue() / (double) blocks.size();
                        for (final Block block : blocks) {
                            builder.add(Block.getId(block.defaultBlockState()), weight);
                        }
                    }
                }

                if (builder.isEmpty()) {
                    // air as fallback if no list covers this height
                    builder.add(BlockStateInfo.AIR, 1);
                }

                heightMap[index] = builder.build();
                last.clear();
                last.addAll(next);
            }

            next.clear();
        }

        return heightMap;
    }

    private final String name;
    private final int minY;
    private final int maxY;
    private final Map<BlockParser.ConfigBlockValue, Integer> blocks = new LinkedHashMap<>();

    WeightedBlockList(final ConfigurationSection section, final ConfigIssues issues) {
        this.name = section.getName();

        final int minY = Mth.clamp(section.getInt("minY", DimensionType.MIN_Y), DimensionType.MIN_Y, DimensionType.MAX_Y);
        final int maxY = Mth.clamp(section.getInt("maxY", DimensionType.MAX_Y), DimensionType.MIN_Y, DimensionType.MAX_Y);
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);

        final ConfigIssues blocksIssues = issues.section("blocks");
        final ConfigurationSection blocksSection = section.getConfigurationSection("blocks");
        if (blocksSection == null) {
            blocksIssues.error(ConfigIssues.MISSING_OR_EMPTY);
            return;
        }

        boolean isEmpty = true;
        for (final String value : blocksSection.getKeys(false)) {
            final int weight = blocksSection.getInt(value, 1);
            final BlockParser.ConfigBlockValue parsed = BlockParser.parse(issues, value, false);
            this.blocks.put(parsed, weight);
            isEmpty &= parsed.blocks().isEmpty();
        }

        if (isEmpty) {
            blocksIssues.error(ConfigIssues.MISSING_OR_EMPTY);
        }
    }

    String name() {
        return this.name;
    }

    Set<BlockParser.ConfigBlockValue> blocks() {
        return this.blocks.keySet();
    }

    boolean matches(final int y) {
        return this.minY <= y && this.maxY >= y;
    }
}
