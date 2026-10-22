package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.engine.Unmodeled;
import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.mechanics.fluid.FluidWorld;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * Connected movement/tail/passenger boundaries; isolated horse base phases remain explicitly uncertain.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class HorsePlayerOracleTest {

    static HorseOracle.Control facts(DataInputStream in, HorseState.Kind kind) throws Exception {
        return new HorseOracle.Control(kind, true, true, false, in.readBoolean(), in.readBoolean(), in.readLong(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), in.readFloat());
    }

    static void tail(DataInputStream in, HorseState state, String context) throws Exception {
        HorseControlOracleTest.compare(in, state, state.kind == HorseState.Kind.CAMEL, context);
        HorsePlayerOracleTest.bits(in.readFloat(), state.standAnimation, context);
        HorsePlayerOracleTest.bits(in.readFloat(), state.oldStandAnimation, context);
        assertEquals(in.readUTF(), state.pose.name(), context);
    }

    static void bits(float e, float a, String s) {
        assertEquals(Float.floatToIntBits(e), Float.floatToIntBits(a), s);
    }

    @Test
    void connectedHorseMovementChargeAndPassenger() throws Exception {
        var registry = BlockRegistry.get();
        String path = "/vanilla/777/horse-player.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414852, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            assertEquals(24, in.readInt());
            int ticks = 0;
            for (int scenario = 0; scenario < 24; scenario++) {
                var kind = HorseState.Kind.values()[in.readUnsignedByte()];
                var blocks = new ArrayList<LocalTickOracle.Block>();
                for (int n = in.readInt(); n > 0; n--) blocks.add(new LocalTickOracle.Block(in.readInt(), in.readInt(), in.readInt(), in.readInt()));
                float width = in.readFloat(), height = in.readFloat(), eye = in.readFloat();
                var standing = new HorseOracle.Dimensions(in.readFloat(), in.readFloat(), in.readFloat());
                boolean controlled = in.readBoolean(), pushed = in.readBoolean();
                float scale = in.readFloat();
                var body = PistonPhysicsOracleTest.state(in);
                assertEquals(0, in.readInt());
                body.fixedBodyDimensions = true;
                body.bodyWidth = width;
                body.bodyHeight = height;
                body.firstTick = false;
                body.fluidShapes = FluidShapeCache.empty();
                var graph = new PassengerGraph();
                graph.spawn(1, true);
                graph.spawn(2);
                graph.startRiding(1, 2, true);
                var state = new HorseState(graph.get(2).generation(), kind, body);
                HorseOracle.assign(state,HorseTickOracleTest.state(in, kind == HorseState.Kind.CAMEL));
                body.yRot = state.body.yRot;
                body.xRot = state.body.xRot;
                var own = BoatPlayerOracleTest.Own.read(in);
                var player = own.player(false);
                var local = own.hidden();
                player.fluidShapes = FluidShapeCache.empty();
                var env = LocalPlayerFluidOracleTest.environment(registry, blocks);
                var physics = HorseOracle.body(env, state, eye, pushed, controlled, true, false, standing);
                var contact = new FluidWorld(env, u -> fail("Unexpected body fluid gap " + u), new FluidWorld.EntityFacts(false, eye, pushed));
                var f = new HorseOracle.Control[] { null };
                var defaultSeat = new Vec3d[] { Vec3d.ZERO };
                var ownAttachment = new Vec3d[] { Vec3d.ZERO };
                var ride = new HorsePlayerRide(graph, 1, 2, physics, () -> new HorsePassengerPhysics.Facts(kind, body.position, body.yRot, state.bodyYaw, body.height(), scale, false, false, 0, 1, false, f[0].gameTime(), state.lastPoseChangeTick, state.oldStandAnimation, defaultSeat[0]), () -> new VehiclePacketState(state.generation, Tri.TRUE, body.position, body.yRot, body.xRot, null, false, body.onGround), p -> ownAttachment[0]);
                var playerData = new ClientPlayerData();
                var localPhysics = new MovementTickerPlayer(env, player, local, playerData);
                for (int count = in.readInt(), t = 0; t < count; t++) {
                    boolean loaded = in.readBoolean();
                    var keys = PlayerInput.fromMask(in.readUnsignedByte());
                    player.yRot = in.readFloat();
                    player.xRot = in.readFloat();
                    body.oldPosition = body.position;
                    contact.refresh(body, true);
                    f[0] = facts(in, kind);
                    f[0].apply(state); physics.gameTime(f[0].gameTime());
                    ride.advanceRiddenMovement(player,true);
                    ride.advanceTickTail(f[0].gameTime());
                    String context = "26.3 " + kind + " scenario " + scenario + " tick " + t;
                    PistonPhysicsOracleTest.compare(PistonPhysicsOracleTest.state(in), body, context + " body");
                    assertEquals(in.readInt(), body.movementThisTick.size(), context);
                    tail(in, state, context + " tail");
                    defaultSeat[0] = PistonPhysicsOracleTest.vector(in);
                    f[0] = facts(in, kind);
                    boolean jumpable = in.readBoolean();
                    int cooldown = in.readInt();
                    ownAttachment[0] = PistonPhysicsOracleTest.vector(in);
                    assertEquals(jumpable, ride.jumpableVehicle(), context + " jumpable");
                    assertEquals(cooldown, ride.vehicleJumpCooldown(), context + " cooldown");
                    playerData.clientLoaded = loaded;
                    player.oldPosition = player.position;
                    local.yawOld = player.yRot;
                    var gaps = localPhysics.simulateRideTick(keys, ride);
                    gaps = localPhysics.sendAfterEntityTicks(ride);
                    var result = new TickPackets(java.util.List.copyOf(localPhysics.packets()), gaps);
                    BoatPlayerOracleTest.Own.read(in).compare(player, local, context + " passenger");
                    tail(in, state, context + " charge");
                    var packets = new ArrayList<InboundEvent>();
                    for (int n = in.readInt(); n > 0; n--) packets.add(LocalPlayerOracleCodec.packet(in));
                    assertEquals(packets, Wire.in(result.packets()), context + " packets");
                    assertEquals(Set.of(Unmodeled.RIDING), result.unmodeled(), context + " base gap");
                    ticks++;
                }
            }
            assertEquals(768, ticks);
            assertEquals(-1, in.read());
        }
    }
}
