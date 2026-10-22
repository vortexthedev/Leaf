package ru.minecomplex.mac.fixture.outbound;

/** {@code cooldown}: {@code ItemCooldowns.addCooldown(group, ticks)}, or {@code removeCooldown} for 0 ticks. */
public record ItemCooldown(String group, int ticks) implements OutboundEvent {
}
