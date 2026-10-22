package ru.minecomplex.mac.world;

/**
 * A client handler input a column with dynamic blocks (pistons, shulker boxes) keeps for their local replay: block events,
 * block entity data and the block changes that follow them.
 */
public sealed interface BlockInput permits BlockEvent, BlockEntityData, BlockChange {
}
