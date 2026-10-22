package ru.minecomplex.mac.world;

/**
 * Block tag contents as the client knows them.
 */
@FunctionalInterface
public interface BlockTags {
    /**
     * No block is in any tag (a bare vanilla bootstrap, as used by the movement oracle).
     */
    BlockTags NONE = (tag, block) -> false;

    boolean contains(BlockTag tag, BlockType block);
}
