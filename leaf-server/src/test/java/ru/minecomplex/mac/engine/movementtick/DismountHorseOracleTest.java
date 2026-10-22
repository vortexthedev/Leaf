package ru.minecomplex.mac.engine.movementtick;

import org.junit.jupiter.api.Test;

import ru.minecomplex.mac.player.state.ClientPlayerData;
import ru.minecomplex.mac.Wire;
import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.util.ArrayList;

import java.util.Set;
import java.util.zip.GZIPInputStream;
import ru.minecomplex.mac.entity.PassengerGraph;
import ru.minecomplex.mac.fixture.inbound.InboundEvent;
import ru.minecomplex.mac.util.Tri;
import ru.minecomplex.mac.world.BlockRegistry;
import ru.minecomplex.mac.entity.HorseState;
import ru.minecomplex.mac.mechanics.fluid.FluidShapeCache;
import ru.minecomplex.mac.player.state.PlayerInput;
import ru.minecomplex.mac.util.math.Vec3d;

/**
 * MADH v1: MAHW ride ticks (real horse/camel tick and mounted LocalPlayer), the server's dismount and real on-foot ticks
 * with movement keys from the first tick on, through the components the movement probe chains.
 */
@org.junit.jupiter.api.extension.ExtendWith(ru.minecomplex.mac.NativeRuntimeExtension.class)
class DismountHorseOracleTest {

    @Test
    void horseDismountAndWalk() throws Exception {
        var registry = BlockRegistry.get();
        String path = "/vanilla/777/dismount-horses.bin.gz";
        var raw = getClass().getResourceAsStream(path);
        assertNotNull(raw, path);
        try (var in = new DataInputStream(new GZIPInputStream(raw))) {
            assertEquals(0x4d414448, in.readInt());
            assertEquals(1, in.readUnsignedShort());
            assertEquals(ru.minecomplex.mac.packet.NativeRuntime.PROTOCOL, in.readInt());
            assertEquals(36, in.readInt());
            int ticks = 0;
            for (int scenario = 0; scenario < 36; scenario++) {
                var kind = HorseState.Kind.values()[in.readUnsignedByte()];
                var blocks = new ArrayList<LocalTickOracle.Block>();
                for (int n = in.readInt(); n > 0; n--) blocks.add(new LocalTickOracle.Block(in.readInt(), in.readInt(), in.readInt(), in.readInt()));
                float width = in.readFloat(), height = in.readFloat(), eye = in.readFloat();
                var standing = new HorseOracle.Dimensions(in.readFloat(), in.readFloat(), in.readFloat());
                boolean controlled = in.readBoolean(), pushed = in.readBoolean();
                float scale = in.readFloat();
                int maxHeadYaw = in.readInt();
                boolean baby = in.readBoolean(), navigationCanFloat = in.readBoolean();
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
                HorseWholePlayerOracleTest.extra(in, state, true, "initial");
                var own = BoatPlayerOracleTest.Own.read(in);
                var player = own.player(false);
                var local = own.hidden();
                player.fluidShapes = FluidShapeCache.empty();
                var env = LocalPlayerFluidOracleTest.environment(registry, blocks);
                var physics = HorseOracle.body(env, state, eye, pushed, controlled, true, false, standing);
                var f = new HorseOracle.Control[] { null };
                var defaultSeat = new Vec3d[] { Vec3d.ZERO };
                var ownAttachment = new Vec3d[] { Vec3d.ZERO };
                var ride = new HorsePlayerRide(graph, 1, 2, physics, () -> new HorsePassengerPhysics.Facts(kind, body.position, body.yRot, state.bodyYaw, body.height(), scale, baby, false, 0, 1, false, f[0].gameTime(), state.lastPoseChangeTick, state.oldStandAnimation, defaultSeat[0]), () -> new VehiclePacketState(state.generation, Tri.TRUE, body.position, body.yRot, body.xRot, null, false, body.onGround), p -> ownAttachment[0]);
                var playerData = new ClientPlayerData();
                var simulation = new HorsePlayerTick(env, physics, ride, player, local, playerData);
                for (int count = in.readInt(), t = 0; t < count; t++) {
                    boolean loaded = in.readBoolean();
                    var keys = PlayerInput.fromMask(in.readUnsignedByte());
                    player.yRot = in.readFloat();
                    player.xRot = in.readFloat();
                    f[0] = HorseWholePlayerOracleTest.effective(HorseWholePlayerOracleTest.facts(in, kind), in.readBoolean());
                    assertEquals(Set.of(), HorseOracle.simulate(simulation,state,f[0],maxHeadYaw,true,false,navigationCanFloat));
                    String context = "26.3 " + kind + " scenario " + scenario + " tick " + t;
                    PistonPhysicsOracleTest.compare(PistonPhysicsOracleTest.state(in), body, context + " body");
                    assertEquals(in.readInt(), body.movementThisTick.size(), context);
                    HorseWholePlayerOracleTest.tail(in, state, context + " tail");
                    HorseWholePlayerOracleTest.extra(in, state, false, context);
                    defaultSeat[0] = PistonPhysicsOracleTest.vector(in);
                    f[0] = HorseWholePlayerOracleTest.effective(HorseWholePlayerOracleTest.facts(in, kind), in.readBoolean());
                    boolean jumpable = in.readBoolean();
                    int cooldown = in.readInt();
                    ownAttachment[0] = PistonPhysicsOracleTest.vector(in);
                    assertEquals(jumpable, ride.jumpableVehicle(), context + " jumpable");
                    assertEquals(cooldown, ride.vehicleJumpCooldown(), context + " cooldown");
                    playerData.clientLoaded = loaded;
                    var result = simulation.simulatePassengerTick(keys);
                    {
                        var late = simulation.sendAfterEntityTicks();
                        result = new TickPackets(result.then(late).packets(), late.unmodeled());
                    }
                    BoatPlayerOracleTest.Own.read(in).compare(player, local, context + " passenger");
                    HorseWholePlayerOracleTest.tail(in, state, context + " charge");
                    var packets = new ArrayList<InboundEvent>();
                    for (int n = in.readInt(); n > 0; n--) packets.add(LocalPlayerOracleCodec.packet(in));
                    assertEquals(packets, Wire.in(result.packets()), context + " packets");
                    assertEquals(Set.of(), result.unmodeled(), context + " tick gaps");
                    ticks++;
                }
                boolean teleportFirst = in.readBoolean();
                var teleport = new ru.minecomplex.mac.fixture.outbound.PlayerPosition(0, in.readDouble(), in.readDouble(), in.readDouble(), 0, 0, 0, 0F, 0F, in.readInt());
                var handlers = new ru.minecomplex.mac.listener.ClientPlayerHandlers();
                var data = new ru.minecomplex.mac.player.state.ClientPlayerData();
                if (teleportFirst)
                    handlers.apply(player, local, data, Wire.of(teleport));
                graph.stopRiding(1);
                player.passenger = false;
                local.handsBusy = false;
                local.rootVehicle = null;
                if (!teleportFirst)
                    handlers.apply(player, local, data, Wire.of(teleport));
                DismountMoveOracleTest.compareOnFoot(BoatPlayerOracleTest.Own.read(in), player, local, "26.3 " + kind + " scenario " + scenario + " dismounted");
                for (int count = in.readInt(), t = 0; t < count; t++) {
                    var keys = PlayerInput.fromMask(in.readUnsignedByte());
                    player.yRot = in.readFloat();
                    player.xRot = in.readFloat();
                    playerData.clientLoaded = true;
                    var ticker = new MovementTickerPlayer(env, player, local, playerData);
                    var gaps = ticker.tick(keys);
                    String context = "26.3 " + kind + " scenario " + scenario + " foot tick " + t + " " + keys;
                    assertEquals(Set.of(), gaps, context);
                    DismountMoveOracleTest.compareOnFoot(BoatPlayerOracleTest.Own.read(in), player, local, context);
                    var packets = new ArrayList<InboundEvent>();
                    for (int n = in.readInt(); n > 0; n--) packets.add(LocalPlayerOracleCodec.packet(in));
                    assertEquals(packets, Wire.in(ticker.packets()), context + " packets");
                }
            }
            assertEquals(360, ticks);
            assertEquals(-1, in.read());
        }
    }
}
