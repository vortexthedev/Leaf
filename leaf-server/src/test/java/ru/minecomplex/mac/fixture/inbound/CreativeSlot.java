package ru.minecomplex.mac.fixture.inbound;

import ru.minecomplex.mac.fixture.WireItem;

/**
 * {@code set_creative_mode_slot}: the creative inventory screen changed an inventory menu slot and reports its new stack
 * ({@code MultiPlayerGameMode.handleCreativeModeItemAdd}, from {@code CreativeInventoryListener} or explicit calls), or
 * dropped a stack (slot −1, {@code handleCreativeModeItemDrop}). The stack is exact in vanilla 26.3 (a flat payload,
 * {@link #stack()}).
 */
public record CreativeSlot(int slot, byte[] item) implements InboundEvent {

    public CreativeSlot {
        item = item.clone();
    }

    public static CreativeSlot of(int slot, WireItem stack) {
        return new CreativeSlot(slot, WireItem.encode(stack));
    }

    public WireItem stack() {
        return WireItem.decodeOne(item);
    }

    @Override
    public byte[] item() {
        return item.clone();
    }

    /** The creative inventory screen, from input handling (hotbar snapshots send it inside a tick). */
    @Override
    public boolean betweenTicks() {
        return true;
    }
}
