package ru.minecomplex.mac.fixture.outbound;

import java.util.Arrays;
import java.util.List;
import ru.minecomplex.mac.fixture.WireItem;

/**
 * {@code container_set_content}: for container 0 {@code inventoryMenu.initializeContents(stateId, items, carried)},
 * for the open container its menu's, otherwise ignored ({@code ClientPacketListener.handleContainerContent}).
 * Stacks are flat {@link WireItem} payloads.
 */
public record ContainerContent(int containerId, int stateId, byte[] items, byte[] carried) implements OutboundEvent {

    public ContainerContent {
        items = items.clone();
        carried = carried.clone();
    }

    public static ContainerContent of(int containerId, int stateId, List<WireItem> items, WireItem carried) {
        return new ContainerContent(containerId, stateId, WireItem.encode(items), WireItem.encode(carried));
    }

    public List<WireItem> stacks() {
        return WireItem.decode(items);
    }

    public WireItem carriedStack() {
        return WireItem.decodeOne(carried);
    }

    @Override
    public byte[] items() {
        return items.clone();
    }

    @Override
    public byte[] carried() {
        return carried.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContainerContent that && containerId == that.containerId && stateId == that.stateId
                && Arrays.equals(items, that.items) && Arrays.equals(carried, that.carried);
    }

    @Override
    public int hashCode() {
        return (31 * containerId + stateId) * 31 + Arrays.hashCode(items);
    }

    @Override
    public String toString() {
        return "ContainerContent[containerId=" + containerId + ", stateId=" + stateId + ", items=" + stacks() + "]";
    }
}
