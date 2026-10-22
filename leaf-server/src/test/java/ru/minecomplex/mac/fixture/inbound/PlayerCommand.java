package ru.minecomplex.mac.fixture.inbound;

/**
 * Native {@code player_command}; shift state is carried by the input packet.
 * {@code data} is the jump strength for {@link Action#START_RIDING_JUMP}.
 */
public record PlayerCommand(Action action, int data) implements InboundEvent {

    public enum Action {
        STOP_SLEEPING,
        START_SPRINTING,
        STOP_SPRINTING,
        START_RIDING_JUMP,
        STOP_RIDING_JUMP,
        OPEN_INVENTORY,
        START_FALL_FLYING
    }
}
