package ru.minecomplex.mac.fixture.inbound;

/** {@code player_loaded}: the client finished loading the level and starts ticking the player. */
public record PlayerLoaded() implements InboundEvent {
    public static final PlayerLoaded INSTANCE = new PlayerLoaded();
}
