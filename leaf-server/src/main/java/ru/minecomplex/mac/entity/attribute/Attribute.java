package ru.minecomplex.mac.entity.attribute;

/**
 * Vanilla {@code RangedAttribute}s read by player movement, with their registry defaults and bounds (26.3
 * {@code Attributes}; the bounds of the attributes present in older releases are the same). {@link #playerBase} is the
 * base a player starts with: {@code Player.createAttributes()} overrides movement speed with {@code 0.1F} (a float,
 * widened), every other attribute keeps its default.
 *
 * <p>{@code bounciness}, {@code friction_modifier} and {@code air_drag_modifier} exist from 26.2 on; older clients do not
 * read them ({@code BOUNCE_RESTITUTION}).
 */
public enum  Attribute {
    MOVEMENT_SPEED("movement_speed", 0.7, 0.0, 1024.0, 0.1F),
    JUMP_STRENGTH("jump_strength", 0.42F, 0.0, 32.0),
    STEP_HEIGHT("step_height", 0.6, 0.0, 10.0),
    GRAVITY("gravity", 0.08, -1.0, 1.0),
    SAFE_FALL_DISTANCE("safe_fall_distance", 3.0, -1024.0, 1024.0),
    FALL_DAMAGE_MULTIPLIER("fall_damage_multiplier", 1.0, 0.0, 100.0),
    SCALE("scale", 1.0, 0.0625, 16.0),
    MOVEMENT_EFFICIENCY("movement_efficiency", 0.0, 0.0, 1.0),
    WATER_MOVEMENT_EFFICIENCY("water_movement_efficiency", 0.0, 0.0, 1.0),
    SNEAKING_SPEED("sneaking_speed", 0.3, 0.0, 1.0),
    BOUNCINESS("bounciness", 0.0, 0.0, 1.0),
    FRICTION_MODIFIER("friction_modifier", 1.0, 0.0, 2048.0),
    AIR_DRAG_MODIFIER("air_drag_modifier", 1.0, 0.0, 2048.0),
    /** Read by the happy ghast's flying travel and ridden input (players have no instance of it). */
    FLYING_SPEED("flying_speed", 0.4, 0.0, 1024.0),
    /**
     * Not read by movement itself: {@code LivingEntity.setHealth} clamps the stored health to it, and a respawn that
     * keeps entity data copies that stored health.
     */
    MAX_HEALTH("max_health", 20.0, 1.0, 1024.0),
    /**
     * Not read by movement itself: {@code Player.getCurrentItemAttackStrengthDelay} (an attack's strength, whose
     * knockback slows the attacker). Synced to the client ({@code setSyncable}); {@code attack_damage} and
     * {@code attack_knockback} are not, so the client keeps their bases (1.0, 0.0) and they are not modelled.
     */
    ATTACK_SPEED("attack_speed", 4.0, 0.0, 1024.0);

    /** {@link #values()} without the copy. */
    static final Attribute[] ALL = values();

    private final Identifier id;
    /** The registry key, {@code namespace:path}. */
    private final String key;
    private final double defaultValue;
    private final double min;
    private final double max;
    private final double playerBase;

    Attribute(String path, double defaultValue, double min, double max) {
        this(path, defaultValue, min, max, defaultValue);
    }

    Attribute(String path, double defaultValue, double min, double max, double playerBase) {
        this.id = Identifier.minecraft(path);
        this.key = id.toString();
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.playerBase = playerBase;
    }

    public Identifier id() {
        return id;
    }

    public String key() {
        return key;
    }

    public double defaultValue() {
        return defaultValue;
    }

    public double playerBase() {
        return playerBase;
    }

    /** {@code RangedAttribute.sanitizeValue}: NaN becomes the minimum, then {@code Mth.clamp}. */
    public double sanitize(double value) {
        return Double.isNaN(value) ? min : value < min ? min : Math.min(value, max);
    }
}
