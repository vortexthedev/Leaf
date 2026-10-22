package ru.minecomplex.mac.fixture.inbound;

/**
 * {@code container_close} from the client: {@code LocalPlayer.closeContainer} sends the id of its {@code containerMenu}
 * (0 for its own inventory screen) and makes the inventory menu current.
 */
public record ContainerClosed(int containerId) implements InboundEvent {
    /** A screen key (escape, the inventory key) closes it from input handling. */
    @Override
    public boolean betweenTicks() {
        return true;
    }
}
