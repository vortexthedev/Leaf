package ru.minecomplex.mac.fixture.outbound;

/** {@code player_abilities}. */
public record Abilities(boolean invulnerable, boolean flying, boolean mayFly, boolean instabuild,
                        float flyingSpeed, float walkingSpeed) implements OutboundEvent {
}
