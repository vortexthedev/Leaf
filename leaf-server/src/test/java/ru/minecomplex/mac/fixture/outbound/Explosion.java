package ru.minecomplex.mac.fixture.outbound;

/** {@code explode}. The client adds the knockback to its delta movement when present. */
public record Explosion(double centerX, double centerY, double centerZ, boolean hasKnockback,
                        double knockbackX, double knockbackY, double knockbackZ) implements OutboundEvent {
}
