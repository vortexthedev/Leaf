package ru.minecomplex.mac.fixture.outbound;

/**
 * {@code mount_screen_open}: a horse or nautilus inventory menu for that entity becomes {@code containerMenu} (another
 * entity leaves the open menu as it was); its layout depends on the entity and the inventory columns.
 */
public record MountScreenOpen(int containerId, int inventoryColumns, int entityId) implements OutboundEvent {
}
