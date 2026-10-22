package ru.minecomplex.mac.world.piston;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.world.BlockRegistry;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class PistonNbtOracleTest {

    @Test
    void matchesVanilla() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/piston-nbt.bin.gz"); assertNotNull(raw);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d41504e, in.readInt()); assertEquals(1, in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); assertEquals(1024, in.readInt());
            for (int s = 0; s < 1024; s++) {
                byte[] bytes = new byte[in.readInt()]; in.readFully(bytes); boolean crashed = in.readBoolean();
                MovingPiston actual = null; boolean rejected = false;
                try { actual = PistonNbt.load(new MovingPiston(0, null, false, false), bytes, BlockRegistry.get()); }
                catch (IllegalArgumentException invalidData) { rejected = true; }
                String context = "26.3 NBT " + s; assertEquals(crashed, rejected, context + " rejected");
                if (crashed) continue;
                assertEquals(in.readInt(), actual.movedState(), context); assertEquals(in.readInt(), actual.direction().ordinal(), context);
                assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(actual.progress()), context);
                assertEquals(Float.floatToIntBits(in.readFloat()), Float.floatToIntBits(actual.progressOld()), context);
                assertEquals(in.readBoolean(), actual.extending(), context); assertEquals(in.readBoolean(), actual.source(), context);
            }
            assertEquals(-1, in.read());
        }
    }
}
