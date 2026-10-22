package ru.minecomplex.mac.fixture.outbound;

/** {@code set_held_slot}: the selected hotbar slot when {@code Inventory.isHotbarSlot(slot)}, otherwise ignored. */
public record SetHeldSlot(int slot) implements OutboundEvent {
}
