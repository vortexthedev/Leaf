package ru.minecomplex.mac.fixture.outbound;

import java.util.Arrays;

import ru.minecomplex.mac.fixture.WireItem;

/**
 * {@code container_set_slot}: for container 0 {@code inventoryMenu.setItem(slot, stateId, item)}, for the open container
 * its menu's, otherwise ignored ({@code ClientPacketListener.handleContainerSetSlot}).
 */
public record ContainerSlot(int containerId, int stateId, int slot, byte[] item) implements OutboundEvent {

    public ContainerSlot {
        item = item.clone();
    }

    public static ContainerSlot of(int containerId, int stateId, int slot, WireItem item) {
        return new ContainerSlot(containerId, stateId, slot, WireItem.encode(item));
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
        return other instanceof ContainerSlot that && containerId == that.containerId && stateId == that.stateId
                && slot == that.slot && Arrays.equals(item, that.item);
    }

    @Override
    public int hashCode() {
        return ((31 * containerId + stateId) * 31 + slot) * 31 + Arrays.hashCode(item);
    }

    @Override
    public String toString() {
        return "ContainerSlot[containerId=" + containerId + ", stateId=" + stateId + ", slot=" + slot + ", item=" + stack() + "]";
    }
}
