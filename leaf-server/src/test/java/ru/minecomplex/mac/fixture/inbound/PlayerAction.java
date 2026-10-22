package ru.minecomplex.mac.fixture.inbound;

/**
 * {@code player_action}: digging, item drops, using-item release, the offhand swap and stab.
 * {@code CHANGE_DESTROY_DIRECTION} exists from 26.3; the adapter decodes the action with the client's protocol.
 *
 * @param face {@code Direction} id of the packet (0 down … 5 east)
 */
public record PlayerAction(Action action, int x, int y, int z, int face, int sequence) implements InboundEvent {
    public enum Action {
        START_DESTROY_BLOCK,
        CHANGE_DESTROY_DIRECTION,
        ABORT_DESTROY_BLOCK,
        STOP_DESTROY_BLOCK,
        DROP_ALL_ITEMS,
        DROP_ITEM,
        RELEASE_USE_ITEM,
        SWAP_ITEM_WITH_OFFHAND,
        STAB
    }

    /** An action at {@code BlockPos.ZERO}, face down, sequence 0 (what drops and the offhand swap send). */
    public static PlayerAction of(Action action) {
        return new PlayerAction(action, 0, 0, 0, 0, 0);
    }
}
