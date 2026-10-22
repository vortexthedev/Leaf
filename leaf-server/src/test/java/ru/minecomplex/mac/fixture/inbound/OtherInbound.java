package ru.minecomplex.mac.fixture.inbound;

/** A serverbound packet the model does not interpret yet; kept for ordering, frame structure and replay. */
public record OtherInbound(String name) implements InboundEvent {
    /** Chat, commands and the other screen packets come from input handling (a tick packet among these names is never followed by a pong in its tick). */
    @Override
    public boolean betweenTicks() {
        return true;
    }
}
