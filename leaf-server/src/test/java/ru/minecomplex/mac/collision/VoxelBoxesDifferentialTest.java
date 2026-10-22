package ru.minecomplex.mac.collision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.DataInputStream;
import java.util.List;
import java.util.zip.GZIPInputStream;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class VoxelBoxesDifferentialTest {
    @Test
    void boxesMatchVanillaInOrder() throws Exception {
        var resource = getClass().getResourceAsStream("/vanilla/" + ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL + "/voxel-boxes.bin.gz");
        assertNotNull(resource);
        try (var in = new DataInputStream(new GZIPInputStream(resource))) {
            assertEquals(0x4D414356, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            int count = in.readInt();
            assertEquals(2048, count);
            for (int sample = 0; sample < count; sample++) {
                int[] sizes = new int[3];
                double[][] coords = new double[3][];
                for (int axis = 0; axis < 3; axis++) {
                    sizes[axis] = in.readInt();
                    coords[axis] = new double[sizes[axis] + 1];
                    for (int i = 0; i < coords[axis].length; i++) coords[axis][i] = in.readDouble();
                }
                long[] bits = new long[(sizes[0] * sizes[1] * sizes[2] + 63) / 64];
                int words = in.readInt();
                for (int i = 0; i < words; i++) bits[i] = in.readLong();
                VoxelShape shape = VoxelShape.array(sizes[0], sizes[1], sizes[2], bits, coords[0], coords[1], coords[2]);
                List<Aabb> actual = shape.toAabbs();
                String context = "sample " + sample;
                assertEquals(in.readInt(), actual.size(), context);
                for (Aabb box : actual) {
                    for (double face : new double[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()}) {
                        assertEquals(Double.doubleToLongBits(in.readDouble()), Double.doubleToLongBits(face), context);
                    }
                }
                assertEquals(actual, shape.toAabbs(), "enumeration must not consume the shape: " + context);
            }
            assertEquals(-1, in.read());
        }
    }
}
