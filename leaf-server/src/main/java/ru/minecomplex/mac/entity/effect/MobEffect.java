package ru.minecomplex.mac.entity.effect;

/** Mob effects read by client movement. */
public enum MobEffect {
    JUMP_BOOST,
    LEVITATION,
    SLOW_FALLING,
    WEAVING,
    DOLPHINS_GRACE,
    /** Read through {@code hasEffect} by the sprint gates ({@code isMobilityRestricted}); no arithmetic. */
    BLINDNESS
}
