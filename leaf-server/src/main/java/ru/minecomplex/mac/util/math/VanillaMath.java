package ru.minecomplex.mac.util.math;


/**
 * Vanilla numeric operations from {@code Mth} and {@code Entity}, operation for operation. Do not replace them with
 * "equivalent" JDK calls: NaN, signed zero and rounding behaviour must match vanilla.
 */
public final class VanillaMath {
    private VanillaMath() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    private static final double FRAC_BIAS = Double.longBitsToDouble(4805340802404319232L);
    private static final double[] ASIN_TAB = new double[257];
    private static final double[] COS_TAB = new double[257];

    static {
        for (int ind = 0; ind < 257; ind++) {
            double v = ind / 256.0;
            double asinv = Math.asin(v);
            COS_TAB[ind] = Math.cos(asinv);
            ASIN_TAB[ind] = asinv;
        }
    }

    /**
     * {@code Mth.wrapDegrees(double)}.
     */
    public static double wrapDegrees(double angle) {
        double normalized = angle % 360.0;
        if (normalized >= 180.0) normalized -= 360.0;
        if (normalized < -180.0) normalized += 360.0;
        return normalized;
    }

    /**
     * {@code Mth.atan2}: the table-based approximation (the same in every supported release).
     */
    public static double atan2(double y, double x) {
        double d2 = x * x + y * y;
        if (Double.isNaN(d2)) return Double.NaN;
        boolean negY = y < 0.0;
        if (negY) y = -y;
        boolean negX = x < 0.0;
        if (negX) x = -x;
        boolean steep = y > x;
        if (steep) {
            double t = x;
            x = y;
            y = t;
        }
        double rinv = fastInvSqrt(d2);
        x *= rinv;
        y *= rinv;
        double yp = FRAC_BIAS + y;
        int index = (int) Double.doubleToRawLongBits(yp);
        double phi = ASIN_TAB[index];
        double cPhi = COS_TAB[index];
        double sPhi = yp - FRAC_BIAS;
        double sd = y * cPhi - x * sPhi;
        double d = (6.0 + sd * sd) * sd * 0.16666666666666666;
        double theta = phi + d;
        if (steep) theta = (Math.PI / 2) - theta;
        if (negX) theta = Math.PI - theta;
        if (negY) theta = -theta;
        return theta;
    }

    /**
     * {@code Mth.fastInvSqrt}.
     */
    static double fastInvSqrt(double x) {
        double xhalf = 0.5 * x;
        long i = Double.doubleToRawLongBits(x);
        i = 6910469410427058090L - (i >> 1);
        x = Double.longBitsToDouble(i);
        return x * (1.5 - xhalf * x * x);
    }

    /**
     * Entity.setPosRaw retains the original vector when all coordinates are numerically equal.
     */
    public static Vec3d position(Vec3d previous, Vec3d next) {
        if (previous.x() != next.x() || previous.y() != next.y() || previous.z() != next.z()) return next;
        return previous;
    }

    /**
     * {@code Mth.clamp(float, float, float)}.
     */
    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    /**
     * {@code Mth.clamp(double, double, double)}.
     */
    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /**
     * {@code Mth.clamp(int, int, int)}.
     */
    public static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    /**
     * {@code Mth.floor(double)}.
     */
    public static int floor(double value) {
        return (int) Math.floor(value);
    }

    /**
     * {@code Mth.lfloor(double)}.
     */
    public static long lfloor(double value) {
        return (long) Math.floor(value);
    }

    /**
     * {@code Mth.frac(double)}: {@code num - lfloor(num)}.
     */
    public static double frac(double value) {
        return value - lfloor(value);
    }

    /**
     * {@code Mth.sign(double)}.
     */
    public static int sign(double value) {
        if (value == 0.0) {
            return 0;
        }
        return value > 0.0 ? 1 : -1;
    }

    /**
     * {@code Mth.lerp(double, double, double)}.
     */
    public static double lerp(double alpha, double p0, double p1) {
        return p0 + alpha * (p1 - p0);
    }

    /**
     * {@code Mth.lerp(float, float, float)}.
     */
    public static float lerp(float alpha, float p0, float p1) {
        return p0 + alpha * (p1 - p0);
    }

    /**
     * {@code Mth.equal(double, double)}: the tolerance is the float {@code 1.0E-5F}.
     */
    public static boolean equal(double a, double b) {
        return Math.abs(b - a) < 1.0E-5F;
    }

    /**
     * {@code Mth.square(double)}.
     */
    public static double square(double value) {
        return value * value;
    }
}
