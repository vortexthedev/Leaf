package ru.minecomplex.mac.util.math;

/**
 * Vanilla Vec2 arithmetic; float operation order and signed zeros are significant.
 */
public record Vec2f(float x, float y) {
    public static final Vec2f ZERO = new Vec2f(0.0F, 0.0F);

    public Vec2f scale(float factor) {
        return new Vec2f(x * factor, y * factor);
    }

    public float lengthSquared() {
        return x * x + y * y;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    public Vec2f normalized() {
        float length = length();
        return length < 1.0E-4F ? ZERO : new Vec2f(x / length, y / length);
    }
}
