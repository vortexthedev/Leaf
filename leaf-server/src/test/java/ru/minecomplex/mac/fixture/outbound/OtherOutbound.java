package ru.minecomplex.mac.fixture.outbound;

/** A clientbound packet the model does not interpret yet; kept for ordering and replay. */
public record OtherOutbound(String name) implements OutboundEvent {
}
