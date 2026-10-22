package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import ru.minecomplex.mac.fixture.inbound.InboundEvent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.util.math.Vec2f;

/** Direct arithmetic/item/packet boundaries for vanilla 26.3; chained coverage is in LocalTickInputPacketsTest. */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class LocalPlayerOracleTest {

    @Test
    void matchesDirectFloatAndPacketBoundaryOracles() throws IOException {
        var inputPort = SendPositions.inputPort();
        String path = "/vanilla/" + ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL + "/local-boundaries" + ".bin.gz";
        var resource = getClass().getResourceAsStream(path);
        assertNotNull(resource, "Missing direct oracle " + path);
        try (var in = new DataInputStream(new GZIPInputStream(resource))) {
            assertEquals(0x4d414344, in.readInt());
            assertEquals(2, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            org.junit.jupiter.api.Assertions.assertEquals("26.3", in.readUTF(), "Fixture version");
            int inputCount = in.readInt();
            assertEquals(4096, inputCount);
            for (int i = 0; i < inputCount; i++) {
                var vector = new Vec2f(in.readFloat(), in.readFloat());
                boolean usingItem = in.readBoolean(), passenger = in.readBoolean();
                float itemFactor = in.readFloat();
                boolean slowly = in.readBoolean();
                double sneakSpeed = in.readDouble();
                Vec2f normalized = vector.normalized();
                bits(in.readFloat(), normalized.x(), "26.3" + " normalized x " + i);
                bits(in.readFloat(), normalized.y(), "26.3" + " normalized y " + i);
                Vec2f modified = inputPort.modifyInput(vector, usingItem, passenger, itemFactor, slowly, sneakSpeed);
                bits(in.readFloat(), modified.x(), "26.3" + " modified x " + i);
                bits(in.readFloat(), modified.y(), "26.3" + " modified y " + i);
            }
            int packetCount = in.readInt();
            assertEquals(1024, packetCount);
            for (int i = 0; i < packetCount; i++) {
                boolean loaded = in.readBoolean(), camera = in.readBoolean(), autoJump = in.readBoolean();
                var before = LocalPlayerOracleCodec.State.read(in);
                boolean shiftBefore = in.readBoolean();
                var after = LocalPlayerOracleCodec.State.read(in);
                boolean shiftAfter = in.readBoolean();
                var expected = new ArrayList<InboundEvent>();
                int sent = in.readInt();
                for (int p = 0; p < sent; p++) expected.add(LocalPlayerOracleCodec.packet(in));
                var state = before.sendState();
                state.wasShiftKeyDown = shiftBefore;
                var result = SendPositions.send(before.player().toState(), state, before.input(), loaded, camera, autoJump, null);
                String context = "26.3" + " direct send " + i;
                assertEquals(expected, Wire.in(result.packets()), context);
                assertTrue(result.unmodeled().isEmpty(), context);
                assertSendState(after, state, context);
                assertEquals(shiftAfter, state.wasShiftKeyDown, context + " remembered shift");
            }
            assertEquals(-1, in.read(), "No trailing direct oracle data");
        }
    }

    private static void assertSendState(LocalPlayerOracleCodec.State after, ru.minecomplex.mac.player.state.SendState send, String context) {
        assertEquals(after.lastSentInput(), send.lastSentInput, context);
        assertEquals(after.wasSprinting(), send.wasSprinting, context);
        bits(after.xLast(), send.xLast, context + " xLast");
        bits(after.yLast(), send.yLast, context + " yLast");
        bits(after.zLast(), send.zLast, context + " zLast");
        bits(after.yRotLast(), send.yRotLast, context + " yRotLast");
        bits(after.xRotLast(), send.xRotLast, context + " xRotLast");
        assertEquals(after.positionReminder(), send.positionReminder, context);
        assertEquals(after.lastOnGround(), send.lastOnGround, context);
        assertEquals(after.lastHorizontalCollision(), send.lastHorizontalCollision, context);
        assertEquals(after.autoJumpEnabled(), send.autoJumpEnabled, context);
    }

    private static void bits(float expected, float actual, String context) {
        assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual), context);
    }

    private static void bits(double expected, double actual, String context) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual), context);
    }
}
