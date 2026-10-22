package ru.minecomplex.mac.util.math;

import ru.minecomplex.mac.collision.Axis;

/**
 * {@code net.minecraft.world.phys.Vec3}: immutable, same arithmetic.
 */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);

    public double get(Axis axis) {
        return axis.choose(x, y, z);
    }

    public Vec3d with(Axis axis, double value) {
        return switch (axis) {
            case X -> new Vec3d(value, y, z);
            case Y -> new Vec3d(x, value, z);
            case Z -> new Vec3d(x, y, value);
        };
    }

    public Vec3d add(double dx, double dy, double dz) {
        return new Vec3d(x + dx, y + dy, z + dz);
    }

    public Vec3d add(Vec3d other) {
        return add(other.x, other.y, other.z);
    }

    /**
     * {@code Vec3.subtract(x, y, z)} is {@code add(-x, -y, -z)}.
     */
    public Vec3d subtract(double dx, double dy, double dz) {
        return add(-dx, -dy, -dz);
    }

    /**
     * {@code Vec3.subtract(Vec3)}.
     */
    public Vec3d subtract(Vec3d other) {
        return subtract(other.x, other.y, other.z);
    }

    public Vec3d multiply(Vec3d other) {
        return multiply(other.x, other.y, other.z);
    }

    public Vec3d multiply(double mx, double my, double mz) {
        return new Vec3d(x * mx, y * my, z * mz);
    }

    public Vec3d scale(double factor) {
        return multiply(factor, factor, factor);
    }

    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    public double horizontalDistanceSqr() {
        return x * x + z * z;
    }

    public double horizontalDistance() {
        return Math.sqrt(x * x + z * z);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * {@code Vec3.normalize()}: zero below the float {@code 1.0E-5F}.
     */
    public Vec3d normalize() {
        double dist = Math.sqrt(x * x + y * y + z * z);
        return dist < 1.0E-5F ? ZERO : new Vec3d(x / dist, y / dist, z / dist);
    }

    public double distanceToSqr(Vec3d other) {
        double xd = other.x - x;
        double yd = other.y - y;
        double zd = other.z - z;
        return xd * xd + yd * yd + zd * zd;
    }

    /**
     * {@code Vec3.isFinite}.
     */
    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
