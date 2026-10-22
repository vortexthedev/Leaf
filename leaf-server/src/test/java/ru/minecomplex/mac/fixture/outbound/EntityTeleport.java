package ru.minecomplex.mac.fixture.outbound;

/**
 * {@code teleport_entity}: a {@code PositionMoveRotation} with relative flags ({@link PlayerPosition}'s bits: vanilla
 * {@code Relative} order) and the ground flag. Relative axes carry offsets.
 */
public record EntityTeleport(int entityId, double x, double y, double z, double deltaX, double deltaY, double deltaZ,
                             float yRot, float xRot, int relatives, boolean onGround) implements OutboundEvent {
    public boolean isRelative(int flag) {
        return (relatives & flag) != 0;
    }
}
