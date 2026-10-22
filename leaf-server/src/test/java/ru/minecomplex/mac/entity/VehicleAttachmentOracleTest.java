package ru.minecomplex.mac.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.DataInputStream;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.attribute.Attribute;
import ru.minecomplex.mac.entity.attribute.AttributeInstance;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * MAVA (VehicleAttachmentSamples): the real local player's {@code getVehicleAttachmentPoint} and the rideable living
 * types' {@code getDimensions(pose)}/{@code getClamped(PASSENGER, …)}, computed from the generated MAET table, bit-exact.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class VehicleAttachmentOracleTest {

    private static Vec3d vector(DataInputStream in) throws Exception {
        return new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
    }

    private static void bits(Vec3d expected, Vec3d actual, String context) {
        assertEquals(Double.doubleToLongBits(expected.x()), Double.doubleToLongBits(actual.x()), context + " x");
        assertEquals(Double.doubleToLongBits(expected.y()), Double.doubleToLongBits(actual.y()), context + " y");
        assertEquals(Double.doubleToLongBits(expected.z()), Double.doubleToLongBits(actual.z()), context + " z");
    }

    private static float scaleValue(double base) {
        return (float) new AttributeInstance(Attribute.SCALE, base).value();
    }

    @Test
    void attachmentsMatchVanilla() throws Exception {
        var types = EntityTypes.get();
        String path = "/vanilla/777/vehicle-attachments.bin.gz";
        var stream = getClass().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var in = new DataInputStream(new GZIPInputStream(stream))) {
            assertEquals(0x4d415641, in.readInt());
            assertEquals(2, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            in.readUTF();
            int cases = in.readInt();
            for (int c = 0; c < cases; c++) {
                Pose pose = types.vanillaPose(in.readUnsignedByte());
                double scale = in.readDouble();
                float yaw = in.readFloat();
                float width = in.readFloat(), height = in.readFloat(), eye = in.readFloat();
                Vec3d expected = vector(in);
                String context = "26.3 player " + c + " " + pose + " scale " + scale + " yaw " + yaw;
                var dimensions = types.playerDimensions(pose, scaleValue(scale));
                assertEquals(Float.floatToIntBits(width), Float.floatToIntBits(dimensions.width()), context + " width");
                assertEquals(Float.floatToIntBits(height), Float.floatToIntBits(dimensions.height()), context + " height");
                assertEquals(Float.floatToIntBits(eye), Float.floatToIntBits(dimensions.eyeHeight()), context + " eye");
                bits(expected, dimensions.attachments().get(EntityAttachment.VEHICLE, 0, yaw), context);
            }
            for (int n = in.readInt(); n > 0; n--) {
                String type = in.readUTF();
                var rideable = types.rideable(type);
                assertNotNull(rideable, type);
                for (int c = in.readInt(), i = 0; i < c; i++) {
                    boolean baby = in.readBoolean();
                    Pose pose = types.vanillaPose(in.readUnsignedByte());
                    double scale = in.readDouble();
                    float yaw = in.readFloat();
                    int index = in.readInt();
                    float width = in.readFloat(), height = in.readFloat(), eye = in.readFloat();
                    Vec3d expected = vector(in);
                    String context = "26.3 " + type + " " + i + " baby " + baby + " " + pose + " scale " + scale;
                    var dimensions = types.livingDimensions(rideable, baby, pose, rideable.scale(new AttributeInstance(Attribute.SCALE, scale).value()));
                    assertEquals(Float.floatToIntBits(width), Float.floatToIntBits(dimensions.width()), context + " width");
                    assertEquals(Float.floatToIntBits(height), Float.floatToIntBits(dimensions.height()), context + " height");
                    assertEquals(Float.floatToIntBits(eye), Float.floatToIntBits(dimensions.eyeHeight()), context + " eye");
                    bits(expected, dimensions.attachments().getClamped(EntityAttachment.PASSENGER, index, yaw), context);
                }
            }
            assertEquals(-1, in.read());
        }
    }
}
