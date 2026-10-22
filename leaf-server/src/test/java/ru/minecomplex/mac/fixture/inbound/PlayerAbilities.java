package ru.minecomplex.mac.fixture.inbound;

/** Serverbound player_abilities: the client reports its flying flag, not permission to fly. */
public record PlayerAbilities(boolean flying) implements InboundEvent {}
