package ru.minecomplex.mac.entity;

import java.util.List;

/**
 * Every vanilla pose (the same 18 in 1.21.4 – 26.3) with the player's dimensions: {@code Player.POSES} (from 1.21.9
 * {@code Avatar.POSES}) has the first seven; every other pose, which only the server can give the own player (entity
 * data), has none, so {@code getDefaultDimensions} returns the standing dimensions. Fixed dimensions ignore the
 * {@code scale} attribute ({@code EntityDimensions.scale} returns them unchanged). The player's poses keep their
 * order: data files index them by ordinal.
 */
public enum Pose {
    STANDING(0.6F, 1.8F, false),
    FALL_FLYING(0.6F, 0.6F, false),
    SLEEPING(0.2F, 0.2F, true),
    SWIMMING(0.6F, 0.6F, false),
    SPIN_ATTACK(0.6F, 0.6F, false),
    CROUCHING(0.6F, 1.5F, false),
    DYING(0.2F, 0.2F, true),
    LONG_JUMPING,
    CROAKING,
    USING_TONGUE,
    SITTING,
    ROARING,
    SNIFFING,
    EMERGING,
    DIGGING,
    SLIDING,
    SHOOTING,
    INHALING;

    /** The poses with an entry in the player's dimension table, in the order data files use. */
    public static final List<Pose> PLAYER_POSES = List.of(STANDING, FALL_FLYING, SLEEPING, SWIMMING, SPIN_ATTACK, CROUCHING, DYING);

    private final float width;
    private final float height;
    private final boolean fixed;
    private final boolean player;

    Pose(float width, float height, boolean fixed) {
        this.width = width;
        this.height = height;
        this.fixed = fixed;
        this.player = true;
    }

    /** Not in the player's table: {@code POSES.getOrDefault(pose, STANDING_DIMENSIONS)}. */
    Pose() {
        this.width = 0.6F;
        this.height = 1.8F;
        this.fixed = false;
        this.player = false;
    }

    /** The pose whose dimensions (and eye height) a player in this pose has. */
    public Pose dimensionPose() {
        return player ? this : STANDING;
    }

    /** {@code getDefaultDimensions(pose).scale(scale)}: width. */
    public float width(float scale) {
        return fixed || scale == 1.0F ? width : width * scale;
    }

    /** {@code getDefaultDimensions(pose).scale(scale)}: height. */
    public float height(float scale) {
        return fixed || scale == 1.0F ? height : height * scale;
    }
}
