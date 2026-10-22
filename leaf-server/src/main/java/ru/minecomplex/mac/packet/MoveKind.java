package ru.minecomplex.mac.packet;

/** Which fields a {@code move_player_*} carries: {@code Pos}, {@code PosRot}, {@code Rot} or {@code StatusOnly}. */
public enum MoveKind {
    POS(true, false), POS_ROT(true, true), ROT(false, true), STATUS_ONLY(false, false);

    private final boolean position;
    private final boolean rotation;

    MoveKind(boolean position, boolean rotation) {
        this.position = position;
        this.rotation = rotation;
    }

    public boolean hasPosition() {
        return position;
    }

    public boolean hasRotation() {
        return rotation;
    }

    public static MoveKind of(boolean position, boolean rotation) {
        return position ? (rotation ? POS_ROT : POS) : rotation ? ROT : STATUS_ONLY;
    }
}
