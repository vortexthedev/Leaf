package ru.minecomplex.mac.collision;

/** {@code Direction.Axis}. */
public enum Axis {
    X,
    Y,
    Z;

    static final Axis[] VALUES = values();

    public int choose(int x, int y, int z) {
        return switch (this) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }

    public double choose(double x, double y, double z) {
        return switch (this) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }
}
