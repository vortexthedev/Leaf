package ru.minecomplex.mac.world.shulker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
public class ShulkerAnimationOracleTest {

    @Test
    void matchesVanilla() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/shulker-animation.bin.gz");
        assertNotNull(raw, "26.3");
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d415348, in.readInt()); assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); assertEquals(128, in.readInt());
            for (int s = 0; s < 128; s++) {
                ShulkerAnimation lid = ShulkerAnimation.closed(); assertEquals(128, in.readInt());
                for (int t = 0; t < 128; t++) {
                    int action = in.readInt(), parameter = in.readInt(); boolean eventOnly = in.readBoolean();
                    assertEquals(action == 1, in.readBoolean());
                    lid = lid.event(action, parameter); if (!eventOnly) lid = lid.tick();
                    String context = "26.3 scenario " + s + " step " + t;
                    assertEquals(in.readInt(), lid.openCount(), context);
                    assertEquals(in.readUTF(), lid.status().name(), context);
                    assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(lid.progress()), context);
                    assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(lid.progressOld()), context);
                    assertEquals(in.readBoolean(), lid.isClosed(), context);
                    float partial = in.readFloat();
                    assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(lid.getProgress(partial)), context);
                }
            }
            assertEquals(-1, in.read());
        }
    }
}
