package ru.minecomplex.mac.fixture.outbound;

import java.util.Arrays;

import ru.minecomplex.mac.fixture.WireItem;

/** {@code set_player_inventory}: {@code player.getInventory().setItem(slot, contents)}. */
public record PlayerInventorySlot(int slot, byte[] item) implements OutboundEvent {

    public PlayerInventorySlot {
        item = item.clone();
    }

    public static PlayerInventorySlot of(int slot, WireItem item) {
        return new PlayerInventorySlot(slot, WireItem.encode(item));
    }

    public WireItem stack() {
        return WireItem.decodeOne(item);
    }

    @Override
    public byte[] item() {
        return item.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlayerInventorySlot that && slot == that.slot && Arrays.equals(item, that.item);
    }

    @Override
    public int hashCode() {
        return 31 * slot + Arrays.hashCode(item);
    }

    @Override
    public String toString() {
        return "PlayerInventorySlot[slot=" + slot + ", item=" + stack() + "]";
    }
}
