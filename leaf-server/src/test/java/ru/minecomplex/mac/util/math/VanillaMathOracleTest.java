package ru.minecomplex.mac.util.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;

/** MAMA (MathSamples): {@link VanillaMath#atan2} against vanilla 26.3 {@code Mth.atan2}, bit-exact. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class VanillaMathOracleTest {

    @Test
    void atan2MatchesVanilla() throws Exception {
        String path = "/vanilla/777/math-atan2.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414d41, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            for (int n = in.readInt(), c = 0; c < n; c++) {
                double y = in.readDouble(), x = in.readDouble(), expected = in.readDouble();
                assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(VanillaMath.atan2(y, x)),
                        "26.3 case " + c + " atan2(" + y + ", " + x + ")");
            }
            assertEquals(-1, in.read());
        }
    }
}
