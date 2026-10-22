package ru.minecomplex.mac.fixture.inbound;

/**
 * {@code use_item}: {@code MultiPlayerGameMode.useItem} for one hand (after {@code set_carried_item} when the slot
 * changed); the client ran {@code ItemStack.use} unless the item was on cooldown.
 */
public record UseItem(boolean offHand, int sequence, float yRot, float xRot) implements InboundEvent {
}
