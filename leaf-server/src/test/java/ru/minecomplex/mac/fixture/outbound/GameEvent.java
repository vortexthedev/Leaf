package ru.minecomplex.mac.fixture.outbound;

/** {@code game_event} with the vanilla event id (3 = change game mode, 11 = immediate respawn, …). */
public record GameEvent(int type, float param) implements OutboundEvent {
    public static final int CHANGE_GAME_MODE = 3;
    public static final int IMMEDIATE_RESPAWN = 11;
}
