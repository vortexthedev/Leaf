package ru.minecomplex.mac.fixture.outbound;

/**
 * {@code light_update}: the client queues the column's light data ({@code ClientLevel.queueLightUpdate}), applied in a
 * later frame. Only the column is kept: the model reads light only where it is surely settled.
 */
public record LightUpdate(int x, int z) implements OutboundEvent {
}
