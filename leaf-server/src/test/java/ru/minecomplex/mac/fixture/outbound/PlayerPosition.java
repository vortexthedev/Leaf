package ru.minecomplex.mac.fixture.outbound;

/**
 * {@code player_position} (teleport). Values are interpreted by the client with
 * {@code PositionMoveRotation.calculateAbsolute} against its current state and the {@code relatives} bit set
 * (vanilla {@code Relative} bits: X, Y, Z, Y_ROT, X_ROT, DELTA_X, DELTA_Y, DELTA_Z, ROTATE_DELTA).
 */
public record PlayerPosition(
        int teleportId,
        double x,
        double y,
        double z,
        double deltaX,
        double deltaY,
        double deltaZ,
        float yRot,
        float xRot,
        int relatives
) implements OutboundEvent {

    public static final int RELATIVE_X = 1;
    public static final int RELATIVE_Y = 1 << 1;
    public static final int RELATIVE_Z = 1 << 2;
    public static final int RELATIVE_Y_ROT = 1 << 3;
    public static final int RELATIVE_X_ROT = 1 << 4;
    public static final int RELATIVE_DELTA_X = 1 << 5;
    public static final int RELATIVE_DELTA_Y = 1 << 6;
    public static final int RELATIVE_DELTA_Z = 1 << 7;
    public static final int ROTATE_DELTA = 1 << 8;

    public boolean isRelative(int flag) {
        return (relatives & flag) != 0;
    }
}
