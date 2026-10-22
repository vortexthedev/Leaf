package ru.minecomplex.mac.fixture.outbound;

/**
 * {@code open_screen}: {@code MenuScreens.create} makes the client menu of this type ({@code type.create(containerId,
 * player.getInventory())}) the player's {@code containerMenu}.
 *
 * @param menuType menu type registry id in the client's registry ({@code inventory.MenuLayouts})
 */
public record OpenContainer(int containerId, int menuType) implements OutboundEvent {
}
