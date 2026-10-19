package org.dreeam.leaf.orebfuscator.config;

import net.minecraft.world.level.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.joml.Matrix4f;
import org.jspecify.annotations.NullMarked;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A {@code proximity} section: blocks hidden until a player comes close.
 */
@NullMarked
public final class ProximityConfig extends WorldConfig {

    private final int distance;

    private final boolean frustumCullingEnabled;
    private final float frustumCullingMinDistanceSquared;
    private final Matrix4f frustumCullingProjectionMatrix;

    private final boolean rayCastCheckEnabled;
    private final boolean rayCastCheckOnlyCheckCenter;

    private int defaultBlockFlags;
    private final Map<BlockParser.ConfigBlockValue, Integer> hiddenBlocks = new LinkedHashMap<>();
    private final Set<Block> allowForUseBlockBelow = new HashSet<>();

    ProximityConfig(final ConfigurationSection section, final ConfigIssues issues) {
        super(section, issues);

        this.distance = section.getInt("distance", 24);
        issues.errorMinValue("distance", 1, this.distance);

        this.frustumCullingEnabled = section.getBoolean("frustumCulling.enabled", false);
        final float frustumCullingMinDistance = (float) section.getDouble("frustumCulling.minDistance", 3d);
        final float frustumCullingFov = (float) section.getDouble("frustumCulling.fov", 80d);
        if (this.frustumCullingEnabled) {
            issues.errorMinMaxValue("frustumCulling.fov", 10, 170, (int) frustumCullingFov);
        }

        this.frustumCullingMinDistanceSquared = frustumCullingMinDistance * frustumCullingMinDistance;
        // projection matrix with a 16:9 aspect ratio, same as the plugin
        this.frustumCullingProjectionMatrix = new Matrix4f()
            .perspective(frustumCullingFov, 16f / 9f, 0.01f, 2 * this.distance);

        this.rayCastCheckEnabled = section.getBoolean("rayCastCheck.enabled", false);
        this.rayCastCheckOnlyCheckCenter = section.getBoolean("rayCastCheck.onlyCheckCenter", false);

        this.defaultBlockFlags = ProximityHeightCondition.create(this.minY, this.maxY);
        if (section.getBoolean("useBlockBelow", true)) {
            this.defaultBlockFlags |= BlockFlags.FLAG_USE_BLOCK_BELOW;
        }

        this.parseHiddenBlocks(section, issues.section("hiddenBlocks"));
        this.parseRandomBlocks(section, issues);

        for (final WeightedBlockList list : this.weightedBlockLists) {
            for (final BlockParser.ConfigBlockValue value : list.blocks()) {
                this.allowForUseBlockBelow.addAll(value.blocks());
            }
        }

        this.disableOnError(issues);
    }

    private void parseHiddenBlocks(final ConfigurationSection section, final ConfigIssues issues) {
        final ConfigurationSection blocksSection = section.getConfigurationSection("hiddenBlocks");
        if (blocksSection == null) {
            return;
        }

        boolean isEmpty = true;
        for (final String key : blocksSection.getKeys(false)) {
            // like the plugin, only entries with a (possibly empty) section are used
            final ConfigurationSection block = blocksSection.getConfigurationSection(key);
            if (block == null) {
                continue;
            }

            final BlockParser.ConfigBlockValue parsed = BlockParser.parse(issues, key, true);
            int blockFlags = this.defaultBlockFlags;

            // block specific height condition
            if (block.get("minY") instanceof Number && block.get("maxY") instanceof Number) {
                final int minY = block.getInt("minY", this.minY);
                final int maxY = block.getInt("maxY", this.maxY);
                blockFlags = ProximityHeightCondition.remove(blockFlags)
                    | ProximityHeightCondition.create(Math.min(minY, maxY), Math.max(minY, maxY));
            }

            // block specific useBlockBelow
            if (block.isBoolean("useBlockBelow")) {
                if (block.getBoolean("useBlockBelow", true)) {
                    blockFlags |= BlockFlags.FLAG_USE_BLOCK_BELOW;
                } else {
                    blockFlags &= ~BlockFlags.FLAG_USE_BLOCK_BELOW;
                }
            }

            this.hiddenBlocks.put(parsed, blockFlags);
            isEmpty &= parsed.blocks().isEmpty();
        }

        if (isEmpty) {
            issues.error(ConfigIssues.MISSING_OR_EMPTY);
        }
    }

    public int distance() {
        return this.distance;
    }

    public boolean frustumCullingEnabled() {
        return this.frustumCullingEnabled;
    }

    public float frustumCullingMinDistanceSquared() {
        return this.frustumCullingMinDistanceSquared;
    }

    /**
     * @return a copy, the matrix is modified by the caller
     */
    public Matrix4f frustumCullingProjectionMatrix() {
        return new Matrix4f(this.frustumCullingProjectionMatrix);
    }

    public boolean rayCastCheckEnabled() {
        return this.rayCastCheckEnabled;
    }

    public boolean rayCastCheckOnlyCheckCenter() {
        return this.rayCastCheckOnlyCheckCenter;
    }

    Map<BlockParser.ConfigBlockValue, Integer> hiddenBlocks() {
        return Collections.unmodifiableMap(this.hiddenBlocks);
    }

    Set<Block> allowForUseBlockBelow() {
        return Collections.unmodifiableSet(this.allowForUseBlockBelow);
    }
}
