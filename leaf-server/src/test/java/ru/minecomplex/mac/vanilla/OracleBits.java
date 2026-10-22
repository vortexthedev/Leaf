package ru.minecomplex.mac.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.IOException;
import ru.minecomplex.mac.util.math.Vec3d;

/** Readers and bit-exact comparisons shared by the vanilla oracle tests. */
public final class OracleBits {
    private OracleBits() {
    }

    public static Vec3d vector(DataInputStream in) throws IOException {
        return new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
    }

    public static void bits(float expected, float actual, String context) {
        assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual), context);
    }
}
