package ru.minecomplex.mac.world;

import java.util.Set;

/**
 * A block of a protocol's registry, with static properties read from vanilla.
 *
 * @param index               position in the registry file (dense, 0-based)
 * @param name                registry key, e.g. {@code minecraft:stone}
 * @param registryId          numeric id in the block registry (used by {@code update_tags})
 * @param className           simple name of the vanilla block class (behaviour selector for dynamic shapes)
 * @param classChain          simple names of the class and its superclasses up to {@code Block}, for vanilla's
 *                            {@code instanceof} tests
 * @param friction            {@code Block.getFriction()}
 * @param speedFactor         {@code Block.getSpeedFactor()}
 * @param jumpFactor          {@code Block.getJumpFactor()}
 * @param bounceRestitution   {@code Block.getBounceRestitution()} (26.2+; 0 before)
 * @param offsetKind          {@code BlockBehaviour.OffsetType} of the block
 * @param maxHorizontalOffset {@code getMaxHorizontalOffset()} (only meaningful with an offset)
 * @param maxVerticalOffset   {@code getMaxVerticalOffset()} (only meaningful with {@link OffsetKind#XYZ})
 */
public record BlockType(int index, String name, int registryId, String className, Set<String> classChain,
                        float friction, float speedFactor, float jumpFactor, float bounceRestitution,
                        int firstStateId, int stateCount,
                        OffsetKind offsetKind, float maxHorizontalOffset, float maxVerticalOffset) {

    /**
     * {@code block instanceof <simpleName>}.
     */
    public boolean isInstance(String simpleName) {
        return classChain.contains(simpleName);
    }

    public enum OffsetKind {
        NONE,
        XZ,
        XYZ
    }
}
