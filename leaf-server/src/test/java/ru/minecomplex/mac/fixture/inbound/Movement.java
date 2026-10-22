package ru.minecomplex.mac.fixture.inbound;

/**
 * {@code move_player_pos}, {@code _pos_rot}, {@code _rot} or {@code _status_only}. Position and rotation fields
 * are meaningful only when {@link #kind()} carries them.
 */
public record Movement(
        Kind kind,
        double x,
        double y,
        double z,
        float yRot,
        float xRot,
        boolean onGround,
        boolean horizontalCollision
) implements InboundEvent {

    public enum Kind {
        POS(true, false),
        POS_ROT(true, true),
        ROT(false, true),
        STATUS_ONLY(false, false);

        private final boolean position;
        private final boolean rotation;

        Kind(boolean position, boolean rotation) {
            this.position = position;
            this.rotation = rotation;
        }

        public boolean hasPosition() {
            return position;
        }

        public boolean hasRotation() {
            return rotation;
        }
    }

    public static Movement pos(double x, double y, double z, boolean onGround, boolean horizontalCollision) {
        return new Movement(Kind.POS, x, y, z, 0, 0, onGround, horizontalCollision);
    }

    public static Movement posRot(double x, double y, double z, float yRot, float xRot, boolean onGround, boolean horizontalCollision) {
        return new Movement(Kind.POS_ROT, x, y, z, yRot, xRot, onGround, horizontalCollision);
    }

    public static Movement rot(float yRot, float xRot, boolean onGround, boolean horizontalCollision) {
        return new Movement(Kind.ROT, 0, 0, 0, yRot, xRot, onGround, horizontalCollision);
    }

    public static Movement status(boolean onGround, boolean horizontalCollision) {
        return new Movement(Kind.STATUS_ONLY, 0, 0, 0, 0, 0, onGround, horizontalCollision);
    }

    public boolean hasPosition() {
        return kind.hasPosition();
    }

    public boolean hasRotation() {
        return kind.hasRotation();
    }
}
