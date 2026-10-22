package ru.minecomplex.mac.fixture.inbound;

/**
 * {@code use_item_on}: {@code MultiPlayerGameMode.useItemOn} for one hand on the block at {@code x, y, z} the client
 * aims at; the client ran {@code performUseItemOn} (block use, then {@code ItemStack.useOn}) unless a spectator.
 */
public record UseItemOn(boolean offHand, int sequence, int x, int y, int z) implements InboundEvent {
}
