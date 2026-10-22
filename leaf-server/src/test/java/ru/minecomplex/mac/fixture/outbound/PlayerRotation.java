package ru.minecomplex.mac.fixture.outbound;

/** Native 26.3 player_rotation, including independent relative yaw/pitch flags. */
public record PlayerRotation(float yRot, boolean relativeY, float xRot, boolean relativeX) implements OutboundEvent {
}
