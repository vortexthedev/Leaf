package ru.minecomplex.mac.fixture.inbound;

/** {@code set_carried_item} from the client. */
public record HeldSlot(int slot) implements InboundEvent {
}
