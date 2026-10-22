package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.Wire;

import java.io.*;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.util.Tri;
import static org.junit.jupiter.api.Assertions.*;
import ru.minecomplex.mac.entity.BoatState;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.player.state.PlayerState;
import ru.minecomplex.mac.player.state.SendState;
import ru.minecomplex.mac.util.math.Vec3d;

@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
public class VehiclePacketOracleTest {

    public static Vec3d vector(DataInputStream in) throws IOException { return new Vec3d(in.readDouble(), in.readDouble(), in.readDouble()); }
    @Test
    void matchesMountedVanillaSendPhase() throws Exception {
        var raw = getClass().getResourceAsStream("/vanilla/777/vehicle-packets.bin.gz"); assertNotNull(raw);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d415650, in.readInt()); assertEquals(1, in.readUnsignedShort()); assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt()); assertEquals(2048, in.readInt());
            for (int i = 0; i < 2048; i++) {
                boolean loaded = in.readBoolean(), authority = in.readBoolean(), sprint = in.readBoolean(), wasSprint = in.readBoolean(), wasShift = in.readBoolean();
                int mask = in.readUnsignedByte(), lastMask = in.readUnsignedByte();
                Vec3d position = vector(in); float yaw = in.readFloat(), pitch = in.readFloat(); boolean interpolating = in.readBoolean();
                var target = new VehiclePacketState.Target(vector(in), in.readFloat(), in.readFloat()); boolean ground = in.readBoolean();
                var root = new VehiclePacketState(new Object(), authority ? Tri.TRUE : Tri.FALSE, position, yaw, pitch,
                        interpolating ? target : null, interpolating, ground);
                var boat = new BoatState(root.generation(),1.375F,0.5625F,0.4F);
                boat.body.position = position; boat.body.yRot = yaw; boat.body.xRot = pitch; boat.body.onGround = ground;
                if (interpolating) boat.interpolation.receive(boat.body,target.position(),target.yaw(),target.pitch());
                root = boat.packetState(authority ? Tri.TRUE : Tri.FALSE);
                var player = new PlayerState(); player.passenger = true; player.sprinting = sprint;
                player.yRot = in.readFloat(); player.xRot = in.readFloat(); player.onGround = in.readBoolean(); player.horizontalCollision = in.readBoolean();
                var state = new SendState(); state.lastSentInput = PlayerInput.fromMask(lastMask); state.wasSprinting = wasSprint; state.wasShiftKeyDown = wasShift;
                var expected = new ArrayList<InboundEvent>(); for (int n = in.readInt(); n > 0; n--) expected.add(LocalPlayerOracleCodec.packet(in));
                var actual = SendPositions.send(player, state, PlayerInput.fromMask(mask), loaded, true, true, root);
                assertEquals(expected, Wire.in(actual.packets()), "26.3 packets " + i);
                assertEquals(in.readBoolean(), state.wasSprinting, "26.3 sprint history " + i);
                assertTrue(actual.unmodeled().isEmpty(), "26.3 packets " + i);
            }
            assertEquals(-1, in.read());
        }
    }
}
