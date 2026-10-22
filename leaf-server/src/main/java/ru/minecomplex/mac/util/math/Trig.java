package ru.minecomplex.mac.util.math;

/**
 * {@code Mth.sin}/{@code Mth.cos} of 26.3: a 65536-entry table, {@code SIN[(int)((long)(x * 10430.378350470453) & 65535L)]}
 * with {@code SIN[i] = (float)Math.sin(i / 10430.378350470453)}. Every movement call site passes a float product such as
 * {@code yRot * (float)(Math.PI / 180.0)}; that float is widened to the double parameter.
 */
public final class Trig {

    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) {
            SIN[i] = (float) Math.sin(i / 10430.378350470453);
        }
    }

    private Trig() {
    }

    public static float sin(float radians) {
        double x = radians;
        return SIN[(int) ((long) (x * 10430.378350470453) & 65535L)];
    }

    public static float cos(float radians) {
        double x = radians;
        return SIN[(int) ((long) (x * 10430.378350470453 + 16384.0) & 65535L)];
    }
}
