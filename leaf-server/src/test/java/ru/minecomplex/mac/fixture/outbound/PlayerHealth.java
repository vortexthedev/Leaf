package ru.minecomplex.mac.fixture.outbound;

/** {@code set_health}: {@code LocalPlayer.hurtTo(health)}, then the food level and saturation are replaced. */
public record PlayerHealth(float health, int food, float saturation) implements OutboundEvent {
}
